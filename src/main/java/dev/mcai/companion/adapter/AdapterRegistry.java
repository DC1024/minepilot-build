package dev.mcai.companion.adapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AdapterRegistry {
    private final Map<String, ModAdapter> adapters = new LinkedHashMap<>();

    public synchronized void register(final ModAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        validateAdapterId(adapter.adapterId());
        if (adapter.targetModIds().isEmpty()) {
            throw new IllegalArgumentException("Adapter must declare a target mod");
        }
        if (adapters.putIfAbsent(adapter.adapterId(), adapter) != null) {
            throw new IllegalArgumentException("Duplicate adapter ID: " + adapter.adapterId());
        }
    }

    public synchronized List<ActiveAdapter> activate(final AdapterEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        final List<ActiveAdapter> active = new ArrayList<>();
        for (ModAdapter adapter : adapters.values()) {
            final AdapterCompatibility compatibility = adapter.detect(environment);
            if (compatibility.compatible()) {
                active.add(new ActiveAdapter(adapter, compatibility));
            }
        }
        active.sort(Comparator.comparing(entry -> entry.adapter().adapterId()));
        return List.copyOf(active);
    }

    private static void validateAdapterId(final String id) {
        if (id == null || id.isBlank() || id.length() > 128
            || !id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid adapter ID");
        }
    }

    public record ActiveAdapter(
        ModAdapter adapter,
        AdapterCompatibility compatibility
    ) {
        public ActiveAdapter {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(compatibility, "compatibility");
            if (!compatibility.compatible()) {
                throw new IllegalArgumentException("Inactive adapter cannot be wrapped as active");
            }
        }
    }
}
