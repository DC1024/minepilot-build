package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.waypoint.DimensionRef;

final class LocalAStarPlannerTest {
    private static final LocalPlanningBudget GENEROUS_BUDGET =
        new LocalPlanningBudget(50_000, Duration.ofSeconds(1));

    @Test
    void routesAroundTwoBlockObstacleUsingOnlyObservedVoxels() {
        final Map<GridPos, VoxelKind> overrides = new HashMap<>();
        overrides.put(new GridPos(2, 1, 1), VoxelKind.SOLID);
        overrides.put(new GridPos(2, 2, 1), VoxelKind.SOLID);
        final List<ObservedVoxel> voxels = flatVoxels(0, 4, 0, 2, overrides, Map.of());
        final GridPos start = new GridPos(0, 1, 1);
        final GridPos goal = new GridPos(4, 1, 1);

        final LocalRoute first = new LocalAStarPlanner().plan(
            snapshot(1, voxels),
            start,
            goal,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        final List<ObservedVoxel> reversed = new ArrayList<>(voxels);
        Collections.reverse(reversed);
        final LocalRoute second = new LocalAStarPlanner().plan(
            snapshot(1, reversed),
            start,
            goal,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertTrue(first.found());
        assertEquals(first.steps(), second.steps());
        assertFalse(
            first.steps().stream()
                .map(LocalStep::to)
                .anyMatch(new GridPos(2, 1, 1)::equals)
        );
        assertTrue(first.steps().size() > 4);
    }

    @Test
    void usesJumpForStepUpAndControlledDrop() {
        final Map<GridPos, VoxelKind> overrides = new HashMap<>();
        overrides.put(new GridPos(1, 1, 0), VoxelKind.SOLID);
        overrides.put(new GridPos(1, 2, 0), VoxelKind.AIR);
        overrides.put(new GridPos(1, 3, 0), VoxelKind.AIR);
        final LocalNavSnapshot snapshot = snapshot(
            2,
            flatVoxels(0, 2, 0, 0, overrides, Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner().plan(
            snapshot,
            new GridPos(0, 1, 0),
            new GridPos(2, 1, 0),
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertTrue(route.found());
        assertEquals(
            List.of(MovementPrimitive.JUMP, MovementPrimitive.JUMP),
            route.steps().stream().map(LocalStep::primitive).toList()
        );
        assertEquals(new GridPos(1, 2, 0), route.steps().getFirst().to());
    }

    @Test
    void hardcoreWeightsDangerMoreThanNormalNavigation() {
        final GridPos dangerPosition = new GridPos(2, 1, 1);
        final LocalNavSnapshot snapshot = snapshot(
            3,
            flatVoxels(
                0,
                4,
                0,
                2,
                Map.of(),
                Map.of(dangerPosition, 0.03)
            )
        );
        final GridPos start = new GridPos(0, 1, 1);
        final GridPos goal = new GridPos(4, 1, 1);
        final LocalAStarPlanner planner = new LocalAStarPlanner();

        final LocalRoute normal = planner.plan(
            snapshot,
            start,
            goal,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        final LocalRoute hardcore = planner.plan(
            snapshot,
            start,
            goal,
            LocalPlannerOptions.hardcore(GENEROUS_BUDGET)
        );

        assertTrue(normal.found());
        assertTrue(hardcore.found());
        assertTrue(normal.steps().stream().map(LocalStep::to).anyMatch(dangerPosition::equals));
        assertFalse(hardcore.steps().stream().map(LocalStep::to).anyMatch(dangerPosition::equals));
        assertTrue(hardcore.steps().size() > normal.steps().size());
    }

    @Test
    void unknownVoxelIsNeverAssumedToBeTraversable() {
        final List<ObservedVoxel> disconnected = new ArrayList<>();
        addStandingColumn(disconnected, 0, 0, 0.0);
        addStandingColumn(disconnected, 2, 0, 0.0);
        final LocalRoute route = new LocalAStarPlanner().plan(
            snapshot(4, disconnected),
            new GridPos(0, 1, 0),
            new GridPos(2, 1, 0),
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertEquals(LocalRouteStatus.NO_PATH, route.status());
        assertTrue(route.steps().isEmpty());
    }

    @Test
    void arrivalRegionStopsAtReachableObservedCellBeforeOccupiedTarget() {
        final Map<GridPos, VoxelKind> overrides = new HashMap<>();
        overrides.put(new GridPos(4, 1, 0), VoxelKind.SOLID);
        overrides.put(new GridPos(4, 2, 0), VoxelKind.SOLID);
        final LocalNavSnapshot snapshot = snapshot(
            5,
            flatVoxels(0, 4, 0, 0, overrides, Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner().planWithinRadius(
            snapshot,
            new GridPos(0, 1, 0),
            new PerceptionVec3(4.5, 1.0, 0.5),
            2.5,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertTrue(route.found());
        assertEquals(new GridPos(2, 1, 0), route.reached());
        assertFalse(
            route.steps().stream()
                .map(LocalStep::to)
                .anyMatch(new GridPos(4, 1, 0)::equals)
        );
    }

    @Test
    void arrivalRegionNeverUsesUnknownEndpoint() {
        final LocalNavSnapshot snapshot = snapshot(
            6,
            flatVoxels(0, 1, 0, 0, Map.of(), Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner().planWithinRadius(
            snapshot,
            new GridPos(0, 1, 0),
            new PerceptionVec3(5.5, 1.0, 0.5),
            1.5,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertEquals(
            LocalRouteStatus.INVALID_START_OR_GOAL,
            route.status()
        );
        assertTrue(route.steps().isEmpty());
    }

    @Test
    void observedFrontierAdvancesWithoutInventingTheUnknownCorridor() {
        final LocalNavSnapshot snapshot = snapshot(
            7,
            flatVoxels(0, 1, 0, 0, Map.of(), Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner()
            .planTowardObserved(
                snapshot,
                new GridPos(0, 1, 0),
                new PerceptionVec3(8.5, 1.0, 0.5),
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
            );

        assertTrue(route.found());
        assertEquals(new GridPos(1, 1, 0), route.reached());
        assertEquals(1, route.steps().size());
        assertEquals(
            Set.of(
                new GridPos(1, 0, 0),
                new GridPos(1, 1, 0),
                new GridPos(1, 2, 0)
            ),
            route.steps().getFirst().observedDependencies()
        );
    }

    @Test
    void observedFrontierNeverWalksBackwardFromTheTarget() {
        final LocalNavSnapshot snapshot = snapshot(
            8,
            flatVoxels(-1, 0, 0, 0, Map.of(), Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner()
            .planTowardObserved(
                snapshot,
                new GridPos(0, 1, 0),
                new PerceptionVec3(8.5, 1.0, 0.5),
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
            );

        assertEquals(LocalRouteStatus.NO_PATH, route.status());
        assertTrue(route.steps().isEmpty());
    }

    @Test
    void observedFrontierMayBeginATangentialObservedDetour() {
        /*
         * The target is the unobserved cell directly north. East and west are
         * safe, observed transitions. A caller may use one of them to begin a
         * detour around an observed obstruction, but must remember traversed
         * frontier cells and refuse a repeated loop.
         */
        final LocalNavSnapshot snapshot = snapshot(
            9,
            flatVoxels(-1, 1, 0, 0, Map.of(), Map.of())
        );

        final LocalRoute route = new LocalAStarPlanner()
            .planTowardObserved(
                snapshot,
                new GridPos(0, 1, 0),
                new PerceptionVec3(0.5, 1.0, -0.5),
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
            );

        assertTrue(route.found());
        assertEquals(1, route.steps().size());
        assertTrue(
            route.reached().equals(new GridPos(-1, 1, 0))
                || route.reached().equals(new GridPos(1, 1, 0))
        );
    }

    @Test
    void verticalMovementRequiresObservedHeadClearance() {
        final GridPos start = new GridPos(0, 1, 0);
        final GridPos goal = new GridPos(0, 2, 0);
        final List<ObservedVoxel> obstructed = List.of(
            new ObservedVoxel(start, VoxelKind.CLIMBABLE, 0.0, 0),
            new ObservedVoxel(goal, VoxelKind.CLIMBABLE, 0.0, 0)
        );

        final LocalRoute unknownHead = new LocalAStarPlanner().plan(
            snapshot(4, obstructed),
            start,
            goal,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        assertEquals(LocalRouteStatus.NO_PATH, unknownHead.status());

        final List<ObservedVoxel> clear = new ArrayList<>(obstructed);
        clear.add(new ObservedVoxel(goal.above(), VoxelKind.AIR, 0.0, 0));
        final LocalRoute observedHead = new LocalAStarPlanner().plan(
            snapshot(4, clear),
            start,
            goal,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        assertTrue(observedHead.found());
        assertEquals(MovementPrimitive.CLIMB, observedHead.steps().getFirst().primitive());
    }

    @Test
    void liquidOrClimbableStartStillRequiresObservedHeadClearance() {
        final GridPos start = new GridPos(0, 1, 0);
        final LocalRoute route = new LocalAStarPlanner().plan(
            snapshot(
                4,
                List.of(new ObservedVoxel(start, VoxelKind.WATER, 0.0, 0))
            ),
            start,
            start,
            LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertEquals(LocalRouteStatus.INVALID_START_OR_GOAL, route.status());
    }

    @Test
    void startBodyContactIsAllowedButDestinationNeedsFreshExplicitTop() {
        final long revision = 7;
        final GridPos start = new GridPos(0, 1, 0);
        final GridPos goal = new GridPos(1, 1, 0);
        final List<ObservedVoxel> safe = twoCellEvidence(
                revision,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.STURDY_FULL_TOP,
                revision
        );

        final LocalRoute route = new LocalAStarPlanner().plan(
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        safe
                ),
                start,
                goal,
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );

        assertTrue(route.found());
        assertEquals(
                MovementPrimitive.WALK,
                route.steps().getFirst().primitive()
        );

        final LocalRoute genericSolid = new LocalAStarPlanner().plan(
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        twoCellEvidence(
                                revision,
                                OccupancyEvidence.MULTI_RAY_CLEAR,
                                TopSupportAffordance.UNKNOWN,
                                revision
                        )
                ),
                start,
                goal,
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        assertEquals(LocalRouteStatus.NO_PATH, genericSolid.status());

        final LocalRoute staleTop = new LocalAStarPlanner().plan(
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        twoCellEvidence(
                                revision,
                                OccupancyEvidence.MULTI_RAY_CLEAR,
                                TopSupportAffordance.STURDY_FULL_TOP,
                                revision - 1
                        )
                ),
                start,
                goal,
                LocalPlannerOptions.hardcore(GENEROUS_BUDGET)
        );
        assertEquals(LocalRouteStatus.NO_PATH, staleTop.status());
    }

    @Test
    void singleAirRayNeverBecomesANormalOrHardcoreCorridor() {
        final long revision = 8;
        final GridPos start = new GridPos(0, 1, 0);
        final GridPos goal = new GridPos(1, 1, 0);
        final LocalNavSnapshot snapshot = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                twoCellEvidence(
                        revision,
                        OccupancyEvidence.SINGLE_RAY_CLEAR,
                        TopSupportAffordance.STURDY_FULL_TOP,
                        revision
                )
        );

        assertEquals(
                LocalRouteStatus.NO_PATH,
                new LocalAStarPlanner().plan(
                        snapshot,
                        start,
                        goal,
                        LocalPlannerOptions.normal(GENEROUS_BUDGET)
                ).status()
        );
        assertEquals(
                LocalRouteStatus.NO_PATH,
                new LocalAStarPlanner().plan(
                        snapshot,
                        start,
                        goal,
                        LocalPlannerOptions.hardcore(GENEROUS_BUDGET)
                ).status()
        );
    }

    @Test
    void explicitWaterClimbAndClosedDoorEvidenceRemainUsable() {
        final long revision = 9;
        final GridPos waterStart = new GridPos(0, 1, 0);
        final GridPos waterGoal = new GridPos(1, 1, 0);
        final LocalRoute water = new LocalAStarPlanner().plan(
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        List.of(
                                voxel(
                                        waterStart,
                                        VoxelKind.WATER,
                                        revision,
                                        OccupancyEvidence.BODY_OCCUPIED,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        waterStart.above(),
                                        VoxelKind.WATER,
                                        revision,
                                        OccupancyEvidence.BODY_OCCUPIED,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        waterGoal,
                                        VoxelKind.WATER,
                                        revision,
                                        OccupancyEvidence.SURFACE_HIT,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        waterGoal.above(),
                                        VoxelKind.AIR,
                                        revision,
                                        OccupancyEvidence.MULTI_RAY_CLEAR,
                                        TopSupportAffordance.UNKNOWN
                                )
                        )
                ),
                waterStart,
                waterGoal,
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        assertTrue(water.found());
        assertEquals(
                MovementPrimitive.SWIM,
                water.steps().getFirst().primitive()
        );

        final GridPos doorStart = new GridPos(0, 1, 0);
        final GridPos doorGoal = new GridPos(1, 1, 0);
        final LocalRoute door = new LocalAStarPlanner().plan(
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        List.of(
                                voxel(
                                        doorStart.below(),
                                        VoxelKind.SOLID,
                                        revision,
                                        OccupancyEvidence.BODY_CONTACT,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        doorStart,
                                        VoxelKind.AIR,
                                        revision,
                                        OccupancyEvidence.BODY_OCCUPIED,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        doorStart.above(),
                                        VoxelKind.AIR,
                                        revision,
                                        OccupancyEvidence.BODY_OCCUPIED,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        doorGoal.below(),
                                        VoxelKind.SOLID,
                                        revision,
                                        OccupancyEvidence.SURFACE_HIT,
                                        TopSupportAffordance.STURDY_FULL_TOP
                                ),
                                voxel(
                                        doorGoal,
                                        VoxelKind.CLOSED_DOOR,
                                        revision,
                                        OccupancyEvidence.SURFACE_HIT,
                                        TopSupportAffordance.UNKNOWN
                                ),
                                voxel(
                                        doorGoal.above(),
                                        VoxelKind.AIR,
                                        revision,
                                        OccupancyEvidence.MULTI_RAY_CLEAR,
                                        TopSupportAffordance.UNKNOWN
                                )
                        )
                ),
                doorStart,
                doorGoal,
                LocalPlannerOptions.normal(GENEROUS_BUDGET)
        );
        assertTrue(door.found());
        assertEquals(
                MovementPrimitive.OPEN_DOOR,
                door.steps().getFirst().primitive()
        );
    }

    @Test
    void terminatesAtStrictNodeAndTimeBudgets() {
        final LocalNavSnapshot snapshot = snapshot(
            5,
            flatVoxels(0, 10, 0, 0, Map.of(), Map.of())
        );
        final LocalPlannerOptions nodeLimited = LocalPlannerOptions.normal(
            new LocalPlanningBudget(1, Duration.ofSeconds(1))
        );
        final LocalRoute nodeResult = new LocalAStarPlanner().plan(
            snapshot,
            new GridPos(0, 1, 0),
            new GridPos(10, 1, 0),
            nodeLimited
        );
        assertEquals(LocalRouteStatus.NODE_BUDGET_EXCEEDED, nodeResult.status());
        assertEquals(1, nodeResult.expandedNodes());

        final AtomicLong clock = new AtomicLong();
        final LocalAStarPlanner timeBoundPlanner =
            new LocalAStarPlanner(() -> clock.getAndAdd(100));
        final LocalRoute timeResult = timeBoundPlanner.plan(
            snapshot,
            new GridPos(0, 1, 0),
            new GridPos(10, 1, 0),
            LocalPlannerOptions.normal(
                new LocalPlanningBudget(100, Duration.ofNanos(50))
            )
        );
        assertEquals(LocalRouteStatus.TIME_BUDGET_EXCEEDED, timeResult.status());
        assertEquals(0, timeResult.expandedNodes());
    }

    @Test
    void emitsDoorSwimClimbBridgeAndPillarPrimitivesWhenObserved() {
        assertEquals(
            List.of(
                MovementPrimitive.WALK,
                MovementPrimitive.SPRINT,
                MovementPrimitive.JUMP,
                MovementPrimitive.SWIM,
                MovementPrimitive.CLIMB,
                MovementPrimitive.OPEN_DOOR,
                MovementPrimitive.BRIDGE,
                MovementPrimitive.PILLAR
            ),
            List.of(MovementPrimitive.values())
        );
    }

    private static LocalNavSnapshot snapshot(long revision, List<ObservedVoxel> voxels) {
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels.stream().map(voxel -> {
                    final OccupancyEvidence occupancy =
                            voxel.occupancyEvidence()
                            == OccupancyEvidence.UNKNOWN
                        ? switch (voxel.kind()) {
                            case AIR ->
                                    OccupancyEvidence.MULTI_RAY_CLEAR;
                            case SOLID, WATER, LAVA, CLIMBABLE,
                                    OPEN_DOOR, CLOSED_DOOR ->
                                    OccupancyEvidence.SURFACE_HIT;
                        }
                        : voxel.occupancyEvidence();
                    final TopSupportAffordance support =
                            voxel.kind().supportsWeight()
                            && voxel.topSupportAffordance()
                                == TopSupportAffordance.UNKNOWN
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : voxel.topSupportAffordance();
                    return new ObservedVoxel(
                            voxel.position(),
                            voxel.kind(),
                            voxel.danger(),
                            revision,
                            occupancy,
                            support
                    );
                }).toList()
        );
    }

    private static List<ObservedVoxel> twoCellEvidence(
            long revision,
            OccupancyEvidence destinationAir,
            TopSupportAffordance destinationTop,
            long destinationSupportRevision
    ) {
        final GridPos start = new GridPos(0, 1, 0);
        final GridPos goal = new GridPos(1, 1, 0);
        return List.of(
                voxel(
                        start.below(),
                        VoxelKind.SOLID,
                        revision,
                        OccupancyEvidence.BODY_CONTACT,
                        TopSupportAffordance.UNKNOWN
                ),
                voxel(
                        start,
                        VoxelKind.AIR,
                        revision,
                        OccupancyEvidence.BODY_OCCUPIED,
                        TopSupportAffordance.UNKNOWN
                ),
                voxel(
                        start.above(),
                        VoxelKind.AIR,
                        revision,
                        OccupancyEvidence.BODY_OCCUPIED,
                        TopSupportAffordance.UNKNOWN
                ),
                voxel(
                        goal.below(),
                        VoxelKind.SOLID,
                        destinationSupportRevision,
                        OccupancyEvidence.SURFACE_HIT,
                        destinationTop
                ),
                voxel(
                        goal,
                        VoxelKind.AIR,
                        revision,
                        destinationAir,
                        TopSupportAffordance.UNKNOWN
                ),
                voxel(
                        goal.above(),
                        VoxelKind.AIR,
                        revision,
                        destinationAir,
                        TopSupportAffordance.UNKNOWN
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

    private static List<ObservedVoxel> flatVoxels(
        int minimumX,
        int maximumX,
        int minimumZ,
        int maximumZ,
        Map<GridPos, VoxelKind> overrides,
        Map<GridPos, Double> dangers
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                addStandingColumn(voxels, x, z, dangers.getOrDefault(
                    new GridPos(x, 1, z),
                    0.0
                ));
                voxels.add(new ObservedVoxel(
                    new GridPos(x, 3, z),
                    VoxelKind.AIR,
                    0.0,
                    0
                ));
            }
        }
        if (!overrides.isEmpty()) {
            final Map<GridPos, ObservedVoxel> indexed = new HashMap<>();
            voxels.forEach(voxel -> indexed.put(voxel.position(), voxel));
            overrides.forEach((position, kind) -> indexed.put(
                position,
                new ObservedVoxel(
                    position,
                    kind,
                    dangers.getOrDefault(position, 0.0),
                    0
                )
            ));
            return new ArrayList<>(indexed.values());
        }
        return voxels;
    }

    private static void addStandingColumn(
        List<ObservedVoxel> voxels,
        int x,
        int z,
        double feetDanger
    ) {
        voxels.add(new ObservedVoxel(
            new GridPos(x, 0, z),
            VoxelKind.SOLID,
            0.0,
            0
        ));
        voxels.add(new ObservedVoxel(
            new GridPos(x, 1, z),
            VoxelKind.AIR,
            feetDanger,
            0
        ));
        voxels.add(new ObservedVoxel(
            new GridPos(x, 2, z),
            VoxelKind.AIR,
            0.0,
            0
        ));
    }
}
