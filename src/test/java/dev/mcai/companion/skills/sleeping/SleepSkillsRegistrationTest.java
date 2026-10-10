package dev.mcai.companion.skills.sleeping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SleepSkillsRegistrationTest {
    @Test
    void registersExactPublicSkillAndDocumentsFairContract() {
        SkillRegistry registry = SleepSkills.registerAll(
                new SkillRegistry(),
                SleepSkillTestFixtures.PLAYER_ID,
                new SleepSkillTestFixtures.RecordingActuator(),
                new SleepSkillTestFixtures.MutableFrames(
                        new SleepSkillTestFixtures.FrameBuilder().build()
                )
        );

        assertEquals(
                Set.of("sleep_in_observed_bed"),
                registry.names()
        );
        assertTrue(SleepSkills.plannerGuide().contains(
                "visibleBlockFaces"
        ));
        assertTrue(SleepSkills.plannerGuide().contains(
                "naturally wakes"
        ));
        assertTrue(SleepSkills.plannerGuide().contains(
                "never changes time"
        ));
    }
}
