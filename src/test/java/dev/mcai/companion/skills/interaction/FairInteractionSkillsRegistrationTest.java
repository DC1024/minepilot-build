package dev.mcai.companion.skills.interaction;

import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FairInteractionSkillsRegistrationTest {
    @Test
    void registersExactPublicSkillNames() {
        SkillRegistry registry = FairInteractionSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new InteractionSkillTestFixtures.RecordingActuator(),
                new InteractionSkillTestFixtures.MutableFrames(frame())
        );

        assertEquals(
                Set.of(
                        "break_block",
                        "use_block",
                        "attack_entity",
                        "interact_entity",
                        "use_item",
                        "consume_owned_food"
                ),
                registry.names()
        );
    }
}
