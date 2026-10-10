package dev.mcai.companion.skills.farming;

import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.INITIAL_SEQUENCE;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.SESSION;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.matureCropFrame;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.parameters;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.replantedFrame;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.substrateFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HarvestAndReplantStepSkillTest {
    @Test
    void harvestsThenUsesSeedOnObservedSubstrateAndConfirmsReplant()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                coreFrames,
                coreActuator,
                frames,
                actuator
        );
        var parameters = parameters();

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.mining.size());

        frames.frame = substrateFrame(5);
        frames.crosshair = frames.frame.visibleBlockFaces().getFirst();
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(
                List.of("minecraft:wheat_seeds"),
                actuator.equipped
        );
        assertEquals(1, actuator.uses.size());
        assertEquals(63, actuator.uses.getFirst().y());

        frames.frame = replantedFrame(4);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103), parameters).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(103), parameters).status()
        );
    }

    @Test
    void rejectsImmatureCropAndMissingSeedBeforeAnyMutation()
            throws Exception {
        VisibleBlockFace immature =
                FarmingSkillTestFixtures.cropFace("6");
        var immatureFrames = new FarmingSkillTestFixtures.MutableFrames(
                FarmingSkillTestFixtures.frame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        List.of(immature),
                        4
                )
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var immatureCore =
                new FarmingSkillTestFixtures.CoupledCoreFrames(
                        immatureFrames
                );
        var skill = skill(
                immatureCore,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                immatureFrames,
                actuator
        );

        assertEquals(
                "harvest_and_replant_step.crop_not_mature",
                skill.preconditions(context(100), parameters())
                        .orElseThrow()
                        .code()
        );

        var noSeedFrames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(0)
        );
        skill = skill(
                new FarmingSkillTestFixtures.CoupledCoreFrames(
                        noSeedFrames
                ),
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                noSeedFrames,
                actuator
        );
        assertEquals(
                "harvest_and_replant_step.seed_unavailable",
                skill.preconditions(context(100), parameters())
                        .orElseThrow()
                        .code()
        );
        assertTrue(actuator.mining.isEmpty());
    }

    @Test
    void repairsAStaleModelCoordinateOnlyWhenOneMatureCropRemainsVisible()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                frames,
                actuator
        );
        var stale = new HarvestAndReplantParameters(
                dev.mcai.companion.waypoint.DimensionRef.OVERWORLD,
                CropKind.WHEAT,
                new dev.mcai.companion.skills.interaction.ObservedBlockTarget(
                        INITIAL_SEQUENCE,
                        99,
                        64,
                        99,
                        dev.mcai.companion.action.BlockFace.WEST
                )
        );

        assertTrue(skill.preconditions(context(100), stale).isEmpty());
        skill.start(context(100), stale);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), stale).status()
        );
        assertEquals(1, actuator.mining.size());
    }

    @Test
    void acceptsDelayedModelHandleOnlyWhenCropIsStillCurrentlyVisible()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                List.of(FarmingSkillTestFixtures.cropFace("7")),
                5
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                frames,
                actuator
        );

        assertTrue(skill.preconditions(context(100), parameters()).isEmpty());
        skill.start(context(100), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters()).status()
        );
        assertEquals(1, actuator.mining.size());
    }

    @Test
    void replacementSessionCannotBeMutatedOrAborted() throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        actuator.beginMiningOutcome = ActionOutcome.IN_PROGRESS;
        var skill = skill(
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                frames,
                actuator
        );
        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());

        actuator.session = SESSION + 1;
        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 1,
                SESSION + 1,
                List.of(FarmingSkillTestFixtures.cropFace("7")),
                5
        );
        SkillTickResult result = skill.tick(context(102), parameters());

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "harvest_and_replant_step.session_mismatch",
                result.failure().orElseThrow().code()
        );
        assertEquals(0, actuator.abortCalls);
        assertTrue(actuator.uses.isEmpty());
    }

    @Test
    void requiresAConfirmedNewObservationAfterPlanting()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var skill = new HarvestAndReplantStepSkill(
                PLAYER_ID,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                coreFrames,
                actuator,
                frames,
                new FarmingSkillPolicy(10, 100, 2, 6.0)
        );
        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());
        frames.frame = substrateFrame(5);
        frames.crosshair = frames.frame.visibleBlockFaces().getFirst();
        skill.tick(context(102), parameters());

        SkillTickResult result = skill.tick(context(104), parameters());
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "harvest_and_replant_step.replant_not_confirmed",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void vanillaSeedConsumptionWithCrosshairPlantConfirmsReplant()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var skill = skill(
                coreFrames,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                frames,
                new FarmingSkillTestFixtures.RecordingActuator()
        );
        var parameters = parameters();

        skill.start(context(100), parameters);
        skill.tick(context(101), parameters);
        frames.frame = substrateFrame(5);
        frames.crosshair = frames.frame.visibleBlockFaces().getFirst();
        skill.tick(context(102), parameters);
        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                List.of(),
                4,
                1
        );
        frames.crosshair = FarmingSkillTestFixtures.cropFace("0");
        coreFrames.lookOverride = FarmingSkillTestFixtures.coreFrame(
                matureCropFrame(5)
        ).lookDirection();

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103), parameters).status()
        );
    }

    @Test
    void turnsTowardCropBeforeDispatchingVanillaMining()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        coreFrames.lookOverride = new PerceptionVec3(
                -1.0,
                0.0,
                0.0
        );
        var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                coreFrames,
                coreActuator,
                frames,
                actuator
        );

        skill.start(context(100), parameters());
        SkillTickResult aiming = skill.tick(
                context(101),
                parameters()
        );

        assertEquals(SkillTickResult.Status.RUNNING, aiming.status());
        assertEquals(1, coreActuator.looks.size());
        assertTrue(actuator.mining.isEmpty());

        coreFrames.lookOverride = null;
        skill.tick(context(102), parameters());
        assertEquals(1, actuator.mining.size());
    }

    @Test
    void looksDownToRevealSoilBeforeAnyReplantAction()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                coreFrames,
                coreActuator,
                frames,
                actuator
        );

        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());
        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                List.of(),
                5
        );

        SkillTickResult revealing = skill.tick(
                context(102),
                parameters()
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                revealing.status()
        );
        assertTrue(coreActuator.looks.size() >= 2);
        assertTrue(actuator.uses.isEmpty());
    }

    @Test
    void waitsForVanillaPickupDelayBeforeApproachingHarvest()
            throws Exception {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                matureCropFrame(5)
        );
        var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                coreFrames,
                coreActuator,
                frames,
                actuator
        );
        var parameters = parameters();

        skill.start(context(100), parameters);
        skill.tick(context(101), parameters);
        frames.frame = substrateFrame(5);
        frames.crosshair = frames.frame.visibleBlockFaces().getFirst();
        skill.tick(context(102), parameters);
        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                List.of(FarmingSkillTestFixtures.cropFace("0")),
                4,
                0
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103), parameters).status()
        );
        coreFrames.lookOverride = new PerceptionVec3(
                1.0,
                0.0,
                0.0
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(104), parameters).status()
        );
        assertTrue(coreActuator.moves.isEmpty());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(115), parameters).status()
        );
        assertTrue(
                coreActuator.moves.stream()
                        .anyMatch(move -> move.forward() > 0.0)
        );

        frames.frame = FarmingSkillTestFixtures.frame(
                INITIAL_SEQUENCE + 3,
                SESSION,
                List.of(FarmingSkillTestFixtures.cropFace("0")),
                4,
                1
        );
        coreFrames.lookOverride = null;
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(116), parameters).status()
        );
    }

    private static HarvestAndReplantStepSkill skill(
            FarmingSkillTestFixtures.CoupledCoreFrames coreFrames,
            FarmingSkillTestFixtures.RecordingCoreActuator coreActuator,
            FarmingSkillTestFixtures.MutableFrames frames,
            FarmingSkillTestFixtures.RecordingActuator actuator
    ) {
        return new HarvestAndReplantStepSkill(
                PLAYER_ID,
                coreActuator,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
