package dev.mcai.companion.navigation;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public record RouteInvalidation(
    boolean invalidated,
    OptionalInt firstInvalidStep,
    List<LocalStep> validPrefix,
    long evaluatedRevision
) {
    public RouteInvalidation {
        Objects.requireNonNull(firstInvalidStep, "firstInvalidStep");
        Objects.requireNonNull(validPrefix, "validPrefix");
        validPrefix = List.copyOf(validPrefix);
        if (evaluatedRevision < 0) {
            throw new IllegalArgumentException("Evaluated revision must be non-negative");
        }
        if (invalidated != firstInvalidStep.isPresent()) {
            throw new IllegalArgumentException("Invalidation state and index disagree");
        }
    }
}
