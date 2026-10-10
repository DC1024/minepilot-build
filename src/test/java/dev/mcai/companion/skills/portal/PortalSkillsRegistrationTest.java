package dev.mcai.companion.skills.portal;

import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PortalSkillsRegistrationTest {
    @Test
    void registersTheFairPortalEntrySkill() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var registry = PortalSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );

        assertTrue(registry.contains(
                PortalSkills.ENTER_OBSERVED_PORTAL
        ));
    }

    @Test
    void parserAcceptsAnOptionalExpectedDestination() {
        var parsed = PortalSkills.parseEnterObservedPortal(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "10"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "north"),
                argument("expectedDestination", "minecraft:the_nether")
        ));

        assertTrue(parsed.value().isPresent());
        assertEquals(
                DimensionRef.NETHER,
                parsed.value()
                        .orElseThrow()
                        .expectedDestination()
                        .orElseThrow()
        );
    }

    @Test
    void parserRejectsHiddenOrNonCanonicalTargetData() {
        List<SkillArgument> arguments = new ArrayList<>(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "010"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "north")
        ));
        assertTrue(
                PortalSkills.parseEnterObservedPortal(arguments)
                        .value()
                        .isEmpty()
        );

        arguments.set(1, argument("sampleSequence", "10"));
        arguments.add(argument("destinationX", "100"));
        assertTrue(
                PortalSkills.parseEnterObservedPortal(arguments)
                        .value()
                        .isEmpty()
        );
    }

    private static SkillArgument argument(String name, String value) {
        return new SkillArgument(name, value);
    }
}
