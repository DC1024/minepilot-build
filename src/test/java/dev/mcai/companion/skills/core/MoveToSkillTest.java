package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.currentCellOnly;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalPlanningBudget;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MoveToSkillTest {
    private static final PerceptionVec3 EAST =
            new PerceptionVec3(1.0, 0.0, 0.0);
    private static final PerceptionVec3 WEST =
            new PerceptionVec3(-1.0, 0.0, 0.0);

    @Test
    void startIsStateOnlyThenFollowsObservedRouteAndStopsOnArrival() throws Exception {
        LocalNavSnapshot nav = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );

        assertTrue(skill.preconditions(context(1, false), target).isEmpty());
        skill.start(context(1, false), target);
        assertEquals(0, actuator.movements.size());
        assertEquals(0, actuator.stops);

        SkillTickResult first = skill.tick(context(2, false), target);
        assertEquals(SkillTickResult.Status.RUNNING, first.status());
        assertEquals(1, actuator.movements.size());
        assertTrue(actuator.movements.getFirst().sprint());

        frames.frame = frame(2, 1.5, 1.0, 0.5, EAST, nav, 0.0);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3, false), target).status()
        );
        frames.frame = frame(3, 2.5, 1.0, 0.5, EAST, nav, 0.0);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(4, false), target).status()
        );
        assertEquals(SkillResult.Status.COMPLETED,
                skill.result(context(4, false), target).status());
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(5, false), target).status()
        );
        assertTrue(actuator.stops >= 2);
    }

    @Test
    void finalRouteCellUsesLowSpeedSneakingPrecisionInsteadOfSprinting()
            throws Exception {
        LocalNavSnapshot nav = corridor(1, 1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                1.5,
                1.0,
                0.5,
                0.25
        );
        skill.start(context(1, false), target);

        SkillTickResult result = skill.tick(context(2, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, actuator.movements.size());
        var movement = actuator.movements.getFirst();
        assertTrue(movement.forward() >= 0.12);
        assertTrue(movement.forward() <= 0.35);
        assertFalse(movement.sprint());
        assertTrue(movement.sneak());
    }

    @Test
    void ordinaryBuildingRadiusRetainsNormalFinalCellMovement()
            throws Exception {
        LocalNavSnapshot nav = corridor(1, 1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                1.5,
                1.0,
                0.5,
                0.30
        );
        skill.start(context(1, false), target);

        SkillTickResult result = skill.tick(context(2, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, actuator.movements.size());
        var movement = actuator.movements.getFirst();
        assertEquals(1.0, movement.forward());
        assertTrue(movement.sprint());
        assertFalse(movement.sneak());
    }

    @Test
    void waterBodyContinuouslySwimsTowardFreshObservedLanding() {
        final long revision = 1;
        final GridPos landingSupport = new GridPos(1, 0, 0);
        final LocalNavSnapshot navigation = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                List.of(
                        new ObservedVoxel(
                                landingSupport,
                                VoxelKind.SOLID,
                                0.0,
                                revision,
                                OccupancyEvidence.SURFACE_HIT,
                                TopSupportAffordance.STURDY_FULL_TOP
                        ),
                        new ObservedVoxel(
                                landingSupport.above(),
                                VoxelKind.AIR,
                                0.0,
                                revision,
                                OccupancyEvidence.MULTI_RAY_CLEAR,
                                TopSupportAffordance.UNKNOWN
                        ),
                        new ObservedVoxel(
                                landingSupport.above(2),
                                VoxelKind.AIR,
                                0.0,
                                revision,
                                OccupancyEvidence.MULTI_RAY_CLEAR,
                                TopSupportAffordance.UNKNOWN
                        )
                )
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        new CoreSkillFrame(
                                PLAYER_ID,
                                DimensionRef.OVERWORLD,
                                revision,
                                revision,
                                new PerceptionVec3(0.5, 1.0, 0.5),
                                new PerceptionVec3(0.5, 2.62, 0.5),
                                EAST,
                                false,
                                true,
                                0.0,
                                navigation,
                                List.of()
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                3.5,
                1.0,
                0.5,
                0.35
        );
        skill.start(context(1, false), target);

        final SkillTickResult result = skill.tick(
                context(2, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, actuator.jumps);
        assertEquals(1, actuator.movements.size());
        assertEquals(0.60, actuator.movements.getFirst().forward());
        assertFalse(actuator.movements.getFirst().sprint());
    }

    @Test
    void sameCellCorrectionUsesPrecisionAndRequiresTightAlignment()
            throws Exception {
        LocalNavSnapshot nav = currentCellOnly(1);
        double angle = Math.toRadians(8.0);
        PerceptionVec3 eightDegreesOff = new PerceptionVec3(
                Math.cos(angle),
                0.0,
                Math.sin(angle)
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.1,
                                1.0,
                                0.5,
                                eightDegreesOff,
                                nav,
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                0.5,
                0.25
        );
        skill.start(context(1, false), target);

        SkillTickResult turning = skill.tick(context(2, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, turning.status());
        assertTrue(actuator.movements.isEmpty());
        assertFalse(actuator.looks.isEmpty());
        assertTrue(actuator.stops > 0);

        LocalNavSnapshot alignedNav = currentCellOnly(2);
        frames.frame = frame(
                2,
                0.1,
                1.0,
                0.5,
                EAST,
                alignedNav,
                0.0
        );
        SkillTickResult approaching = skill.tick(
                context(3, false),
                target
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                approaching.status()
        );
        assertEquals(1, actuator.movements.size());
        var movement = actuator.movements.getFirst();
        assertTrue(movement.forward() < 1.0);
        assertFalse(movement.sprint());
        assertTrue(movement.sneak());
    }

    @Test
    void sameCellDockingUsesLowSpeedForThirtyFiveCentimetreArrival()
            throws Exception {
        final LocalNavSnapshot nav = currentCellOnly(1);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.10, 1.0, 0.5, EAST, nav, 0.0)
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                0.5,
                0.35
        );
        skill.start(context(1, false), target);

        final SkillTickResult result = skill.tick(
                context(2, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, actuator.movements.size());
        assertTrue(actuator.movements.getFirst().forward() <= 0.35);
        assertFalse(actuator.movements.getFirst().sprint());
        assertTrue(actuator.movements.getFirst().sneak());
    }

    @Test
    void sameCellPrecisionContinuesWithoutARescanBetweenFrames()
            throws Exception {
        LocalNavSnapshot nav = currentCellOnly(1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.10, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                1.0,
                0.5,
                0.25
        );
        skill.start(context(1, false), target);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2, false), target).status()
        );
        frames.frame = frame(
                2,
                0.11,
                1.0,
                0.5,
                EAST,
                nav,
                0.0
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3, false), target).status()
        );

        assertEquals(
                2,
                actuator.movements.size(),
                "Same-cell docking must renew forward input every tick; "
                    + "falling into the scan branch creates the observed "
                    + "move-stop-turn loop"
        );
        assertTrue(
                actuator.looks.size() >= 2,
                "Each physical movement frame must still re-aim at the "
                    + "exact target"
        );
    }

    @Test
    void observedFrontierStepDoesNotMasqueradeAsPrecisionArrival()
            throws Exception {
        final LocalNavSnapshot nav = corridor(1, 1);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                8.5,
                1.0,
                0.5,
                0.25
        );
        skill.start(context(1, false), target);

        final SkillTickResult first = skill.tick(
                context(2, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, first.status());
        assertEquals(1, actuator.movements.size());
        assertFalse(
                actuator.movements.getFirst().sneak(),
                "A one-step observed frontier is not the requested exact "
                    + "arrival cell and must retain ordinary route movement"
        );

        frames.frame = frame(
                1,
                1.5,
                1.0,
                0.5,
                EAST,
                nav,
                0.0
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3, false), target).status()
        );
        assertEquals(
                1,
                actuator.movements.size(),
                "Reaching a partial frontier must stop for a newer fair "
                    + "observation, not switch to unplanned direct docking"
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(4, false), target).status()
        );
        assertEquals(
                1,
                actuator.movements.size(),
                "An unchanged semantic map must keep scanning rather than "
                    + "blindly renewing movement beyond the frontier"
        );
    }

    @Test
    void unknownRouteScansAtRateLimitAndNeverBlindlyMoves() throws Exception {
        LocalNavSnapshot nav = currentCellOnly(1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                10.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(1, false), target);

        skill.tick(context(2, false), target);
        int firstLookCount = actuator.looks.size();
        skill.tick(context(3, false), target);

        assertTrue(firstLookCount > 0);
        assertTrue(
                actuator.looks.getFirst().pitchDegrees() >= 55.0F,
                "Unknown local support must inspect the immediate "
                    + "destination floor rather than pass above it"
        );
        assertEquals(firstLookCount, actuator.looks.size());
        assertTrue(actuator.movements.isEmpty());
        assertTrue(actuator.stops >= 2);
    }

    @Test
    void arrivalRadiusPlansToNearbyCellInsteadOfOccupiedExactGoal()
            throws Exception {
        LocalNavSnapshot nav = corridor(
                1,
                4,
                new GridPos(4, 1, 0),
                VoxelKind.SOLID,
                0.0
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                4.5,
                1.0,
                0.5,
                2.5
        );
        skill.start(context(1, false), target);

        SkillTickResult first = skill.tick(
                context(2, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, first.status());
        assertEquals(1, actuator.movements.size());
        assertTrue(actuator.movements.getFirst().sprint());
    }

    @Test
    void planningCombinesRecentSafetyGradeFirstPersonEvidence()
            throws Exception {
        final long revision = 20;
        final java.util.List<ObservedVoxel> voxels =
                new java.util.ArrayList<>();
        voxels.add(new ObservedVoxel(
                new GridPos(0, 0, 0),
                VoxelKind.SOLID,
                0.0,
                revision,
                OccupancyEvidence.BODY_CONTACT,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(new ObservedVoxel(
                new GridPos(0, 1, 0),
                VoxelKind.AIR,
                0.0,
                revision,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(new ObservedVoxel(
                new GridPos(0, 2, 0),
                VoxelKind.AIR,
                0.0,
                revision,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        ));
        for (int x = 1; x <= 2; x++) {
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 0, 0),
                    VoxelKind.SOLID,
                    0.0,
                    revision - 3,
                    OccupancyEvidence.SURFACE_HIT,
                    TopSupportAffordance.STURDY_FULL_TOP
            ));
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 1, 0),
                    VoxelKind.AIR,
                    0.0,
                    revision - 2,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 2, 0),
                    VoxelKind.AIR,
                    0.0,
                    revision - 1,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
        }
        final LocalNavSnapshot nav = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                revision,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                nav,
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill =
                new MoveToSkill(PLAYER_ID, actuator, frames);
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(revision, false), target);

        final SkillTickResult result =
                skill.tick(context(revision + 1, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, actuator.movements.size());
    }

    @Test
    void planningNeverRefreshesExpiredVisualMemory()
            throws Exception {
        final long revision = 30;
        final LocalNavSnapshot stale = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                corridor(1, 1).observedVoxels()
                        .values()
                        .stream()
                        .map(voxel -> {
                            if (voxel.position().x() != 0) {
                                return voxel;
                            }
                            final boolean support =
                                    voxel.position().y() == 0;
                            return new ObservedVoxel(
                                    voxel.position(),
                                    voxel.kind(),
                                    voxel.danger(),
                                    revision,
                                    support
                                        ? OccupancyEvidence.BODY_CONTACT
                                        : OccupancyEvidence.BODY_OCCUPIED,
                                    support
                                        ? TopSupportAffordance.UNKNOWN
                                        : voxel.topSupportAffordance()
                            );
                        })
                        .toList()
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                revision,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                stale,
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill =
                new MoveToSkill(PLAYER_ID, actuator, frames);
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                1.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(revision, false), target);

        final SkillTickResult result =
                skill.tick(context(revision + 1, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertTrue(actuator.movements.isEmpty());
        assertFalse(actuator.looks.isEmpty());
    }

    @Test
    void changedStepDependencyStopsAndInvalidatesBeforeMoreMovement()
            throws Exception {
        LocalNavSnapshot initial = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, initial, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);
        int movementCount = actuator.movements.size();

        LocalNavSnapshot changed = corridor(
                2,
                2,
                new GridPos(1, 1, 0),
                VoxelKind.SOLID,
                0.0
        );
        frames.frame = frame(2, 0.5, 1.0, 0.5, EAST, changed, 0.0);
        SkillTickResult invalidated = skill.tick(context(3, false), target);

        assertEquals(SkillTickResult.Status.RUNNING, invalidated.status());
        assertEquals(movementCount, actuator.movements.size());
        assertTrue(actuator.stops > 0);
    }

    @Test
    void singleRayDowngradeStopsBeforeEnteringTheCell() throws Exception {
        LocalNavSnapshot initial = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, initial, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = target();
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);
        int movementCount = actuator.movements.size();

        frames.frame = frame(
                2,
                0.5,
                1.0,
                0.5,
                EAST,
                replaceEvidence(
                        initial,
                        2,
                        new GridPos(1, 1, 0),
                        2,
                        OccupancyEvidence.SINGLE_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                0.0
        );
        SkillTickResult invalidated = skill.tick(
                context(3, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, invalidated.status());
        assertEquals(movementCount, actuator.movements.size());
        assertTrue(actuator.stops > 0);
    }

    @Test
    void recentUnchangedTopSupportMemoryKeepsMovementContinuous()
            throws Exception {
        for (boolean hardcore : new boolean[]{false, true}) {
            LocalNavSnapshot initial = corridor(1, 2);
            CoreSkillTestFixtures.MutableFrames frames =
                    new CoreSkillTestFixtures.MutableFrames(
                            frame(
                                    1,
                                    0.5,
                                    1.0,
                                    0.5,
                                    EAST,
                                    initial,
                                    0.0
                            )
                    );
            CoreSkillTestFixtures.RecordingActuator actuator =
                    new CoreSkillTestFixtures.RecordingActuator();
            MoveToSkill skill = new MoveToSkill(
                    PLAYER_ID,
                    actuator,
                    frames
            );
            MoveToParameters target = target();
            skill.start(context(1, hardcore), target);
            SkillTickResult first = skill.tick(
                    context(2, hardcore),
                    target
            );
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    first.status(),
                    "hardcore=" + hardcore + " "
                            + first.failure()
                                    .map(value -> value.code())
                                    .orElse("no failure")
            );
            int movementCount = actuator.movements.size();

            frames.frame = frame(
                    2,
                    0.5,
                    1.0,
                    0.5,
                    EAST,
                    replaceEvidence(
                            initial,
                            2,
                            new GridPos(1, 0, 0),
                            1,
                            OccupancyEvidence.SURFACE_HIT,
                            TopSupportAffordance.STURDY_FULL_TOP
                    ),
                    0.0
            );
            SkillTickResult invalidated = skill.tick(
                    context(3, hardcore),
                    target
            );

            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    invalidated.status(),
                    invalidated.failure()
                            .map(value -> value.code())
                            .orElse("no failure")
            );
            assertEquals(
                    movementCount + 1,
                    actuator.movements.size()
            );
        }
    }

    @Test
    void expiredTopSupportMemoryStopsEveryModeBeforeMovement()
            throws Exception {
        for (boolean hardcore : new boolean[]{false, true}) {
            LocalNavSnapshot initial = corridor(1, 2);
            CoreSkillTestFixtures.MutableFrames frames =
                    new CoreSkillTestFixtures.MutableFrames(
                            frame(
                                    1,
                                    0.5,
                                    1.0,
                                    0.5,
                                    EAST,
                                    initial,
                                    0.0
                            )
                    );
            CoreSkillTestFixtures.RecordingActuator actuator =
                    new CoreSkillTestFixtures.RecordingActuator();
            MoveToSkill skill = new MoveToSkill(
                    PLAYER_ID,
                    actuator,
                    frames
            );
            MoveToParameters target = target();
            skill.start(context(1, hardcore), target);
            skill.tick(context(2, hardcore), target);
            int movementCount = actuator.movements.size();

            frames.frame = frame(
                    18,
                    0.5,
                    1.0,
                    0.5,
                    EAST,
                    replaceEvidence(
                            initial,
                            18,
                            new GridPos(1, 0, 0),
                            1,
                            OccupancyEvidence.SURFACE_HIT,
                            TopSupportAffordance.STURDY_FULL_TOP
                    ),
                    0.0
            );
            SkillTickResult invalidated = skill.tick(
                    context(3, hardcore),
                    target
            );

            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    invalidated.status()
            );
            assertEquals(
                    movementCount,
                    actuator.movements.size()
            );
            assertTrue(actuator.stops > 0);
        }
    }

    @Test
    void hardcoreRejectsBodyAndRouteDanger() throws Exception {
        LocalNavSnapshot safe = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames dangerousBody =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, safe, 0.2)
                );
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        MoveToSkill first = new MoveToSkill(
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                dangerousBody
        );
        assertEquals(
                "move_to.hardcore_danger",
                first.preconditions(context(1, true), target)
                        .orElseThrow()
                        .code()
        );

        LocalNavSnapshot dangerousRoute = corridor(
                2,
                2,
                new GridPos(1, 1, 0),
                VoxelKind.AIR,
                0.2
        );
        CoreSkillTestFixtures.MutableFrames routeFrames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(2, 0.5, 1.0, 0.5, EAST, dangerousRoute, 0.0)
                );
        MoveToSkill second = new MoveToSkill(
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                routeFrames,
                (context, frame, parameters) -> true
        );
        second.start(context(1, true), target);
        SkillTickResult result = second.tick(context(2, true), target);
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertTrue(result.failure().orElseThrow().code().startsWith(
                "move_to.hardcore_"
        ));
    }

    @Test
    void scopedAuthorizationAdmitsOnlyTheAggregateRiskSample()
            throws Exception {
        final LocalNavSnapshot safe = corridor(1, 2);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                safe,
                                0.2
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill authorized = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames,
                (context, frame, parameters) -> true
        );
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        final SkillContext dangerousContext = new SkillContext(
                1,
                1,
                1,
                true,
                true,
                0.2
        );

        assertTrue(
                authorized.preconditions(
                        dangerousContext,
                        target
                ).isEmpty()
        );
        authorized.start(dangerousContext, target);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                authorized.tick(
                        new SkillContext(
                                1,
                                1,
                                2,
                                true,
                                true,
                                0.2
                        ),
                        target
                ).status()
        );
        assertEquals(1, actuator.movements.size());
    }

    @Test
    void transientPlanningTimeExhaustionIsRetriedButBounded()
            throws Exception {
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                corridor(1, 4),
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final CoreSkillPolicy oneNanosecondPolicy =
                new CoreSkillPolicy(
                        new LocalPlanningBudget(
                                2_048,
                                Duration.ofNanos(1)
                        ),
                        4,
                        30.0F,
                        24,
                        12.0,
                        3.0,
                        0.10
                );
        final MoveToSkill skill = new MoveToSkill(
                PLAYER_ID,
                actuator,
                frames,
                new LocalAStarPlanner(),
                oneNanosecondPolicy
        );
        final MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                4.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(1, false), target);

        for (long tick = 2; tick <= 9; tick++) {
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    skill.tick(
                            context(tick, false),
                            target
                    ).status()
            );
        }
        final SkillTickResult terminal = skill.tick(
                context(10, false),
                target
        );

        assertEquals(
                SkillTickResult.Status.FAILED,
                terminal.status()
        );
        assertEquals(
                "move_to.planning_time_budget_exceeded",
                terminal.failure().orElseThrow().code()
        );
        assertTrue(actuator.movements.isEmpty());
        assertTrue(actuator.stops >= 9);
    }

    @Test
    void cancellationQuiescesAndProducesCancelledResult() throws Exception {
        LocalNavSnapshot nav = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);

        skill.cancel(context(3, false), target);

        assertTrue(actuator.stops > 0);
        assertFalse(actuator.looks.isEmpty());
        assertEquals(
                SkillResult.Status.CANCELLED,
                skill.result(context(3, false), target).status()
        );
    }

    @Test
    void repeatedCollisionEvidenceTriggersBoundedLocalRecovery()
            throws Exception {
        LocalNavSnapshot nav = corridor(1, 2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(1, 0.5, 1.0, 0.5, EAST, nav, 0.0)
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        MoveToSkill skill = new MoveToSkill(PLAYER_ID, actuator, frames);
        MoveToParameters target = new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);

        CoreSkillFrame original = frames.frame;
        frames.frame = original.withPose(new CoreSkillPose(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                22,
                original.position(),
                original.eyePosition(),
                original.lookDirection(),
                true,
                false
        ));
        SkillTickResult recovery = skill.tick(
                context(22, false),
                target
        );
        assertEquals(SkillTickResult.Status.RUNNING, recovery.status());
        int stopsAfterRecovery = actuator.stops;

        frames.frame = frames.frame.withPose(new CoreSkillPose(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                23,
                frames.frame.position(),
                frames.frame.eyePosition(),
                frames.frame.lookDirection(),
                true,
                false
        ));
        SkillTickResult replanned = skill.tick(
                context(23, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, replanned.status());
        assertTrue(actuator.stops >= stopsAfterRecovery);
        assertTrue(
                actuator.jumps > 0 || actuator.looks.size() > 1,
                "recovery must select a new bounded local action"
        );
    }

    @Test
    void persistentTurnMisalignmentCannotRunForever() throws Exception {
        final LocalNavSnapshot nav = corridor(1, 2);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                WEST,
                                nav,
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final MoveToSkill skill =
                new MoveToSkill(PLAYER_ID, actuator, frames);
        final MoveToParameters target = target();
        skill.start(context(1, false), target);

        SkillTickResult result =
                SkillTickResult.running(false, true);
        for (long tick = 2;
                tick <= 400
                        && result.status()
                                == SkillTickResult.Status.RUNNING;
                tick++) {
            final CoreSkillFrame previous = frames.frame;
            frames.frame = previous.withPose(new CoreSkillPose(
                    PLAYER_ID,
                    DimensionRef.OVERWORLD,
                    tick,
                    previous.position(),
                    previous.eyePosition(),
                    WEST,
                    true,
                    false
            ));
            result = skill.tick(context(tick, false), target);
        }

        assertEquals(
                SkillTickResult.Status.FAILED,
                result.status(),
                "A rejected or stale turn must reach a bounded recovery "
                        + "outcome instead of leaving its parent skill "
                        + "RUNNING until the outer real-time gate expires"
        );
        assertTrue(
                result.failure().orElseThrow().code()
                        .startsWith("move_to.")
        );
        assertTrue(
                actuator.movements.isEmpty(),
                "The fixed frame never aligned, so the skill must not claim "
                        + "that forward input was safely issued"
        );
    }

    private static SkillContext context(long tick, boolean hardcore) {
        return new SkillContext(1, 1, tick, hardcore, true, 0.0);
    }

    private static MoveToParameters target() {
        return new MoveToParameters(
                DimensionRef.OVERWORLD,
                2.5,
                1.0,
                0.5,
                0.5
        );
    }

    private static LocalNavSnapshot replaceEvidence(
            LocalNavSnapshot source,
            long snapshotRevision,
            GridPos replaced,
            long replacementRevision,
            OccupancyEvidence occupancy,
            TopSupportAffordance support
    ) {
        return new LocalNavSnapshot(
                source.dimension(),
                snapshotRevision,
                source.observedVoxels().values().stream()
                        .map(voxel -> new ObservedVoxel(
                                voxel.position(),
                                voxel.kind(),
                                voxel.danger(),
                                voxel.position().equals(replaced)
                                        ? replacementRevision
                                        : snapshotRevision,
                                voxel.position().equals(replaced)
                                        ? occupancy
                                        : voxel.occupancyEvidence(),
                                voxel.position().equals(replaced)
                                        ? support
                                        : voxel.topSupportAffordance()
                        ))
                        .toList()
        );
    }
}
