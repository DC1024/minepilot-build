package dev.mcai.companion.navigation;

import java.util.Objects;
import java.util.UUID;

public record TopologyEdge(
    UUID id,
    UUID fromNode,
    UUID toNode,
    TransportMode mode,
    double expectedCost,
    double danger,
    boolean verified,
    long environmentRevision
) {
    public TopologyEdge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromNode, "fromNode");
        Objects.requireNonNull(toNode, "toNode");
        Objects.requireNonNull(mode, "mode");
        if (fromNode.equals(toNode)) {
            throw new IllegalArgumentException("Topology edge must connect distinct nodes");
        }
        if (!Double.isFinite(expectedCost) || expectedCost <= 0.0) {
            throw new IllegalArgumentException("Expected edge cost must be finite and positive");
        }
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("Edge danger must be in [0, 1]");
        }
        if (environmentRevision < 0) {
            throw new IllegalArgumentException("Environment revision must be non-negative");
        }
    }

    public boolean isUsable() {
        return mode != TransportMode.PORTAL || verified;
    }

    public double effectiveCost(NavigationRiskProfile riskProfile) {
        Objects.requireNonNull(riskProfile, "riskProfile");
        return expectedCost + danger * riskProfile.voxelDangerWeight();
    }
}
