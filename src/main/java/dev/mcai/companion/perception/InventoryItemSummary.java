package dev.mcai.companion.perception;

public record InventoryItemSummary(String itemId, int count) {
    public InventoryItemSummary {
        itemId = PerceptionValidation.identifier(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("Inventory count must be positive");
        }
    }
}
