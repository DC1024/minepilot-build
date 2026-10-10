package dev.mcai.companion.perception;

/**
 * One slot the companion can legitimately see in its currently open vanilla
 * menu. Slot numbers are menu-local and expire when the menu closes or its
 * state changes.
 */
public record MenuSlotSummary(
        int slot,
        String itemId,
        int count,
        int damage,
        int maxDamage,
        boolean playerInventory,
        boolean mayPickup,
        String role
) {
    /** Backward-compatible constructor for callers without role metadata. */
    public MenuSlotSummary(
            final int slot,
            final String itemId,
            final int count,
            final int damage,
            final int maxDamage,
            final boolean playerInventory,
            final boolean mayPickup
    ) {
        this(
                slot,
                itemId,
                count,
                damage,
                maxDamage,
                playerInventory,
                mayPickup,
                "UNKNOWN"
        );
    }

    public MenuSlotSummary {
        if (slot < 0 || slot > 4_096) {
            throw new IllegalArgumentException("menu slot is outside its bound");
        }
        itemId = PerceptionValidation.identifier(itemId, "itemId");
        if (count < 0
                || damage < 0
                || maxDamage < 0
                || damage > maxDamage) {
            throw new IllegalArgumentException("Invalid menu item values");
        }
        if (count == 0
                && (!itemId.equals("minecraft:air")
                || damage != 0
                || maxDamage != 0)) {
            throw new IllegalArgumentException(
                "Empty menu slot must use the canonical air summary"
            );
        }
        role = PerceptionValidation.token(role, "role", 48);
    }
}
