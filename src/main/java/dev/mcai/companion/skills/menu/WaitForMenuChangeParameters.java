package dev.mcai.companion.skills.menu;

import java.util.Objects;

public record WaitForMenuChangeParameters(
        MenuBinding binding,
        int timeoutTicks
) {
    public WaitForMenuChangeParameters {
        Objects.requireNonNull(binding, "binding");
        if (timeoutTicks < 1 || timeoutTicks > 1_200) {
            throw new IllegalArgumentException(
                    "Menu wait must be between 1 and 1200 ticks"
            );
        }
    }
}
