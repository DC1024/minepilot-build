package dev.mcai.companion.skills.combat;

import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.PLAYER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CombatSkillsRegistrationTest {
    @Test
    void registersExactTwoArgumentPublicContract() {
        SkillRegistry registry = new SkillRegistry();
        CombatSkills.registerAll(
                registry,
                PLAYER_ID,
                new CombatSkillTestFixtures.RecordingCoreActuator(),
                new CombatSkillTestFixtures.MutableCoreFrames(null),
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator(),
                new CombatSkillTestFixtures
                        .MutableInteractionFrames(null)
        );

        assertEquals(
                java.util.Set.of(
                        CombatSkills.ENGAGE_OBSERVED_ENTITY,
                        ShootObservedEntitySkill.NAME
                ),
                registry.names()
        );
        var validator = registry.modelArgumentValidators()
                .get(CombatSkills.ENGAGE_OBSERVED_ENTITY);
        assertTrue(validator.validate(List.of(
                new SkillArgument("sampleSequence", "12"),
                new SkillArgument("observationId", "visible-0")
        )).isEmpty());
        assertFalse(validator.validate(List.of(
                new SkillArgument("sampleSequence", "12"),
                new SkillArgument("observationId", "visible-0"),
                new SkillArgument(
                        "entityUuid",
                        CombatSkillTestFixtures.TARGET_ID.toString()
                )
        )).isEmpty());

        var ranged = registry.modelArgumentValidators()
                .get(ShootObservedEntitySkill.NAME);
        assertTrue(ranged.validate(List.of(
                new SkillArgument("sampleSequence", "12"),
                new SkillArgument("observationId", "visible-0"),
                new SkillArgument("hand", "main_hand"),
                new SkillArgument("shots", "1")
        )).isEmpty());
        assertFalse(ranged.validate(List.of(
                new SkillArgument("sampleSequence", "12"),
                new SkillArgument("observationId", "visible-0"),
                new SkillArgument("hand", "main_hand"),
                new SkillArgument("shots", "01")
        )).isEmpty());
        assertFalse(validator.validate(List.of(
                new SkillArgument("sampleSequence", "012"),
                new SkillArgument("observationId", "visible-0")
        )).isEmpty());
    }

    @Test
    void plannerGuideStatesFairSingleTargetBoundary() {
        String guide = CombatSkills.plannerGuide();

        assertTrue(guide.contains("sampleSequence"));
        assertTrue(guide.contains("observationId"));
        assertTrue(guide.contains("never exposes or accepts UUIDs"));
        assertTrue(guide.contains("vanilla attack"));
        assertTrue(guide.contains("shoot_observed_entity"));
        assertTrue(guide.contains("End crystal"));
    }
}
