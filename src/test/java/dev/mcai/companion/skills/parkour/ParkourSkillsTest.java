package dev.mcai.companion.skills.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ParkourSkillsTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "63000000-0000-0000-0000-000000000001"
    );

    @Test
    void parsesAndRegistersTheBoundedContract() {
        final var parsed = ParkourSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "0.5"),
                argument("y", "64"),
                argument("z", "8.5"),
                argument("arrivalRadius", "0.65"),
                argument("maxJumps", "4"),
                argument("maxGap", "2")
        ));
        assertEquals(4, parsed.value().orElseThrow().maxJumps());
        assertEquals(2, parsed.value().orElseThrow().maxGap());
        assertFalse(ParkourSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "0.5"),
                argument("y", "64"),
                argument("z", "8.5"),
                argument("arrivalRadius", "0.65"),
                argument("maxJumps", "04"),
                argument("maxGap", "2")
        )).value().isPresent());
        assertFalse(ParkourSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "0.5"),
                argument("y", "64"),
                argument("z", "8.5"),
                argument("arrivalRadius", "0.65"),
                argument("maxJumps", "4"),
                argument("maxGap", "3")
        )).value().isPresent());

        final SkillRegistry registry = ParkourSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(frame(1, 0.65, List.of(), 20.0F))
        );
        assertEquals(java.util.Set.of("parkour_to"), registry.names());
        assertTrue(ParkourSkills.plannerGuide().contains("vanilla"));
    }

    @Test
    void refusesHardcoreRiskAndLowHealth() {
        final ParkourToParameters parameters = parameters();
        final ParkourToSkill risky = new ParkourToSkill(
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(frame(1, 0.65, List.of(), 20.0F))
        );
        assertEquals(
                "parkour_to.danger_detected",
                risky.preconditions(
                    context(1, true, 0.05),
                    parameters
                ).orElseThrow().code()
        );

        final ParkourToSkill injured = new ParkourToSkill(
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(frame(1, 0.65, List.of(), 17.0F))
        );
        assertEquals(
                "parkour_to.health_too_low",
                injured.preconditions(
                    context(1, true, 0.0),
                    parameters
                ).orElseThrow().code()
        );
    }

    @Test
    void neverJumpsTowardAnUnseenLanding() {
        final MutableFrames frames = new MutableFrames(
                frame(1, 0.65, List.of(), 20.0F)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final ParkourToSkill skill = new ParkourToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final ParkourToParameters parameters = parameters();
        assertTrue(
                skill.preconditions(
                    context(10, false, 0.0),
                    parameters
                ).isEmpty()
        );
        skill.start(context(10, false, 0.0), parameters);
        skill.tick(context(11, false, 0.0), parameters);
        assertEquals(0, actuator.jumps);

        final PerceptionVec3 hit =
                new PerceptionVec3(0.5, 64.0, 2.5);
        frames.frame = frame(
                2,
                0.65,
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 2),
                        "minecraft:smooth_stone",
                        "up",
                        hit,
                        hit.subtract(
                            new PerceptionVec3(0.5, 65.62, 0.65)
                        ).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )),
                20.0F
        );
        skill.tick(context(12, false, 0.0), parameters);
        assertEquals(1, actuator.jumps);
        assertTrue(
                actuator.moves.getLast().sprint(),
                "A one-gap jump needs sprint speed to reach stable overlap; "
                    + "the airborne brake prevents overshoot"
        );

        frames.frame = withPose(
                frame(
                        3,
                        1.81,
                        frames.frame.visibleBlockFaces(),
                        20.0F
                ),
                new PerceptionVec3(0.5, 64.35, 1.81),
                false
        );
        final int stopsBeforeAirborneBrake = actuator.stops;
        skill.tick(context(13, false, 0.0), parameters);
        assertEquals(-1.0, actuator.moves.getLast().forward());
        assertFalse(actuator.moves.getLast().sprint());
        assertEquals(
                stopsBeforeAirborneBrake + 1,
                actuator.stops,
                "First landing overlap must release forward input once"
        );

        frames.frame = withPose(
                frame(
                        4,
                        1.95,
                        frames.frame.visibleBlockFaces(),
                        20.0F
                ),
                new PerceptionVec3(0.5, 64.40, 1.95),
                false
        );
        skill.tick(context(14, false, 0.0), parameters);
        assertEquals(
                stopsBeforeAirborneBrake + 1,
                actuator.stops,
                "Later braking ticks must not reset the input controller"
        );
        assertEquals(-1.0, actuator.moves.getLast().forward());

        frames.frame = withPose(
                frame(
                        5,
                        2.5,
                        frames.frame.visibleBlockFaces(),
                        20.0F
                ),
                new PerceptionVec3(0.5, 64.0, 2.5),
                true
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(15, false, 0.0),
                        parameters
                ).status(),
                "A final landing must finish and recenter its active jump "
                    + "before the skill accepts coordinate arrival"
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(16, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(17, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(18, false, 0.0),
                        parameters
                ).status(),
                "Three stable centered ticks should finish recentering "
                    + "without bypassing the landing phase"
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(19, false, 0.0),
                        parameters
                ).status()
        );
    }

    @Test
    void unknownGapWaitsAndRefreshedContinuousPlatformWalks() {
        final PerceptionVec3 hit =
                new PerceptionVec3(0.5, 64.0, 2.5);
        final List<VisibleBlockFace> faces = List.of(
                new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 2),
                        "minecraft:smooth_stone",
                        "up",
                        hit,
                        hit.subtract(
                            new PerceptionVec3(0.5, 65.62, 0.65)
                        ).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )
        );
        final CoreSkillFrame staleGap = withVoxel(
                frame(2, 0.65, faces, 20.0F),
                evidenceVoxel(
                        new GridPos(0, 63, 1),
                        VoxelKind.AIR,
                        0.0,
                        1,
                        false
                )
        );
        final MutableFrames frames = new MutableFrames(staleGap);
        final RecordingActuator actuator = new RecordingActuator();
        final ParkourToSkill skill = new ParkourToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final ParkourToParameters parameters = parameters();
        skill.start(context(10, false, 0.0), parameters);
        skill.tick(context(11, false, 0.0), parameters);
        assertEquals(
                0,
                actuator.jumps,
                "Stale AIR beneath an adjacent cell is unknown, not a gap"
        );

        frames.frame = withVoxel(
                frame(3, 0.65, faces, 20.0F),
                evidenceVoxel(
                        new GridPos(0, 63, 1),
                        VoxelKind.SOLID,
                        0.0,
                        3,
                        true
                )
        );
        skill.tick(context(12, false, 0.0), parameters);
        assertEquals(
                0,
                actuator.jumps,
                "A refreshed continuous platform must be walked"
        );
        assertEquals(1.0, actuator.moves.getLast().forward());
    }

    @Test
    void lShapedRouteUsesVisibleLegAndTurnsAfterChangingFeetCell() {
        final RecordingActuator actuator = new RecordingActuator();
        final ParkourToSkill skill = new ParkourToSkill(
                PLAYER_ID,
                actuator,
                new MutableFrames(frame(1, 0.5, List.of(), 20.0F))
        );
        final ParkourToParameters diagonal = new ParkourToParameters(
                DimensionRef.OVERWORLD,
                3.5,
                64.0,
                3.5,
                0.5,
                2,
                1
        );
        final PerceptionVec3 xTop =
                new PerceptionVec3(2.5, 64.0, 0.5);
        final CoreSkillFrame visibleXLeg = directionFrame(
                10,
                new PerceptionVec3(0.56, 64.0, 0.5),
                List.of(),
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(2, 63, 0),
                        "minecraft:smooth_stone",
                        "up",
                        xTop,
                        2.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                ))
        );

        assertEquals(
                new ParkourToSkill.Direction(1, 0),
                skill.selectGroundedDirection(
                        visibleXLeg,
                        diagonal,
                        0.12,
                        false
                ),
                "Visible +X landing must beat a slightly longer unknown +Z "
                    + "corridor"
        );
        assertEquals(
                new ParkourToSkill.Direction(1, 0),
                skill.selectGroundedDirection(
                        directionFrame(
                                11,
                                new PerceptionVec3(0.76, 64.0, 0.5),
                                List.of(),
                                List.of()
                        ),
                        diagonal,
                        0.12,
                        false
                ),
                "The selected leg must survive a short loss of the face ray "
                    + "inside the same takeoff cell"
        );
        assertEquals(
                new ParkourToSkill.Direction(0, 1),
                skill.selectGroundedDirection(
                        directionFrame(
                                12,
                                new PerceptionVec3(2.5, 64.0, 0.5),
                                List.of(),
                                List.of()
                        ),
                        diagonal,
                        0.12,
                        false
                ),
                "After landing in a new feet cell, the remaining +Z leg "
                    + "must be selected"
        );
    }

    @Test
    void equallySafeDirectionsUseGeometryAndUnknownTieUsesX() {
        final List<ObservedVoxel> bothAdjacent = new ArrayList<>();
        add(bothAdjacent, 1, 63, 0, VoxelKind.SOLID, 20);
        add(bothAdjacent, 1, 64, 0, VoxelKind.AIR, 20);
        add(bothAdjacent, 1, 65, 0, VoxelKind.AIR, 20);
        add(bothAdjacent, 0, 63, 1, VoxelKind.SOLID, 20);
        add(bothAdjacent, 0, 64, 1, VoxelKind.AIR, 20);
        add(bothAdjacent, 0, 65, 1, VoxelKind.AIR, 20);
        final CoreSkillFrame bothSafe = directionFrame(
                20,
                new PerceptionVec3(0.5, 64.0, 0.5),
                bothAdjacent,
                List.of()
        );
        final ParkourToSkill safeSkill = new ParkourToSkill(
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(bothSafe)
        );
        assertEquals(
                new ParkourToSkill.Direction(0, 1),
                safeSkill.selectGroundedDirection(
                        bothSafe,
                        new ParkourToParameters(
                                DimensionRef.OVERWORLD,
                                2.5,
                                64.0,
                                3.5,
                                0.5,
                                2,
                                1
                        ),
                        0.12,
                        false
                ),
                "When both adjacent cells are equally safe, the longer "
                    + "target-reducing axis must win"
        );

        final CoreSkillFrame unknown = directionFrame(
                21,
                new PerceptionVec3(0.5, 64.0, 0.5),
                List.of(),
                List.of()
        );
        final ParkourToSkill unknownSkill = new ParkourToSkill(
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(unknown)
        );
        assertEquals(
                new ParkourToSkill.Direction(1, 0),
                unknownSkill.selectGroundedDirection(
                        unknown,
                        new ParkourToParameters(
                                DimensionRef.OVERWORLD,
                                3.5,
                                64.0,
                                3.5,
                                0.5,
                                2,
                                1
                        ),
                        0.12,
                        false
                ),
                "An exact all-unknown tie must deterministically choose X"
        );
    }

    @Test
    void scanPhasesHoldSixTicksAndCompleteAStableCycle() {
        for (int tick = 1; tick <= 18; tick++) {
            assertEquals(
                (tick - 1) / 6,
                ParkourToSkill.scanPhase(tick, 3),
                "Each requested look must remain stable for six ticks"
            );
        }
        assertEquals(0, ParkourToSkill.scanPhase(19, 3));
        assertThrows(
            IllegalArgumentException.class,
            () -> ParkourToSkill.scanPhase(0, 3)
        );
    }

    @Test
    void hardcoreScanStartsBeforeVanillaSneakEdgeLimit() {
        final ParkourToSkill.Direction positiveZ =
            new ParkourToSkill.Direction(0, 1);

        assertTrue(ParkourToSkill.shouldApproachHardcoreScanEdge(
            new PerceptionVec3(4.5, 70.0, 8.59),
            positiveZ,
            false
        ));
        assertFalse(ParkourToSkill.shouldApproachHardcoreScanEdge(
            new PerceptionVec3(4.5, 70.0, 8.61),
            positiveZ,
            false
        ));
        assertFalse(ParkourToSkill.shouldApproachHardcoreScanEdge(
            new PerceptionVec3(4.5, 70.0, 8.64),
            positiveZ,
            false
        ));
        assertFalse(ParkourToSkill.shouldApproachHardcoreScanEdge(
            new PerceptionVec3(4.5, 70.0, 8.52),
            positiveZ,
            true
        ));
        assertTrue(ParkourToSkill.retainHardcoreScanDirection(
            true,
            1,
            false
        ));
        assertTrue(ParkourToSkill.retainHardcoreScanDirection(
            true,
            0,
            true
        ));
        assertFalse(ParkourToSkill.retainHardcoreScanDirection(
            false,
            1,
            true
        ));
        assertTrue(ParkourToSkill.withinHardcoreScanEvidenceWindow(
            10,
            14,
            10
        ));
        assertTrue(ParkourToSkill.withinHardcoreScanEvidenceWindow(
            10,
            14,
            14
        ));
        assertFalse(ParkourToSkill.withinHardcoreScanEvidenceWindow(
            10,
            15,
            10
        ));
        assertFalse(ParkourToSkill.withinHardcoreScanEvidenceWindow(
            10,
            14,
            9
        ));
        assertFalse(ParkourToSkill.withinHardcoreScanEvidenceWindow(
            10,
            14,
            15
        ));
    }

    @Test
    void evidenceDrivenScanFocusesUnknownGapThenFarthestLanding() {
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 1),
            ParkourToSkill.scanPlan(false, false, 1, 1)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 1),
            ParkourToSkill.scanPlan(false, false, 2, 37)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 2),
            ParkourToSkill.scanPlan(false, true, 1, 1)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 3),
            ParkourToSkill.scanPlan(false, true, 2, 37)
        );
    }

    @Test
    void hardcoreScanRetainsEveryRecoveryPhaseAndCycleBoundary() {
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 3),
            ParkourToSkill.scanPlan(true, true, 2, 1)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 3),
            ParkourToSkill.scanPlan(true, true, 2, 6)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(true, 1),
            ParkourToSkill.scanPlan(true, true, 2, 7)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(true, 1),
            ParkourToSkill.scanPlan(true, true, 2, 12)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(true, 2),
            ParkourToSkill.scanPlan(true, true, 2, 13)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(true, 2),
            ParkourToSkill.scanPlan(true, true, 2, 18)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 3),
            ParkourToSkill.scanPlan(true, true, 2, 19)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 2),
            ParkourToSkill.scanPlan(true, true, 1, 1)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(true, 1),
            ParkourToSkill.scanPlan(true, true, 1, 7)
        );
        assertEquals(
            new ParkourToSkill.ScanPlan(false, 2),
            ParkourToSkill.scanPlan(true, true, 1, 13)
        );

        final PerceptionVec3 recoveryProbe =
            ParkourToSkill.scanTarget(
                context(10, true, 0.0),
                parameters(),
                new GridPos(0, 64, 0),
                new ParkourToSkill.Direction(0, 1),
                7,
                true
            );
        assertEquals(60.80, recoveryProbe.y(), 1.0E-9);
        assertEquals(1.98, recoveryProbe.z(), 1.0E-9);
    }

    @Test
    void landingRecenterBrakesApproachesAndCoastsBeforeReady() {
        final MutableFrames frames = new MutableFrames(
                frame(1, 0.55, List.of(), 20.0F)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final ParkourToSkill skill = new ParkourToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final ParkourToParameters parameters = parameters();
        skill.start(context(10, false, 0.0), parameters);

        final PerceptionVec3 hit =
                new PerceptionVec3(0.5, 64.0, 2.5);
        final List<VisibleBlockFace> faces = List.of(
                new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 2),
                        "minecraft:smooth_stone",
                        "up",
                        hit,
                        hit.subtract(
                            new PerceptionVec3(0.5, 65.62, 0.65)
                        ).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )
        );
        frames.frame = frame(2, 0.65, faces, 20.0F);
        skill.tick(context(12, false, 0.0), parameters);

        frames.frame = withPose(
                frame(3, 1.81, faces, 20.0F),
                new PerceptionVec3(0.5, 64.35, 1.81),
                false
        );
        skill.tick(context(13, false, 0.0), parameters);

        frames.frame = frame(4, 3.03, faces, 20.0F);
        skill.tick(context(14, false, 0.0), parameters);
        assertEquals(
                -1.0,
                actuator.moves.getLast().forward(),
                "BRAKE_LANDING must counter initial forward inertia"
        );
        assertTrue(
                actuator.moves.getLast().sneak(),
                "Grounded landing correction must hold vanilla sneak"
        );

        frames.frame = frame(5, 3.16, faces, 20.0F);
        skill.tick(context(15, false, 0.0), parameters);
        assertEquals(-1.0, actuator.moves.getLast().forward());

        final int stopsBeforeReversal = actuator.stops;
        frames.frame = frame(6, 3.14, faces, 20.0F);
        skill.tick(context(16, false, 0.0), parameters);
        assertTrue(
                actuator.stops > stopsBeforeReversal,
                "BRAKE_LANDING must release input at the first reversal"
        );
        assertTrue(
                actuator.moves.getLast().sneak(),
                "BRAKE_LANDING must retain edge protection while stopped"
        );

        frames.frame = frame(7, 3.14, faces, 20.0F);
        skill.tick(context(17, false, 0.0), parameters);
        assertEquals(
                -0.30,
                actuator.moves.getLast().forward(),
                "APPROACH must use bounded ordinary movement"
        );
        assertFalse(actuator.moves.getLast().sprint());
        assertTrue(actuator.moves.getLast().sneak());

        final int stopsBeforeCapture = actuator.stops;
        frames.frame = frame(8, 2.52, faces, 20.0F);
        skill.tick(context(18, false, 0.0), parameters);
        assertTrue(
                actuator.stops > stopsBeforeCapture,
                "Entering the capture band must release movement"
        );
        assertTrue(
                actuator.moves.getLast().sneak(),
                "COAST must retain vanilla edge protection"
        );

        frames.frame = frame(9, 2.46, faces, 20.0F);
        skill.tick(context(19, false, 0.0), parameters);
        frames.frame = frame(10, 2.46, faces, 20.0F);
        skill.tick(context(20, false, 0.0), parameters);
        frames.frame = frame(11, 2.46, faces, 20.0F);
        skill.tick(context(21, false, 0.0), parameters);
        frames.frame = frame(12, 2.46, faces, 20.0F);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(22, false, 0.0),
                        parameters
                ).status(),
                "COAST must require three centered low-motion ticks"
        );
        frames.frame = frame(13, 2.46, faces, 20.0F);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(23, false, 0.0),
                        parameters
                ).status()
        );
    }

    @Test
    void airborneBrakingRequiresRealLandingOverlapInEveryDirection() {
        final GridPos landing = new GridPos(10, 64, 20);

        assertFalse(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(9.70, 64.0, 20.5),
                landing,
                1,
                0
        ));
        assertTrue(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(9.81, 64.0, 20.5),
                landing,
                1,
                0
        ));
        assertFalse(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(11.30, 64.0, 20.5),
                landing,
                -1,
                0
        ));
        assertTrue(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(11.19, 64.0, 20.5),
                landing,
                -1,
                0
        ));
        assertFalse(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(10.5, 64.0, 19.70),
                landing,
                0,
                1
        ));
        assertTrue(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(10.5, 64.0, 19.81),
                landing,
                0,
                1
        ));
        assertFalse(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(10.5, 64.0, 21.30),
                landing,
                0,
                -1
        ));
        assertTrue(ParkourToSkill.hasMinimumForwardLandingOverlap(
                new PerceptionVec3(10.5, 64.0, 21.19),
                landing,
                0,
                -1
        ));
    }

    @Test
    void twoEmptyBlockJumpStartsVanillaInputBrakeBeforeLandingEdge() {
        final GridPos takeoff = new GridPos(0, 64, 0);
        final GridPos longLanding = new GridPos(0, 64, 3);

        assertFalse(ParkourToSkill.shouldStartLandingBrake(
                new PerceptionVec3(0.5, 64.4, 2.34),
                takeoff,
                longLanding,
                0,
                1
        ));
        assertTrue(ParkourToSkill.shouldStartLandingBrake(
                new PerceptionVec3(0.5, 64.4, 2.36),
                takeoff,
                longLanding,
                0,
                1
        ));

        final GridPos shortLanding = new GridPos(0, 64, 2);
        assertFalse(ParkourToSkill.shouldStartLandingBrake(
                new PerceptionVec3(0.5, 64.4, 1.79),
                takeoff,
                shortLanding,
                0,
                1
        ));
        assertTrue(ParkourToSkill.shouldStartLandingBrake(
                new PerceptionVec3(0.5, 64.4, 1.81),
                takeoff,
                shortLanding,
                0,
                1
        ));
    }

    @Test
    void everyActualGapUsesVanillaSprintJump() {
        final GridPos takeoff = new GridPos(0, 64, 0);

        assertFalse(ParkourToSkill.requiresSprintJump(
                takeoff,
                new GridPos(0, 64, 1)
        ));
        assertTrue(ParkourToSkill.requiresSprintJump(
                takeoff,
                new GridPos(0, 64, 2)
        ));
        assertTrue(ParkourToSkill.requiresSprintJump(
                takeoff,
                new GridPos(1, 65, 0)
        ));
    }

    @Test
    void standabilityRequiresCurrentSupportAndClearanceEvidence() {
        final CoreSkillFrame current = frame(
                7,
                0.65,
                List.of(),
                20.0F
        );
        final GridPos landing = new GridPos(0, 64, 2);
        assertTrue(ParkourToSkill.standable(
                current,
                landing,
                0.12
        ));
        assertFalse(ParkourToSkill.standable(
                withLandingEvidence(current, 6, 7, 7, true),
                landing,
                0.12
        ), "A retained stale support must not turn a gap into a walkway");
        assertFalse(ParkourToSkill.standable(
                withLandingEvidence(current, 7, 6, 7, true),
                landing,
                0.12
        ), "Stale feet clearance must fail closed");
        assertFalse(ParkourToSkill.standable(
                withLandingEvidence(current, 7, 7, 6, true),
                landing,
                0.12
        ), "Stale head clearance must fail closed");
        assertFalse(ParkourToSkill.standable(
                withLandingEvidence(current, 7, 7, 7, false),
                landing,
                0.12
        ), "A generic solid without a sturdy observed top is not support");
    }

    @Test
    void adjacentObservedPlatformDoesNotRequireConvergingAirRays() {
        final long revision = 12;
        final GridPos adjacent = new GridPos(0, 64, 1);
        final CoreSkillFrame visibleTop = directionFrame(
                revision,
                new PerceptionVec3(0.5, 64.0, 0.48),
                List.of(
                    evidenceVoxel(
                        adjacent.below(),
                        VoxelKind.SOLID,
                        0.0,
                        revision,
                        true
                    ),
                    new ObservedVoxel(
                        adjacent,
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.SINGLE_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                    )
                ),
                List.of()
        );
        assertTrue(ParkourToSkill.adjacentPlatformWalkable(
                visibleTop,
                adjacent,
                0.12
        ));

        final CoreSkillFrame blockedHead = withVoxel(
                visibleTop,
                evidenceVoxel(
                    adjacent.above(),
                    VoxelKind.SOLID,
                    0.0,
                    revision,
                    false
                )
        );
        assertFalse(ParkourToSkill.adjacentPlatformWalkable(
                blockedHead,
                adjacent,
                0.12
        ));
        assertFalse(ParkourToSkill.adjacentPlatformWalkable(
                withVoxel(
                    visibleTop,
                    evidenceVoxel(
                        adjacent.below(),
                        VoxelKind.SOLID,
                        0.0,
                        revision - 1,
                        true
                    )
                ),
                adjacent,
                0.12
        ));
    }

    @Test
    void committedJumpIgnoresMissingRefreshButRejectsContradiction() {
        final CoreSkillFrame initial = frame(
                7,
                0.65,
                List.of(),
                20.0F
        );
        final GridPos landing = new GridPos(0, 64, 2);
        final List<ObservedVoxel> certified = landingEvidence(
                7,
                VoxelKind.SOLID,
                7,
                VoxelKind.AIR,
                7,
                VoxelKind.AIR,
                0.0
        );
        assertTrue(ParkourToSkill.committedLandingStillSafe(
                withNavigationEvidence(initial, 8, certified),
                landing,
                7,
                0.12
        ), "A later snapshot without a new landing ray keeps the certificate");

        assertFalse(ParkourToSkill.committedLandingStillSafe(
                withNavigationEvidence(
                        initial,
                        8,
                        landingEvidence(
                            8,
                            VoxelKind.AIR,
                            7,
                            VoxelKind.AIR,
                            7,
                            VoxelKind.AIR,
                            0.0
                        )
                ),
                landing,
                7,
                0.12
        ), "Current evidence that support disappeared revokes the jump");
        assertFalse(ParkourToSkill.committedLandingStillSafe(
                withNavigationEvidence(
                        initial,
                        8,
                        landingEvidence(
                            7,
                            VoxelKind.SOLID,
                            8,
                            VoxelKind.SOLID,
                            7,
                            VoxelKind.AIR,
                            0.0
                        )
                ),
                landing,
                7,
                0.12
        ), "A current feet obstruction revokes the jump");
        assertFalse(ParkourToSkill.committedLandingStillSafe(
                withNavigationEvidence(
                        initial,
                        8,
                        landingEvidence(
                            7,
                            VoxelKind.SOLID,
                            7,
                            VoxelKind.AIR,
                            8,
                            VoxelKind.WATER,
                            0.0
                        )
                ),
                landing,
                7,
                0.12
        ), "Current liquid in the head clearance revokes the jump");
        assertFalse(ParkourToSkill.committedLandingStillSafe(
                withNavigationEvidence(
                        initial,
                        8,
                        landingEvidence(
                            7,
                            VoxelKind.SOLID,
                            8,
                            VoxelKind.AIR,
                            7,
                            VoxelKind.AIR,
                            0.40
                        )
                ),
                landing,
                7,
                0.12
        ), "Current excessive landing danger revokes the jump");
    }

    @Test
    void hardcoreNeverJumpsAcrossAnUnobservedDeepRecoveryGap() {
        final MutableFrames frames = new MutableFrames(
                frame(1, 0.65, List.of(), 20.0F)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final ParkourToSkill skill = new ParkourToSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final ParkourToParameters parameters = parameters();
        assertTrue(
                skill.preconditions(
                    context(10, true, 0.0),
                    parameters
                ).isEmpty()
        );
        skill.start(context(10, true, 0.0), parameters);

        final PerceptionVec3 hit =
                new PerceptionVec3(0.5, 64.0, 2.5);
        frames.frame = frame(
                2,
                0.55,
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 2),
                        "minecraft:smooth_stone",
                        "up",
                        hit,
                        hit.subtract(
                            new PerceptionVec3(0.5, 65.62, 0.55)
                        ).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )),
                20.0F
        );

        skill.tick(context(11, true, 0.0), parameters);
        assertTrue(
                actuator.moves.getLast().sneak(),
                "Hardcore recovery scan must approach the ledge "
                    + "with vanilla sneak input"
        );
        assertFalse(actuator.moves.getLast().sprint());

        frames.frame = frame(
                3,
                0.70,
                frames.frame.visibleBlockFaces(),
                20.0F
        );
        for (long tick = 12; tick <= 32; tick++) {
            skill.tick(context(tick, true, 0.0), parameters);
        }
        assertEquals(
                0,
                actuator.jumps,
                "Unknown/deep miss space must not become a Hardcore jump"
        );
        assertTrue(
                actuator.looks.stream()
                    .anyMatch(look -> look.pitchDegrees() > 70.0F),
                "Hardcore edge scan must inspect the recovery column"
        );
    }

    private static ParkourToParameters parameters() {
        return new ParkourToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                64.0,
                2.5,
                0.5,
                1,
                1
        );
    }

    private static SkillContext context(
            final long tick,
            final boolean hardcore,
            final double risk
    ) {
        return new SkillContext(1, 1, tick, hardcore, true, risk);
    }

    private static CoreSkillFrame frame(
            final long revision,
            final double z,
            final List<VisibleBlockFace> faces,
            final float health
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        add(voxels, 0, 63, 0, VoxelKind.SOLID, revision);
        add(voxels, 0, 64, 0, VoxelKind.AIR, revision);
        add(voxels, 0, 65, 0, VoxelKind.AIR, revision);
        add(voxels, 0, 64, 1, VoxelKind.AIR, revision);
        add(voxels, 0, 65, 1, VoxelKind.AIR, revision);
        add(voxels, 0, 63, 1, VoxelKind.AIR, revision);
        add(voxels, 0, 63, 2, VoxelKind.SOLID, revision);
        add(voxels, 0, 64, 2, VoxelKind.AIR, revision);
        add(voxels, 0, 65, 2, VoxelKind.AIR, revision);
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, z),
                new PerceptionVec3(0.5, 65.62, z),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        voxels
                ),
                faces,
                health,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static CoreSkillFrame withPose(
            final CoreSkillFrame frame,
            final PerceptionVec3 position,
            final boolean onGround
    ) {
        return new CoreSkillFrame(
                frame.playerId(),
                frame.dimension(),
                frame.gameTime(),
                frame.observationRevision(),
                position,
                new PerceptionVec3(
                        position.x(),
                        position.y() + 1.62,
                        position.z()
                ),
                frame.lookDirection(),
                onGround,
                frame.inWater(),
                frame.danger(),
                frame.navigation(),
                frame.visibleBlockFaces(),
                frame.health(),
                frame.maxHealth(),
                frame.foodLevel(),
                frame.inventory(),
                frame.mainHand(),
                frame.offHand(),
                frame.visibleEntities(),
                frame.dangerSignals()
        );
    }

    private static CoreSkillFrame directionFrame(
            final long revision,
            final PerceptionVec3 position,
            final List<ObservedVoxel> evidence,
            final List<VisibleBlockFace> faces
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                position,
                new PerceptionVec3(
                        position.x(),
                        position.y() + 1.62,
                        position.z()
                ),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        evidence
                ),
                faces,
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static CoreSkillFrame withLandingEvidence(
            final CoreSkillFrame frame,
            final long supportRevision,
            final long feetRevision,
            final long headRevision,
            final boolean sturdyTop
    ) {
        final List<ObservedVoxel> evidence = List.of(
                new ObservedVoxel(
                        new GridPos(0, 63, 2),
                        VoxelKind.SOLID,
                        0.0,
                        supportRevision,
                        OccupancyEvidence.SURFACE_HIT,
                        sturdyTop
                                ? TopSupportAffordance.STURDY_FULL_TOP
                                : TopSupportAffordance.UNKNOWN
                ),
                new ObservedVoxel(
                        new GridPos(0, 64, 2),
                        VoxelKind.AIR,
                        0.0,
                        feetRevision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                new ObservedVoxel(
                        new GridPos(0, 65, 2),
                        VoxelKind.AIR,
                        0.0,
                        headRevision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                )
        );
        return withNavigationEvidence(
                frame,
                frame.navigation().revision(),
                evidence
        );
    }

    private static List<ObservedVoxel> landingEvidence(
            final long supportRevision,
            final VoxelKind supportKind,
            final long feetRevision,
            final VoxelKind feetKind,
            final long headRevision,
            final VoxelKind headKind,
            final double feetDanger
    ) {
        return List.of(
                evidenceVoxel(
                        new GridPos(0, 63, 2),
                        supportKind,
                        0.0,
                        supportRevision,
                        supportKind == VoxelKind.SOLID
                ),
                evidenceVoxel(
                        new GridPos(0, 64, 2),
                        feetKind,
                        feetDanger,
                        feetRevision,
                        false
                ),
                evidenceVoxel(
                        new GridPos(0, 65, 2),
                        headKind,
                        0.0,
                        headRevision,
                        false
                )
        );
    }

    private static ObservedVoxel evidenceVoxel(
            final GridPos position,
            final VoxelKind kind,
            final double danger,
            final long revision,
            final boolean sturdyTop
    ) {
        return new ObservedVoxel(
                position,
                kind,
                danger,
                revision,
                kind == VoxelKind.AIR
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.SURFACE_HIT,
                sturdyTop
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        );
    }

    private static CoreSkillFrame withNavigationEvidence(
            final CoreSkillFrame frame,
            final long snapshotRevision,
            final List<ObservedVoxel> evidence
    ) {
        return new CoreSkillFrame(
                frame.playerId(),
                frame.dimension(),
                frame.gameTime(),
                Math.max(
                        frame.observationRevision(),
                        snapshotRevision
                ),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.danger(),
                new LocalNavSnapshot(
                        frame.navigation().dimension(),
                        snapshotRevision,
                        evidence
                ),
                frame.visibleBlockFaces(),
                frame.health(),
                frame.maxHealth(),
                frame.foodLevel(),
                frame.inventory(),
                frame.mainHand(),
                frame.offHand(),
                frame.visibleEntities(),
                frame.dangerSignals()
        );
    }

    private static CoreSkillFrame withVoxel(
            final CoreSkillFrame frame,
            final ObservedVoxel replacement
    ) {
        final List<ObservedVoxel> evidence = new ArrayList<>(
                frame.navigation().observedVoxels().values()
        );
        evidence.removeIf(voxel ->
                voxel.position().equals(replacement.position())
        );
        evidence.add(replacement);
        return withNavigationEvidence(
                frame,
                frame.navigation().revision(),
                evidence
        );
    }

    private static void add(
            final List<ObservedVoxel> output,
            final int x,
            final int y,
            final int z,
            final VoxelKind kind,
            final long revision
    ) {
        output.add(new ObservedVoxel(
                new GridPos(x, y, z),
                kind,
                0.0,
                revision,
                kind == VoxelKind.AIR
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.SURFACE_HIT,
                kind == VoxelKind.SOLID
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        ));
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }

    private static final class MutableFrames
            implements CoreSkillFrameSource {
        private CoreSkillFrame frame;

        private MutableFrames(final CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.of(frame);
        }
    }

    private static final class RecordingActuator
            implements CoreSkillActuator {
        private final List<MovementIntent> moves = new ArrayList<>();
        private final List<LookIntent> looks = new ArrayList<>();
        private int jumps;
        private int stops;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            moves.add(intent);
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks.add(intent);
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
