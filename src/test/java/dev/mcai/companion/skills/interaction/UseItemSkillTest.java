package dev.mcai.companion.skills.interaction;

import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class UseItemSkillTest {
    @Test
    void holdsForBoundedTicksThenAlwaysReleases() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new UseItemSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = new UseItemParameters(
                DimensionRef.OVERWORLD,
                ActionHand.OFF_HAND,
                2
        );

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103), parameters).status()
        );
        assertEquals(1, actuator.itemUses.size());
        assertEquals(1, actuator.continueUsingCalls);
        assertEquals(1, actuator.releaseUseCalls);
    }

    @Test
    void tapReleasesInSameAtomicTick() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new UseItemSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = new UseItemParameters(
                DimensionRef.OVERWORLD,
                ActionHand.MAIN_HAND,
                0
        );
        skill.start(context(100), parameters);

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.releaseUseCalls);
    }

    @Test
    void cancellationReleasesButNaturalEndDoesNotDoubleRelease()
            throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new UseItemSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = new UseItemParameters(
                DimensionRef.OVERWORLD,
                ActionHand.OFF_HAND,
                20
        );
        skill.start(context(100), parameters);
        skill.tick(context(101), parameters);
        skill.cancel(context(102), parameters);
        assertEquals(1, actuator.releaseUseCalls);

        var naturallyFinished = new UseItemSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        naturallyFinished.start(context(200), parameters);
        naturallyFinished.tick(context(201), parameters);
        actuator.continueUsingOutcome = ActionOutcome.NO_ACTIVE_ACTION;
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                naturallyFinished.tick(context(202), parameters).status()
        );
        assertEquals(1, actuator.releaseUseCalls);
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
