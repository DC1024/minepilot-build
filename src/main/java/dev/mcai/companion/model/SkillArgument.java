package dev.mcai.companion.model;

import java.util.Objects;

/**
 * A wire-safe skill argument. The local skill schema is responsible for
 * converting the string value into its actual type.
 */
public record SkillArgument(String name, String value) {
    public SkillArgument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
