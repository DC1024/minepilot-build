package dev.mcai.companion.navigation;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

public final class RouteInvalidator {
    public RouteInvalidation inspect(
        LocalRoute route,
        long environmentRevision,
        Set<GridPos> changedPositions
    ) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(changedPositions, "changedPositions");
        if (changedPositions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Changed positions must not contain null");
        }
        if (environmentRevision < route.snapshotRevision()) {
            throw new IllegalArgumentException("Environment revision moved backwards");
        }
        if (environmentRevision == route.snapshotRevision() || changedPositions.isEmpty()) {
            return new RouteInvalidation(
                false,
                OptionalInt.empty(),
                route.steps(),
                environmentRevision
            );
        }

        for (int index = 0; index < route.steps().size(); index++) {
            final LocalStep step = route.steps().get(index);
            if (step.observedDependencies().stream().anyMatch(changedPositions::contains)) {
                return new RouteInvalidation(
                    true,
                    OptionalInt.of(index),
                    route.steps().subList(0, index),
                    environmentRevision
                );
            }
        }
        return new RouteInvalidation(
            false,
            OptionalInt.empty(),
            route.steps(),
            environmentRevision
        );
    }
}
