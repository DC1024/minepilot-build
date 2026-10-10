package dev.mcai.companion.skills.survey;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;

/**
 * Goal-scoped aggregate made exclusively from successive fair first-person
 * semantic samples.
 */
public record SurveyResultSnapshot(
        long goalRevision,
        DimensionRef dimension,
        PerceptionVec3 origin,
        long completedGameTick,
        int sampledViews,
        long firstObservationRevision,
        long lastObservationRevision,
        List<BlockData> blocks,
        List<EntityData> entities,
        List<DangerData> dangers
) {
    /*
     * This snapshot is a model-facing summary, not the local navigation map.
     * The latter retains thousands of fairly observed voxels independently.
     * Keeping raw survey samples here below the trusted-runtime budget avoids
     * turning a successful 360-degree survey into an observation overflow.
     */
    public static final int MAXIMUM_BLOCKS = 64;
    public static final int MAXIMUM_ENTITIES = 16;

    public SurveyResultSnapshot {
        if (goalRevision < 0
                || completedGameTick < 0
                || sampledViews < 1
                || sampledViews > 48
                || firstObservationRevision < 0
                || lastObservationRevision < firstObservationRevision) {
            throw new IllegalArgumentException(
                    "Survey counters are invalid"
            );
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(origin, "origin");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        entities = List.copyOf(
                Objects.requireNonNull(entities, "entities")
        );
        dangers = List.copyOf(
                Objects.requireNonNull(dangers, "dangers")
        );
        if (blocks.size() > MAXIMUM_BLOCKS
                || entities.size() > MAXIMUM_ENTITIES) {
            throw new IllegalArgumentException(
                    "Survey result exceeds its model-facing bound"
            );
        }
    }

    public record BlockData(
            String blockId,
            int x,
            int y,
            int z,
            long sampleSequence,
            String face,
            double nearestDistance
    ) {
        public BlockData {
            blockId = identifier(blockId);
            if (sampleSequence < 0) {
                throw new IllegalArgumentException(
                        "Survey block sample sequence is invalid"
                );
            }
            if (face == null
                    || !face.matches(
                        "down|up|north|south|west|east"
                    )) {
                throw new IllegalArgumentException(
                        "Survey block face is invalid"
                );
            }
            finiteNonNegative(nearestDistance);
        }
    }

    public record EntityData(
            String entityTypeId,
            int uniqueCount,
            PerceptionVec3 nearestObservedPosition,
            double nearestDistance,
            boolean hostile,
            boolean projectile
    ) {
        public EntityData {
            entityTypeId = identifier(entityTypeId);
            if (uniqueCount < 1 || uniqueCount > MAXIMUM_ENTITIES) {
                throw new IllegalArgumentException(
                        "Survey entity count is invalid"
                );
            }
            Objects.requireNonNull(
                    nearestObservedPosition,
                    "nearestObservedPosition"
            );
            finiteNonNegative(nearestDistance);
        }
    }

    public record DangerData(
            String kind,
            double maximumObservedSeverity
    ) {
        public DangerData {
            if (kind == null
                    || kind.isBlank()
                    || kind.length() > 64
                    || kind.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Survey danger kind is invalid"
                );
            }
            if (!Double.isFinite(maximumObservedSeverity)
                    || maximumObservedSeverity < 0.0
                    || maximumObservedSeverity > 1.0) {
                throw new IllegalArgumentException(
                        "Survey danger severity is invalid"
                );
            }
        }
    }

    private static String identifier(final String value) {
        if (value == null
                || !value.matches(
                        "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"
                )) {
            throw new IllegalArgumentException(
                    "Survey registry identifier is invalid"
            );
        }
        return value;
    }

    private static void finiteNonNegative(final double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    "Survey distance is invalid"
            );
        }
    }
}
