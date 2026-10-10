package dev.mcai.companion.perception;

public record HeldItemSummary(
        String itemId,
        int count,
        int damage,
        int maxDamage
) {
    public HeldItemSummary {
        itemId = PerceptionValidation.identifier(itemId, "itemId");
        if (count < 0 || damage < 0 || maxDamage < 0 || damage > maxDamage) {
            throw new IllegalArgumentException("Invalid held item values");
        }
        if (count == 0 && (!itemId.equals("minecraft:air") || damage != 0 || maxDamage != 0)) {
            throw new IllegalArgumentException("Empty hand must use the canonical air summary");
        }
    }

    public static HeldItemSummary empty() {
        return new HeldItemSummary("minecraft:air", 0, 0, 0);
    }

    public boolean emptyHand() {
        return count == 0;
    }
}
