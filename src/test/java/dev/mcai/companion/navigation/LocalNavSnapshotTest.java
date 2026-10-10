package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LocalNavSnapshotTest {
    @Test
    void exposesOnlyTheNewestObservationAsAnIncrementalDelta() {
        final ObservedVoxel retained = voxel(0, 3);
        final ObservedVoxel firstLatest = voxel(1, 7);
        final ObservedVoxel secondLatest = voxel(2, 7);

        final LocalNavSnapshot snapshot = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                7,
                List.of(retained, firstLatest, secondLatest)
        );

        assertEquals(3, snapshot.observedVoxels().size());
        assertEquals(
                List.of(firstLatest, secondLatest),
                snapshot.latestObservedVoxels()
        );
    }

    private static ObservedVoxel voxel(
            final int x,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, 64, 0),
                VoxelKind.AIR,
                0.0,
                revision,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }
}
