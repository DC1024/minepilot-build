package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TravelSkillsRegistrationTest {
    @Test
    void registersTravelToWithSessionBinding() {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frame(
                                1,
                                0.5,
                                1.0,
                                0.5,
                                new PerceptionVec3(1.0, 0.0, 0.0),
                                corridor(1, 2),
                                0.0
                        )
                );
        SkillRegistry registry = TravelSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                frames,
                () -> 1L
        );

        assertTrue(registry.contains(TravelSkills.TRAVEL_TO));
        assertTrue(TravelSkills.plannerGuide().contains(
                "first-person semantic observations"
        ));
    }

    @Test
    void parserRequiresTheExactFiveFieldsAndCapsArrivalAtThree() {
        List<SkillArgument> valid = List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "123.5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "-42.25"),
                new SkillArgument("arrivalRadius", "3")
        );
        assertTrue(TravelSkills.parseTravelTo(valid).value().isPresent());

        List<SkillArgument> tooWide = List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "123.5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "-42.25"),
                new SkillArgument("arrivalRadius", "3.01")
        );
        assertEquals(
                "travel_to.invalid_arguments",
                TravelSkills.parseTravelTo(tooWide)
                        .failure()
                        .orElseThrow()
                        .code()
        );

        List<SkillArgument> extra = List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "123.5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "-42.25"),
                new SkillArgument("arrivalRadius", "3"),
                new SkillArgument("teleport", "true")
        );
        assertEquals(
                "travel_to.invalid_arguments",
                TravelSkills.parseTravelTo(extra)
                        .failure()
                        .orElseThrow()
                        .code()
        );
    }
}
