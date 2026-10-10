package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.waypoint.DimensionRef;

final class RouteInvalidatorTest {
    @Test
    void invalidatesOnlyTheAffectedRouteSuffix() {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            voxels.add(voxel(
                    new GridPos(x, 0, 0),
                    VoxelKind.SOLID,
                    OccupancyEvidence.SURFACE_HIT,
                    TopSupportAffordance.STURDY_FULL_TOP
            ));
            for (int y = 1; y <= 3; y++) {
                voxels.add(voxel(
                        new GridPos(x, y, 0),
                        VoxelKind.AIR,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
            }
        }
        final LocalRoute route = new LocalAStarPlanner().plan(
            new LocalNavSnapshot(DimensionRef.OVERWORLD, 1, voxels),
            new GridPos(0, 1, 0),
            new GridPos(4, 1, 0),
            LocalPlannerOptions.normal(
                new LocalPlanningBudget(1_000, Duration.ofSeconds(1))
            )
        );
        assertTrue(route.found());

        final RouteInvalidator invalidator = new RouteInvalidator();
        final GridPos changed = route.steps().get(2).to();
        final RouteInvalidation affected = invalidator.inspect(
            route,
            2,
            Set.of(changed)
        );
        assertTrue(affected.invalidated());
        assertEquals(2, affected.firstInvalidStep().orElseThrow());
        assertEquals(route.steps().subList(0, 2), affected.validPrefix());

        final RouteInvalidation unrelated = invalidator.inspect(
            route,
            3,
            Set.of(new GridPos(500, 64, 500))
        );
        assertFalse(unrelated.invalidated());
        assertEquals(route.steps(), unrelated.validPrefix());
    }

    private static ObservedVoxel voxel(
            GridPos position,
            VoxelKind kind,
            OccupancyEvidence occupancy,
            TopSupportAffordance support
    ) {
        return new ObservedVoxel(
                position,
                kind,
                0.0,
                1,
                occupancy,
                support
        );
    }
}
