package dev.mcai.companion.skills.core;

import dev.mcai.companion.perception.InventoryItemSummary;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Conservative vanilla food allow-list. Item use is still dispatched through
 * the normal player packet path; this list merely avoids trying to consume an
 * arbitrary held tool during an emergency.
 */
public final class VanillaFoodItems {
    private static final Set<String> IDS = Set.of(
            "minecraft:apple",
            "minecraft:baked_potato",
            "minecraft:beef",
            "minecraft:beetroot",
            "minecraft:beetroot_soup",
            "minecraft:bread",
            "minecraft:carrot",
            "minecraft:cod",
            "minecraft:cooked_beef",
            "minecraft:cooked_chicken",
            "minecraft:cooked_cod",
            "minecraft:cooked_mutton",
            "minecraft:cooked_porkchop",
            "minecraft:cooked_rabbit",
            "minecraft:cooked_salmon",
            "minecraft:cookie",
            "minecraft:dried_kelp",
            "minecraft:enchanted_golden_apple",
            "minecraft:glow_berries",
            "minecraft:golden_apple",
            "minecraft:golden_carrot",
            "minecraft:honey_bottle",
            "minecraft:melon_slice",
            "minecraft:mushroom_stew",
            "minecraft:mutton",
            "minecraft:porkchop",
            "minecraft:potato",
            "minecraft:pumpkin_pie",
            "minecraft:rabbit",
            "minecraft:rabbit_stew",
            "minecraft:salmon",
            "minecraft:sweet_berries",
            "minecraft:tropical_fish"
    );

    private VanillaFoodItems() {
    }

    /**
     * Returns whether the item is a conservative, normally safe vanilla
     * emergency food. Foods with intentional harmful effects are excluded.
     */
    public static boolean isSafeFood(final String itemId) {
        return IDS.contains(itemId);
    }

    static Optional<String> preferredAvailable(
            final List<InventoryItemSummary> inventory,
            final boolean criticalHealth
    ) {
        return inventory.stream()
                .filter(item -> isSafeFood(item.itemId()))
                .min(Comparator
                        .comparingInt((InventoryItemSummary item) ->
                                preference(
                                        item.itemId(),
                                        criticalHealth
                                )
                        )
                        .thenComparing(
                                InventoryItemSummary::itemId
                        ))
                .map(InventoryItemSummary::itemId);
    }

    private static int preference(
            final String itemId,
            final boolean criticalHealth
    ) {
        if (criticalHealth) {
            if ("minecraft:golden_apple".equals(itemId)) {
                return 0;
            }
            if ("minecraft:enchanted_golden_apple".equals(itemId)) {
                return 1;
            }
        }
        return switch (itemId) {
            case "minecraft:cooked_beef",
                    "minecraft:cooked_porkchop",
                    "minecraft:golden_carrot" -> 10;
            case "minecraft:cooked_mutton",
                    "minecraft:cooked_salmon",
                    "minecraft:cooked_chicken" -> 11;
            case "minecraft:bread",
                    "minecraft:baked_potato",
                    "minecraft:cooked_cod",
                    "minecraft:cooked_rabbit" -> 12;
            case "minecraft:pumpkin_pie",
                    "minecraft:rabbit_stew",
                    "minecraft:mushroom_stew",
                    "minecraft:beetroot_soup" -> 13;
            case "minecraft:golden_apple" -> 30;
            case "minecraft:enchanted_golden_apple" -> 31;
            default -> 20;
        };
    }
}
