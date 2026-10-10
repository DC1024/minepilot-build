package dev.mcai.companion.adapter;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AdapterEnvironment(
    String minecraftVersion,
    String forgeVersion,
    Map<String, String> loadedMods
) {
    public AdapterEnvironment {
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        forgeVersion = requireText(forgeVersion, "forgeVersion");
        loadedMods = Map.copyOf(Objects.requireNonNull(loadedMods, "loadedMods"));
    }

    public Optional<String> modVersion(final String modId) {
        return Optional.ofNullable(loadedMods.get(modId));
    }

    private static String requireText(final String value, final String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
