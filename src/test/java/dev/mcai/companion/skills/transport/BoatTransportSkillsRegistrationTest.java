package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BoatTransportSkillsRegistrationTest {
    @Test
    void registersBothPublicBoatSkills() {
        SkillRegistry registry = new SkillRegistry();
        BoatTransportSkills.registerAll(
                registry,
                BoatTransportTestFixtures.PLAYER_ID,
                new BoatTransportTestFixtures.RecordingActuator(),
                new BoatTransportTestFixtures.MutableFrames(null)
        );

        assertEquals(
                Set.of(
                        BoatTransportSkills.ENTER_OBSERVED_BOAT,
                        BoatTransportSkills.BOAT_TRAVEL_TO
                ),
                registry.names()
        );
        var enter = registry.modelArgumentValidators()
                .get(BoatTransportSkills.ENTER_OBSERVED_BOAT);
        assertTrue(enter.validate(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("sampleSequence", "21"),
                new SkillArgument("observationId", "visible-0")
        )).isEmpty());
        assertFalse(enter.validate(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("sampleSequence", "21"),
                new SkillArgument("observationId", "visible-0"),
                new SkillArgument(
                        "boatUuid",
                        BoatTransportTestFixtures.BOAT_ID.toString()
                )
        )).isEmpty());
    }

    @Test
    void plannerGuideStatesFairControlAndDismountBoundary() {
        String guide = BoatTransportSkills.plannerGuide();

        assertTrue(guide.contains("sampleSequence"));
        assertTrue(guide.contains("observationId"));
        assertTrue(guide.contains("internally"));
        assertTrue(guide.contains("without teleporting"));
        assertTrue(guide.contains("safe bank"));
    }
}
