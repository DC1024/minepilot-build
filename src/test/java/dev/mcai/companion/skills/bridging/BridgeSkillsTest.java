package dev.mcai.companion.skills.bridging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
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

final class BridgeSkillsTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "62000000-0000-0000-0000-000000000001"
    );

    @Test
    void parsesAndRegistersOnlyTheBoundedExplicitContract() {
        final var parsed = BridgeSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "10.5"),
                argument("y", "64"),
                argument("z", "-2.5"),
                argument("arrivalRadius", "0.75"),
                argument("maxBlocks", "8")
        ));

        assertEquals(8, parsed.value().orElseThrow().maxBlocks());
        assertFalse(BridgeSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "10.5"),
                argument("y", "64"),
                argument("z", "-2.5"),
                argument("arrivalRadius", "0.75"),
                argument("maxBlocks", "08")
        )).value().isPresent());
        assertFalse(BridgeSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "10.5"),
                argument("y", "64"),
                argument("z", "-2.5"),
                argument("arrivalRadius", "0.75"),
                argument("maxBlocks", "65")
        )).value().isPresent());

        final SkillRegistry registry = BridgeSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(frame(
                        1,
                        0.5,
                        VoxelKind.AIR,
                        4,
                        List.of(),
                        new PerceptionVec3(0.0, 0.0, 1.0)
                )),
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        assertEquals(
                java.util.Set.of(
                        "bridge_to",
                        "tower_up",
                        "water_clutch_descend"
                ),
                registry.names()
        );
        assertTrue(
                BridgeSkills.plannerGuide()
                        .contains("never teleports")
        );
        assertTrue(
                BridgeSkills.plannerGuide()
                        .contains("arrivalTolerance is decimal")
        );
        assertTrue(
                BridgeSkills.plannerGuide()
                        .contains("arrivalRadius is decimal 0.25..0.9")
        );
        assertTrue(
                BridgeSkills.plannerGuide()
                        .contains("maxBlocks is integer")
                    && BridgeSkills.plannerGuide().contains("1..32")
        );
    }

    @Test
    void waterClutchDescentUsesOnlyAVisibleAdjacentLanding() {
        final var parsed =
                WaterClutchDescendSkillParameters.parse(List.of(
                        argument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        argument("x", "1.5"),
                        argument("y", "64"),
                        argument("z", "0.5"),
                        argument("arrivalRadius", "0.6"),
                        argument("maximumDropBlocks", "8")
                ));
        final WaterClutchDescendParameters parameters =
                parsed.value().orElseThrow();
        assertFalse(
                WaterClutchDescendSkillParameters.parse(List.of(
                        argument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        argument("x", "1.5"),
                        argument("y", "64"),
                        argument("z", "0.5"),
                        argument("arrivalRadius", "0.6"),
                        argument("maximumDropBlocks", "08")
                )).value().isPresent()
        );

        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final PerceptionVec3 initialEye =
                new PerceptionVec3(0.5, 71.62, 0.5);
        final MutableFrames frames = new MutableFrames(
                waterClutchFrame(
                        20,
                        DimensionRef.OVERWORLD,
                        70.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(initialEye).normalized()
                )
        );
        final RecordingActuator actuator =
                new RecordingActuator();
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        actuator,
                        frames
                );
        assertTrue(
                skill.preconditions(
                        context(300, true, 0.0),
                        parameters
                ).isEmpty()
        );
        skill.start(context(300, true, 0.0), parameters);
        frames.frame = waterClutchFrame(
                21,
                DimensionRef.OVERWORLD,
                70.0,
                true,
                false,
                false,
                1,
                0,
                landing.subtract(initialEye).normalized()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(301, true, 0.0),
                        parameters
                ).status()
        );
        skill.tick(context(302, true, 0.0), parameters);
        assertEquals(
                0.35,
                actuator.moves.getLast().forward(),
                1.0E-9
        );

        frames.frame = waterClutchFrame(
                22,
                DimensionRef.OVERWORLD,
                68.0,
                false,
                false,
                true,
                1,
                0,
                landing.subtract(
                        new PerceptionVec3(1.1, 69.62, 0.5)
                ).normalized()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(303, true, 0.0),
                        parameters
                ).status()
        );
        frames.frame = waterClutchFrame(
                23,
                DimensionRef.OVERWORLD,
                64.0,
                false,
                true,
                true,
                0,
                1,
                new PerceptionVec3(0.0, -1.0, 0.0)
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(304, true, 0.0),
                        parameters
                ).status()
        );

        frames.frame = waterClutchFrame(
                24,
                DimensionRef.NETHER,
                70.0,
                true,
                false,
                true,
                1,
                0,
                landing.subtract(initialEye).normalized()
        );
        final WaterClutchDescendSkill netherSkill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        actuator,
                        frames
                );
        final WaterClutchDescendParameters nether =
                new WaterClutchDescendParameters(
                        DimensionRef.NETHER,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        8
                );
        assertEquals(
                "water_clutch_descend.water_unavailable_in_nether",
                netherSkill.preconditions(
                        context(305, true, 0.0),
                        nether
                ).orElseThrow().code()
        );

        frames.frame = waterClutchFrame(
                25,
                DimensionRef.OVERWORLD,
                70.0,
                true,
                false,
                false,
                1,
                0,
                landing.subtract(initialEye).normalized()
        );
        assertEquals(
                "water_clutch_descend.visible_safe_landing_required",
                skill.preconditions(
                        context(306, true, 0.0),
                        parameters
                ).orElseThrow().code()
        );
    }

    @Test
    void waterClutchDescentUsesItsObservedLandingExactlyOnce() {
        final CoreSkillFrame alignedReachable =
                waterClutchFrame(
                        61,
                        DimensionRef.OVERWORLD,
                        67.0,
                        false,
                        false,
                        true,
                        1,
                        0,
                        new PerceptionVec3(
                                1.5,
                                64.0,
                                0.5
                        ).subtract(
                                new PerceptionVec3(
                                        0.5,
                                        68.62,
                                        0.5
                                )
                        ).normalized()
                );

        final RecordingActuator actuator =
                exerciseWaterUseAtDescent(alignedReachable);

        assertEquals(
                List.of(ActionHand.MAIN_HAND),
                actuator.itemUses,
                "A legal descent must issue exactly one ordinary "
                    + "main-hand use-item action"
        );
    }

    @Test
    void waterClutchNeverUsesWithoutEveryFairPlacementGate() {
        final PerceptionVec3 reachableLook =
                new PerceptionVec3(
                        1.5,
                        64.0,
                        0.5
                ).subtract(
                        new PerceptionVec3(
                                0.5,
                                68.62,
                                0.5
                        )
                ).normalized();
        final CoreSkillFrame unseen = waterClutchFrame(
                61,
                DimensionRef.OVERWORLD,
                67.0,
                false,
                false,
                false,
                1,
                0,
                reachableLook
        );
        final CoreSkillFrame beyondReach = waterClutchFrame(
                61,
                DimensionRef.OVERWORLD,
                68.0,
                false,
                false,
                true,
                1,
                0,
                new PerceptionVec3(
                        1.5,
                        64.0,
                        0.5
                ).subtract(
                        new PerceptionVec3(
                                0.5,
                                69.62,
                                0.5
                        )
                ).normalized()
        );
        final CoreSkillFrame misaligned = waterClutchFrame(
                61,
                DimensionRef.OVERWORLD,
                67.0,
                false,
                false,
                true,
                1,
                0,
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        final CoreSkillFrame wrongMainHand = withMainHand(
                waterClutchFrame(
                        61,
                        DimensionRef.OVERWORLD,
                        67.0,
                        false,
                        false,
                        true,
                        1,
                        0,
                        reachableLook
                ),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        1,
                        0,
                        0
                )
        );

        assertTrue(
                exerciseWaterUseAtDescent(unseen)
                        .itemUses.isEmpty(),
                "An unseen landing face must never be used"
        );
        assertTrue(
                exerciseWaterUseAtDescent(beyondReach)
                        .itemUses.isEmpty(),
                "A landing beyond live vanilla reach must never be used"
        );
        assertTrue(
                exerciseWaterUseAtDescent(misaligned)
                        .itemUses.isEmpty(),
                "A crosshair that has not aligned must never use the bucket"
        );
        assertTrue(
                exerciseWaterUseAtDescent(wrongMainHand)
                        .itemUses.isEmpty(),
                "A non-water-bucket main hand must never be used"
        );
    }

    @Test
    void waterClutchPreflightsTheObservedExitCorridor() {
        final WaterClutchDescendParameters parameters =
                new WaterClutchDescendParameters(
                        DimensionRef.OVERWORLD,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        8
                );
        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final PerceptionVec3 initialEye =
                new PerceptionVec3(0.5, 71.62, 0.5);
        final CoreSkillFrame base = waterClutchFrame(
                70,
                DimensionRef.OVERWORLD,
                70.0,
                true,
                false,
                true,
                1,
                0,
                landing.subtract(initialEye).normalized()
        );

        /*
         * The east cell is directly beyond an eastbound landing and can
         * catch overshoot above the water. The north cell is equally
         * adjacent and elevated, but lies outside the observed approach
         * corridor and must not cause a blanket neighborhood rejection.
         */
        final CoreSkillFrame dangerousBeyond =
                withObservedSolid(
                        base,
                        new GridPos(2, 65, 0)
                );
        final CoreSkillFrame staleBeyond =
                withObservedSolid(
                        base,
                        new GridPos(2, 65, 0),
                        base.navigation().revision() - 1
                );
        final CoreSkillFrame safeSide =
                withObservedSolid(
                        base,
                        new GridPos(1, 65, 1)
                );
        final RecordingActuator actuator =
                new RecordingActuator();
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        actuator,
                        new MutableFrames(dangerousBeyond)
                );
        assertEquals(
                "water_clutch_descend."
                    + "landing_exit_corridor_obstructed",
                skill.preconditions(
                        context(800, true, 0.0),
                        parameters
                ).orElseThrow().code()
        );
        assertTrue(actuator.moves.isEmpty());
        assertTrue(actuator.itemUses.isEmpty());

        final WaterClutchDescendSkill staleSkill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        new MutableFrames(staleBeyond)
                );
        assertTrue(
                staleSkill.preconditions(
                        context(801, true, 0.0),
                        parameters
                ).isEmpty(),
                "A retained solid from an older semantic sample is route "
                    + "memory, not proof that the live exit is obstructed"
        );

        final WaterClutchDescendSkill sideSkill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        new MutableFrames(safeSide)
                );
        assertTrue(
                sideSkill.preconditions(
                        context(801, true, 0.0),
                        parameters
                ).isEmpty(),
                "An elevated block outside the projected exit corridor "
                    + "must remain a valid adjacent feature"
        );
    }

    @Test
    void waterClutchActivelyBrakesPredictedHorizontalOvershoot() {
        final WaterClutchDescendParameters parameters =
                new WaterClutchDescendParameters(
                        DimensionRef.OVERWORLD,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        8
                );
        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final CoreSkillFrame initial = withPose(
                waterClutchFrame(
                        80,
                        DimensionRef.OVERWORLD,
                        70.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(
                                new PerceptionVec3(
                                        0.9,
                                        71.62,
                                        0.5
                                )
                        ).normalized()
                ),
                80,
                new PerceptionVec3(0.9, 70.0, 0.5),
                true
        );
        final MutableFrames frames =
                new MutableFrames(initial);
        final RecordingActuator actuator =
                new RecordingActuator();
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        actuator,
                        frames
                );
        skill.start(context(900, true, 0.0), parameters);
        skill.tick(context(901, true, 0.0), parameters);
        skill.tick(context(902, true, 0.0), parameters);

        frames.frame = withPose(
                waterClutchFrame(
                        81,
                        DimensionRef.OVERWORLD,
                        68.0,
                        false,
                        false,
                        true,
                        1,
                        0,
                        new PerceptionVec3(
                                1.0,
                                -4.0,
                                0.0
                        ).normalized()
                ),
                81,
                new PerceptionVec3(1.45, 68.0, 0.5),
                false
        );
        skill.tick(context(903, true, 0.0), parameters);
        frames.frame = withPose(
                waterClutchFrame(
                        82,
                        DimensionRef.OVERWORLD,
                        67.5,
                        false,
                        false,
                        true,
                        1,
                        0,
                        new PerceptionVec3(
                                1.0,
                                -4.0,
                                0.0
                        ).normalized()
                ),
                82,
                new PerceptionVec3(1.56, 67.5, 0.5),
                false
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(904, true, 0.0),
                        parameters
                ).status()
        );
        assertTrue(
                actuator.moves.getLast().forward() < 0.0,
                "An east-facing body projected east of the water must "
                    + "receive a westward braking input"
        );
    }

    @Test
    void waterClutchOverridesOnlyItsIntentionalHardcoreFallRisk() {
        final WaterClutchDescendParameters parameters =
                new WaterClutchDescendParameters(
                        DimensionRef.OVERWORLD,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        20
                );
        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final PerceptionVec3 initialEye =
                new PerceptionVec3(0.5, 81.62, 0.5);
        final MutableFrames frames = new MutableFrames(
                waterClutchFrame(
                        30,
                        DimensionRef.OVERWORLD,
                        80.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(initialEye).normalized()
                )
        );
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        frames
                );
        assertTrue(
                skill.preconditions(
                        context(400, true, 0.0),
                        parameters
                ).isEmpty()
        );
        skill.start(context(400, true, 0.0), parameters);
        skill.tick(context(401, true, 0.0), parameters);
        skill.tick(context(402, true, 0.0), parameters);

        final DangerSignal falling = new DangerSignal(
                DangerKind.FALLING,
                0.80,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        frames.frame = waterClutchFrame(
                31,
                DimensionRef.OVERWORLD,
                72.0,
                false,
                false,
                true,
                1,
                0,
                landing.subtract(
                        new PerceptionVec3(1.1, 73.62, 0.5)
                ).normalized(),
                List.of(falling)
        );
        assertEquals(
                1.0,
                skill.hardcoreRiskThresholdOverride(
                        context(403, true, 0.80),
                        parameters
                ).orElseThrow(),
                1.0E-9
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(403, true, 0.80),
                        parameters
                ).status()
        );

        final DangerSignal fire = new DangerSignal(
                DangerKind.ON_FIRE,
                1.0,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        frames.frame = waterClutchFrame(
                32,
                DimensionRef.OVERWORLD,
                70.0,
                false,
                false,
                true,
                1,
                0,
                landing.subtract(
                        new PerceptionVec3(1.2, 71.62, 0.5)
                ).normalized(),
                List.of(falling, fire)
        );
        assertTrue(
                skill.hardcoreRiskThresholdOverride(
                        context(404, true, 1.0),
                        parameters
                ).isEmpty()
        );
        assertEquals(
                SkillTickResult.Status.FAILED,
                skill.tick(
                        context(404, true, 1.0),
                        parameters
                ).status()
        );
        assertEquals(
                "water_clutch_descend.danger_detected",
                skill.result(
                        context(404, true, 1.0),
                        parameters
                ).failure().orElseThrow().code()
        );
    }

    @Test
    void acceptsOnlyItsOwnObservedSourceWaterDuringDescent() {
        final WaterClutchDescendParameters parameters =
                new WaterClutchDescendParameters(
                        DimensionRef.OVERWORLD,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        8
                );
        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final MutableFrames frames = new MutableFrames(
                waterClutchFrame(
                        40,
                        DimensionRef.OVERWORLD,
                        70.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(
                            new PerceptionVec3(0.5, 71.62, 0.5)
                        ).normalized()
                )
        );
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        frames
                );
        skill.start(context(500, true, 0.0), parameters);
        skill.tick(context(501, true, 0.0), parameters);
        skill.tick(context(502, true, 0.0), parameters);
        frames.frame = waterClutchFrame(
                41,
                DimensionRef.OVERWORLD,
                68.0,
                false,
                false,
                true,
                1,
                0,
                landing.subtract(
                    new PerceptionVec3(1.1, 69.62, 0.5)
                ).normalized()
        );
        skill.tick(context(503, true, 0.0), parameters);

        frames.frame = deployedWaterFrame(42, "0", false);
        assertEquals(
                1.0,
                skill.hardcoreRiskThresholdOverride(
                        context(504, true, 0.8),
                        parameters
                ).orElseThrow(),
                1.0E-9
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(504, true, 0.8),
                        parameters
                ).status()
        );
        frames.frame = deployedWaterFrame(43, "0", true);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(505, true, 0.0),
                        parameters
                ).status()
        );

        final MutableFrames flowingFrames = new MutableFrames(
                waterClutchFrame(
                        50,
                        DimensionRef.OVERWORLD,
                        70.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(
                            new PerceptionVec3(0.5, 71.62, 0.5)
                        ).normalized()
                )
        );
        final WaterClutchDescendSkill flowingSkill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        flowingFrames
                );
        flowingSkill.start(context(600, true, 0.0), parameters);
        flowingSkill.tick(context(601, true, 0.0), parameters);
        flowingSkill.tick(context(602, true, 0.0), parameters);
        flowingFrames.frame = waterClutchFrame(
                51,
                DimensionRef.OVERWORLD,
                68.0,
                false,
                false,
                true,
                1,
                0,
                landing.subtract(
                    new PerceptionVec3(1.1, 69.62, 0.5)
                ).normalized()
        );
        flowingSkill.tick(context(603, true, 0.0), parameters);
        flowingFrames.frame = deployedWaterFrame(52, "1", false);
        assertTrue(
                flowingSkill.hardcoreRiskThresholdOverride(
                        context(604, true, 0.8),
                        parameters
                ).isEmpty()
        );
        assertEquals(
                SkillTickResult.Status.FAILED,
                flowingSkill.tick(
                        context(604, true, 0.8),
                        parameters
                ).status()
        );
        assertEquals(
                "water_clutch_descend.landing_became_unverified",
                flowingSkill.result(
                        context(604, true, 0.8),
                        parameters
                ).failure().orElseThrow().code()
        );
    }

    @Test
    void crouchesUsesOnlyVisibleFaceVerifiesSupportThenCrosses() {
        final MutableFrames frames = new MutableFrames(frame(
                10,
                0.5,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        ));
        final RecordingActuator actuator =
                new RecordingActuator();
        final BridgeToSkill skill = new BridgeToSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        final BridgeToParameters parameters =
                new BridgeToParameters(
                        DimensionRef.OVERWORLD,
                        0.5,
                        64.0,
                        1.5,
                        0.5,
                        1
                );

        assertTrue(
                skill.preconditions(
                        context(100, false, 0.0),
                        parameters
                ).isEmpty()
        );
        skill.start(context(100, false, 0.0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(101, false, 0.0),
                        parameters
                ).status()
        );
        assertTrue(actuator.moves.getLast().sneak());

        frames.frame = frame(
                10,
                1.07,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        skill.tick(context(102, false, 0.0), parameters);

        final PerceptionVec3 eye =
                new PerceptionVec3(0.5, 65.62, 1.07);
        final PerceptionVec3 hit =
                new PerceptionVec3(0.5, 63.5, 1.0);
        frames.frame = frame(
                11,
                1.07,
                VoxelKind.AIR,
                4,
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 0),
                        "minecraft:stone",
                        "minecraft:south",
                        hit,
                        hit.subtract(eye).length(),
                        PerceptionProvenance
                                .BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )),
                hit.subtract(eye).normalized()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(103, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(1, actuator.uses.size());
        assertEquals(0, actuator.uses.getFirst().x());
        assertEquals(63, actuator.uses.getFirst().y());
        assertEquals(0, actuator.uses.getFirst().z());

        frames.frame = frame(
                12,
                1.07,
                VoxelKind.SOLID,
                3,
                List.of(),
                hit.subtract(eye).normalized()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(104, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(
                0.0,
                actuator.moves.getLast().forward(),
                1.0E-9
        );
        assertFalse(actuator.moves.getLast().sneak());

        frames.frame = frame(
                13,
                1.07,
                VoxelKind.SOLID,
                3,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(105, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(
                0.65,
                actuator.moves.getLast().forward(),
                1.0E-9
        );

        frames.frame = frame(
                14,
                1.5,
                VoxelKind.SOLID,
                3,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(106, false, 0.0),
                        parameters
                ).status()
        );
        assertEquals(
                dev.mcai.companion.skill.SkillResult.Status.COMPLETED,
                skill.result(
                        context(106, false, 0.0),
                        parameters
                ).status()
        );
    }

    @Test
    void bridgeStillRequiresLiveGroundAfterSupportWasObserved() {
        final MutableFrames frames = new MutableFrames(frame(
                9,
                0.5,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0),
                false
        ));
        final BridgeToSkill skill = new BridgeToSkill(
                PLAYER_ID,
                new RecordingActuator(),
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        final BridgeToParameters parameters =
                new BridgeToParameters(
                        DimensionRef.OVERWORLD,
                        0.5,
                        64.0,
                        1.5,
                        0.5,
                        1
                );

        assertEquals(
                "bridge_to.stable_ground_required",
                skill.preconditions(
                    context(90, false, 0.0),
                    parameters
                ).orElseThrow().code()
        );

        frames.frame = frame(
                10,
                0.5,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true
        );
        assertTrue(
                skill.preconditions(
                    context(91, false, 0.0),
                    parameters
                ).isEmpty()
        );
    }

    @Test
    void observedAttachmentPlacesAgainstVisibleWallWhenDestinationIsUnknown() {
        final MutableFrames frames = new MutableFrames(
                attachmentFrame(10, 0.5, 4, false)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final BridgeToSkill skill = new BridgeToSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        final BridgeToParameters parameters = new BridgeToParameters(
                DimensionRef.OVERWORLD,
                0.5,
                64.0,
                1.5,
                0.5,
                1,
                true
        );

        assertTrue(
                skill.preconditions(context(100, false, 0.0), parameters)
                        .isEmpty()
        );
        skill.start(context(100, false, 0.0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101, false, 0.0), parameters).status()
        );

        frames.frame = attachmentFrame(11, 1.1, 4, false);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102, false, 0.0), parameters).status()
        );
        frames.frame = attachmentFrame(12, 1.1, 4, false);
        skill.tick(context(103, false, 0.0), parameters);
        frames.frame = attachmentFrame(13, 1.1, 4, false);
        skill.tick(context(104, false, 0.0), parameters);

        assertEquals(1, actuator.uses.size());
        assertEquals(1, actuator.uses.getFirst().x());
        assertEquals(64, actuator.uses.getFirst().y());
        assertEquals(1, actuator.uses.getFirst().z());
        assertEquals(
                dev.mcai.companion.action.BlockFace.WEST,
                actuator.uses.getFirst().face()
        );
    }

    @Test
    void refusesHardcoreRiskAndNeverUsesAnUnseenFace() {
        final MutableFrames frames = new MutableFrames(frame(
                10,
                0.5,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        ));
        final RecordingActuator actuator =
                new RecordingActuator();
        final BridgeToSkill skill = new BridgeToSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        final BridgeToParameters parameters =
                new BridgeToParameters(
                        DimensionRef.OVERWORLD,
                        0.5,
                        64.0,
                        1.5,
                        0.5,
                        1
                );

        assertEquals(
                "bridge_to.danger_detected",
                skill.preconditions(
                        context(100, true, 0.09),
                        parameters
                ).orElseThrow().code()
        );

        skill.start(context(100, false, 0.0), parameters);
        skill.tick(context(101, false, 0.0), parameters);
        frames.frame = frame(
                11,
                1.07,
                VoxelKind.AIR,
                4,
                List.of(),
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        skill.tick(context(102, false, 0.0), parameters);
        skill.tick(context(103, false, 0.0), parameters);
        assertTrue(
                actuator.uses.isEmpty(),
                "No visible matching support face means no use packet"
        );
    }

    @Test
    void towerUsesJumpThenTopFaceAndWaitsForARealLanding() {
        final var parsed = TowerSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("targetY", "65"),
                argument("arrivalTolerance", "0.1"),
                argument("maxBlocks", "1")
        ));
        assertEquals(
                65.0,
                parsed.value().orElseThrow().targetY()
        );
        assertFalse(TowerSkillParameters.parse(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("targetY", "65"),
                argument("arrivalTolerance", "0.1"),
                argument("maxBlocks", "01")
        )).value().isPresent());

        final PerceptionVec3 supportHit =
                new PerceptionVec3(0.5, 64.0, 0.5);
        final MutableFrames frames = new MutableFrames(
                towerFrame(
                        10,
                        64.0,
                        true,
                        VoxelKind.AIR,
                        4,
                        List.of(new VisibleBlockFace(
                                new BlockCoordinate(0, 63, 0),
                                "minecraft:obsidian",
                                "up",
                                supportHit,
                                1.62,
                                PerceptionProvenance
                                        .BLOCK_SURFACE_RAY_CLIP,
                                Map.of()
                        )),
                        new PerceptionVec3(0.0, -1.0, 0.0)
                )
        );
        final RecordingActuator actuator =
                new RecordingActuator();
        final TowerUpSkill skill = new TowerUpSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        4
                )
        );
        final TowerUpParameters parameters =
                new TowerUpParameters(
                        DimensionRef.OVERWORLD,
                        65.0,
                        0.1,
                        1
                );

        assertTrue(
                skill.preconditions(
                        context(200, true, 0.0),
                        parameters
                ).isEmpty()
        );
        skill.start(context(200, true, 0.0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(201, true, 0.0),
                        parameters
                ).status()
        );
        assertEquals(1, actuator.jumps);
        assertTrue(actuator.uses.isEmpty());

        frames.frame = towerFrame(
                10,
                65.05,
                false,
                VoxelKind.AIR,
                4,
                List.of(),
                supportHit.subtract(
                        new PerceptionVec3(
                                0.5,
                                66.67,
                                0.5
                        )
                ).normalized()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(202, true, 0.0),
                        parameters
                ).status()
        );
        assertEquals(1, actuator.uses.size());
        assertEquals(
                dev.mcai.companion.action.BlockFace.UP,
                actuator.uses.getFirst().face()
        );

        frames.frame = towerFrame(
                11,
                65.0,
                true,
                VoxelKind.SOLID,
                3,
                List.of(),
                new PerceptionVec3(0.0, -1.0, 0.0)
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(203, true, 0.0),
                        parameters
                ).status()
        );
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }

    private static SkillContext context(
            final long tick,
            final boolean hardcore,
            final double risk
    ) {
        return new SkillContext(
                7,
                8,
                tick,
                hardcore,
                true,
                risk
        );
    }

    private static CoreSkillFrame frame(
            final long revision,
            final double z,
            final VoxelKind desiredSupport,
            final int blockCount,
            final List<VisibleBlockFace> faces,
            final PerceptionVec3 look
    ) {
        return frame(
                revision,
                z,
                desiredSupport,
                blockCount,
                faces,
                look,
                true
        );
    }

    private static CoreSkillFrame attachmentFrame(
            final long revision,
            final double z,
            final int blockCount,
            final boolean destinationPlaced
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        voxels.add(voxel(0, 63, 0, VoxelKind.SOLID, revision));
        voxels.add(voxel(0, 64, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 65, 0, VoxelKind.AIR, revision));
        if (destinationPlaced) {
            voxels.add(voxel(0, 64, 1, VoxelKind.SOLID, revision));
        }
        final PerceptionVec3 eye = new PerceptionVec3(0.5, 65.62, z);
        final PerceptionVec3 hit = new PerceptionVec3(1.0, 64.5, 1.5);
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, z),
                eye,
                hit.subtract(eye).normalized(),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        voxels
                ),
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 64, 1),
                        "minecraft:end_stone",
                        "minecraft:west",
                        hit,
                        hit.subtract(eye).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                )),
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        "minecraft:cobblestone",
                        blockCount
                )),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        blockCount,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static CoreSkillFrame frame(
            final long revision,
            final double z,
            final VoxelKind desiredSupport,
            final int blockCount,
            final List<VisibleBlockFace> faces,
            final PerceptionVec3 look,
            final boolean onGround
    ) {
        final List<ObservedVoxel> voxels =
                new ArrayList<>();
        voxels.add(voxel(0, 63, 0, VoxelKind.SOLID, revision));
        voxels.add(voxel(0, 64, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 65, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 63, 1, desiredSupport, revision));
        voxels.add(voxel(0, 64, 1, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 65, 1, VoxelKind.AIR, revision));
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, z),
                new PerceptionVec3(0.5, 65.62, z),
                look.normalized(),
                onGround,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        voxels
                ),
                faces,
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        "minecraft:cobblestone",
                        blockCount
                )),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        blockCount,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static CoreSkillFrame towerFrame(
            final long revision,
            final double y,
            final boolean onGround,
            final VoxelKind placedBlock,
            final int blockCount,
            final List<VisibleBlockFace> faces,
            final PerceptionVec3 look
    ) {
        final List<ObservedVoxel> voxels =
                new ArrayList<>();
        voxels.add(voxel(0, 63, 0, VoxelKind.SOLID, revision));
        voxels.add(voxel(0, 64, 0, placedBlock, revision));
        voxels.add(voxel(0, 65, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 66, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 67, 0, VoxelKind.AIR, revision));
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, y, 0.5),
                new PerceptionVec3(0.5, y + 1.62, 0.5),
                look.normalized(),
                onGround,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        voxels
                ),
                faces,
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        "minecraft:cobblestone",
                        blockCount
                )),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        blockCount,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static CoreSkillFrame waterClutchFrame(
            final long revision,
            final DimensionRef dimension,
            final double y,
            final boolean onGround,
            final boolean inWater,
            final boolean visibleLanding,
            final int waterBuckets,
            final int emptyBuckets,
            final PerceptionVec3 look
    ) {
        return waterClutchFrame(
                revision,
                dimension,
                y,
                onGround,
                inWater,
                visibleLanding,
                waterBuckets,
                emptyBuckets,
                look,
                List.of()
        );
    }

    private static RecordingActuator exerciseWaterUseAtDescent(
            final CoreSkillFrame descentFrame
    ) {
        final WaterClutchDescendParameters parameters =
                new WaterClutchDescendParameters(
                        DimensionRef.OVERWORLD,
                        1.5,
                        64.0,
                        0.5,
                        0.6,
                        8
                );
        final PerceptionVec3 landing =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final MutableFrames frames = new MutableFrames(
                waterClutchFrame(
                        60,
                        DimensionRef.OVERWORLD,
                        70.0,
                        true,
                        false,
                        true,
                        1,
                        0,
                        landing.subtract(
                                new PerceptionVec3(
                                        0.5,
                                        71.62,
                                        0.5
                                )
                        ).normalized()
                )
        );
        final RecordingActuator actuator =
                new RecordingActuator();
        final WaterClutchDescendSkill skill =
                new WaterClutchDescendSkill(
                        PLAYER_ID,
                        actuator,
                        frames
                );
        skill.start(context(700, true, 0.0), parameters);
        skill.tick(context(701, true, 0.0), parameters);
        skill.tick(context(702, true, 0.0), parameters);
        frames.frame = descentFrame;
        SkillTickResult result =
                skill.tick(
                        context(703, true, 0.0),
                        parameters
                );
        if (result.status() == SkillTickResult.Status.RUNNING) {
            result = skill.tick(
                    context(704, true, 0.0),
                    parameters
            );
        }
        if (result.status() == SkillTickResult.Status.RUNNING) {
            skill.tick(
                    context(705, true, 0.0),
                    parameters
            );
        }
        return actuator;
    }

    private static CoreSkillFrame withMainHand(
            final CoreSkillFrame frame,
            final HeldItemSummary mainHand
    ) {
        return new CoreSkillFrame(
                frame.playerId(),
                frame.dimension(),
                frame.gameTime(),
                frame.observationRevision(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.danger(),
                frame.navigation(),
                frame.visibleBlockFaces(),
                frame.health(),
                frame.maxHealth(),
                frame.foodLevel(),
                frame.inventory(),
                mainHand,
                frame.offHand(),
                frame.visibleEntities(),
                frame.dangerSignals()
        );
    }

    private static CoreSkillFrame withObservedSolid(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return withObservedSolid(
                frame,
                position,
                frame.navigation().revision()
        );
    }

    private static CoreSkillFrame withObservedSolid(
            final CoreSkillFrame frame,
            final GridPos position,
            final long observationRevision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>(
                frame.navigation()
                    .observedVoxels()
                    .values()
        );
        voxels.add(new ObservedVoxel(
                position,
                VoxelKind.SOLID,
                0.0,
                observationRevision
        ));
        return withNavigation(
                frame,
                new LocalNavSnapshot(
                        frame.dimension(),
                        frame.navigation().revision(),
                        voxels
                )
        );
    }

    private static CoreSkillFrame withNavigation(
            final CoreSkillFrame frame,
            final LocalNavSnapshot navigation
    ) {
        return new CoreSkillFrame(
                frame.playerId(),
                frame.dimension(),
                frame.gameTime(),
                frame.observationRevision(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.danger(),
                navigation,
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

    private static CoreSkillFrame withPose(
            final CoreSkillFrame frame,
            final long gameTime,
            final PerceptionVec3 position,
            final boolean onGround
    ) {
        final double eyeHeight =
                frame.eyePosition().y() - frame.position().y();
        return new CoreSkillFrame(
                frame.playerId(),
                frame.dimension(),
                gameTime,
                frame.observationRevision(),
                position,
                new PerceptionVec3(
                        position.x(),
                        position.y() + eyeHeight,
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

    private static CoreSkillFrame deployedWaterFrame(
            final long revision,
            final String waterLevel,
            final boolean inWater
    ) {
        final List<ObservedVoxel> voxels = List.of(
                voxel(1, 63, 0, VoxelKind.SOLID, revision),
                voxel(1, 64, 0, VoxelKind.WATER, revision),
                voxel(1, 65, 0, VoxelKind.AIR, revision)
        );
        final PerceptionVec3 position = new PerceptionVec3(
                inWater ? 1.5 : 1.2,
                inWater ? 64.0 : 66.0,
                0.5
        );
        final PerceptionVec3 eye = new PerceptionVec3(
                position.x(),
                position.y() + 1.62,
                position.z()
        );
        final PerceptionVec3 hit =
                new PerceptionVec3(1.5, 65.0, 0.5);
        final DangerSignal falling = new DangerSignal(
                DangerKind.FALLING,
                0.8,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                position,
                eye,
                hit.subtract(eye).normalized(),
                false,
                inWater,
                0.8,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        voxels
                ),
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 64, 0),
                        "minecraft:water",
                        "up",
                        hit,
                        hit.subtract(eye).length(),
                        PerceptionProvenance
                                .BLOCK_SURFACE_RAY_CLIP,
                        Map.of("level", waterLevel)
                )),
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        "minecraft:bucket",
                        1
                )),
                new HeldItemSummary(
                        "minecraft:bucket",
                        1,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of(falling)
        );
    }

    private static CoreSkillFrame waterClutchFrame(
            final long revision,
            final DimensionRef dimension,
            final double y,
            final boolean onGround,
            final boolean inWater,
            final boolean visibleLanding,
            final int waterBuckets,
            final int emptyBuckets,
            final PerceptionVec3 look,
            final List<DangerSignal> dangers
    ) {
        final List<ObservedVoxel> voxels =
                new ArrayList<>();
        voxels.add(voxel(0, 69, 0, VoxelKind.SOLID, revision));
        voxels.add(voxel(0, 70, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(0, 71, 0, VoxelKind.AIR, revision));
        voxels.add(voxel(1, 63, 0, VoxelKind.SOLID, revision));
        voxels.add(voxel(
                1,
                64,
                0,
                inWater ? VoxelKind.WATER : VoxelKind.AIR,
                revision
        ));
        voxels.add(voxel(1, 65, 0, VoxelKind.AIR, revision));
        final PerceptionVec3 hit =
                new PerceptionVec3(1.5, 64.0, 0.5);
        final List<VisibleBlockFace> faces = visibleLanding
                ? List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 63, 0),
                        "minecraft:stone",
                        "up",
                        hit,
                        hit.subtract(
                                new PerceptionVec3(
                                        0.5,
                                        y + 1.62,
                                        0.5
                                )
                        ).length(),
                        PerceptionProvenance
                                .BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                ))
                : List.of();
        final List<InventoryItemSummary> inventory =
                new ArrayList<>();
        if (waterBuckets > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:water_bucket",
                    waterBuckets
            ));
        }
        if (emptyBuckets > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:bucket",
                    emptyBuckets
            ));
        }
        final HeldItemSummary mainHand = waterBuckets > 0
                ? new HeldItemSummary(
                        "minecraft:water_bucket",
                        1,
                        0,
                        0
                )
                : emptyBuckets > 0
                    ? new HeldItemSummary(
                            "minecraft:bucket",
                            1,
                            0,
                            0
                    )
                    : HeldItemSummary.empty();
        return new CoreSkillFrame(
                PLAYER_ID,
                dimension,
                revision,
                revision,
                new PerceptionVec3(
                        inWater ? 1.5 : 0.5,
                        y,
                        0.5
                ),
                new PerceptionVec3(
                        inWater ? 1.5 : 0.5,
                        y + 1.62,
                        0.5
                ),
                look.normalized(),
                onGround,
                inWater,
                dangers.stream()
                        .mapToDouble(DangerSignal::severity)
                        .max()
                        .orElse(0.0),
                new LocalNavSnapshot(
                        dimension,
                        revision,
                        voxels
                ),
                faces,
                20.0F,
                20.0F,
                20,
                inventory,
                mainHand,
                HeldItemSummary.empty(),
                List.of(),
                dangers
        );
    }

    private static ObservedVoxel voxel(
            final int x,
            final int y,
            final int z,
            final VoxelKind kind,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                kind,
                0.0,
                revision
        );
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
        private final List<MovementIntent> moves =
                new ArrayList<>();
        private final List<LookIntent> looks =
                new ArrayList<>();
        private final List<BlockInteractionTarget> uses =
                new ArrayList<>();
        private final List<ActionHand> itemUses =
                new ArrayList<>();
        private int jumps;

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
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            uses.add(target);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            itemUses.add(hand);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
