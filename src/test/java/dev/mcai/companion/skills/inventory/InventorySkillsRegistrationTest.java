package dev.mcai.companion.skills.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class InventorySkillsRegistrationTest {
    @Test
    void registersExactPublicSkillNames() {
        final InventorySkillActuator actuator = new InventorySkillActuator() {
            @Override
            public InventoryOperationResult checkEquip(
                    final EquipItemParameters parameters
            ) {
                return InventoryOperationResult.success();
            }

            @Override
            public InventoryOperationResult equip(
                    final EquipItemParameters parameters
            ) {
                return InventoryOperationResult.success();
            }

            @Override
            public InventoryOperationResult checkDrop(
                    final DropItemParameters parameters
            ) {
                return InventoryOperationResult.success();
            }

            @Override
            public InventoryOperationResult drop(
                    final DropItemParameters parameters
            ) {
                return InventoryOperationResult.success();
            }

            @Override
            public InventoryOperationResult checkCraft(
                    final CraftRecipeParameters parameters
            ) {
                return InventoryOperationResult.success();
            }

            @Override
            public InventoryOperationResult craftOnce(
                    final CraftRecipeParameters parameters
            ) {
                return InventoryOperationResult.success();
            }
        };

        final SkillRegistry registry = InventorySkills.registerAll(
                new SkillRegistry(),
                actuator
        );

        assertEquals(
                Set.of("equip_item", "drop_item", "craft_recipe"),
                registry.names()
        );
    }
}
