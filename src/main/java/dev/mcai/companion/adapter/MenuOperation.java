package dev.mcai.companion.adapter;

import java.util.Map;
import java.util.Objects;

/**
 * Declarative normal menu interaction. It contains no direct inventory/NBT
 * mutation primitive.
 */
public record MenuOperation(
    String operation,
    Map<String, String> arguments
) {
    public MenuOperation {
        if (operation == null || operation.isBlank() || operation.length() > 64
            || !operation.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid menu operation");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
