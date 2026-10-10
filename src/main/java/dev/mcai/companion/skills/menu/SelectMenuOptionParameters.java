package dev.mcai.companion.skills.menu;

import java.util.Objects;

public record SelectMenuOptionParameters(
        MenuBinding binding,
        int optionId
) {
    public SelectMenuOptionParameters {
        Objects.requireNonNull(binding, "binding");
        if (optionId < 0 || optionId > 4_096) {
            throw new IllegalArgumentException("optionId is outside bounds");
        }
    }
}
