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
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Binds fair semantic samples to live state owned by the companion's current
 * boat. It never asks a level for blocks, entities, chunks, or routes.
 */
public final class ServerBoatSkillFrameSource
        implements BoatSkillFrameSource {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private SemanticObservation latest;
    private long publishedSessionGeneration = -1;

    public ServerBoatSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    public synchronized BoatSkillFrame publish(
            SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match boat frame source"
            );
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish without an active companion body"
            );
        }
        latest = observation;
        publishedSessionGeneration = status.sessionGeneration();
        return current().orElseThrow();
    }

    @Override
    public synchronized Optional<BoatSkillFrame> current() {
        if (!server.isSameThread() || latest == null) {
            return Optional.empty();
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration()
                != publishedSessionGeneration) {
            return Optional.empty();
        }
        ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !player.isAlive()
                || !expectedPlayerId.equals(player.getUUID())) {
            return Optional.empty();
        }
        DimensionRef dimension = DimensionRef.parse(
                player.level().dimension().identifier().toString()
        );
        if (!dimension.id().equals(latest.body().dimensionId())) {
            return Optional.empty();
        }
        long gameTime = player.level().getGameTime();
        if (gameTime < latest.body().gameTime()) {
            return Optional.empty();
        }
        double danger = latest.dangers().stream()
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        Optional<BoatState> controlled = Optional.empty();
        if (player.getControlledVehicle() instanceof AbstractBoat boat
                && !boat.isRemoved()
                && boat.isAlive()
                && boat.getControllingPassenger() == player) {
            controlled = Optional.of(new BoatState(
                    boat.getUUID(),
                    vector(boat.position()),
                    boat.getYRot(),
                    vector(boat.getDeltaMovement()),
                    boat.horizontalCollision,
                    boat.isUnderWater()
            ));
        }
        return Optional.of(new BoatSkillFrame(
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
                controlled
        ));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Boat observations require the server thread"
            );
        }
    }

    private static PerceptionVec3 vector(Vec3 value) {
        return new PerceptionVec3(value.x, value.y, value.z);
    }
}
