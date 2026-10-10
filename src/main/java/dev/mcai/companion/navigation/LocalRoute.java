package dev.mcai.companion.navigation;

import java.util.List;
import java.util.Objects;

public record LocalRoute(
    LocalRouteStatus status,
    GridPos start,
    GridPos requestedGoal,
    GridPos reached,
    List<LocalStep> steps,
    double totalCost,
    int expandedNodes,
    long snapshotRevision
) {
    public LocalRoute {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(requestedGoal, "requestedGoal");
        Objects.requireNonNull(reached, "reached");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        if (!Double.isFinite(totalCost) || totalCost < 0.0) {
            throw new IllegalArgumentException("Route cost must be finite and non-negative");
        }
        if (expandedNodes < 0 || snapshotRevision < 0) {
            throw new IllegalArgumentException("Route counters must be non-negative");
        }
        GridPos cursor = start;
        double computedCost = 0.0;
        for (LocalStep step : steps) {
            if (!step.from().equals(cursor) || step.plannedRevision() != snapshotRevision) {
                throw new IllegalArgumentException("Local route steps are discontinuous or stale");
            }
            cursor = step.to();
            computedCost += step.cost();
        }
        if (!cursor.equals(reached)) {
            throw new IllegalArgumentException("Reached position does not match the route");
        }
        if (Math.abs(computedCost - totalCost) > 1.0e-8 * Math.max(1.0, totalCost)) {
            throw new IllegalArgumentException("Route total cost does not match its steps");
        }
        if (status == LocalRouteStatus.FOUND && !reached.equals(requestedGoal)) {
            throw new IllegalArgumentException("A found route must reach its requested goal");
        }
        if (status != LocalRouteStatus.FOUND && !steps.isEmpty()) {
            throw new IllegalArgumentException("Failed routes must not expose an executable prefix");
        }
    }

    public boolean found() {
        return status == LocalRouteStatus.FOUND;
    }

    static LocalRoute failure(
        LocalRouteStatus status,
        GridPos start,
        GridPos goal,
        int expandedNodes,
        long revision
    ) {
        return new LocalRoute(
            status,
            start,
            goal,
            start,
            List.of(),
            0.0,
            expandedNodes,
            revision
        );
    }
}
