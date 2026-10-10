package dev.mcai.companion.skills.menu;

import java.util.Objects;

public record TransferMenuItemParameters(
        MenuBinding binding,
        int sourceSlot,
        int destinationSlot,
        int count
) {
    public TransferMenuItemParameters {
        Objects.requireNonNull(binding, "binding");
        if (sourceSlot < 0
                || destinationSlot < 0
                || sourceSlot == destinationSlot) {
            throw new IllegalArgumentException("Menu slots are invalid");
        }
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException(
                    "Transfer count must be between 1 and 64"
            );
        }
    }
}
