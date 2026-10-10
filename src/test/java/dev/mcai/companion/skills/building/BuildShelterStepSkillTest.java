package dev.mcai.companion.skills.building;

import static dev.mcai.companion.skills.building.ShelterTestFixtures.flatFrame;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.flatSnapshot;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.placedFace;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.topFace;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.voxel;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.withWorld;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BuildShelterStepSkillTest {
    private static final SkillContext CONTEXT =
            new SkillContext(1, 1, 1, true, true, 0.0);

    @Test
    void crosshairRetargetCannotReopenAnotherStructuralPhase() {
        assertTrue(
                BuildShelterStepSkill.canAdaptCrosshairToRole(
                        ShelterStepRole.ROOF,
                        ShelterStepRole.ROOF
                )
        );
        assertFalse(
                BuildShelterStepSkill.canAdaptCrosshairToRole(
                        ShelterStepRole.ROOF,
                        ShelterStepRole.UPPER_WALL
                )
        );
        assertFalse(
                BuildShelterStepSkill.canAdaptCrosshairToRole(
                        ShelterStepRole.ROOF,
                        ShelterStepRole.LOWER_WALL
                )
        );
    }

    @Test
    void activePlanTraversalChoosesObservedInteriorAfterReachableBatchEnds() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos outside = plan.origin().offset(-1, 0, -1);
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final ShelterFrame fromOutside = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                outside,
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                traversableNavigation,
                base.visibleBlockFaces(),
                base.recentVisibleEntities()
        );
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.LOWER_WALL)
                .limit(3)
                .forEach(step -> confirmed.set(step.index()));

        final GridPos destination =
                BuildShelterStepSkill.activePlanTraversalTarget(
                        fromOutside,
                        plan,
                        confirmed
                ).orElseThrow();

        assertTrue(
                BuildShelterStepSkill.isInteriorFloorPosition(
                        plan,
                        destination
                )
        );
        assertFalse(outside.equals(destination));
    }

    @Test
    void activePlanTraversalUsesExteriorApronForLastOuterRoofGap() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep missingOuterRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .findFirst()
                        .orElseThrow();
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .filter(step ->
                        step.index()
                                != missingOuterRoof.index())
                .forEach(step ->
                        confirmed.set(step.index()));
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final ShelterFrame traversable = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                traversableNavigation,
                base.visibleBlockFaces(),
                base.recentVisibleEntities()
        );

        final GridPos destination =
                BuildShelterStepSkill.activePlanTraversalTarget(
                        traversable,
                        plan,
                        confirmed
                ).orElseThrow();

        assertTrue(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        destination
                ),
                "An opaque completed roof makes another interior survey "
                        + "strictly worse for the final outer-ring gap"
        );

        final GridPos fallbackDestination =
                BuildShelterStepSkill.activePlanTraversalTarget(
                        traversable,
                        plan,
                        confirmed,
                        Set.of(),
                        true
                ).orElseThrow();
        assertTrue(
                BuildShelterStepSkill.isInteriorFloorPosition(
                        plan,
                        fallbackDestination
                ),
                "After the bounded apron search has already failed, a "
                        + "deferred roof cycle must change to another "
                        + "observed interior stance instead of starting the "
                        + "same exterior circuit again"
        );
    }

    @Test
    void activePlanRoofTraversalDoesNotOscillateBetweenSurveyedApronCells() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep missingOuterRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .max(java.util.Comparator.comparingDouble(step ->
                                horizontalDistance(
                                        BuildShelterStepSkill
                                                .exteriorDoorwayStand(
                                                        plan
                                                ),
                                        step.target()
                                )))
                        .orElseThrow();
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .filter(step ->
                        step.index() != missingOuterRoof.index())
                .forEach(step ->
                        confirmed.set(step.index()));
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final ShelterFrame atDoor = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                exteriorDoor,
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                traversableNavigation,
                base.visibleBlockFaces(),
                base.recentVisibleEntities()
        );

        final GridPos first =
                BuildShelterStepSkill.activePlanTraversalTarget(
                        atDoor,
                        plan,
                        confirmed,
                        Set.of(exteriorDoor)
                ).orElseThrow();
        final ShelterFrame atFirst = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                first,
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                traversableNavigation,
                base.visibleBlockFaces(),
                base.recentVisibleEntities()
        );
        final GridPos second =
                BuildShelterStepSkill.activePlanTraversalTarget(
                        atFirst,
                        plan,
                        confirmed,
                        Set.of(exteriorDoor, first)
                ).orElseThrow();

        assertEquals(1.0, horizontalDistance(exteriorDoor, first), 1.0E-9);
        assertEquals(1.0, horizontalDistance(first, second), 1.0E-9);
        assertFalse(second.equals(exteriorDoor));
        assertTrue(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        first
                )
        );
        assertTrue(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        second
                )
        );
    }

    @Test
    void activePlanRoofTraversalBacktracksToReachObservedApronFrontier() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep missingOuterRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .findFirst()
                        .orElseThrow();
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .filter(step ->
                        step.index() != missingOuterRoof.index())
                .forEach(step ->
                        confirmed.set(step.index()));

        final GridPos current = plan.origin().offset(
                3,
                0,
                plan.exteriorDepth()
        );
        final GridPos previous = current.offset(1, 0, 0);
        final GridPos unvisitedFrontier = previous.offset(1, 0, 0);
        final var navigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.navigation().revision(),
                                current,
                                previous,
                                unvisitedFrontier
                        )
                );

        assertEquals(
                previous,
                BuildShelterStepSkill.activePlanTraversalTarget(
                        shelterFrameAt(
                                base,
                                navigation,
                                current
                        ),
                        plan,
                        confirmed,
                        Set.of(current, previous)
                ).orElseThrow(),
                "A body-verified apron cell may be the first transit hop "
                        + "toward a different observed frontier; rejecting "
                        + "every visited transit cell turns a one-sided "
                        + "observation gap into a false dead end"
        );
        final ShelterFrame arrivedAtKnownTransit = shelterFrameAt(
                base,
                navigation,
                previous
        );
        assertEquals(
                Optional.of(unvisitedFrontier),
                BuildShelterStepSkill
                        .observedActivePlanTransitContinuation(
                                false,
                                true,
                                arrivedAtKnownTransit,
                                plan,
                                confirmed,
                                previous,
                                Set.of(current, previous)
                        ),
                "Revisiting a body-verified transit cell must immediately "
                        + "continue toward the still-unvisited frontier "
                        + "instead of paying for another 24-view panorama"
        );
        assertEquals(
                Optional.empty(),
                BuildShelterStepSkill
                        .observedActivePlanTransitContinuation(
                                false,
                                false,
                                arrivedAtKnownTransit,
                                plan,
                                confirmed,
                                previous,
                                Set.of(current, previous)
                        ),
                "A first arrival at a new frontier must still stop for a "
                        + "fresh fair observation"
        );
    }

    @Test
    void roofAimFromExteriorCannotShortcutAcrossTheCompletedWall() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep roof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .findFirst()
                .orElseThrow();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos inward = new GridPos(
                plan.doorLower().x() - exterior.x(),
                0,
                plan.doorLower().z() - exterior.z()
        );
        final GridPos interior =
                plan.doorLower().offset(
                        inward.x(),
                        0,
                        inward.z()
                );
        final GridPos exteriorNeighbour =
                exterior.offset(
                        inward.z(),
                        0,
                        inward.x()
                );

        assertFalse(
                BuildShelterStepSkill
                        .isPermittedAimTraversalStand(
                                plan,
                                roof,
                                exterior,
                                interior
                        ),
                "MoveTo must not route from the apron to an interior aim "
                        + "candidate through stale AIR inside a built wall"
        );
        assertTrue(
                BuildShelterStepSkill
                        .isPermittedAimTraversalStand(
                                plan,
                                roof,
                                exterior,
                                exteriorNeighbour
                        )
        );
        assertTrue(
                BuildShelterStepSkill
                        .isPermittedAimTraversalStand(
                                plan,
                                roof,
                                interior,
                                interior
                        )
        );
        assertFalse(
                BuildShelterStepSkill
                        .isPermittedAimTraversalStand(
                                plan,
                                roof,
                                interior,
                                exterior,
                                true
                        ),
                "After this roof step has exhausted the exterior apron and "
                        + "returned through the doorway, the generic aiming "
                        + "candidate path must not send it outside again"
        );
        assertTrue(
                BuildShelterStepSkill
                        .isPermittedAimTraversalStand(
                                plan,
                                roof,
                                interior,
                                exterior,
                                false
                        ),
                "A fresh roof step may still use an observed exterior "
                        + "vantage"
        );
    }

    @Test
    void roofInteriorReturnFollowsApronDoorAndInteriorCorridor() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos farApron =
                traversableNavigation.observedVoxels()
                        .values()
                        .stream()
                        .map(ObservedVoxel::position)
                        .filter(position ->
                                BuildShelterStepSkill
                                        .isExteriorRoofApronPosition(
                                                plan,
                                                position
                                        ))
                        .max(java.util.Comparator.comparingDouble(position ->
                                horizontalDistance(
                                        position,
                                        exteriorDoor
                                )))
                        .orElseThrow();
        final GridPos firstReturn =
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        traversableNavigation,
                                        farApron
                                ),
                                plan
                        ).orElseThrow();

        assertEquals(1.0, horizontalDistance(farApron, firstReturn), 1.0E-9);
        assertTrue(
                horizontalDistance(firstReturn, exteriorDoor)
                        < horizontalDistance(farApron, exteriorDoor)
        );
        assertEquals(
                plan.doorLower(),
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        traversableNavigation,
                                        exteriorDoor
                                ),
                                plan
                        ).orElseThrow()
        );

        final GridPos inward = new GridPos(
                plan.doorLower().x() - exteriorDoor.x(),
                0,
                plan.doorLower().z() - exteriorDoor.z()
        );
        final GridPos expectedInterior =
                plan.doorLower().offset(
                        inward.x(),
                        0,
                        inward.z()
                );
        assertEquals(
                expectedInterior,
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        traversableNavigation,
                                        plan.doorLower()
                                ),
                                plan
                        ).orElseThrow()
        );
        assertTrue(
                BuildShelterStepSkill.isInteriorFloorPosition(
                        plan,
                        expectedInterior
                )
        );
    }

    @Test
    void observedRoofReturnArrivalChainsTheKnownCardinalCorridor() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos farApron =
                traversableNavigation.observedVoxels()
                        .values()
                        .stream()
                        .map(ObservedVoxel::position)
                        .filter(position ->
                                BuildShelterStepSkill
                                        .isExteriorRoofApronPosition(
                                                plan,
                                                position
                                        ))
                        .max(java.util.Comparator.comparingDouble(position ->
                                horizontalDistance(
                                        position,
                                        exteriorDoor
                                )))
                        .orElseThrow();
        final ShelterFrame arrived = shelterFrameAt(
                base,
                traversableNavigation,
                farApron
        );

        final GridPos next = BuildShelterStepSkill
                .observedRoofReturnContinuation(
                        true,
                        arrived,
                        plan,
                        farApron,
                        Set.of(farApron)
                )
                .orElseThrow();

        assertEquals(1.0, horizontalDistance(farApron, next), 1.0E-9);
        assertTrue(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(plan, next)
        );
    }

    @Test
    void stalePreArrivalFrameCannotChainRoofReturnMovement() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos expectedArrival =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos staleFeet = expectedArrival.offset(1, 0, 0);

        assertTrue(
                BuildShelterStepSkill
                        .observedRoofReturnContinuation(
                                true,
                                shelterFrameAt(
                                        base,
                                        base.navigation(),
                                        staleFeet
                                ),
                                plan,
                                expectedArrival,
                                Set.of(staleFeet)
                        )
                        .isEmpty(),
                "A movement completion receipt must wait for a newer "
                        + "semantic frame at the destination before "
                        + "chaining another physical hop"
        );
    }

    @Test
    void roofInteriorReturnEscapesOppositeApronDistanceMinimum() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final var traversableNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR
                                                ? new ObservedVoxel(
                                                        voxel.position(),
                                                        voxel.kind(),
                                                        voxel.danger(),
                                                        voxel.observationRevision(),
                                                        OccupancyEvidence
                                                                .MULTI_RAY_CLEAR,
                                                        TopSupportAffordance
                                                                .UNKNOWN
                                                )
                                                : voxel)
                                .toList()
                );
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth() - 1;
        final int maximumZ = plan.origin().z()
                + plan.exteriorDepth() - 1;
        final GridPos opposite = exteriorDoor.x()
                        < plan.origin().x()
                ? new GridPos(
                        maximumX + 1,
                        exteriorDoor.y(),
                        exteriorDoor.z()
                )
                : exteriorDoor.x() > maximumX
                        ? new GridPos(
                                plan.origin().x() - 1,
                                exteriorDoor.y(),
                                exteriorDoor.z()
                        )
                        : exteriorDoor.z()
                                < plan.origin().z()
                                ? new GridPos(
                                        exteriorDoor.x(),
                                        exteriorDoor.y(),
                                        maximumZ + 1
                                )
                                : new GridPos(
                                        exteriorDoor.x(),
                                        exteriorDoor.y(),
                                        plan.origin().z() - 1
                                );

        final GridPos firstStep = BuildShelterStepSkill
                .roofInteriorReturnTraversalTarget(
                        shelterFrameAt(
                                base,
                                traversableNavigation,
                                opposite
                        ),
                        plan
                ).orElseThrow();

        assertEquals(
                1.0,
                horizontalDistance(opposite, firstStep),
                1.0E-9
        );
        assertTrue(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(
                                plan,
                                firstStep
                        )
        );
        assertTrue(
                horizontalDistance(firstStep, exteriorDoor)
                        > horizontalDistance(
                                opposite,
                                exteriorDoor
                        ),
                "Returning from the opposite-side midpoint must allow the "
                        + "first safe ring step to move farther from the "
                        + "door before turning the corner"
        );
    }

    @Test
    void roofInteriorReturnAdvancesToObservedFrontierBeforeDoorIsSafe() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final int inwardX = doorway.x() - exteriorDoor.x();
        final int inwardZ = doorway.z() - exteriorDoor.z();
        final GridPos frontier = exteriorDoor.offset(
                inwardZ,
                0,
                -inwardX
        );
        final GridPos current = exteriorDoor.offset(
                inwardZ * 2,
                0,
                -inwardX * 2
        );
        assertTrue(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(
                                plan,
                                current
                        )
        );
        assertTrue(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(
                                plan,
                                frontier
                        )
        );
        final var partialNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.observationRevision(),
                                current,
                                frontier
                        )
                );

        assertEquals(
                frontier,
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        partialNavigation,
                                        current
                                ),
                                plan,
                                Set.of(current)
                        ).orElseThrow(),
                "The body must walk to the edge of currently proven "
                        + "terrain and survey again instead of requiring "
                        + "the entire doorway route up front"
        );
        assertTrue(
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        partialNavigation,
                                        current
                                ),
                                plan,
                                Set.of(current),
                                Set.of(frontier)
                        ).isEmpty(),
                "A return destination whose bounded MoveTo already failed "
                        + "must not be selected again from the same observed "
                        + "evidence"
        );
    }

    @Test
    void roofInteriorReturnBridgesIncompleteFanThroughBodyVerifiedTransit() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final int inwardX = doorway.x() - exteriorDoor.x();
        final int inwardZ = doorway.z() - exteriorDoor.z();
        final int tangentX = inwardZ;
        final int tangentZ = -inwardX;
        final GridPos nearDoor = exteriorDoor.offset(
                tangentX,
                0,
                tangentZ
        );
        final GridPos nextFromCurrent = exteriorDoor.offset(
                tangentX * 2,
                0,
                tangentZ * 2
        );
        final GridPos current = exteriorDoor.offset(
                tangentX * 3,
                0,
                tangentZ * 3
        );
        final java.util.ArrayList<ObservedVoxel> partialVoxels =
                new java.util.ArrayList<>(safeStandVoxels(
                        base.observationRevision(),
                        current,
                        exteriorDoor
                ));
        for (GridPos physicallyCrossed :
                List.of(nextFromCurrent, nearDoor)) {
            partialVoxels.add(new ObservedVoxel(
                    physicallyCrossed,
                    VoxelKind.AIR,
                    0.0,
                    base.observationRevision(),
                    OccupancyEvidence.UNKNOWN,
                    TopSupportAffordance.UNKNOWN
            ));
        }
        final var incompleteFan =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        partialVoxels
                );
        final ShelterFrame currentFrame = shelterFrameAt(
                base,
                incompleteFan,
                current
        );

        assertTrue(
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                currentFrame,
                                plan,
                                Set.of(current),
                                Set.of()
                        ).isEmpty(),
                "An incomplete fan alone must not invent safe transit cells"
        );
        assertEquals(
                nextFromCurrent,
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                currentFrame,
                                plan,
                                Set.of(current),
                                Set.of(),
                                Set.of(
                                        nextFromCurrent,
                                        nearDoor
                                )
                        ).orElseThrow(),
                "Ground cells physically occupied by this body during the "
                        + "same plan must reconnect the observed doorway "
                        + "corridor without teleporting across it"
        );
    }

    @Test
    void roofInteriorReturnUsesObservedExteriorDetourAroundBrokenApron() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final int outwardX = exteriorDoor.x() - doorway.x();
        final int outwardZ = exteriorDoor.z() - doorway.z();
        final int tangentX = -outwardZ;
        final int tangentZ = outwardX;
        final GridPos current = exteriorDoor.offset(
                tangentX * 2,
                0,
                tangentZ * 2
        );
        final GridPos firstDetour = current.offset(
                outwardX,
                0,
                outwardZ
        );
        final GridPos secondDetour = exteriorDoor.offset(
                tangentX + outwardX,
                0,
                tangentZ + outwardZ
        );
        final GridPos thirdDetour = exteriorDoor.offset(
                outwardX,
                0,
                outwardZ
        );
        final var navigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.observationRevision(),
                                current,
                                firstDetour,
                                secondDetour,
                                thirdDetour,
                                exteriorDoor
                        )
                );

        assertTrue(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(plan, current)
        );
        assertFalse(
                BuildShelterStepSkill
                        .isExteriorRoofApronPosition(plan, firstDetour)
        );
        assertEquals(
                firstDetour,
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        navigation,
                                        current
                                ),
                                plan
                        ).orElseThrow(),
                "A gap in the one-cell apron must use a fairly observed "
                        + "outside detour instead of declaring a false "
                        + "dead end"
        );
    }

    @Test
    void elevatedExteriorRoofStandStillReturnsThroughTheDoor() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep innerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) > 0)
                .findFirst()
                .orElseThrow();
        final GridPos elevatedExterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan)
                        .offset(1, 1, 0);

        assertFalse(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        elevatedExterior
                ),
                "An elevated natural ledge is not a legal roof-placement "
                        + "apron stance"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                innerRoof,
                                elevatedExterior
                        ),
                "A body stranded one block above the exterior apron must "
                        + "descend through observed terrain and return via "
                        + "the doorway instead of aiming through the roof"
        );
    }

    @Test
    void roofInteriorReturnCanDescendOneObservedExteriorStep() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final int outwardX = exteriorDoor.x() - doorway.x();
        final int outwardZ = exteriorDoor.z() - doorway.z();
        final int tangentX = -outwardZ;
        final int tangentZ = outwardX;
        final GridPos elevated = exteriorDoor.offset(
                tangentX * 2,
                1,
                tangentZ * 2
        );
        final GridPos descent = elevated.offset(
                -tangentX,
                -1,
                -tangentZ
        );
        final var navigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.observationRevision(),
                                elevated,
                                descent
                        )
                );

        assertEquals(
                descent,
                BuildShelterStepSkill
                        .roofInteriorReturnTraversalTarget(
                                shelterFrameAt(
                                        base,
                                        navigation,
                                        elevated
                                ),
                                plan
                        ).orElseThrow(),
                "The return graph must take one ordinary horizontal "
                        + "one-block descent toward the doorway"
        );
    }

    @Test
    void roofReturnRemainsExclusiveUntilTheBodyEntersTheInterior() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final GridPos inward = new GridPos(
                doorway.x() - exterior.x(),
                0,
                doorway.z() - exterior.z()
        );
        final GridPos interior = doorway.offset(
                inward.x(),
                0,
                inward.z()
        );

        assertTrue(
                BuildShelterStepSkill
                        .roofInteriorReturnStillPending(
                                true,
                                plan,
                                exterior
                        )
        );
        assertTrue(
                BuildShelterStepSkill
                        .roofInteriorReturnStillPending(
                                true,
                                plan,
                                doorway
                        ),
                "A newly visible roof face at the doorway must not "
                        + "preempt the compound return corridor"
        );
        assertFalse(
                BuildShelterStepSkill
                        .roofInteriorReturnStillPending(
                                true,
                                plan,
                                interior
                        )
        );
    }

    @Test
    void constructionTraversalMustActuallyEnterTheSelectedGridCell() {
        assertTrue(
                BuildShelterStepSkill
                        .CONSTRUCTION_STAND_ARRIVAL_RADIUS < 0.5,
                "A centre-targeted adjacent-cell move with radius >= 0.5 "
                        + "can report arrival while the body remains in the "
                        + "previous grid cell"
        );
    }

    @Test
    void exteriorDoorwayTargetSettlesBeyondTheOpenThreshold() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos door = plan.doorLower();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final PerceptionVec3 target =
                BuildShelterStepSkill.exteriorDoorwayTarget(plan);
        final int outwardX =
                Integer.compare(exterior.x(), door.x());
        final int outwardZ =
                Integer.compare(exterior.z(), door.z());

        assertEquals(
                exterior.x() + 0.5
                        + outwardX
                        * BuildShelterStepSkill
                                .EXTERIOR_DOORWAY_OUTWARD_BIAS,
                target.x(),
                1.0E-9
        );
        assertEquals(exterior.y(), target.y(), 1.0E-9);
        assertEquals(
                exterior.z() + 0.5
                        + outwardZ
                        * BuildShelterStepSkill
                                .EXTERIOR_DOORWAY_OUTWARD_BIAS,
                target.z(),
                1.0E-9
        );
        assertEquals(
                exterior,
                new GridPos(
                        (int) Math.floor(target.x()),
                        (int) Math.floor(target.y()),
                        (int) Math.floor(target.z())
                ),
                "The outward bias must remain inside the verified apron "
                        + "cell while giving post-arrival drift margin"
        );
    }

    @Test
    void localRepairSurveysBothObservationLimitedPlannerOutcomes() {
        assertTrue(
                BuildShelterStepSkill
                        .recoverableRepairObservationFailure(
                                "shelter.insufficient_observation"
                        )
        );
        assertTrue(
                BuildShelterStepSkill
                        .recoverableRepairObservationFailure(
                                "shelter.no_safe_footprint"
                        ),
                "A first-person local map can classify every currently "
                        + "observed candidate as blocked while safer cells "
                        + "remain outside the current view"
        );
        assertFalse(
                BuildShelterStepSkill
                        .recoverableRepairObservationFailure(
                                "shelter.insufficient_structural_material"
                        )
        );
    }

    @Test
    void initialSiteSearchIsBoundedButNotSingleShot() {
        assertTrue(
                BuildShelterStepSkill
                        .initialSiteRelocationAvailable(0)
        );
        assertTrue(
                BuildShelterStepSkill
                        .initialSiteRelocationAvailable(1),
                "One unsuitable first-person survey must not terminate "
                        + "the whole shelter transaction"
        );
        assertTrue(
                BuildShelterStepSkill
                        .initialSiteRelocationAvailable(3)
        );
        assertFalse(
                BuildShelterStepSkill
                        .initialSiteRelocationAvailable(4),
                "Site exploration must remain bounded when no legal "
                        + "footprint is observable"
        );
    }

    @Test
    void changingRequestedScaleStartsAFreshBoundedSiteSearch() {
        final ShelterFrame frame = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        new ShelterTestFixtures.RecordingActuator(),
                        () -> Optional.of(frame)
                );

        assertTrue(skill.bindInitialSiteSearch(
                2L,
                ShelterTestFixtures.SESSION,
                ShelterScale.STANDARD
        ));
        assertFalse(skill.bindInitialSiteSearch(
                2L,
                ShelterTestFixtures.SESSION,
                ShelterScale.STANDARD
        ));
        assertTrue(
                skill.bindInitialSiteSearch(
                        2L,
                        ShelterTestFixtures.SESSION,
                        ShelterScale.COMPACT
                ),
                "A compact plan must reconsider stands rejected only for "
                        + "a larger standard footprint"
        );
    }

    @Test
    void activePlanTraversalMaySurveyTheWholeBoundedApronInOneInvocation() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final int maximum = BuildShelterStepSkill
                .maximumActivePlanTraversalRelocations(plan);
        final int apronPerimeter = 2 * (
                plan.exteriorWidth() + plan.exteriorDepth()
        ) + 4;

        assertEquals(
                apronPerimeter * 2,
                maximum,
                "The hard bound must cover one observed apron circuit plus "
                        + "one circuit of verified backtracking"
        );
        assertTrue(
                BuildShelterStepSkill
                        .activePlanTraversalRelocationAvailable(
                                maximum - 1,
                                plan
                        )
        );
        assertFalse(
                BuildShelterStepSkill
                        .activePlanTraversalRelocationAvailable(
                                maximum,
                                plan
                        ),
                "A local compound may walk the complete generated apron, "
                        + "but it must remain bounded"
        );
    }

    @Test
    void deferredAimMaskDoesNotMutateConfirmedProgress() {
        final BitSet confirmed = new BitSet();
        confirmed.set(2);
        final BitSet deferred = new BitSet();
        deferred.set(5);

        final BitSet excluded =
                BuildShelterStepSkill.excludingDeferred(
                        confirmed,
                        deferred
                );

        assertTrue(excluded.get(2));
        assertTrue(excluded.get(5));
        assertFalse(confirmed.get(5));
        assertFalse(deferred.get(2));
    }

    @Test
    void upperWallPrefersVisibleTopSupportOverCloserOccludedEdge() {
        final GridPos target = new GridPos(3, 2, 3);
        final VisibleBlockFace belowTop = new VisibleBlockFace(
                new BlockCoordinate(3, 1, 3),
                "minecraft:oak_planks",
                "up",
                new PerceptionVec3(3.5, 2.0, 3.5),
                3.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final VisibleBlockFace neighbourEdge = new VisibleBlockFace(
                new BlockCoordinate(3, 2, 2),
                "minecraft:oak_planks",
                "south",
                new PerceptionVec3(3.5, 2.5, 3.0),
                1.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(belowTop, neighbourEdge)
        );

        final var support =
                BuildShelterStepSkill.preferredVisibleSupport(
                        base,
                        new ShelterBuildStep(
                                0,
                                ShelterStepRole.UPPER_WALL,
                                target
                        )
                ).orElseThrow();

        assertEquals(dev.mcai.companion.action.BlockFace.UP,
                support.face());
        assertEquals(target.below().x(), support.x());
        assertEquals(target.below().y(), support.y());
        assertEquals(target.below().z(), support.z());
    }

    @Test
    void upperWallDoesNotRetargetToOutwardNeighbourEdge() {
        final GridPos target = new GridPos(3, 2, 3);
        final ShelterBuildStep step = new ShelterBuildStep(
                0,
                ShelterStepRole.UPPER_WALL,
                target
        );
        final BlockInteractionTarget hiddenTop =
                new BlockInteractionTarget(
                        3,
                        1,
                        3,
                        BlockFace.UP,
                        new ActionVec3(3.5, 2.0, 3.5)
                );
        final VisibleBlockFace exposedNeighbour =
                new VisibleBlockFace(
                        new BlockCoordinate(2, 2, 3),
                        "minecraft:oak_planks",
                        "east",
                        new PerceptionVec3(3.0, 2.5, 3.5),
                        1.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                );
        final ShelterFrame frame = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(exposedNeighbour)
        );

        assertTrue(
                BuildShelterStepSkill
                        .visibleRetargetForCurrentStep(
                                frame,
                                step,
                                hiddenTop
                        )
                        .isEmpty(),
                "upper walls must keep the visible top-support invariant"
        );
    }

    @Test
    void roofRetargetDoesNotReturnToAnAlreadyRejectedSupportFace() {
        final GridPos target = new GridPos(3, 2, 3);
        final ShelterBuildStep step = new ShelterBuildStep(
                0,
                ShelterStepRole.ROOF,
                target
        );
        final BlockInteractionTarget northSupport =
                new BlockInteractionTarget(
                        3,
                        2,
                        4,
                        BlockFace.NORTH,
                        new ActionVec3(3.5, 2.5, 4.0)
                );
        final VisibleBlockFace eastFace =
                new VisibleBlockFace(
                        new BlockCoordinate(2, 2, 3),
                        "minecraft:oak_planks",
                        "east",
                        new PerceptionVec3(3.0, 2.5, 3.5),
                        1.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                );
        final ShelterFrame eastOnly = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(eastFace)
        );
        final BlockInteractionTarget eastSupport =
                BuildShelterStepSkill
                        .visibleRetargetForCurrentStep(
                                eastOnly,
                                step,
                                northSupport,
                                Set.of()
                        )
                        .orElseThrow();
        final VisibleBlockFace northFace =
                new VisibleBlockFace(
                        new BlockCoordinate(3, 2, 4),
                        "minecraft:oak_planks",
                        "north",
                        new PerceptionVec3(3.5, 2.5, 4.0),
                        1.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                );
        final ShelterFrame northOnly = flatFrame(
                2,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(northFace)
        );

        assertTrue(
                BuildShelterStepSkill
                        .visibleRetargetForCurrentStep(
                                northOnly,
                                step,
                                eastSupport,
                                Set.of(
                                        BuildShelterStepSkill
                                                .PlacementSupportIdentity
                                                .from(northSupport)
                                )
                        )
                        .isEmpty(),
                "camera motion must not bounce back to a support face "
                        + "that this same step already abandoned"
        );
    }

    @Test
    void lowerWallRejectsNeighbourSideThatFacesOutsideTheFootprint() {
        final GridPos target = new GridPos(3, 0, 3);
        final ShelterBuildStep step = new ShelterBuildStep(
                0,
                ShelterStepRole.LOWER_WALL,
                target
        );
        final VisibleBlockFace neighbourSide =
                new VisibleBlockFace(
                        new BlockCoordinate(3, 0, 4),
                        "minecraft:oak_planks",
                        "north",
                        new PerceptionVec3(3.5, 0.5, 4.0),
                        1.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                );
        final ShelterFrame frame = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(neighbourSide)
        );

        assertTrue(
                BuildShelterStepSkill
                        .preferredVisibleSupport(frame, step)
                        .isEmpty(),
                "a peripheral ray must not select an outward wall face"
        );
    }

    @Test
    void roofEdgePlacementRequiresStandingOneBlockAboveAndAdjacent() {
        final ShelterBuildStep roof = new ShelterBuildStep(
                30,
                ShelterStepRole.ROOF,
                new GridPos(4, 2, 7)
        );

        assertTrue(BuildShelterStepSkill.isAdjacentRoofEdgeStand(
                new GridPos(3, 3, 7),
                roof
        ));
        assertTrue(BuildShelterStepSkill.isAdjacentRoofEdgeStand(
                new GridPos(4, 3, 8),
                roof
        ));
        assertFalse(BuildShelterStepSkill.isAdjacentRoofEdgeStand(
                new GridPos(3, 2, 7),
                roof
        ));
        assertFalse(BuildShelterStepSkill.isAdjacentRoofEdgeStand(
                new GridPos(2, 3, 7),
                roof
        ));
        assertFalse(BuildShelterStepSkill.isAdjacentRoofEdgeStand(
                new GridPos(3, 3, 7),
                new ShelterBuildStep(
                        1,
                        ShelterStepRole.UPPER_WALL,
                        roof.target()
                )
        ));
    }

    @Test
    void jumpAimIsRequiredOnlyWhileEyeCannotSeeTopPlane() {
        final BlockInteractionTarget top =
                new BlockInteractionTarget(
                        4,
                        2,
                        8,
                        BlockFace.UP,
                        new ActionVec3(4.5, 3.0, 8.5)
                );
        final BlockInteractionTarget side =
                new BlockInteractionTarget(
                        4,
                        2,
                        8,
                        BlockFace.NORTH,
                        new ActionVec3(4.5, 2.5, 8.0)
                );

        assertTrue(
                BuildShelterStepSkill.requiresJumpToSeeTopFace(
                        2.62,
                        top
                )
        );
        assertFalse(
                BuildShelterStepSkill.requiresJumpToSeeTopFace(
                        3.06,
                        top
                )
        );
        assertFalse(
                BuildShelterStepSkill.requiresJumpToSeeTopFace(
                        2.0,
                        side
                )
        );
        assertTrue(
                BuildShelterStepSkill
                        .requiresJumpToSeePlacementFace(
                                2.0,
                                side
                        ),
                "An eye below a raised horizontal support face sees the "
                        + "block underside first and must jump before the "
                        + "vanilla centre ray can reach that side"
        );
        assertFalse(
                BuildShelterStepSkill
                        .requiresJumpToSeePlacementFace(
                                2.06,
                                side
                        )
        );
    }

    @Test
    void finalRoofCellUsesAStationaryLowAngleClickFromBelow() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep roof = plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) > 0)
                .findFirst()
                .orElseThrow();
        final GridPos feet = new GridPos(
                roof.target().x(),
                plan.origin().y(),
                roof.target().z()
        );
        final GridPos support = roof.target().offset(-1, 0, 0);
        final BlockInteractionTarget side =
                new BlockInteractionTarget(
                        support.x(),
                        support.y(),
                        support.z(),
                        BlockFace.EAST,
                        new ActionVec3(
                                support.x() + 1.0,
                                support.y() + 0.1,
                                support.z() + 0.5
                        )
                );

        assertTrue(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                roof,
                                feet
                        ),
                "the last roof opening is the only collision-free column "
                        + "from which a player can click its neighbour"
        );
        assertFalse(
                BuildShelterStepSkill.planTargetBlocksAimVantage(
                        plan,
                        feet
                ),
                "a future floor torch is not a physical obstacle before "
                        + "it is placed"
        );
        assertTrue(
                BuildShelterStepSkill.planTargetBlocksAimVantage(
                        plan,
                        roof.target()
                ),
                "a generated structural cell remains excluded as a "
                        + "standing position"
        );
        assertFalse(
                BuildShelterStepSkill
                        .requiresJumpToSeePlacementFace(
                                feet.y() + 1.62,
                                side,
                                feet,
                                roof.target()
                        ),
                "jumping below the target would move the player's body "
                        + "into the block being placed; the visible low "
                        + "side-face ray must remain grounded"
        );
        assertFalse(
                BuildShelterStepSkill
                        .aimVantageNeedsObservedJumpHeadroom(
                                true,
                                side,
                                feet,
                                roof.target()
                        ),
                "a recovery requested because the old stance has a "
                        + "ceiling must not demand jump clearance at the "
                        + "new grounded low-angle stance"
        );
        assertTrue(
                BuildShelterStepSkill
                        .aimVantageNeedsObservedJumpHeadroom(
                                true,
                                side,
                                feet.offset(1, 0, 0),
                                roof.target()
                        ),
                "ordinary side-face recovery still needs observed jump "
                        + "clearance when it is not below the opening"
        );
    }

    @Test
    void exhaustedGroundedJumpAimRequiresAReposition() {
        assertFalse(
                BuildShelterStepSkill
                        .jumpAimRepositionRequired(true, 3)
        );
        assertTrue(
                BuildShelterStepSkill
                        .jumpAimRepositionRequired(true, 4)
        );
        assertFalse(
                BuildShelterStepSkill
                        .jumpAimRepositionRequired(false, 4),
                "An airborne attempt must be allowed to finish before "
                        + "the builder changes its physical stance"
        );
    }

    @Test
    void airborneJumpAimAcceptsTheMeasuredApexTrackingError() {
        assertFalse(
                BuildShelterStepSkill.aimAlignmentSatisfied(
                        2.8814,
                        false
                ),
                "ordinary stationary placement keeps the strict "
                        + "two-degree alignment contract"
        );
        assertTrue(
                BuildShelterStepSkill.aimAlignmentSatisfied(
                        2.8814,
                        true
                ),
                "a physical jump has only one apex sample and may inspect "
                        + "the exact vanilla crosshair within four degrees"
        );
        assertFalse(
                BuildShelterStepSkill.aimAlignmentSatisfied(
                        4.01,
                        true
                )
        );
    }

    @Test
    void roofPrefersVisibleSideOnceAHorizontalNeighbourExists() {
        final GridPos target = new GridPos(3, 2, 3);
        final VisibleBlockFace belowTop = new VisibleBlockFace(
                new BlockCoordinate(3, 1, 3),
                "minecraft:oak_planks",
                "up",
                new PerceptionVec3(3.5, 2.0, 3.5),
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final VisibleBlockFace neighbourSide = new VisibleBlockFace(
                new BlockCoordinate(3, 2, 2),
                "minecraft:oak_planks",
                "south",
                new PerceptionVec3(3.5, 2.5, 3.0),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final ShelterFrame frame = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of(belowTop, neighbourSide)
        );

        final BlockInteractionTarget support =
                BuildShelterStepSkill.preferredVisibleSupport(
                        frame,
                        new ShelterBuildStep(
                                30,
                                ShelterStepRole.ROOF,
                                target
                        )
                ).orElseThrow();

        assertEquals(BlockFace.SOUTH, support.face());
        assertEquals(target.offset(0, 0, -1).x(), support.x());
        assertEquals(target.offset(0, 0, -1).y(), support.y());
        assertEquals(target.offset(0, 0, -1).z(), support.z());
        assertTrue(BuildShelterStepSkill.interactionPlacesStep(
                support,
                new ShelterBuildStep(
                        30,
                        ShelterStepRole.ROOF,
                        target
                )
        ));
        assertFalse(BuildShelterStepSkill.interactionPlacesStep(
                new BlockInteractionTarget(
                        target.x() - 1,
                        target.y(),
                        target.z() - 1,
                        BlockFace.SOUTH,
                        new ActionVec3(
                                target.x() - 0.5,
                                target.y() + 0.5,
                                target.z()
                        )
                ),
                new ShelterBuildStep(
                        30,
                        ShelterStepRole.ROOF,
                        target
                )
        ));
    }

    @Test
    void jumpVantageRequiresAnObservedThirdClearCell() {
        final GridPos stand = new GridPos(2, 0, 2);
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of()
        );
        final ShelterFrame open = withWorld(
                base,
                1,
                flatSnapshot(1, List.of(new ObservedVoxel(
                        stand.above(2),
                        VoxelKind.AIR,
                        0.0,
                        1,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ))),
                List.of()
        );
        assertTrue(BuildShelterStepSkill.hasObservedJumpHeadroom(
                open,
                stand
        ));

        final ShelterFrame roofed = withWorld(
                open,
                2,
                flatSnapshot(2, List.of(voxel(
                        stand.x(),
                        stand.y() + 2,
                        stand.z(),
                        VoxelKind.SOLID,
                        2
                ))),
                List.of()
        );
        assertFalse(BuildShelterStepSkill.hasObservedJumpHeadroom(
                roofed,
                stand
        ));
    }

    @Test
    void roofPlacementKeepsJumpEyeHeightInsteadOfCrouching() {
        assertFalse(
                BuildShelterStepSkill
                        .requiresProtectivePlacementSneak(
                                new ShelterBuildStep(
                                        30,
                                        ShelterStepRole.ROOF,
                                        new GridPos(4, 2, 7)
                                )
                        )
        );
        assertTrue(
                BuildShelterStepSkill
                        .requiresProtectivePlacementSneak(
                                new ShelterBuildStep(
                                        1,
                                        ShelterStepRole.LOWER_WALL,
                                        new GridPos(4, 0, 7)
                                )
                        )
        );
    }

    @Test
    void doorNeverUsesRememberedSideSupport() {
        final GridPos door = new GridPos(3, 0, 3);
        final ShelterBuildStep step = new ShelterBuildStep(
                55,
                ShelterStepRole.DOOR,
                door
        );
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of()
        );
        final ShelterFrame sideOnly = withWorld(
                base,
                1,
                flatSnapshot(
                        1,
                        List.of(
                                voxel(
                                        door.x(),
                                        door.y() - 1,
                                        door.z(),
                                        VoxelKind.AIR,
                                        1
                                ),
                                voxel(
                                        door.x() - 1,
                                        door.y(),
                                        door.z(),
                                        VoxelKind.SOLID,
                                        1
                                )
                        )
                ),
                List.of()
        );

        assertTrue(
                BuildShelterStepSkill
                        .rememberedTarget(sideOnly, step)
                        .isEmpty(),
                "a door cannot be placed by clicking a neighbouring wall"
        );
    }

    @Test
    void doorAndLightRetainOnlyTheirObservedFoundationHint() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep door = plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.DOOR)
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep light = plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.LIGHT)
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep wall = plan.steps().getFirst();

        final BlockInteractionTarget doorHint =
                BuildShelterStepSkill
                        .plannedFunctionalTarget(plan, door)
                        .orElseThrow();
        final BlockInteractionTarget lightHint =
                BuildShelterStepSkill
                        .plannedFunctionalTarget(plan, light)
                        .orElseThrow();

        assertEquals(BlockFace.UP, doorHint.face());
        assertEquals(door.target().below().x(), doorHint.x());
        assertEquals(door.target().below().y(), doorHint.y());
        assertEquals(door.target().below().z(), doorHint.z());
        assertEquals(BlockFace.UP, lightHint.face());
        assertTrue(
                BuildShelterStepSkill
                        .plannedFunctionalTarget(plan, wall)
                        .isEmpty(),
                "the immutable foundation fallback is not a structural shortcut"
        );
    }

    @Test
    void passableDoorVoxelDoesNotRevokeCausalPlacementCheckpoint() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep door = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.DOOR)
                .findFirst()
                .orElseThrow();
        final ObservedVoxel clearDoorCell = voxel(
                door.target().x(),
                door.target().y(),
                door.target().z(),
                VoxelKind.AIR,
                plan.sourceRevision() + 1
        );
        final ShelterFrame clearRayFrame = withWorld(
                base,
                plan.sourceRevision() + 1,
                flatSnapshot(
                        plan.sourceRevision() + 1,
                        List.of(clearDoorCell)
                ),
                List.of()
        );

        assertFalse(
                BuildShelterStepSkill
                        .confirmedPlacementContradicted(
                                clearRayFrame,
                                plan,
                                door
                        ),
                "a clear navigation ray can pass through a real door"
        );

        final ShelterFrame visiblyReplaced = withWorld(
                base,
                plan.sourceRevision() + 2,
                flatSnapshot(
                        plan.sourceRevision() + 2,
                        List.of(clearDoorCell)
                ),
                List.of(placedFace(
                        door.target(),
                        "minecraft:cobblestone"
                ))
        );
        assertTrue(
                BuildShelterStepSkill
                        .confirmedPlacementContradicted(
                                visiblyReplaced,
                                plan,
                                door
                        ),
                "a different ray-hit block at the exact target is a real "
                        + "contradiction"
        );
    }

    @Test
    void heuristicAirDoesNotRevokeCausalStructuralPlacement() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep wall = plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .findFirst()
                .orElseThrow();
        final long newerRevision = plan.sourceRevision() + 1;
        final ObservedVoxel heuristicClear = new ObservedVoxel(
                wall.target(),
                VoxelKind.AIR,
                0.0,
                newerRevision,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
        final ShelterFrame rayClearFrame = withWorld(
                base,
                newerRevision,
                flatSnapshot(
                        newerRevision,
                        List.of(heuristicClear)
                ),
                List.of()
        );

        assertFalse(
                BuildShelterStepSkill
                        .confirmedPlacementContradicted(
                                rayClearFrame,
                                plan,
                                wall
                        ),
                "multi-ray air is navigation evidence, not proof that a "
                        + "full structural block disappeared"
        );

        final ObservedVoxel bodyOccupied = new ObservedVoxel(
                wall.target(),
                VoxelKind.AIR,
                0.0,
                newerRevision + 1,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        );
        final ShelterFrame bodyInsideFrame = withWorld(
                base,
                newerRevision + 1,
                flatSnapshot(
                        newerRevision + 1,
                        List.of(bodyOccupied)
                ),
                List.of()
        );
        assertTrue(
                BuildShelterStepSkill
                        .confirmedPlacementContradicted(
                                bodyInsideFrame,
                                plan,
                                wall
                        ),
                "the player's body occupying the target is direct missing "
                        + "block evidence"
        );

        final ShelterFrame visiblyReplaced = withWorld(
                base,
                newerRevision + 2,
                flatSnapshot(
                        newerRevision + 2,
                        List.of()
                ),
                List.of(placedFace(
                        wall.target(),
                        "minecraft:bedrock"
                ))
        );
        assertTrue(
                BuildShelterStepSkill
                        .confirmedPlacementContradicted(
                                visiblyReplaced,
                                plan,
                                wall
                        ),
                "a different ray-hit block at the exact structural target "
                        + "is direct contradiction evidence"
        );
    }

    @Test
    void interiorConstructionPositionIsStrictlyInsideWallRing() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos origin = plan.origin();

        assertTrue(BuildShelterStepSkill.isInteriorFloorPosition(
                plan,
                origin.offset(1, 0, 1)
        ));
        assertTrue(BuildShelterStepSkill.isInteriorFloorPosition(
                plan,
                origin.offset(3, 0, 3)
        ));
        assertFalse(BuildShelterStepSkill.isInteriorFloorPosition(
                plan,
                origin
        ));
        assertFalse(BuildShelterStepSkill.isInteriorFloorPosition(
                plan,
                plan.doorLower()
        ));
        assertFalse(BuildShelterStepSkill.isInteriorFloorPosition(
                plan,
                origin.offset(1, 1, 1)
        ));
    }

    @Test
    void wallStaysInsideWhileRoofMayUseBoundedExteriorApron() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos origin = plan.origin();
        final ShelterBuildStep wall = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.UPPER_WALL)
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep roof = plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.ROOF)
                .findFirst()
                .orElseThrow();

        assertTrue(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                wall,
                                origin.offset(2, 0, 2)
                        )
        );
        assertFalse(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                wall,
                                origin
                        ),
                "wall-ring cells must never become wall aiming stands"
        );
        assertFalse(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                wall,
                                origin.offset(-1, 0, 2)
                        ),
                "wall construction must not route around the exterior"
        );
        assertTrue(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                roof,
                                origin.offset(-1, 0, 2)
                        ),
                "an observed one-block apron is a valid human roof stance"
        );
        assertTrue(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                roof,
                                origin.offset(2, 0, 2)
                ),
                "roof aiming should remain on the observed interior floor"
        );
        assertFalse(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                roof,
                                origin.offset(-2, 0, 2)
                        ),
                "roof recovery must not wander beyond the bounded apron"
        );
        assertFalse(
                BuildShelterStepSkill
                        .isPermittedConstructionStand(
                                plan,
                                roof,
                                origin.offset(-1, 1, 2)
                ),
                "the apron is an ordinary floor stance, not hidden scaffolding"
        );
        final GridPos exteriorDoorway =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        assertTrue(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        exteriorDoorway
                ),
                "the generated doorway exit must lead onto the bounded apron"
        );
        assertEquals(
                1.0,
                exteriorDoorway.euclideanDistance(
                        plan.doorLower()
                )
        );
    }

    @Test
    void wallCornersAreConstructionDependenciesBeforeEdges() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep corner = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.LOWER_WALL)
                .filter(step ->
                        step.target().x() == plan.origin().x()
                                && step.target().z()
                                        == plan.origin().z())
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep edge = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.LOWER_WALL)
                .filter(step ->
                        step.target().x() == plan.origin().x())
                .filter(step ->
                        step.target().z() > plan.origin().z()
                                && step.target().z()
                                        < plan.origin().z()
                                                + plan.exteriorDepth() - 1)
                .findFirst()
                .orElseThrow();

        assertEquals(
                0,
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        corner
                )
        );
        assertEquals(
                1,
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        edge
                )
        );
    }

    @Test
    void roofConstructionCompletesOuterRingsBeforeTheCentre() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos origin = plan.origin();
        final ShelterBuildStep outer = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        step.target().equals(origin.above(2)))
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep inner = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        step.target().equals(
                                origin.offset(1, 2, 1)
                        ))
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep centre = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        step.target().equals(
                                origin.offset(2, 2, 2)
                        ))
                .findFirst()
                .orElseThrow();

        assertEquals(
                0,
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        outer
                )
        );
        assertEquals(
                1,
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        inner
                )
        );
        assertEquals(
                2,
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        centre
                )
        );
    }

    @Test
    void completedVanillaReceiptRecoversOccludedPlacementAtTimeout() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterBuildStep first = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow().steps().getFirst();
        final ShelterFrame initial = withWorld(
                base,
                1,
                base.navigation(),
                List.of(topFace(first.target().below()))
        );
        final ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(initial);
        final ShelterTestFixtures.RecordingActuator actuator =
                new ShelterTestFixtures.RecordingActuator();
        actuator.useOutcome =
                dev.mcai.companion.action.ActionOutcome.COMPLETED;
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        actuator,
                        frames,
                        planner
                );
        final BuildShelterStepParameters parameters =
                new BuildShelterStepParameters(
                        initial.dimension(),
                        1,
                        ShelterScale.COMPACT
                );

        skill.start(CONTEXT, parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(CONTEXT, parameters).status()
        );
        frames.frame = ShelterTestFixtures.withMainHand(
                withWorld(
                        initial,
                        2,
                        flatSnapshot(2, List.of()),
                        List.of()
                ),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        63,
                        0,
                        0
                )
        );

        final SkillTickResult receipt = skill.tick(
                new SkillContext(
                        1,
                        2,
                        61,
                        true,
                        true,
                        0.0
                ),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                receipt.status()
        );
        assertEquals(1, skill.confirmedStepCount());
    }

    @Test
    void placesThroughFairActuatorConfirmsAndRejectsLaterConflict() {
        DynamicShelterPlanner planner = new DynamicShelterPlanner();
        ShelterFrame unobservedFaces = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        ShelterPlan preview = planner.plan(
                unobservedFaces,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        ShelterBuildStep first = preview.steps().getFirst();
        GridPos support = first.target().below();
        ShelterFrame initial = withWorld(
                unobservedFaces,
                1,
                unobservedFaces.navigation(),
                List.of(topFace(support))
        );
        ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(initial);
        ShelterTestFixtures.RecordingActuator actuator =
                new ShelterTestFixtures.RecordingActuator();
        BuildShelterStepSkill skill = new BuildShelterStepSkill(
                ShelterTestFixtures.PLAYER_ID,
                actuator,
                frames,
                planner
        );
        BuildShelterStepParameters parameters =
                new BuildShelterStepParameters(
                        initial.dimension(),
                        1,
                        ShelterScale.COMPACT
                );

        assertTrue(skill.preconditions(CONTEXT, parameters).isEmpty());
        skill.start(CONTEXT, parameters);
        SkillTickResult dispatched = skill.tick(CONTEXT, parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                dispatched.status()
        );
        assertEquals(1, actuator.uses.size());

        ObservedVoxel placed = voxel(
                first.target().x(),
                first.target().y(),
                first.target().z(),
                VoxelKind.SOLID,
                2
        );
        frames.frame = withWorld(
                initial,
                2,
                flatSnapshot(2, List.of(placed)),
                List.of(placedFace(
                        first.target(),
                        "minecraft:cobblestone"
                ))
        );
        SkillTickResult confirmed = skill.tick(
                new SkillContext(1, 1, 2, true, true, 0.0),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                confirmed.status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(CONTEXT, parameters).status()
        );
        assertEquals(1, skill.confirmedStepCount());

        ShelterBuildStep conflicting = preview.steps().get(1);
        ObservedVoxel conflict = voxel(
                conflicting.target().x(),
                conflicting.target().y(),
                conflicting.target().z(),
                VoxelKind.SOLID,
                3
        );
        frames.frame = withWorld(
                initial,
                3,
                flatSnapshot(3, List.of(placed, conflict)),
                List.of()
        );
        var failure = skill.preconditions(
                new SkillContext(1, 3, 3, true, true, 0.0),
                new BuildShelterStepParameters(
                        initial.dimension(),
                        3,
                        ShelterScale.COMPACT
                )
        );

        assertTrue(failure.isPresent());
        assertEquals(
                "build_shelter_step.plan_conflict",
                failure.orElseThrow().code()
        );

        ShelterFrame replacementBase = flatFrame(
                4,
                new PerceptionVec3(0, 0, 1),
                100,
                List.of()
        );
        ShelterPlan replacementPreview = planner.plan(
                replacementBase,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        ShelterFrame replacement = withWorld(
                replacementBase,
                4,
                replacementBase.navigation(),
                List.of(topFace(
                        replacementPreview.steps()
                                .getFirst()
                                .target()
                                .below()
                ))
        );
        frames.frame = replacement;
        SkillContext replacementGoal =
                new SkillContext(2, 4, 4, true, true, 0.0);
        BuildShelterStepParameters replacementParameters =
                new BuildShelterStepParameters(
                        replacement.dimension(),
                        4,
                        ShelterScale.COMPACT
                );

        assertTrue(skill.preconditions(
                replacementGoal,
                replacementParameters
        ).isEmpty());
        skill.start(replacementGoal, replacementParameters);
        assertEquals(
                4,
                skill.activePlan().orElseThrow().sourceRevision(),
                "A replacement goal must not inherit an old shelter plan"
        );
    }

    @Test
    void activePhysicalPlanIgnoresLaterModelScaleDrift() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan preview = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterFrame initial = withWorld(
                base,
                1,
                base.navigation(),
                List.of(topFace(
                        preview.steps().getFirst().target().below()
                ))
        );
        final ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(initial);
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        new ShelterTestFixtures.RecordingActuator(),
                        frames,
                        planner
                );
        final BuildShelterStepParameters compact =
                new BuildShelterStepParameters(
                        initial.dimension(),
                        1,
                        ShelterScale.COMPACT
                );
        final BuildShelterStepParameters drifted =
                new BuildShelterStepParameters(
                        initial.dimension(),
                        1,
                        ShelterScale.STANDARD
                );

        assertTrue(skill.preconditions(CONTEXT, compact).isEmpty());
        skill.start(CONTEXT, compact);
        assertTrue(
                skill.preconditions(CONTEXT, drifted).isEmpty(),
                "A later provider response must continue the persisted "
                        + "transaction instead of rejecting a changed scale"
        );
        assertEquals(
                ShelterScale.COMPACT,
                skill.activePlan().orElseThrow().scale()
        );
    }

    @Test
    void obstructionPushDestinationCrossesAndClearsTargetCell() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final var movementNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel -> voxel.kind()
                                                == VoxelKind.AIR
                                        ? new ObservedVoxel(
                                                voxel.position(),
                                                voxel.kind(),
                                                voxel.danger(),
                                                voxel.observationRevision(),
                                                dev.mcai.companion.navigation
                                                        .OccupancyEvidence
                                                        .MULTI_RAY_CLEAR,
                                                dev.mcai.companion.perception
                                                        .TopSupportAffordance
                                                        .UNKNOWN
                                        )
                                        : voxel)
                                .toList()
                );
        final ShelterFrame frame = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                movementNavigation,
                base.visibleBlockFaces()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                frame,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep wall = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.LOWER_WALL)
                .max(java.util.Comparator.comparingDouble(step ->
                        frame.feet().euclideanDistance(
                                step.target()
                        )))
                .orElseThrow();
        final PerceptionVec3 destination =
                BuildShelterStepSkill
                        .placementPushDestination(
                                frame,
                                plan,
                                wall
                        )
                        .orElseThrow();

        assertTrue(
                wall.target().euclideanDistance(
                        new GridPos(
                                (int) Math.floor(destination.x()),
                                wall.target().y(),
                                (int) Math.floor(destination.z())
                        )
                ) >= 1.0,
                "The body must finish beyond, not inside, the blocked cell"
        );
        assertFalse(
                BuildShelterStepSkill.playerBodyIntersectsBlock(
                        destination,
                        wall.target()
                )
        );
    }

    @Test
    void roofApronStagingWalksTowardOccludedFarSide() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos exteriorDoor =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final var movementNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        base.navigation().observedVoxels()
                                .values()
                                .stream()
                                .map(voxel -> voxel.kind()
                                                == VoxelKind.AIR
                                        ? new ObservedVoxel(
                                                voxel.position(),
                                                voxel.kind(),
                                                voxel.danger(),
                                                voxel.observationRevision(),
                                                OccupancyEvidence
                                                        .MULTI_RAY_CLEAR,
                                                TopSupportAffordance
                                                        .UNKNOWN
                                        )
                                        : voxel)
                                .toList()
                );
        final ShelterBuildStep farRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .max(java.util.Comparator.comparingDouble(step ->
                        horizontalDistance(
                                exteriorDoor,
                                step.target()
                        )))
                .orElseThrow();
        final ShelterFrame atDoor = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                exteriorDoor,
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                movementNavigation,
                base.visibleBlockFaces()
        );

        final List<GridPos> staging =
                BuildShelterStepSkill
                        .roofApronObservationStagingCandidates(
                                atDoor,
                                plan,
                                farRoof,
                                Set.of(
                                        plan.doorLower(),
                                        exteriorDoor
                                )
                        );

        assertFalse(staging.isEmpty());
        assertTrue(
                horizontalDistance(
                        staging.getFirst(),
                        farRoof.target()
                ) < horizontalDistance(
                        exteriorDoor,
                        farRoof.target()
                ),
                "The first fair staging move must make geometric progress "
                        + "around the opaque shelter"
        );
        assertEquals(
                1.0,
                horizontalDistance(
                        exteriorDoor,
                        staging.getFirst()
                ),
                1.0E-9,
                "A roof survey must advance one observed apron cell at a "
                        + "time; a direct far-side target lets stale air "
                        + "inside the newly built wall attract MoveTo"
        );
        assertTrue(
                BuildShelterStepSkill.isExteriorRoofApronPosition(
                        plan,
                        staging.getFirst()
                )
        );
    }

    @Test
    void roofApronStagingMayStepAwayToExploreAroundOcclusion() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth() - 1;
        final int middleZ = plan.origin().z()
                + plan.exteriorDepth() / 2;
        final ShelterBuildStep roof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        step.target().x() == maximumX)
                .filter(step ->
                        step.target().z() == middleZ)
                .findFirst()
                .orElseThrow();
        final GridPos current = new GridPos(
                maximumX + 1,
                plan.origin().y(),
                middleZ
        );
        final GridPos frontier = current.offset(0, 0, 1);
        assertTrue(
                horizontalDistance(frontier, roof.target())
                        > horizontalDistance(
                                current,
                                roof.target()
                        ) + 0.25
        );
        final var partialNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.observationRevision(),
                                current,
                                frontier
                        )
                );

        assertEquals(
                List.of(frontier),
                BuildShelterStepSkill
                        .roofApronObservationStagingCandidates(
                                shelterFrameAt(
                                        base,
                                        partialNavigation,
                                        current
                                ),
                                plan,
                                roof,
                                Set.of(current)
                        ),
                "When the desired face is hidden around a corner, the body "
                        + "must be able to explore one observed-safe cell "
                        + "away from the target instead of timing out"
        );
    }

    @Test
    void roofApronArrivalUsesTargetedRefreshInsteadOfPanorama() {
        assertTrue(
                BuildShelterStepSkill
                        .requiresPanoramicAimRecoverySurvey(
                                true,
                                false,
                                false
                        ),
                "the initial doorway staging still needs a broad exterior "
                        + "survey"
        );
        assertTrue(
                BuildShelterStepSkill
                        .requiresPanoramicAimRecoverySurvey(
                                false,
                                true,
                                false
                        ),
                "the first exterior doorway sample still establishes the "
                        + "safe apron"
        );
        assertFalse(
                BuildShelterStepSkill
                        .requiresPanoramicAimRecoverySurvey(
                                false,
                                false,
                                true
                        ),
                "each one-cell apron hop should turn toward its active "
                        + "support and wait for one semantic refresh, not "
                        + "repeat a 24-view panorama"
        );
    }

    @Test
    void roofApronCornerGlancesTowardUnobservedForwardCell() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth() - 1;
        final int maximumZ = plan.origin().z()
                + plan.exteriorDepth() - 1;
        final ShelterBuildStep cornerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        step.target().x() == maximumX
                                && step.target().z() == maximumZ)
                .findFirst()
                .orElseThrow();
        final GridPos corner = new GridPos(
                maximumX + 1,
                plan.origin().y(),
                maximumZ + 1
        );
        final GridPos visitedBack =
                corner.offset(-1, 0, 0);
        final GridPos unobservedForward =
                corner.offset(0, 0, -1);
        final var partialNavigation =
                new dev.mcai.companion.navigation.LocalNavSnapshot(
                        base.dimension(),
                        base.navigation().revision(),
                        safeStandVoxels(
                                base.observationRevision(),
                                corner,
                                visitedBack
                        )
                );

        assertEquals(
                Optional.of(unobservedForward),
                BuildShelterStepSkill
                        .roofApronTargetedRefreshCandidate(
                                shelterFrameAt(
                                        base,
                                        partialNavigation,
                                        corner
                                ),
                                plan,
                                cornerRoof,
                                Set.of(corner, visitedBack)
                        ),
                "At an opaque apron corner the body should briefly look "
                        + "toward the unobserved forward footing instead of "
                        + "retreating over already visited cells"
        );
    }

    @Test
    void roofRepositionBudgetCoversACompleteObservedApron() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep roof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .findFirst()
                .orElseThrow();
        final int apronWidth = plan.exteriorWidth() + 2;
        final int apronDepth = plan.exteriorDepth() + 2;
        final int apronPerimeter =
                2 * (apronWidth + apronDepth) - 4;

        assertTrue(
                BuildShelterStepSkill
                        .aimRepositionAttemptBudget(plan, roof)
                        >= apronPerimeter,
                "A bounded roof search must be able to traverse one complete "
                        + "fairly observed apron; the former fixed budget of "
                        + "eight counted transit hops and stopped midway"
        );
    }

    @Test
    void innerRoofRepositionReturnsThroughDoorInsteadOfCirclingApron() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep outerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) == 0)
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep innerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) > 0)
                .findFirst()
                .orElseThrow();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos interior = plan.doorLower().offset(
                plan.doorLower().x() - exterior.x(),
                0,
                plan.doorLower().z() - exterior.z()
        );

        assertFalse(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                outerRoof,
                                exterior
                        ),
                "An unfinished outer-ring face is legitimately worked from "
                        + "the exterior apron"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                innerRoof,
                                exterior
                        ),
                "A completed outer ring occludes the inner roof from ground "
                        + "level, so repositioning must return through the "
                        + "known doorway"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                innerRoof,
                                interior
                        ),
                "Once inside, normal visible support and jump placement "
                        + "should resume"
        );
    }

    @Test
    void exhaustedOuterRoofRepositionReturnsThroughDoor() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep outerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) == 0)
                .findFirst()
                .orElseThrow();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final int fallbackAttempts =
                BuildShelterStepSkill
                        .exteriorRoofInteriorFallbackAttempts(plan);

        assertFalse(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                outerRoof,
                                exterior,
                                fallbackAttempts - 1
                        ),
                "A reachable outer-ring roof face keeps its bounded exterior "
                        + "working search"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                outerRoof,
                                exterior,
                                fallbackAttempts
                        ),
                "After a bounded exterior search, an outer-ring roof face "
                        + "must use the known doorway instead of circling "
                        + "until the live gate times out"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldReturnInsideForRoofReposition(
                                plan,
                                outerRoof,
                                exterior,
                                0,
                                true
                        ),
                "A model-batch restart resets the local attempt counter, "
                        + "but must preserve that this exact outer step "
                        + "already exhausted its exterior search"
        );
    }

    @Test
    void interiorRoofRetargetKeepsTheActiveInteriorFallback() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final List<ShelterBuildStep> outerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .limit(2)
                        .toList();
        final ShelterBuildStep exhausted =
                outerRoof.getFirst();
        final ShelterBuildStep visibleRetarget =
                outerRoof.getLast();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos interior = plan.doorLower().offset(
                plan.doorLower().x() - exterior.x(),
                0,
                plan.doorLower().z() - exterior.z()
        );

        assertTrue(
                BuildShelterStepSkill
                        .shouldCarryRoofInteriorFallback(
                                plan,
                                exhausted,
                                visibleRetarget,
                                interior,
                                true
                        ),
                "A centre-crosshair retarget after entering the shelter "
                        + "must not discard the bounded exterior-search "
                        + "fallback and send the body straight back outside"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldCarryRoofInteriorFallback(
                                plan,
                                exhausted,
                                visibleRetarget,
                                exterior,
                                true
                        ),
                "Ordinary exterior roof work must remain target-specific"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldCarryRoofInteriorFallback(
                                plan,
                                exhausted,
                                visibleRetarget,
                                interior,
                                false
                        ),
                "An unexhausted roof target must retain its normal exterior "
                        + "search budget"
        );
    }

    @Test
    void roofInteriorFallbackSurvivesUnrelatedInnerPlacement() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final List<ShelterBuildStep> outerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .toList();
        final ShelterBuildStep innerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) > 0)
                        .findFirst()
                        .orElseThrow();
        final BitSet confirmed = new BitSet();
        confirmed.set(innerRoof.index());

        assertTrue(
                BuildShelterStepSkill.roofInteriorFallbackApplies(
                        plan,
                        outerRoof.getFirst(),
                        0
                ),
                "Every target in the exhausted outer ring must remain in "
                        + "interior mode even when selection changes without "
                        + "a centre-crosshair adaptation"
        );
        assertEquals(
                0,
                BuildShelterStepSkill
                        .roofInteriorFallbackPriorityAfterPlacement(
                                plan,
                                confirmed,
                                0
                        ),
                "Placing a visible inner cell must not clear the fallback "
                        + "while an outer-ring target remains pending"
        );

        outerRoof.forEach(step ->
                confirmed.set(step.index()));
        assertEquals(
                -1,
                BuildShelterStepSkill
                        .roofInteriorFallbackPriorityAfterPlacement(
                                plan,
                                confirmed,
                                0
                        ),
                "The fallback ring can be released only after every target "
                        + "at that construction priority is confirmed"
        );
    }

    @Test
    void traversalDrivenRoofReturnCommitsInteriorFallbackMode() {
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                flatFrame(
                        1,
                        new PerceptionVec3(0, 0, 1),
                        64,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep outerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .findFirst()
                        .orElseThrow();
        final ShelterBuildStep innerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) > 0)
                        .findFirst()
                        .orElseThrow();
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .forEach(step ->
                        confirmed.set(step.index()));

        confirmed.clear(outerRoof.index());
        assertEquals(
                0,
                BuildShelterStepSkill
                        .roofInteriorTraversalFallbackPriority(
                                plan,
                                confirmed,
                                -1
                        ),
                "A generic doorway return for an outer roof gap must keep "
                        + "the next interior traversal inside"
        );

        confirmed.set(outerRoof.index());
        confirmed.clear(innerRoof.index());
        assertEquals(
                BuildShelterStepSkill.constructionPriority(
                        plan,
                        innerRoof
                ),
                BuildShelterStepSkill
                        .roofInteriorTraversalFallbackPriority(
                                plan,
                                confirmed,
                                -1
                        ),
                "The committed mode must follow the currently pending "
                        + "generated roof ring"
        );

        confirmed.set(innerRoof.index());
        assertEquals(
                -1,
                BuildShelterStepSkill
                        .roofInteriorTraversalFallbackPriority(
                                plan,
                                confirmed,
                                -1
                        ),
                "No fallback should be invented after every roof step is "
                        + "server-confirmed"
        );
    }

    @Test
    void fullyDeferredFallbackRingRequiresAnotherInteriorStand() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final List<ShelterBuildStep> outerRoof =
                plan.steps().stream()
                        .filter(step ->
                                step.role() == ShelterStepRole.ROOF)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                plan,
                                                step
                                        ) == 0)
                        .toList();
        final ShelterBuildStep firstPending =
                outerRoof.getFirst();
        final ShelterBuildStep secondPending =
                outerRoof.get(1);
        final BitSet confirmed = new BitSet();
        plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .forEach(step ->
                        confirmed.set(step.index()));
        confirmed.clear(firstPending.index());
        confirmed.clear(secondPending.index());
        final BitSet deferred = new BitSet();
        deferred.set(firstPending.index());

        assertFalse(
                BuildShelterStepSkill
                        .deferredRoofFallbackCycleExhausted(
                                plan,
                                confirmed,
                                deferred,
                                0
                        )
        );
        deferred.set(secondPending.index());
        assertTrue(
                BuildShelterStepSkill
                        .deferredRoofFallbackCycleExhausted(
                                plan,
                                confirmed,
                                deferred,
                                0
                        ),
                "Once every pending target in the active fallback ring was "
                        + "occluded from one stance, the executor must walk "
                        + "to another observed interior stance rather than "
                        + "failing the skill"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldClearTraversalHistoryAfterAimDeferral(
                                0
                        ),
                "Fallback relocation must remember prior interior stands or "
                        + "it will oscillate between the same two cells"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldClearTraversalHistoryAfterAimDeferral(
                                -1
                        )
        );
    }

    @Test
    void innerRoofObservationStaysInsideAfterBatchResume() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep outerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) == 0)
                .findFirst()
                .orElseThrow();
        final ShelterBuildStep innerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) > 0)
                .findFirst()
                .orElseThrow();
        final GridPos exterior =
                BuildShelterStepSkill.exteriorDoorwayStand(plan);
        final GridPos interior = plan.doorLower().offset(
                plan.doorLower().x() - exterior.x(),
                0,
                plan.doorLower().z() - exterior.z()
        );

        assertTrue(
                BuildShelterStepSkill
                        .shouldStageExteriorRoofObservation(
                                plan,
                                outerRoof,
                                interior
                        ),
                "An outer-ring target may still stage through the doorway "
                        + "to inspect an exterior support"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldStageExteriorRoofObservation(
                                plan,
                                innerRoof,
                                interior
                        ),
                "A resumed inner-ring target must not walk from the interior "
                        + "to the exterior only to immediately return"
        );
        assertFalse(
                BuildShelterStepSkill
                        .shouldStageExteriorRoofObservation(
                                plan,
                                outerRoof,
                                interior,
                                true
                        ),
                "Once this outer-ring step has exhausted the exterior apron "
                        + "and physically returned through the doorway, its "
                        + "next retry must remain inside instead of restarting "
                        + "the same exterior circuit"
        );
        assertTrue(
                BuildShelterStepSkill
                        .shouldStageExteriorRoofObservation(
                                plan,
                                outerRoof,
                                interior,
                                false
                        ),
                "A fresh outer-ring step still receives its ordinary fair "
                        + "exterior observation route"
        );
    }

    @Test
    void finalInnerRoofAimTimeoutTriesAdjacentRepositionBeforeDeferral() {
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        final ShelterPlan plan = new DynamicShelterPlanner().plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep innerRoof = plan.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) > 0)
                .findFirst()
                .orElseThrow();

        assertEquals(
                1,
                BuildShelterStepSkill.minimumSideFaceAimDistance(),
                "A player can expose a roof support side from the adjacent "
                        + "interior cell; starting at distance two omits the "
                        + "last useful stance in a nearly closed roof"
        );
        assertTrue(
                BuildShelterStepSkill
                        .aimTimeoutRepositionAvailable(
                                plan,
                                innerRoof,
                                0
                        ),
                "A newly resumed final roof step must physically try another "
                        + "observed interior stance before being deferred"
        );
        assertFalse(
                BuildShelterStepSkill
                        .aimTimeoutRepositionAvailable(
                                plan,
                                innerRoof,
                                BuildShelterStepSkill
                                        .aimRepositionAttemptBudget(
                                                plan,
                                                innerRoof
                                        )
                        ),
                "The recovery remains bounded after its normal attempt budget"
        );
    }

    private static double horizontalDistance(
            final GridPos first,
            final GridPos second
    ) {
        return Math.hypot(
                first.x() - second.x(),
                first.z() - second.z()
        );
    }

    private static ShelterFrame shelterFrameAt(
            final ShelterFrame base,
            final dev.mcai.companion.navigation.LocalNavSnapshot navigation,
            final GridPos feet
    ) {
        return new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                feet,
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                navigation,
                base.visibleBlockFaces(),
                base.recentVisibleEntities()
        );
    }

    private static List<ObservedVoxel> safeStandVoxels(
            final long observationRevision,
            final GridPos... stands
    ) {
        final List<ObservedVoxel> voxels =
                new java.util.ArrayList<>(stands.length * 3);
        for (GridPos stand : stands) {
            voxels.add(new ObservedVoxel(
                    stand.below(),
                    VoxelKind.SOLID,
                    0.0,
                    observationRevision,
                    OccupancyEvidence.SURFACE_HIT,
                    TopSupportAffordance.STURDY_FULL_TOP
            ));
            voxels.add(new ObservedVoxel(
                    stand,
                    VoxelKind.AIR,
                    0.0,
                    observationRevision,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
            voxels.add(new ObservedVoxel(
                    stand.above(),
                    VoxelKind.AIR,
                    0.0,
                    observationRevision,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
        }
        return List.copyOf(voxels);
    }

    @Test
    void batchesFreshlyVisibleStepsWithoutAnotherModelDecision() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterBuildStep first = plan.steps().get(0);
        final ShelterBuildStep second = plan.steps().stream()
                .filter(step -> step.index() != first.index())
                .filter(step -> step.role() == first.role())
                .filter(step ->
                        BuildShelterStepSkill.constructionPriority(
                                plan,
                                step
                        ) == BuildShelterStepSkill
                                .constructionPriority(plan, first))
                .findFirst()
                .orElseThrow();
        assertEquals(first.role(), second.role());
        final ShelterFrame initial = withWorld(
                base,
                1,
                base.navigation(),
                List.of(topFace(first.target().below()))
        );
        final ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(initial);
        final ShelterTestFixtures.RecordingActuator actuator =
                new ShelterTestFixtures.RecordingActuator();
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        actuator,
                        frames,
                        planner
                );
        final BuildShelterStepParameters parameters =
                new BuildShelterStepParameters(
                        initial.dimension(),
                        1,
                        ShelterScale.COMPACT
                );

        skill.start(CONTEXT, parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(CONTEXT, parameters).status()
        );

        final ObservedVoxel firstPlaced = voxel(
                first.target().x(),
                first.target().y(),
                first.target().z(),
                VoxelKind.SOLID,
                2
        );
        frames.frame = withWorld(
                initial,
                2,
                flatSnapshot(2, List.of(firstPlaced)),
                List.of(topFace(second.target().below()))
        );
        final SkillTickResult selectedSecond = skill.tick(
                new SkillContext(1, 1, 2, true, true, 0.0),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                selectedSecond.status()
        );
        assertTrue(selectedSecond.safeCheckpoint());
        assertEquals(1, skill.confirmedStepCount());

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        new SkillContext(
                                1,
                                1,
                                3,
                                true,
                                true,
                                0.0
                        ),
                        parameters
                ).status()
        );
        assertEquals(
                2,
                actuator.uses.size(),
                "the second placement must not require a model round trip"
        );

        final ObservedVoxel secondPlaced = voxel(
                second.target().x(),
                second.target().y(),
                second.target().z(),
                VoxelKind.SOLID,
                3
        );
        frames.frame = withWorld(
                initial,
                3,
                flatSnapshot(
                        3,
                        List.of(firstPlaced, secondPlaced)
                ),
                List.of()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        new SkillContext(
                                1,
                                1,
                                4,
                                true,
                                true,
                                0.0
                        ),
                        parameters
                ).status()
        );
        assertEquals(2, skill.confirmedStepCount());
    }

    @Test
    void acceptsRetainedAuthoredSampleAfterNormalModelDelay() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame authoredBase = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan preview = planner.plan(
                authoredBase,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterFrame authored = withWorld(
                authoredBase,
                1,
                authoredBase.navigation(),
                List.of(topFace(
                        preview.steps().getFirst().target().below()
                ))
        );
        final ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(authored);
        /*
         * A semantic camera sample advances while the provider thinks. The
         * high-level decision remains bound to sample 1, while construction
         * is planned and physically revalidated against fresh sample 8.
         */
        frames.frame = withWorld(
                authored,
                8,
                flatSnapshot(8, List.of()),
                authored.visibleBlockFaces()
        );
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        new ShelterTestFixtures.RecordingActuator(),
                        frames,
                        planner
                );
        final BuildShelterStepParameters delayed =
                new BuildShelterStepParameters(
                        authored.dimension(),
                        1,
                        ShelterScale.COMPACT
                );

        assertTrue(
                skill.preconditions(
                        new SkillContext(
                                1,
                                8,
                                8,
                                true,
                                true,
                                0.0
                        ),
                        delayed
                ).isEmpty(),
                "A retained fair sample must survive ordinary model latency"
        );
    }

    @Test
    void internalSurveyReplanDoesNotRevalidateAnEvictedAuthoredSample() {
        assertTrue(
                BuildShelterStepSkill.PreparationAdmission
                        .EXTERNAL_MODEL_DECISION
                        .requiresExactAuthoredSample()
        );
        assertFalse(
                BuildShelterStepSkill.PreparationAdmission
                        .BOUND_INTERNAL_SURVEY
                        .requiresExactAuthoredSample(),
                "Once a skill is admitted and bound to the goal/body "
                        + "session, its own fresh first-person survey must "
                        + "not depend on an old model sample remaining in "
                        + "the rolling history"
        );
    }

    @Test
    void equipsOwnedStructuralMaterialBeforePlacing() {
        final DynamicShelterPlanner planner =
                new DynamicShelterPlanner();
        final ShelterFrame base = flatFrame(
                1,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan plan = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final ShelterFrame wrongHand = ShelterTestFixtures.withMainHand(
                withWorld(
                        base,
                        1,
                        base.navigation(),
                        List.of(topFace(
                                plan.steps().getFirst().target().below()
                        ))
                ),
                new HeldItemSummary(
                        "minecraft:iron_pickaxe",
                        1,
                        0,
                        0
                )
        );
        final ShelterTestFixtures.MutableFrames frames =
                new ShelterTestFixtures.MutableFrames(wrongHand);
        final ShelterTestFixtures.RecordingActuator placement =
                new ShelterTestFixtures.RecordingActuator();
        final ShelterTestFixtures.RecordingInventoryActuator inventory =
                new ShelterTestFixtures.RecordingInventoryActuator();
        final BuildShelterStepSkill skill =
                new BuildShelterStepSkill(
                        ShelterTestFixtures.PLAYER_ID,
                        placement,
                        frames,
                        inventory,
                        planner,
                        (ignoredRevision, ignoredPlan) -> {
                        }
                );
        final BuildShelterStepParameters parameters =
                new BuildShelterStepParameters(
                        wrongHand.dimension(),
                        1,
                        ShelterScale.COMPACT
                );

        assertTrue(skill.preconditions(CONTEXT, parameters).isEmpty());
        skill.start(CONTEXT, parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(CONTEXT, parameters).status()
        );
        assertEquals(1, inventory.equipCalls);
        assertEquals(0, placement.uses.size());

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        new SkillContext(
                                1,
                                1,
                                2,
                                true,
                                true,
                                0.0
                        ),
                        parameters
                ).status()
        );
        assertEquals(1, placement.uses.size());
    }

    @Test
    void registrationIsModelCallableAndGuideExplainsRecovery() {
        ShelterFrame frame = flatFrame(
                4,
                new PerceptionVec3(0, 0, 1),
                64,
                List.of()
        );
        SkillRegistry registry = DynamicShelterSkills.registerAll(
                new SkillRegistry(),
                ShelterTestFixtures.PLAYER_ID,
                new ShelterTestFixtures.RecordingActuator(),
                () -> java.util.Optional.of(frame)
        );

        assertTrue(registry.contains("build_shelter_step"));
        assertFalse(DynamicShelterSkills.plannerGuide().isBlank());
        assertTrue(DynamicShelterSkills.plannerGuide().contains(
                "no_visible_build_step"
        ));
    }
}
