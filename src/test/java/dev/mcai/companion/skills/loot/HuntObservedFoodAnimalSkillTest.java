package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class HuntObservedFoodAnimalSkillTest {
    private static final UUID TARGET =
            UUID.fromString("32000000-0000-0000-0000-000000000001");

    @Test
    void authorizesOnlyClearlyUnownedAdultFoodAnimals() {
        assertTrue(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:cow",
                                false,
                                safeProperties()
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:cow",
                                false,
                                Map.of(
                                        "baby", "true",
                                        "customNamed", "false",
                                        "leashed", "false"
                                )
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:pig",
                                false,
                                Map.of(
                                        "baby", "false",
                                        "customNamed", "true",
                                        "leashed", "false"
                                )
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:sheep",
                                false,
                                Map.of(
                                        "baby", "false",
                                        "customNamed", "false",
                                        "leashed", "true"
                                )
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:villager",
                                false,
                                safeProperties()
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:cow",
                                true,
                                safeProperties()
                        )
                )
        );
        assertFalse(
                HuntObservedFoodAnimalSkill.legalFoodAnimalTarget(
                        entity(
                                "minecraft:cow",
                                false,
                                Map.of()
                        )
                ),
                "Missing ownership evidence must fail closed"
        );
    }

    @Test
    void bindsOnlyTheVanillaMeatDropForThatAnimal() {
        assertTrue(HuntObservedFoodAnimalSkill.acceptedFoodDrop(
                "minecraft:cow",
                "minecraft:beef"
        ));
        assertTrue(HuntObservedFoodAnimalSkill.acceptedFoodDrop(
                "minecraft:cow",
                "minecraft:cooked_beef"
        ));
        assertFalse(HuntObservedFoodAnimalSkill.acceptedFoodDrop(
                "minecraft:cow",
                "minecraft:porkchop"
        ));
        assertFalse(HuntObservedFoodAnimalSkill.acceptedFoodDrop(
                "minecraft:villager",
                "minecraft:emerald"
        ));
    }

    private static Map<String, String> safeProperties() {
        return Map.of(
                "baby", "false",
                "customNamed", "false",
                "leashed", "false",
                "tamed", "false"
        );
    }

    private static VisibleEntity entity(
            final String type,
            final boolean hostile,
            final Map<String, String> properties
    ) {
        return new VisibleEntity(
                TARGET,
                type,
                new PerceptionVec3(2.5, 1.0, 0.5),
                new PerceptionVec3(2.0, 0.0, 0.0),
                2.0,
                hostile,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                properties
        );
    }
}
