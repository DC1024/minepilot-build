package dev.mcai.companion.skills.transport;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

/**
 * Adds only the player's current ridden-minecart state to a fair semantic
 * sample. No rail, chunk, or destination scan is performed.
 */
public final class ServerMinecartSkillFrameSource
        implements MinecartSkillFrameSource {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private SemanticObservation latest;
    private long publishedSessionGeneration = -1;

    public ServerMinecartSkillFrameSource(
            final MinecraftServer server,
            final UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    public synchronized MinecartSkillFrame publish(
            final SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(
                observation.body().playerId()
        )) {
            throw new IllegalArgumentException(
                    "Observation player does not match minecart source"
            );
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish without an active companion body"
            );
        }
        latest = observation;
        publishedSessionGeneration = status.sessionGeneration();
        return current().orElseThrow();
    }

    @Override
    public synchronized Optional<MinecartSkillFrame> current() {
        if (!server.isSameThread() || latest == null) {
            return Optional.empty();
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration()
                        != publishedSessionGeneration) {
            return Optional.empty();
        }
        final ServerPlayer player =
                AiPlayerManager.onlinePlayer(server).orElse(null);
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !player.isAlive()
                || !expectedPlayerId.equals(player.getUUID())) {
            return Optional.empty();
        }
        final DimensionRef dimension = DimensionRef.parse(
                player.level().dimension().identifier().toString()
        );
        if (!dimension.id().equals(
                latest.body().dimensionId()
        )) {
            return Optional.empty();
        }
        final long gameTime = player.level().getGameTime();
        if (gameTime < latest.body().gameTime()) {
            return Optional.empty();
        }
        final double danger = latest.dangers().stream()
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        Optional<MinecartState> ridden = Optional.empty();
        if (player.getVehicle() instanceof AbstractMinecart minecart
                && !minecart.isRemoved()
                && minecart.isAlive()
                && minecart.getFirstPassenger() == player) {
            ridden = Optional.of(new MinecartState(
                    minecart.getUUID(),
                    vector(minecart.position()),
                    vector(minecart.getDeltaMovement()),
                    minecart.horizontalCollision
            ));
        }
        return Optional.of(new MinecartSkillFrame(
                expectedPlayerId,
                dimension,
                gameTime,
                latest.body().gameTime(),
                latest.sequence(),
                publishedSessionGeneration,
                vector(player.position()),
                latest.visibleEntities(),
                latest.visibleBlockFaces(),
                danger,
                ridden
        ));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Minecart observations require server thread"
            );
        }
    }

    private static PerceptionVec3 vector(final Vec3 value) {
        return new PerceptionVec3(value.x, value.y, value.z);
    }
}
