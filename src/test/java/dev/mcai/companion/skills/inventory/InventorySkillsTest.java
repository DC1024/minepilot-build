package dev.mcai.companion.skills.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InventorySkillsTest {
    @Test
    void equipHasNoStartMutationAndCompletesOneAtomicTransaction()
            throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        final EquipItemSkill skill = new EquipItemSkill(actuator);
        final EquipItemParameters parameters = new EquipItemParameters(
                "minecraft:iron_helmet",
                EquipmentTarget.HEAD
        );

        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        assertTrue(actuator.operations.isEmpty());

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of("equip"), actuator.operations);
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(2), parameters).status()
        );
    }

    @Test
    void dropPropagatesBoundedPreconditionFailureWithoutMutation() {
        final RecordingActuator actuator = new RecordingActuator();
        actuator.dropCheck = InventoryOperationResult.rejected(
                "drop_item.insufficient_items"
        );
        final DropItemSkill skill = new DropItemSkill(actuator);
        final DropItemParameters parameters = new DropItemParameters(
                "minecraft:diamond",
                4
        );

        assertEquals(
                "drop_item.insufficient_items",
                skill.preconditions(context(1), parameters)
                        .orElseThrow()
                        .code()
        );
        assertTrue(actuator.operations.isEmpty());
    }

    @Test
    void craftExecutesOnlyOneRecipePerTickAndCheckpointsProgress()
            throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        actuator.craftProduced = 4;
        final CraftRecipeSkill skill = new CraftRecipeSkill(actuator);
        final CraftRecipeParameters parameters = new CraftRecipeParameters(
                "minecraft:oak_planks",
                3
        );

        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3), parameters).status()
        );
        assertEquals(2, actuator.operations.size());
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("\"completedCrafts\":2")
        );
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("\"producedItems\":8")
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(4), parameters).status()
        );
        assertEquals(3, actuator.operations.size());
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(4), parameters).status()
        );
    }

    @Test
    void craftStopsAfterARejectedVanillaTransaction() throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        actuator.craftOutcomes.add(InventoryOperationResult.success(4));
        actuator.craftOutcomes.add(InventoryOperationResult.rejected(
                "craft_recipe.materials_unavailable"
        ));
        final CraftRecipeSkill skill = new CraftRecipeSkill(actuator);
        final CraftRecipeParameters parameters = new CraftRecipeParameters(
                "minecraft:oak_planks",
                2
        );
        skill.start(context(1), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        final SkillTickResult rejected = skill.tick(context(3), parameters);
        assertEquals(SkillTickResult.Status.FAILED, rejected.status());
        assertEquals(
                "craft_recipe.materials_unavailable",
                rejected.failure().orElseThrow().code()
        );
        assertEquals(
                SkillResult.Status.FAILED,
                skill.result(context(3), parameters).status()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private static final class RecordingActuator
            implements InventorySkillActuator {
        private final List<String> operations = new ArrayList<>();
        private final List<InventoryOperationResult> craftOutcomes =
                new ArrayList<>();
        private InventoryOperationResult equipCheck =
                InventoryOperationResult.success();
        private InventoryOperationResult dropCheck =
                InventoryOperationResult.success();
        private InventoryOperationResult craftCheck =
                InventoryOperationResult.success();
        private int craftProduced = 1;

        @Override
        public InventoryOperationResult checkEquip(
                final EquipItemParameters parameters
        ) {
            return equipCheck;
        }

        @Override
        public InventoryOperationResult equip(
                final EquipItemParameters parameters
        ) {
            operations.add("equip");
            return InventoryOperationResult.success(1);
        }

        @Override
        public InventoryOperationResult checkDrop(
                final DropItemParameters parameters
        ) {
            return dropCheck;
        }

        @Override
        public InventoryOperationResult drop(
                final DropItemParameters parameters
        ) {
            operations.add("drop");
            return InventoryOperationResult.success(parameters.count());
        }

        @Override
        public InventoryOperationResult checkCraft(
                final CraftRecipeParameters parameters
        ) {
            return craftCheck;
        }

        @Override
        public InventoryOperationResult craftOnce(
                final CraftRecipeParameters parameters
        ) {
            operations.add("craft");
            if (!craftOutcomes.isEmpty()) {
                return craftOutcomes.removeFirst();
            }
            return InventoryOperationResult.success(craftProduced);
        }
    }
}
