package dev.mcai.companion.navigation;

import java.util.Objects;
import java.util.Set;

public record LocalStep(
    GridPos from,
    GridPos to,
    MovementPrimitive primitive,
    double cost,
    double danger,
    long plannedRevision,
    Set<GridPos> observedDependencies
) {
    public LocalStep {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(primitive, "primitive");
        Objects.requireNonNull(observedDependencies, "observedDependencies");
        if (from.equals(to)) {
            throw new IllegalArgumentException("A local step must change position");
        }
        if (!Double.isFinite(cost) || cost <= 0.0) {
            throw new IllegalArgumentException("Step cost must be finite and positive");
        }
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("Step danger must be in [0, 1]");
        }
        if (plannedRevision < 0) {
            throw new IllegalArgumentException("Planned revision must be non-negative");
        }
        observedDependencies = Set.copyOf(observedDependencies);
        if (observedDependencies.isEmpty() || observedDependencies.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Step dependencies must be non-empty");
        }
    }
}
