package dev.mcai.companion.skills.interaction;

import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.OBSERVED_BLOCK;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class BreakBlockSkillTest {
    @Test
    void resolvesInternalRayHitAndCompletesVanillaMiningLifecycle()
            throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = parameters();

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertTrue(actuator.miningTargets.isEmpty());

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.miningTargets.size());
        assertEquals(
                1.0,
                actuator.miningTargets.getFirst().hitPoint().x()
        );

        actuator.continueMiningOutcome = ActionOutcome.COMPLETED;
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(102), parameters).status()
        );
    }

    @Test
    void fairHitIsBoundAtStartAcrossANewerObservationBeforeFirstTick()
            throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = parameters();

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        frames.frame = InteractionSkillTestFixtures.frame(
                13,
                101,
                101,
                InteractionSkillTestFixtures.SESSION,
                true,
                true
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.miningTargets.size());
        assertEquals(
                1.0,
                actuator.miningTargets.getFirst().hitPoint().x()
        );
    }

    @Test
    void retainedModelSelectionIsRevalidatedAfterNewerSemanticFrame()
            throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        frames.publish(InteractionSkillTestFixtures.frame(
                13,
                101,
                101,
                InteractionSkillTestFixtures.SESSION,
                true,
                true
        ));
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = parameters();

        assertTrue(skill.preconditions(context(101), parameters).isEmpty());
        skill.start(context(101), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(1, actuator.miningTargets.size());
    }

    @Test
    void retainedSelectionCannotMineWhenTargetLeftCurrentView() {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        frames.publish(InteractionSkillTestFixtures.frame(
                13,
                101,
                101,
                InteractionSkillTestFixtures.SESSION,
                false,
                true
        ));
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                new InteractionSkillTestFixtures.RecordingActuator(),
                frames,
                InteractionSkillPolicy.defaults()
        );

        assertEquals(
                "break_block.target_not_visible",
                skill.preconditions(context(101), parameters())
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void sessionReplacementFailsWithoutAbortingReplacementBody()
            throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(100), parameters);
        skill.tick(context(101), parameters);

        actuator.session = 8;
        frames.frame = InteractionSkillTestFixtures.frame(
                13,
                102,
                102,
                8,
                true,
                true
        );
        SkillTickResult result = skill.tick(context(102), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "break_block.session_mismatch",
                result.failure().orElseThrow().code()
        );
        assertEquals(0, actuator.abortMiningCalls);
    }

    @Test
    void localTimeoutAbortsMiningAtSafeCheckpoint() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var policy = new InteractionSkillPolicy(10, 2, 20, 100, 6.0);
        var skill = new BreakBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                policy
        );
        var parameters = parameters();
        skill.start(context(100), parameters);
        skill.tick(context(101), parameters);

        SkillTickResult result = skill.tick(context(102), parameters);
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals("break_block.timed_out",
                result.failure().orElseThrow().code());
        assertEquals(1, actuator.abortMiningCalls);
    }

    private static BreakBlockParameters parameters() {
        return new BreakBlockParameters(
                DimensionRef.OVERWORLD,
                OBSERVED_BLOCK
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
