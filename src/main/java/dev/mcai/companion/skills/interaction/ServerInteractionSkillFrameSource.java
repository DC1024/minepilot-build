package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.FirstPersonCrosshairSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Publishes only fair semantic observations and binds them to the current
 * embodiment session.
 */
public final class ServerInteractionSkillFrameSource
        implements InteractionSkillFrameSource {
    /*
     * The hard model deadline is 90 seconds. Keep one compact semantic
     * interaction frame per possible server tick so even an accelerated
     * GameTest server or a temporarily slow provider can resolve the exact
     * public observation handle it was given.
     */
    private static final int MAX_RETAINED_OBSERVATIONS = 2_048;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final LinkedHashMap<Long, InteractionSkillFrame> history =
            new LinkedHashMap<>();
    private InteractionSkillFrame latest;

    public ServerInteractionSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    public synchronized InteractionSkillFrame publish(
            SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match frame source"
            );
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish an observation without an active body"
            );
        }
        latest = InteractionSkillFrame.from(
                observation,
                status.sessionGeneration()
        );
        history.put(latest.observationRevision(), latest);
        while (history.size() > MAX_RETAINED_OBSERVATIONS) {
            history.remove(history.keySet().iterator().next());
        }
        return latest;
    }

    @Override
    public synchronized Optional<InteractionSkillFrame> current() {
        return currentFrame(latest);
    }

    @Override
    public synchronized Optional<InteractionSkillFrame> atObservation(
            final long observationRevision
    ) {
        return currentFrame(history.get(observationRevision));
    }

    @Override
    public synchronized Optional<VisibleBlockFace> currentCrosshairBlock() {
        if (current().isEmpty()) {
            return Optional.empty();
        }
        return AiPlayerManager.onlinePlayer(server)
                .filter(player ->
                        expectedPlayerId.equals(player.getUUID())
                                && player.connection != null
                                && !player.isRemoved()
                )
                .flatMap(FirstPersonCrosshairSampler::sample);
    }

    private Optional<InteractionSkillFrame> currentFrame(
            final InteractionSkillFrame frame
    ) {
        if (!server.isSameThread() || frame == null) {
            return Optional.empty();
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration()
                != frame.sessionGeneration()) {
            return Optional.empty();
        }
        ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())
                || !frame.dimension().equals(DimensionRef.parse(
                        player.level()
                                .dimension()
                                .identifier()
                                .toString()
                ))) {
            return Optional.empty();
        }
        long gameTime = player.level().getGameTime();
        if (gameTime < frame.observedAtGameTime()) {
            return Optional.empty();
        }
        return Optional.of(frame.atGameTime(gameTime));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Interaction observations require the server thread"
            );
        }
    }
}
