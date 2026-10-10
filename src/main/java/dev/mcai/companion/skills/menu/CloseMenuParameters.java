package dev.mcai.companion.skills.menu;

import java.util.Objects;

public record CloseMenuParameters(MenuBinding binding) {
    public CloseMenuParameters {
        Objects.requireNonNull(binding, "binding");
    }
}
