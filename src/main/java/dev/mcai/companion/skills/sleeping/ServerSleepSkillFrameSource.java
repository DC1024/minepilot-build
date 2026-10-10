package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Server-thread source for fair visible beds and the companion's own sleep
 * state.
 *
 * <p>This source is read-only. It deliberately exposes no level, block-state
 * lookup, entity query, time control, wake operation, or position control.</p>
 */
public final class ServerSleepSkillFrameSource
        implements SleepSkillFrameSource {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;

    private SleepSkillFrame.ObservedSlice latest;

    public ServerSleepSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    public synchronized SleepSkillFrame publish(
            SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match sleep frame source"
            );
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish without an active companion body"
            );
        }
        latest = SleepSkillFrame.observedSlice(
                observation,
                status.sessionGeneration()
        );
        return current().orElseThrow();
    }

    @Override
    public synchronized Optional<SleepSkillFrame> current() {
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
        ServerLevel level = player.level();
        DimensionRef currentDimension = DimensionRef.parse(
                level.dimension().identifier().toString()
        );
        long gameTime = level.getGameTime();
        if (gameTime < latest.observedAtGameTime()) {
            return Optional.empty();
        }

        int activePlayers = (int) level.players().stream()
                .filter(other -> !other.isSpectator())
                .count();
        int sleepingPlayers = (int) level.players().stream()
                .filter(other -> !other.isSpectator())
                .filter(ServerPlayer::isSleeping)
                .count();
        int sleepPercentage = level.getGameRules().get(
                GameRules.PLAYERS_SLEEPING_PERCENTAGE
        );
        int sleepersNeeded = Math.max(
                1,
                Mth.ceil(activePlayers * sleepPercentage / 100.0F)
        );

        Optional<BlockCoordinate> sleepingPosition =
                player.getSleepingPos().map(
                        position -> new BlockCoordinate(
                                position.getX(),
                                position.getY(),
                                position.getZ()
                        )
                );
        Optional<SleepRespawnPoint> respawnPoint =
                Optional.ofNullable(player.getRespawnConfig()).map(
                        config -> {
                            var data = config.respawnData();
                            var position = data.pos();
                            return new SleepRespawnPoint(
                                    DimensionRef.parse(
                                            data.dimension()
                                                    .identifier()
                                                    .toString()
                                    ),
                                    new BlockCoordinate(
                                            position.getX(),
                                            position.getY(),
                                            position.getZ()
                                    )
                            );
                        }
                );

        return Optional.of(new SleepSkillFrame(
                expectedPlayerId,
                currentDimension,
                latest.dimension(),
                gameTime,
                latest.observedAtGameTime(),
                latest.observationRevision(),
                latest.sessionGeneration(),
                player.isAlive(),
                player.isSpectator(),
                player.getHealth(),
                player.getMaxHealth(),
                player.isSleeping(),
                sleepingPosition,
                respawnPoint,
                level.isDarkOutside(),
                level.getDefaultClockTime(),
                player.getSleepTimer(),
                activePlayers,
                sleepingPlayers,
                sleepersNeeded,
                latest.visibleBlockFaces(),
                latest.dangers()
        ));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Sleep observations require the server thread"
            );
        }
    }
}
