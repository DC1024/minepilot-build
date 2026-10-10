package dev.mcai.companion.skills.transport;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fair semantic observation plus state owned by the ridden vehicle.
 */
public record BoatSkillFrame(
        UUID playerId,
        DimensionRef dimension,
        long currentGameTime,
        long observedAtGameTime,
        long observationRevision,
        long sessionGeneration,
        PerceptionVec3 playerPosition,
        List<VisibleEntity> visibleEntities,
        List<VisibleBlockFace> visibleBlockFaces,
        double danger,
        Optional<BoatState> controlledBoat
) {
    private static final Set<String> UNSAFE_DISMOUNT_BLOCKS = Set.of(
            "minecraft:water",
            "minecraft:lava",
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:cactus",
            "minecraft:magma_block",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:powder_snow"
    );

    public BoatSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        if (currentGameTime < 0
                || observedAtGameTime < 0
                || observationRevision < 0
                || sessionGeneration < 0
                || currentGameTime < observedAtGameTime) {
            throw new IllegalArgumentException(
                    "Boat frame counters must be non-negative and monotonic"
            );
        }
        Objects.requireNonNull(playerPosition, "playerPosition");
        visibleEntities = List.copyOf(
                Objects.requireNonNull(visibleEntities, "visibleEntities")
        );
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("danger must be in [0, 1]");
        }
        controlledBoat = Objects.requireNonNull(
                controlledBoat,
                "controlledBoat"
        );
    }

    public long observationAgeTicks() {
        return currentGameTime - observedAtGameTime;
    }

    /**
     * Requires a recently ray-observed, non-hazardous upward surface near the
     * boat before requesting vanilla dismount placement.
     */
    public boolean hasObservedSafeDismountSurface(double maximumDistance) {
        if (!Double.isFinite(maximumDistance) || maximumDistance <= 0.0) {
            throw new IllegalArgumentException(
                    "maximumDistance must be positive and finite"
            );
        }
        BoatState boat = controlledBoat.orElse(null);
        if (boat == null) {
            return false;
        }
        return visibleBlockFaces.stream().anyMatch(face ->
                face.face().equals("up")
                        && face.distance() <= maximumDistance
                        && Math.abs(
                                face.hitPosition().y()
                                        - boat.position().y()
                        ) <= 2.5
                        && safeSurface(face.blockTypeId())
        );
    }

    private static boolean safeSurface(String blockTypeId) {
        String canonical = blockTypeId.toLowerCase(Locale.ROOT);
        return !UNSAFE_DISMOUNT_BLOCKS.contains(canonical)
                && !canonical.endsWith(":air")
                && !canonical.endsWith("_fire")
                && !canonical.endsWith("_campfire");
    }
}
