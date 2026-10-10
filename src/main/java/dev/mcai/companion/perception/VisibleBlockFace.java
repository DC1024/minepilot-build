package dev.mcai.companion.perception;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One surface actually hit by a finite first-person ray.
 */
public record VisibleBlockFace(
        BlockCoordinate block,
        String blockTypeId,
        String face,
        PerceptionVec3 hitPosition,
        double distance,
        PerceptionProvenance provenance,
        Map<String, String> stateProperties,
        TopSupportAffordance topSupportAffordance,
        CollisionAffordance collisionAffordance,
        int adjacentLightLevel
) {
    public static final int MAX_STATE_PROPERTIES = 32;
    private static final Pattern STATE_TOKEN =
            Pattern.compile("[a-z0-9_.:/-]{1,64}");

    public VisibleBlockFace {
        Objects.requireNonNull(block, "block");
        blockTypeId = PerceptionValidation.identifier(blockTypeId, "blockTypeId");
        face = PerceptionValidation.identifier(face, "face");
        Objects.requireNonNull(hitPosition, "hitPosition");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(
                topSupportAffordance,
                "topSupportAffordance"
        );
        Objects.requireNonNull(collisionAffordance, "collisionAffordance");
        stateProperties = Collections.unmodifiableMap(
                new TreeMap<>(
                        Objects.requireNonNull(
                                stateProperties,
                                "stateProperties"
                        )
                )
        );
        distance = PerceptionValidation.finite(distance, "distance");
        if (distance < 0.0
                || provenance
                    != PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                    && provenance
                        != PerceptionProvenance
                            .BLOCK_TRANSLUCENT_RAY_SAMPLE) {
            throw new IllegalArgumentException("Invalid visible block face");
        }
        if (adjacentLightLevel < -1 || adjacentLightLevel > 15) {
            throw new IllegalArgumentException(
                    "Adjacent light level must be unknown (-1) or 0..15"
            );
        }
        if (stateProperties.size() > MAX_STATE_PROPERTIES
                || stateProperties.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null
                                || entry.getValue() == null
                                || !STATE_TOKEN.matcher(
                                        entry.getKey()
                                ).matches()
                                || !STATE_TOKEN.matcher(
                                        entry.getValue()
                                ).matches()
                )) {
            throw new IllegalArgumentException(
                    "Invalid visible block state properties"
            );
        }
    }

    /**
     * Compatibility constructor for callers compiled against semantic format
     * 6.  Unknown remains fail-closed; production samplers publish the
     * collision affordance explicitly.
     */
    public VisibleBlockFace(
            BlockCoordinate block,
            String blockTypeId,
            String face,
            PerceptionVec3 hitPosition,
            double distance,
            PerceptionProvenance provenance,
            Map<String, String> stateProperties,
            TopSupportAffordance topSupportAffordance,
            int adjacentLightLevel
    ) {
        this(
                block,
                blockTypeId,
                face,
                hitPosition,
                distance,
                provenance,
                stateProperties,
                topSupportAffordance,
                CollisionAffordance.UNKNOWN,
                adjacentLightLevel
        );
    }

    /**
     * Compatibility constructor for existing adapters which do not yet
     * publish light at the visible face's adjacent cell. Unknown is kept as
     * {@code -1}; mechanism planners must fail closed instead of guessing.
     */
    public VisibleBlockFace(
            BlockCoordinate block,
            String blockTypeId,
            String face,
            PerceptionVec3 hitPosition,
            double distance,
            PerceptionProvenance provenance,
            Map<String, String> stateProperties,
            TopSupportAffordance topSupportAffordance
    ) {
        this(
                block,
                blockTypeId,
                face,
                hitPosition,
                distance,
                provenance,
                stateProperties,
                topSupportAffordance,
                CollisionAffordance.UNKNOWN,
                -1
        );
    }

    /**
     * Compatibility constructor for callers that do not need block-state
     * details. New fair perception samples always provide the visible state.
     */
    public VisibleBlockFace(
            BlockCoordinate block,
            String blockTypeId,
            String face,
            PerceptionVec3 hitPosition,
            double distance,
            PerceptionProvenance provenance,
            Map<String, String> stateProperties
    ) {
        this(
                block,
                blockTypeId,
                face,
                hitPosition,
                distance,
                provenance,
                stateProperties,
                TopSupportAffordance.UNKNOWN,
                CollisionAffordance.UNKNOWN,
                -1
        );
    }

    /**
     * Compatibility constructor for adapters that have not yet published
     * support affordances.  UNKNOWN is fail-closed for mining movement.
     */
    public VisibleBlockFace(
            BlockCoordinate block,
            String blockTypeId,
            String face,
            PerceptionVec3 hitPosition,
            double distance,
            PerceptionProvenance provenance
    ) {
        this(
                block,
                blockTypeId,
                face,
                hitPosition,
                distance,
                provenance,
                Map.of(),
                TopSupportAffordance.UNKNOWN,
                CollisionAffordance.UNKNOWN,
                -1
        );
    }

    static boolean isSafeStateToken(final String value) {
        return value != null && STATE_TOKEN.matcher(value).matches();
    }
}
