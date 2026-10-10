package dev.mcai.companion.skills.menu;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.SemanticObservation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Production publication point for fair menu observations.
 */
public final class ServerMenuSkillFrameSource
        implements MenuSkillFrameSource {
    private static final int MAX_RETAINED_FRAMES = 512;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final Map<Long, MenuSkillFrame> retained =
            new LinkedHashMap<>();
    private MenuSkillFrame latest;

    public ServerMenuSkillFrameSource(
            final MinecraftServer server,
            final UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    /**
     * Publishes or clears the current menu frame from the semantic sampler.
     */
    public synchronized Optional<MenuSkillFrame> publish(
            final SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match menu frame source"
            );
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            clear();
            return Optional.empty();
        }
        if (observation.openMenu().isEmpty()) {
            clear();
            return Optional.empty();
        }
        latest = MenuSkillFrame.from(
                observation,
                status.sessionGeneration()
        );
        retained.put(latest.sampleSequence(), latest);
        while (retained.size() > MAX_RETAINED_FRAMES) {
            final Long oldest = retained.keySet()
                    .iterator()
                    .next();
            retained.remove(oldest);
        }
        return Optional.of(latest);
    }

    @Override
    public synchronized Optional<MenuSkillFrame> current() {
        return valid(latest);
    }

    @Override
    public synchronized Optional<MenuSkillFrame> retained(
            final long sampleSequence
    ) {
        if (sampleSequence < 0) {
            return Optional.empty();
        }
        return valid(retained.get(sampleSequence));
    }

    private Optional<MenuSkillFrame> valid(
            final MenuSkillFrame frame
    ) {
        if (!server.isSameThread() || frame == null) {
            return Optional.empty();
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        final ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration()
                        != frame.sessionGeneration()
                || player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())
                || !player.level()
                        .dimension()
                        .identifier()
                        .toString()
                        .equals(frame.dimensionId())) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    private void clear() {
        latest = null;
        retained.clear();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Menu observations require the server thread"
            );
        }
    }
}
