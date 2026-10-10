package dev.mcai.companion.skills.inventory;

import java.util.Objects;

public record EquipItemParameters(
        String itemId,
        EquipmentTarget slot
) {
    public EquipItemParameters {
        itemId = Objects.requireNonNull(itemId, "itemId");
        slot = Objects.requireNonNull(slot, "slot");
    }
}
