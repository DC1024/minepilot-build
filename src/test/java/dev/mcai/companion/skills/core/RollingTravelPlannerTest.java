package dev.mcai.companion.skills.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RollingTravelPlannerTest {
    private static final long REVISION = 4;
    private static final GridPos START = new GridPos(0, 1, 0);
    private static final GridPos DESTINATION = new GridPos(1, 1, 0);
    private static final TravelToParameters TARGET =
            new TravelToParameters(
                    DimensionRef.OVERWORLD,
                    4.5,
                    1.0,
                    0.5,
                    0.5
            );

    @Test
    void selectsOnlyExplicitlySupportedMultiRayCandidate() {
        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                select(snapshot(
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.STURDY_FULL_TOP,
                        REVISION
                )).status()
        );
        assertEquals(
                RollingTravelPlanner.SelectionStatus.NEEDS_OBSERVATION,
                select(snapshot(
                        OccupancyEvidence.SINGLE_RAY_CLEAR,
                        TopSupportAffordance.STURDY_FULL_TOP,
                        REVISION
                )).status()
        );
        assertEquals(
                RollingTravelPlanner.SelectionStatus.NEEDS_OBSERVATION,
                select(snapshot(
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN,
                        REVISION
                )).status()
        );
        assertEquals(
                RollingTravelPlanner.SelectionStatus.NEEDS_OBSERVATION,
                select(snapshot(
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.STURDY_FULL_TOP,
                        REVISION - 1
                )).status()
        );
        assertEquals(
                RollingTravelPlanner.SelectionStatus.NEEDS_OBSERVATION,
                select(snapshot(
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.STURDY_FULL_TOP,
                        REVISION,
                        REVISION - 1
                )).status()
        );
    }

    @Test
    void acceptsTheCurrentGroundedBodyAsASafeArrival() {
        final LocalNavSnapshot snapshot = snapshot(
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.STURDY_FULL_TOP,
                REVISION
        );
        final RollingTravelPlanner planner =
                new RollingTravelPlanner(
                        new LocalAStarPlanner(),
                        CoreSkillPolicy.defaults(),
                        TravelSkillPolicy.defaults()
                );

        assertTrue(
                planner.isSafeArrival(snapshot, START, true),
                "Same-sample occupied feet/head plus on-ground body "
                    + "contact are direct safety evidence at the current "
                    + "body, even when the floor top is outside the view fan"
        );
    }

    @Test
    void visibleLavaIsNotMisreportedAsAStandableDangerBlockedRoute() {
        final LocalNavSnapshot snapshot = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                REVISION,
                List.of(
                        voxel(
                                START.below(),
                                VoxelKind.SOLID,
                                REVISION,
                                OccupancyEvidence.BODY_CONTACT,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                START,
                                VoxelKind.AIR,
                                REVISION,
                                OccupancyEvidence.BODY_OCCUPIED,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                START.above(),
                                VoxelKind.AIR,
                                REVISION,
                                OccupancyEvidence.BODY_OCCUPIED,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                DESTINATION.below(),
                                VoxelKind.SOLID,
                                REVISION,
                                OccupancyEvidence.SURFACE_HIT,
                                TopSupportAffordance.STURDY_FULL_TOP
                        ),
                        voxel(
                                DESTINATION,
                                VoxelKind.LAVA,
                                REVISION,
                                OccupancyEvidence.SURFACE_HIT,
                                TopSupportAffordance.NON_STURDY_OR_PARTIAL
                        ),
                        voxel(
                                DESTINATION.above(),
                                VoxelKind.AIR,
                                REVISION,
                                OccupancyEvidence.MULTI_RAY_CLEAR,
                                TopSupportAffordance.UNKNOWN
                        )
                )
        );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.NEEDS_OBSERVATION,
                select(snapshot).status(),
                "An intrinsically impassable hazard is not a standable "
                    + "route candidate and cannot alone prove danger blocking"
        );
    }

    @Test
    void exposesDisconnectedPreferredCandidateForBoundedNextTickRetry() {
        final GridPos connected = new GridPos(1, 1, 0);
        final GridPos disconnected = new GridPos(5, 1, 0);
        final LocalNavSnapshot snapshot = standableSnapshot(
                START,
                Set.of(START, connected, disconnected)
        );
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                6.5,
                1.0,
                0.5,
                0.5
        );
        final RollingTravelPlanner planner = planner();

        final RollingTravelPlanner.SegmentSelection first =
                planner.select(
                        snapshot,
                        START,
                        target,
                        true,
                        Set.of()
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus
                        .CANDIDATE_UNREACHABLE,
                first.status(),
                "The most target-advanced but disconnected candidate must "
                    + "be rejected explicitly, not misreported as a globally "
                    + "unknown route"
        );
        assertEquals(disconnected, first.endpoint().orElseThrow());

        final RollingTravelPlanner.SegmentSelection second =
                planner.select(
                        snapshot,
                        START,
                        target,
                        true,
                        Set.of(disconnected)
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                second.status()
        );
        assertEquals(connected, second.endpoint().orElseThrow());
    }

    @Test
    void courseRecoveryUsesObservedGroundToReturnTowardJourneyLine() {
        final GridPos displaced = new GridPos(8, 1, -8);
        final Set<GridPos> cells = new LinkedHashSet<>();
        for (int x = 0; x <= 8; x++) {
            cells.add(new GridPos(x, 1, -8));
        }
        final LocalNavSnapshot snapshot =
                standableSnapshot(displaced, cells);
        final PerceptionVec3 origin =
                new PerceptionVec3(0.5, 1.0, 0.5);
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                -40.5,
                0.5
        );

        final RollingTravelPlanner.SegmentSelection selection =
                planner().select(
                        snapshot,
                        displaced,
                        target,
                        true,
                        Set.of(),
                        origin,
                        true,
                        1
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                selection.status()
        );
        assertTrue(selection.courseRecovery());
        assertEquals(
                new GridPos(0, 1, -8),
                selection.endpoint().orElseThrow(),
                "Recovery must prefer the safely reachable cell with the "
                    + "smallest course deviation"
        );
    }

    @Test
    void onCourseDeadEndSelectsABoundedObservedDetour() {
        final GridPos stranded = new GridPos(0, 1, -4);
        final Set<GridPos> cells = new LinkedHashSet<>();
        cells.add(stranded);
        for (int x = 1; x <= 4; x++) {
            cells.add(new GridPos(x, 1, -4));
        }
        final LocalNavSnapshot snapshot =
                standableSnapshot(stranded, cells);
        final PerceptionVec3 origin =
                new PerceptionVec3(0.5, 1.0, 0.5);
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                -40.5,
                0.5
        );

        final RollingTravelPlanner.SegmentSelection selection =
                planner().select(
                        snapshot,
                        stranded,
                        target,
                        true,
                        Set.of(),
                        origin,
                        false,
                        0
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                selection.status()
        );
        assertEquals(
                new GridPos(4, 1, -4),
                selection.endpoint().orElseThrow(),
                "A fully observed short step away from the target is legal "
                    + "when the direct frontier is exhausted"
        );
        assertTrue(!selection.courseRecovery());
    }

    @Test
    void smallExcursionOnARejectedSideReturnsToTheCourse() {
        final GridPos stranded = new GridPos(2, 1, -4);
        final Set<GridPos> cells = new LinkedHashSet<>();
        for (int x = 0; x <= 2; x++) {
            cells.add(new GridPos(x, 1, -4));
        }
        final LocalNavSnapshot snapshot =
                standableSnapshot(stranded, cells);
        final PerceptionVec3 origin =
                new PerceptionVec3(0.5, 1.0, 0.5);
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                -40.5,
                0.5
        );

        final RollingTravelPlanner.SegmentSelection selection =
                planner().select(
                        snapshot,
                        stranded,
                        target,
                        true,
                        Set.of(),
                        origin,
                        false,
                        -1
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                selection.status()
        );
        assertTrue(selection.courseRecovery());
        assertEquals(
                new GridPos(0, 1, -4),
                selection.endpoint().orElseThrow(),
                "A rejected detour must return to the route even before it "
                    + "reaches the wide recovery threshold"
        );
    }

    @Test
    void rejectedCourseSideForcesTheOppositeObservedDetour() {
        final Set<GridPos> cells = new LinkedHashSet<>();
        cells.add(START);
        for (int x = 1; x <= 4; x++) {
            cells.add(new GridPos(x, 1, 0));
            cells.add(new GridPos(-x, 1, 0));
        }
        for (int z = -1; z >= -4; z--) {
            cells.add(new GridPos(4, 1, z));
            cells.add(new GridPos(-4, 1, z));
        }
        final LocalNavSnapshot snapshot =
                standableSnapshot(START, cells);
        final PerceptionVec3 origin =
                new PerceptionVec3(0.5, 1.0, 0.5);
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                -40.5,
                0.5
        );

        final RollingTravelPlanner.SegmentSelection selection =
                planner().select(
                        snapshot,
                        START,
                        target,
                        true,
                        Set.of(),
                        origin,
                        false,
                        -1
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                selection.status()
        );
        assertTrue(
                RollingTravelPlanner.signedCourseDeviation(
                        origin,
                        target,
                        selection.endpoint().orElseThrow()
                ) > 0.0,
                "The newly selected frontier must be on the opposite side "
                    + "of the bounded journey line"
        );
    }

    @Test
    void boundedReverseFrontierEscapesARejectedDeadEnd() {
        final GridPos west = new GridPos(-1, 1, 0);
        final GridPos east = new GridPos(1, 1, 0);
        final LocalNavSnapshot snapshot = standableSnapshot(
                START,
                Set.of(START, west, east)
        );
        final TravelToParameters target = new TravelToParameters(
                DimensionRef.OVERWORLD,
                -8.5,
                1.0,
                0.5,
                0.5
        );

        final RollingTravelPlanner.SegmentSelection selection =
                planner().select(
                        snapshot,
                        START,
                        target,
                        true,
                        Set.of(west)
                );

        assertEquals(
                RollingTravelPlanner.SelectionStatus.FOUND,
                selection.status()
        );
        assertEquals(
                east,
                selection.endpoint().orElseThrow(),
                "When the only observed forward cell is a rejected dead end, "
                    + "take one bounded honest step toward the unexplored "
                    + "side rather than spin on the same frontier"
        );
    }

    private static RollingTravelPlanner.SegmentSelection select(
            LocalNavSnapshot snapshot
    ) {
        return planner().select(snapshot, START, TARGET, true, Set.of());
    }

    private static RollingTravelPlanner planner() {
        return new RollingTravelPlanner(
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                TravelSkillPolicy.defaults()
        );
    }

    private static LocalNavSnapshot standableSnapshot(
            GridPos body,
            Set<GridPos> feetCells
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (GridPos feet : feetCells) {
            final boolean current = feet.equals(body);
            voxels.add(voxel(
                    feet.below(),
                    VoxelKind.SOLID,
                    REVISION,
                    current
                            ? OccupancyEvidence.BODY_CONTACT
                            : OccupancyEvidence.SURFACE_HIT,
                    current
                            ? TopSupportAffordance.UNKNOWN
                            : TopSupportAffordance.STURDY_FULL_TOP
            ));
            voxels.add(voxel(
                    feet,
                    VoxelKind.AIR,
                    REVISION,
                    current
                            ? OccupancyEvidence.BODY_OCCUPIED
                            : OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
            voxels.add(voxel(
                    feet.above(),
                    VoxelKind.AIR,
                    REVISION,
                    current
                            ? OccupancyEvidence.BODY_OCCUPIED
                            : OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                REVISION,
                voxels
        );
    }

    private static LocalNavSnapshot snapshot(
            OccupancyEvidence destinationClearance,
            TopSupportAffordance destinationSupport,
            long supportRevision
    ) {
        return snapshot(
                destinationClearance,
                destinationSupport,
                supportRevision,
                REVISION
        );
    }

    private static LocalNavSnapshot snapshot(
            OccupancyEvidence destinationClearance,
            TopSupportAffordance destinationSupport,
            long supportRevision,
            long clearanceRevision
    ) {
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                REVISION,
                List.of(
                        voxel(
                                START.below(),
                                VoxelKind.SOLID,
                                REVISION,
                                OccupancyEvidence.BODY_CONTACT,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                START,
                                VoxelKind.AIR,
                                REVISION,
                                OccupancyEvidence.BODY_OCCUPIED,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                START.above(),
                                VoxelKind.AIR,
                                REVISION,
                                OccupancyEvidence.BODY_OCCUPIED,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                DESTINATION.below(),
                                VoxelKind.SOLID,
                                supportRevision,
                                OccupancyEvidence.SURFACE_HIT,
                                destinationSupport
                        ),
                        voxel(
                                DESTINATION,
                                VoxelKind.AIR,
                                clearanceRevision,
                                destinationClearance,
                                TopSupportAffordance.UNKNOWN
                        ),
                        voxel(
                                DESTINATION.above(),
                                VoxelKind.AIR,
                                clearanceRevision,
                                destinationClearance,
                                TopSupportAffordance.UNKNOWN
                        )
                )
        );
    }

    private static ObservedVoxel voxel(
            GridPos position,
            VoxelKind kind,
            long revision,
            OccupancyEvidence occupancy,
            TopSupportAffordance support
    ) {
        return new ObservedVoxel(
                position,
                kind,
                0.0,
                revision,
                occupancy,
                support
        );
    }
}
