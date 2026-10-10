package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.TopSupportAffordance;
import org.junit.jupiter.api.Test;

final class NavigationEvidenceTest {
    private static final GridPos POSITION = new GridPos(1, 2, 3);

    @Test
    void airNeedsMultiRayOrBodyEvidence() {
        assertFalse(NavigationEvidence.hasTraversalClearance(voxel(
                VoxelKind.AIR,
                4,
                OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        )));
        assertFalse(NavigationEvidence.hasTraversalClearance(voxel(
                VoxelKind.AIR,
                4,
                OccupancyEvidence.SINGLE_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        )));
        assertTrue(NavigationEvidence.hasTraversalClearance(voxel(
                VoxelKind.AIR,
                4,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        )));
        assertTrue(NavigationEvidence.hasTraversalClearance(voxel(
                VoxelKind.AIR,
                4,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        )));
        assertTrue(NavigationEvidence.hasFreshTraversalClearance(
                voxel(
                        VoxelKind.AIR,
                        4,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                4
        ));
        assertFalse(NavigationEvidence.hasFreshTraversalClearance(
                voxel(
                        VoxelKind.AIR,
                        3,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                4
        ));
    }

    @Test
    void directlyObservedWaterClimbableAndOpenDoorRemainTraversable() {
        for (VoxelKind kind : new VoxelKind[]{
                VoxelKind.WATER,
                VoxelKind.CLIMBABLE,
                VoxelKind.OPEN_DOOR
        }) {
            assertTrue(NavigationEvidence.hasTraversalClearance(voxel(
                    kind,
                    4,
                    OccupancyEvidence.SURFACE_HIT,
                    TopSupportAffordance.UNKNOWN
            )));
        }
        assertFalse(NavigationEvidence.hasTraversalClearance(voxel(
                VoxelKind.CLOSED_DOOR,
                4,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.UNKNOWN
        )));
        assertFalse(NavigationEvidence.hasFreshTraversalClearance(
                voxel(
                        VoxelKind.WATER,
                        3,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.UNKNOWN
                ),
                4
        ));
    }

    @Test
    void destinationNeedsCurrentExplicitSafeTop() {
        assertTrue(NavigationEvidence.isFreshStandingSupport(
                voxel(
                        VoxelKind.SOLID,
                        4,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ),
                4
        ));
        assertFalse(NavigationEvidence.isFreshStandingSupport(
                voxel(
                        VoxelKind.SOLID,
                        3,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ),
                4
        ));
        assertTrue(NavigationEvidence.isFreshStandingSupport(
                voxel(
                        VoxelKind.SOLID,
                        4,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance
                                .WALKABLE_FULL_FOOTPRINT_TOP
                ),
                4
        ));
        assertFalse(NavigationEvidence.isFreshStandingSupport(
                voxel(
                        VoxelKind.SOLID,
                        4,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.NON_STURDY_OR_PARTIAL
                ),
                4
        ));
        assertFalse(NavigationEvidence.isFreshStandingSupport(
                voxel(
                        VoxelKind.SOLID,
                        4,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.UNKNOWN
                ),
                4
        ));
    }

    @Test
    void bodyContactIsOnlyAStartSupportException() {
        final ObservedVoxel contact = voxel(
                VoxelKind.SOLID,
                4,
                OccupancyEvidence.BODY_CONTACT,
                TopSupportAffordance.UNKNOWN
        );

        assertTrue(NavigationEvidence.supportsCurrentBody(contact, 4));
        assertFalse(NavigationEvidence.isFreshStandingSupport(contact, 4));
        assertFalse(NavigationEvidence.supportsCurrentBody(contact, 5));
    }

    @Test
    void boundedRecentBodyContactProvesCropHiddenSupportOnly() {
        final ObservedVoxel recentlyStoodOn = voxel(
                VoxelKind.SOLID,
                71,
                OccupancyEvidence.BODY_CONTACT,
                TopSupportAffordance.UNKNOWN
        );
        final ObservedVoxel merelySeen = voxel(
                VoxelKind.SOLID,
                71,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.UNKNOWN
        );
        final ObservedVoxel liquidContact = voxel(
                VoxelKind.WATER,
                71,
                OccupancyEvidence.BODY_CONTACT,
                TopSupportAffordance.UNKNOWN
        );

        assertTrue(NavigationEvidence.isRecentBodyContactSupport(
                recentlyStoodOn,
                100,
                40
        ));
        assertFalse(NavigationEvidence.isRecentBodyContactSupport(
                recentlyStoodOn,
                112,
                40
        ));
        assertFalse(NavigationEvidence.isRecentBodyContactSupport(
                merelySeen,
                100,
                40
        ));
        assertFalse(NavigationEvidence.isRecentBodyContactSupport(
                liquidContact,
                100,
                40
        ));
    }

    private static ObservedVoxel voxel(
            VoxelKind kind,
            long revision,
            OccupancyEvidence occupancy,
            TopSupportAffordance support
    ) {
        return new ObservedVoxel(
                POSITION,
                kind,
                0.0,
                revision,
                occupancy,
                support
        );
    }
}
