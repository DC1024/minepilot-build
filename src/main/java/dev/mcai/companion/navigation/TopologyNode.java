package dev.mcai.companion.navigation;

import java.util.Objects;
import java.util.UUID;

import dev.mcai.companion.waypoint.DimensionRef;

public record TopologyNode(
    UUID id,
    DimensionRef dimension,
    GridPos nativePosition,
    String label
) {
    public TopologyNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(nativePosition, "nativePosition");
        Objects.requireNonNull(label, "label");
        label = label.trim();
        if (label.isEmpty() || label.length() > 128) {
            throw new IllegalArgumentException("Topology label exceeds bounds");
        }
    }
}
