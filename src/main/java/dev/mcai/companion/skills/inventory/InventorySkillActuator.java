package dev.mcai.companion.skills.inventory;

/**
 * Server-owned boundary for legal inventory actions.
 *
 * <p>Check methods are read-only. Mutation methods must use ordinary vanilla
 * menu transactions and must complete at a safe atomic boundary.</p>
 */
public interface InventorySkillActuator {
    InventoryOperationResult checkEquip(EquipItemParameters parameters);

    InventoryOperationResult equip(EquipItemParameters parameters);

    InventoryOperationResult checkDrop(DropItemParameters parameters);

    InventoryOperationResult drop(DropItemParameters parameters);

    InventoryOperationResult checkCraft(CraftRecipeParameters parameters);

    InventoryOperationResult craftOnce(CraftRecipeParameters parameters);

    /**
     * Reports only whether this player's currently open vanilla crafting
     * menu has a 3x3 grid. It exposes no container contents or world data.
     */
    default boolean hasThreeByThreeCraftingMenu() {
        return false;
    }

    /**
     * Closes this player's currently open 3x3 crafting menu through the
     * ordinary vanilla container-close path. Compound skills use this only as
     * a cleanup boundary so later world actions do not depend on another model
     * request to repair local GUI state.
     */
    default InventoryOperationResult closeThreeByThreeCraftingMenu() {
        return InventoryOperationResult.rejected(
                "inventory.close_crafting_menu_unsupported"
        );
    }
}
