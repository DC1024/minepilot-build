package dev.mcai.companion.skills.portal;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.phys.Vec3;

/**
 * Server-owned portal frame source.
 *
 * <p>Only observations published by the fair semantic sampler contribute
 * candidate blocks. Current dimension and pose come from the companion's own
 * ServerPlayer state, which is sufficient to verify a real traversal.</p>
 */
public final class ServerPortalSkillFrameSource
        implements PortalSkillFrameSource {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private PortalSkillFrame latest;

    public ServerPortalSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    public synchronized PortalSkillFrame publish(
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
                    "Cannot publish a portal observation without an active body"
            );
        }
        latest = PortalSkillFrame.from(
                observation,
                status.sessionGeneration(),
                serverTick()
        );
        return latest;
    }

    @Override
    public synchronized Optional<PortalSkillFrame> current() {
        if (!server.isSameThread() || latest == null) {
            return Optional.empty();
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration()
                != latest.sessionGeneration()) {
            return Optional.empty();
        }
        ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())) {
            return Optional.empty();
        }
        Vec3 position = player.position();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        PortalProcessor portal = player.portalProcess;
        return Optional.of(latest.withLivePose(
                DimensionRef.parse(
                        player.level().dimension().identifier().toString()
                ),
                serverTick(),
                vector(position),
                vector(eye),
                vector(look),
                player.onGround(),
                player.isInWater(),
                portal != null,
                portal == null ? 0 : portal.getPortalTime(),
                portal == null
                        ? Optional.empty()
                        : Optional.of(new BlockCoordinate(
                                portal.getEntryPosition().getX(),
                                portal.getEntryPosition().getY(),
                                portal.getEntryPosition().getZ()
                            ))
        ));
    }

    private long serverTick() {
        return Integer.toUnsignedLong(server.getTickCount());
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Portal observations require the server thread"
            );
        }
    }

    private static PerceptionVec3 vector(Vec3 vector) {
        return new PerceptionVec3(vector.x, vector.y, vector.z);
    }
}
