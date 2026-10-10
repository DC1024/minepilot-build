package dev.mcai.companion.adapter;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record BlockAffordance(
    String registryId,
    Set<String> actions,
    Map<String, String> constraints
) {
    public BlockAffordance {
        registryId = requireRegistryId(registryId);
        actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
        constraints = Map.copyOf(Objects.requireNonNull(constraints, "constraints"));
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("Affordance actions must not be empty");
        }
    }

    private static String requireRegistryId(final String value) {
        if (value == null || value.isBlank() || value.length() > 256
            || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid registry ID");
        }
        return value;
    }
}
