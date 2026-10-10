package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable rolling first-person evidence for one mechanism work site.
 *
 * <p>Unlike {@link MechanismSiteFrame}, entries retain the exact observation
 * revision and game time at which the body saw them. Consumers must not call
 * old evidence "current"; construction actions still require a new ordinary
 * first-person observation before mutating a target.</p>
 */
public record MechanismSiteSurvey(
        UUID playerId,
        long sessionGeneration,
        DimensionRef dimension,
        long currentGameTime,
        long sourceRevision,
        GridPos feet,
        PerceptionVec3 lookDirection,
        List<InventoryItemSummary> inventory,
        List<SurfaceObservation> surfaces,
        Map<GridPos, VoxelObservation> voxels,
        Optional<SkyObservation> skyObservation
) {
    public MechanismSiteSurvey {
        Objects.requireNonNull(playerId, "playerId");
        if (sessionGeneration < 0
                || currentGameTime < 0
                || sourceRevision < 0) {
            throw new IllegalArgumentException(
                    "Mechanism survey counters are invalid"
            );
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(lookDirection, "lookDirection");
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException(
                    "Mechanism survey look direction is not normalized"
            );
        }
        inventory = List.copyOf(Objects.requireNonNull(
                inventory,
                "inventory"
        ));
        surfaces = List.copyOf(Objects.requireNonNull(
                surfaces,
                "surfaces"
        ));
        voxels = Map.copyOf(Objects.requireNonNull(voxels, "voxels"));
        skyObservation = Objects.requireNonNull(
                skyObservation,
                "skyObservation"
        );
        if (surfaces.stream().anyMatch(observation ->
                observation.observedAtGameTime() > currentGameTime
                        || observation.observationRevision()
                                > sourceRevision
        ) || voxels.entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(entry.getValue().voxel().position())
                        || entry.getValue().observedAtGameTime()
                                > currentGameTime
                        || entry.getValue().observationRevision()
                                > sourceRevision
        ) || skyObservation.stream().anyMatch(observation ->
                observation.observedAtGameTime() > currentGameTime
                        || observation.observationRevision()
                                > sourceRevision
        )) {
            throw new IllegalArgumentException(
                    "Mechanism survey evidence exceeds its source"
            );
        }
    }

    public List<VisibleBlockFace> visibleBlockFaces() {
        return surfaces.stream().map(SurfaceObservation::face).toList();
    }

    public Optional<ObservedVoxel> voxelAt(final GridPos position) {
        Objects.requireNonNull(position, "position");
        return Optional.ofNullable(voxels.get(position))
                .map(VoxelObservation::voxel);
    }

    public boolean skyVisible() {
        return skyObservation.isPresent();
    }

    public record SurfaceObservation(
            VisibleBlockFace face,
            long observedAtGameTime,
            long observationRevision
    ) {
        public SurfaceObservation {
            Objects.requireNonNull(face, "face");
            counters(observedAtGameTime, observationRevision);
        }
    }

    public record VoxelObservation(
            ObservedVoxel voxel,
            long observedAtGameTime
    ) {
        public VoxelObservation {
            Objects.requireNonNull(voxel, "voxel");
            counters(observedAtGameTime, voxel.observationRevision());
        }

        public long observationRevision() {
            return voxel.observationRevision();
        }
    }

    public record SkyObservation(
            GridPos observerFeet,
            long observedAtGameTime,
            long observationRevision
    ) {
        public SkyObservation {
            Objects.requireNonNull(observerFeet, "observerFeet");
            counters(observedAtGameTime, observationRevision);
        }
    }

    private static void counters(
            final long observedAtGameTime,
            final long observationRevision
    ) {
        if (observedAtGameTime < 0 || observationRevision < 0) {
            throw new IllegalArgumentException(
                    "Mechanism evidence counters are invalid"
            );
        }
    }
}
