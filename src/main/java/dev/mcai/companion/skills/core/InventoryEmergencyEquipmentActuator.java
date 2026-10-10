package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Objects;

/**
 * Adapts the normal atomic inventory-menu transaction to the emergency
 * controller without exposing a player, menu, or inventory implementation.
 */
public final class InventoryEmergencyEquipmentActuator
        implements EmergencyEquipmentActuator {
    private final InventorySkillActuator inventory;

    public InventoryEmergencyEquipmentActuator(
            final InventorySkillActuator inventory
    ) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public ActionOutcome equip(
            final ActionHand hand,
            final String itemId
    ) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(itemId, "itemId");
        final InventoryOperationResult result = inventory.equip(
                new EquipItemParameters(
                        itemId,
                        hand == ActionHand.MAIN_HAND
                                ? EquipmentTarget.MAINHAND
                                : EquipmentTarget.OFFHAND
                )
        );
        if (result.succeeded()) {
            return ActionOutcome.COMPLETED;
        }
        final String code = result.failure()
                .orElseThrow()
                .code();
        if (code.endsWith("wrong_thread")) {
            return ActionOutcome.WRONG_THREAD;
        }
        if (code.endsWith("player_offline")) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        if (code.endsWith("player_dead")
                || code.endsWith("spectator")) {
            return ActionOutcome.PLAYER_INCAPACITATED;
        }
        if (code.endsWith("item_not_found")
                || code.endsWith("unknown_item")) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        return ActionOutcome.INVALID_PLAYER_STATE;
    }
}
