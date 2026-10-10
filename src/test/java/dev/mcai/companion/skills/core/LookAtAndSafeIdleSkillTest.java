package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class LookAtAndSafeIdleSkillTest {
    @Test
    void lookAtTurnsThenCompletesFromFreshPose() throws Exception {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        1.0,
                        0.5,
                        new PerceptionVec3(0.0, 0.0, 1.0),
                        corridor(1, 0),
                        0.0
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        LookAtSkill skill = new LookAtSkill(PLAYER_ID, actuator, frames);
        LookAtParameters target = new LookAtParameters(
                DimensionRef.OVERWORLD,
                10.0,
                2.62,
                0.5
        );
        skill.start(context(1), target);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), target).status()
        );
        assertEquals(1, actuator.looks.size());

        frames.frame = frame(
                2,
                0.5,
                1.0,
                0.5,
                new PerceptionVec3(1.0, 0.0, 0.0),
                corridor(2, 0),
                0.0
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(3), target).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(3), target).status()
        );
    }

    @Test
    void safeIdleHasNoStartSideEffectAndDeterministicallyQuiesces()
            throws Exception {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        1.0,
                        0.5,
                        new PerceptionVec3(1.0, 0.0, 0.0),
                        corridor(1, 0),
                        0.0
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        SafeIdleSkill skill = new SafeIdleSkill(PLAYER_ID, actuator, frames);
        skill.start(context(1), NoParameters.INSTANCE);
        assertEquals(0, actuator.stops);

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(2), NoParameters.INSTANCE).status()
        );
        assertEquals(1, actuator.stops);
        assertEquals(1, actuator.looks.size());
        assertTrue(actuator.movements.isEmpty());
        assertEquals(
                SkillResult.Status.SAFE_IDLE,
                skill.result(context(2), NoParameters.INSTANCE).status()
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
