package dev.mcai.companion.skills.menu;

import java.util.Objects;

public record ObservedMenuSlotParameters(
        MenuBinding binding,
        int slot
) {
    public ObservedMenuSlotParameters {
        Objects.requireNonNull(binding, "binding");
        if (slot < 0) {
            throw new IllegalArgumentException(
                    "Observed menu slot must be non-negative"
            );
        }
    }
}
