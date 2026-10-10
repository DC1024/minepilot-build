package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.currentCellOnly;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TravelToSkillTest {
    private static final PerceptionVec3 EAST =
            new PerceptionVec3(1.0, 0.0, 0.0);

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
        final TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> 1L
        );
        final TravelToParameters target = target(3.5, 0.5);
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
    void rollsAcrossGrowingFairMapsToAPreviouslyRemoteTarget()
            throws Exception {
        AtomicLong generation = new AtomicLong(7);
        CoreSkillTestFixtures.MutableFrames frames =
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
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get
        );
        TravelToParameters target = target(12.5, 0.5);

        skill.start(context(1, false), target);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2, false), target).status()
        );
        assertTrue(
                actuator.movements.isEmpty(),
                "selecting a fair segment is state-only"
        );
        skill.tick(context(3, false), target);
        assertFalse(actuator.movements.isEmpty());

        frames.frame = pose(
                frames.frame,
                4,
                4.5,
                1.0,
                0.5
        );
        skill.tick(context(4, false), target);
        int movementAfterFirstMap = actuator.movements.size();

        frames.frame = pose(
                frame(
                        2,
                        4.5,
                        1.0,
                        0.5,
                        EAST,
                        corridor(2, 8),
                        0.0
                ),
                5,
                4.5,
                1.0,
                0.5
        );
        skill.tick(context(5, false), target);
        skill.tick(context(6, false), target);
        assertTrue(actuator.movements.size() > movementAfterFirstMap);

        frames.frame = pose(
                frames.frame,
                7,
                8.5,
                1.0,
                0.5
        );
        skill.tick(context(7, false), target);
        frames.frame = pose(
                frame(
                        3,
                        8.5,
                        1.0,
                        0.5,
                        EAST,
                        corridor(3, 12),
                        0.0
                ),
                8,
                8.5,
                1.0,
                0.5
        );
        skill.tick(context(8, false), target);
        skill.tick(context(9, false), target);
        frames.frame = pose(
                frames.frame,
                10,
                12.5,
                1.0,
                0.5
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(10, false), target).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(10, false), target).status()
        );
    }

    @Test
    void turnsForANewerObservationAndNeverEntersUnknownCells()
            throws Exception {
        AtomicLong generation = new AtomicLong(1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                currentCellOnly(1),
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get
        );
        TravelToParameters target = target(40.5, 3.0);
        skill.start(context(1, false), target);

        skill.tick(context(2, false), target);
        int firstLookCount = actuator.looks.size();
        skill.tick(context(3, false), target);

        assertTrue(firstLookCount > 0);
        assertEquals(firstLookCount, actuator.looks.size());
        assertTrue(actuator.movements.isEmpty());
        assertTrue(actuator.stops >= 2);
    }

    @Test
    void scanPatternDeliberatelySamplesFloorAndAdjacentCorridors()
            throws Exception {
        AtomicLong generation = new AtomicLong(2);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                currentCellOnly(1),
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(8, 16, 100)
        );
        TravelToParameters target = target(40.5, 3.0);
        skill.start(context(1, false), target);

        skill.tick(context(2, false), target);
        for (long revision = 2; revision <= 4; revision++) {
            frames.frame = frame(
                    revision,
                    0.5,
                    1.0,
                    0.5,
                    EAST,
                    currentCellOnly(revision),
                    0.0
            );
            skill.tick(
                    context(2 + revision, false),
                    target
            );
        }

        assertEquals(4, actuator.looks.size());
        assertTrue(
                actuator.looks.stream().allMatch(
                        look -> look.pitchDegrees() >= 20.0F
                ),
                "early scans must deliberately look down for floor evidence"
        );
        assertTrue(
                actuator.looks.stream()
                        .mapToDouble(LookIntent::yawDegrees)
                        .max()
                        .orElseThrow()
                    - actuator.looks.stream()
                        .mapToDouble(LookIntent::yawDegrees)
                        .min()
                        .orElseThrow()
                    >= 60.0
        );
    }

    @Test
    void blockedRemoteRouteEventuallyScansBehindItsInitialBearing()
            throws Exception {
        final AtomicLong generation = new AtomicLong(22);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                currentCellOnly(1),
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(8, 32, 100)
        );
        final TravelToParameters target = target(40.5, 3.0);
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);

        for (long revision = 2; revision <= 13; revision++) {
            frames.frame = frame(
                    revision,
                    0.5,
                    1.0,
                    0.5,
                    EAST,
                    currentCellOnly(revision),
                    0.0
            );
            skill.tick(context(2 + revision, false), target);
        }

        final float firstYaw = actuator.looks.getFirst().yawDegrees();
        assertTrue(
                actuator.looks.stream().anyMatch(look ->
                        Math.abs(ActionMath.wrapDegrees(
                                look.yawDegrees() - firstYaw
                        )) >= 179.0F
                ),
                "a blocked remote route must inspect the full surrounding "
                    + "space before declaring it unknown"
        );
        assertTrue(
                actuator.movements.isEmpty(),
                "scanning alone must never enter an unknown voxel"
        );
    }

    @Test
    void physicalAdvanceRestartsScanningAtTheDirectFrontier()
            throws Exception {
        final AtomicLong generation = new AtomicLong(3);
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                currentCellOnly(1),
                                0.0
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(8, 16, 100)
        );
        final TravelToParameters target = target(40.5, 3.0);
        skill.start(context(1, false), target);

        skill.tick(context(2, false), target);
        final LookIntent first = actuator.looks.getLast();
        frames.frame = pose(
                frame(
                        2,
                        1.5,
                        1.0,
                        0.5,
                        EAST,
                        currentCellOnly(2),
                        0.0
                ),
                3,
                1.5,
                1.0,
                0.5
        );
        skill.tick(context(3, false), target);
        final LookIntent afterAdvance = actuator.looks.getLast();

        assertEquals(2, actuator.looks.size());
        assertEquals(
                first.yawDegrees(),
                afterAdvance.yawDegrees(),
                0.01F,
                "a moved body must resample the new direct frontier first"
        );
    }

    @Test
    void refusesAnObservedLavaBarrierAndFailsAfterBoundedRescans()
            throws Exception {
        AtomicLong generation = new AtomicLong(3);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                lavaBarrier(1),
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(2, 2, 100)
        );
        TravelToParameters target = target(4.5, 0.5);
        skill.start(context(1, true), target);
        skill.tick(context(2, true), target);

        frames.frame = frame(
                2,
                0.5,
                1.0,
                0.5,
                EAST,
                lavaBarrier(2),
                0.0
        );
        skill.tick(context(3, true), target);
        frames.frame = frame(
                3,
                0.5,
                1.0,
                0.5,
                EAST,
                lavaBarrier(3),
                0.0
        );
        SkillTickResult result = skill.tick(
                context(4, true),
                target
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "travel_to.danger_blocked",
                result.failure().orElseThrow().code()
        );
        assertTrue(actuator.movements.isEmpty());
    }

    @Test
    void stationarySegmentFailsWithAStopInsteadOfWalkingForever()
            throws Exception {
        AtomicLong generation = new AtomicLong(4);
        CoreSkillTestFixtures.MutableFrames frames =
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
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(8, 64, 10)
        );
        TravelToParameters target = target(20.5, 0.5);
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);

        SkillTickResult result = SkillTickResult.running(false, true);
        for (long tick = 3; tick <= 13; tick++) {
            result = skill.tick(context(tick, false), target);
        }

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "travel_to.stuck",
                result.failure().orElseThrow().code()
        );
        assertTrue(actuator.stops > 0);
    }

    @Test
    void oldVoxelEvidenceCausesARescanRatherThanMovement()
            throws Exception {
        AtomicLong generation = new AtomicLong(5);
        LocalNavSnapshot old = corridorWithVoxelRevision(10, 4, 1);
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                10,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                old,
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = skill(
                actuator,
                frames,
                generation,
                policy(2, 2, 100)
        );
        TravelToParameters target = target(4.5, 0.5);
        skill.start(context(1, false), target);

        SkillTickResult result = skill.tick(
                context(2, false),
                target
        );

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertTrue(actuator.looks.size() > 0);
        assertTrue(actuator.movements.isEmpty());
    }

    @Test
    void transientWallClockPlanningExhaustionIsRetriedButBounded()
            throws Exception {
        final AtomicLong generation = new AtomicLong(6);
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
        final CoreSkillPolicy oneNanosecondPolicy = new CoreSkillPolicy(
                new LocalPlanningBudget(2_048, Duration.ofNanos(1)),
                4,
                30.0F,
                24,
                12.0,
                3.0,
                0.10
        );
        final TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get,
                new LocalAStarPlanner(),
                oneNanosecondPolicy,
                TravelSkillPolicy.defaults()
        );
        final TravelToParameters target = target(20.5, 0.5);
        skill.start(context(1, false), target);

        for (long tick = 2; tick <= 9; tick++) {
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    skill.tick(context(tick, false), target).status(),
                    "a single wall-clock budget miss must not terminate travel"
            );
        }
        final SkillTickResult terminal =
                skill.tick(context(10, false), target);

        assertEquals(SkillTickResult.Status.FAILED, terminal.status());
        assertEquals(
                "travel_to.planning_time_budget_exceeded",
                terminal.failure().orElseThrow().code()
        );
        assertTrue(actuator.movements.isEmpty());
        assertTrue(
                actuator.stops >= 9,
                "each deferred or terminal tick must clear movement input"
        );
    }

    @Test
    void sessionGenerationChangeTerminatesBeforeAnotherAction()
            throws Exception {
        AtomicLong generation = new AtomicLong(9);
        CoreSkillTestFixtures.MutableFrames frames =
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
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get
        );
        TravelToParameters target = target(20.5, 0.5);
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);
        int actionCount = actuator.movements.size()
                + actuator.looks.size();

        generation.incrementAndGet();
        SkillTickResult result = skill.tick(
                context(3, false),
                target
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "travel_to.session_changed",
                result.failure().orElseThrow().code()
        );
        assertEquals(
                actionCount + 1,
                actuator.movements.size() + actuator.looks.size(),
                "the only extra action is the safe hold-look during quiesce"
        );
    }

    @Test
    void acceptsNearestObservedSafeLandingForUnsafeWaypoint()
            throws Exception {
        AtomicLong generation = new AtomicLong(11);
        LocalNavSnapshot nav = corridor(
                1,
                4,
                new GridPos(4, 1, 0),
                VoxelKind.SOLID,
                0.0
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                EAST,
                                nav,
                                0.0
                        )
                );
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        TravelToSkill skill = new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get
        );
        TravelToParameters target = target(4.5, 0.5);
        skill.start(context(1, false), target);
        skill.tick(context(2, false), target);
        skill.tick(context(3, false), target);
        frames.frame = pose(
                frames.frame,
                4,
                4.5,
                2.0,
                0.5
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(4, false), target).status()
        );
    }

    private static TravelToSkill skill(
            CoreSkillTestFixtures.RecordingActuator actuator,
            CoreSkillTestFixtures.MutableFrames frames,
            AtomicLong generation,
            TravelSkillPolicy policy
    ) {
        return new TravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                generation::get,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                policy
        );
    }

    private static TravelSkillPolicy policy(
            int maximumVoxelAge,
            int maximumScans,
            long stationaryTicks
    ) {
        return new TravelSkillPolicy(
                8.0,
                24,
                100,
                2_000,
                1_000,
                stationaryTicks,
                maximumScans,
                1,
                30.0F,
                maximumVoxelAge,
                0.25,
                0.10
        );
    }

    private static TravelToParameters target(
            double x,
            double radius
    ) {
        return new TravelToParameters(
                DimensionRef.OVERWORLD,
                x,
                1.0,
                0.5,
                radius
        );
    }

    private static SkillContext context(long tick, boolean hardcore) {
        return new SkillContext(1, 1, tick, hardcore, true, 0.0);
    }

    private static CoreSkillFrame pose(
            CoreSkillFrame frame,
            long gameTime,
            double x,
            double y,
            double z
    ) {
        return frame.withPose(new CoreSkillPose(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                gameTime,
                new PerceptionVec3(x, y, z),
                new PerceptionVec3(x, y + 1.62, z),
                EAST,
                true,
                false
        ));
    }

    private static LocalNavSnapshot lavaBarrier(long revision) {
        return corridor(
                revision,
                4,
                new GridPos(1, 1, 0),
                VoxelKind.LAVA,
                1.0
        );
    }

    private static LocalNavSnapshot corridorWithVoxelRevision(
            long snapshotRevision,
            int maximumX,
            long voxelRevision
    ) {
        List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = 0; x <= maximumX; x++) {
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 0, 0),
                    VoxelKind.SOLID,
                    0.0,
                    voxelRevision
            ));
            for (int y = 1; y <= 3; y++) {
                voxels.add(new ObservedVoxel(
                        new GridPos(x, y, 0),
                        VoxelKind.AIR,
                        0.0,
                        voxelRevision
                ));
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                snapshotRevision,
                voxels
        );
    }
}
