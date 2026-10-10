package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A fair bed observation combined with the bound player's live self-state.
 *
 * <p>Bed candidates and danger signals originate only in the most recent
 * first-person semantic sample. Sleeping, sleeping position, respawn point,
 * clock brightness, and sleep timer are ordinary state belonging to the
 * companion's own {@code ServerPlayer}.</p>
 */
public record SleepSkillFrame(
        UUID playerId,
        DimensionRef currentDimension,
        DimensionRef observedDimension,
        long currentGameTime,
        long observedAtGameTime,
        long observationRevision,
        long sessionGeneration,
        boolean alive,
        boolean spectator,
        float health,
        float maximumHealth,
        boolean sleeping,
        Optional<BlockCoordinate> sleepingPosition,
        Optional<SleepRespawnPoint> respawnPoint,
        boolean darkOutside,
        long clockTime,
        int sleepTimer,
        int activePlayers,
        int sleepingPlayers,
        int sleepersNeeded,
        List<VisibleBlockFace> visibleBlockFaces,
        List<DangerSignal> dangers
) {
    public SleepSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(observedDimension, "observedDimension");
        if (currentGameTime < 0
                || observedAtGameTime < 0
                || currentGameTime < observedAtGameTime
                || observationRevision < 0
                || sessionGeneration < 0
                || clockTime < 0
                || sleepTimer < 0
                || activePlayers < 0
                || sleepingPlayers < 0
                || sleepersNeeded < 1
                || sleepingPlayers > activePlayers
                || !Float.isFinite(health)
                || !Float.isFinite(maximumHealth)
                || health < 0.0F
                || maximumHealth <= 0.0F
                || health > maximumHealth) {
            throw new IllegalArgumentException(
                    "Invalid sleeping frame counters or self-state"
            );
        }
        sleepingPosition = Objects.requireNonNull(
                sleepingPosition,
                "sleepingPosition"
        );
        respawnPoint = Objects.requireNonNull(
                respawnPoint,
                "respawnPoint"
        );
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
        dangers = List.copyOf(Objects.requireNonNull(dangers, "dangers"));
    }

    public long observationAgeTicks() {
        return currentGameTime - observedAtGameTime;
    }

    public double healthFraction() {
        return health / maximumHealth;
    }

    public boolean projectedSleepThresholdMet() {
        int projected = sleepingPlayers + (sleeping ? 0 : 1);
        return projected >= sleepersNeeded;
    }

    public boolean respawnMatches(
            DimensionRef dimension,
            BlockCoordinate block
    ) {
        return respawnPoint
                .map(point -> point.dimension().equals(dimension)
                        && point.block().equals(block))
                .orElse(false);
    }

    public static ObservedSlice observedSlice(
            SemanticObservation observation,
            long sessionGeneration
    ) {
        Objects.requireNonNull(observation, "observation");
        if (sessionGeneration < 0) {
            throw new IllegalArgumentException(
                    "sessionGeneration must be non-negative"
            );
        }
        return new ObservedSlice(
                observation.body().playerId(),
                DimensionRef.parse(observation.body().dimensionId()),
                observation.body().gameTime(),
                observation.sequence(),
                sessionGeneration,
                observation.visibleBlockFaces(),
                observation.dangers()
        );
    }

    /**
     * Immutable fair portion retained between semantic samples.
     */
    public record ObservedSlice(
            UUID playerId,
            DimensionRef dimension,
            long observedAtGameTime,
            long observationRevision,
            long sessionGeneration,
            List<VisibleBlockFace> visibleBlockFaces,
            List<DangerSignal> dangers
    ) {
        public ObservedSlice {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(dimension, "dimension");
            if (observedAtGameTime < 0
                    || observationRevision < 0
                    || sessionGeneration < 0) {
                throw new IllegalArgumentException(
                        "Observed sleeping counters must be non-negative"
                );
            }
            visibleBlockFaces = List.copyOf(
                    Objects.requireNonNull(
                            visibleBlockFaces,
                            "visibleBlockFaces"
                    )
            );
            dangers = List.copyOf(
                    Objects.requireNonNull(dangers, "dangers")
            );
        }
    }
}
