package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;

/**
 * Narrow inventory transaction used only by the local survival supervisor.
 * Implementations must use ordinary player inventory/menu semantics.
 */
@FunctionalInterface
public interface EmergencyEquipmentActuator {
    ActionOutcome equip(ActionHand hand, String itemId);

    static EmergencyEquipmentActuator unavailable() {
        return (hand, itemId) -> ActionOutcome.ITEM_UNAVAILABLE;
    }
}
