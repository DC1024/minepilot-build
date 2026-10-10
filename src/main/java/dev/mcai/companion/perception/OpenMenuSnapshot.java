package dev.mcai.companion.perception;

import java.util.List;
import java.util.Objects;

/**
 * Bounded view of the menu already opened by the companion through an
 * ordinary block/entity interaction.
 */
public record OpenMenuSnapshot(
        String menuType,
        String menuClass,
        int containerId,
        int stateId,
        List<MenuSlotSummary> slots,
        HeldItemSummary carried,
        List<MenuOptionSummary> options
) {
    public static final int MAX_VISIBLE_SLOTS = 256;
    public static final int MAX_VISIBLE_OPTIONS = 128;

    public OpenMenuSnapshot {
        menuType = PerceptionValidation.identifier(menuType, "menuType");
        menuClass = Objects.requireNonNull(menuClass, "menuClass");
        if (menuClass.isBlank()
                || menuClass.length() > 128
                || menuClass.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("menuClass is invalid");
        }
        if (containerId < 0 || stateId < 0) {
            throw new IllegalArgumentException("Menu counters are invalid");
        }
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (slots.size() > MAX_VISIBLE_SLOTS) {
            throw new IllegalArgumentException("Open menu exceeds slot bound");
        }
        Objects.requireNonNull(carried, "carried");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.size() > MAX_VISIBLE_OPTIONS) {
            throw new IllegalArgumentException(
                    "Open menu exceeds option bound"
            );
        }
    }

    public OpenMenuSnapshot(
            final String menuType,
            final String menuClass,
            final int containerId,
            final int stateId,
            final List<MenuSlotSummary> slots,
            final HeldItemSummary carried
    ) {
        this(
                menuType,
                menuClass,
                containerId,
                stateId,
                slots,
                carried,
                List.of()
        );
    }
}
