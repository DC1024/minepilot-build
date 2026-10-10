package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CoreSkillsRegistrationTest {
    @Test
    void registersExactPublicSkillNames() {
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
        SkillRegistry registry = CoreSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );

        assertEquals(
                Set.of(
                        "move_to",
                        "look_at",
                        "follow_entity",
                        "safe_idle"
                ),
                registry.names()
        );
    }
}
