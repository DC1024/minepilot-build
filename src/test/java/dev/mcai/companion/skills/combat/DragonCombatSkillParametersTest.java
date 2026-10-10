package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DragonCombatSkillParametersTest {
    @Test
    void acceptsOnlyTheParameterlessLocalControllerContract() {
        final var parsed = DragonCombatSkillParameters.parse(List.of());

        assertTrue(parsed.value().isPresent());
        assertTrue(DragonCombatSkillParameters.parse(List.of(
                new SkillArgument("rallyX", "0")
        )).value().isEmpty());
        assertTrue(DragonCombatSkillParameters.parse(null)
                .value().isEmpty());
    }
}
