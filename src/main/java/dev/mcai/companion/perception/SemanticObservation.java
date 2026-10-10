package dev.mcai.companion.perception;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete immutable output of one fair first-person semantic sample.
 */
public record SemanticObservation(
        long sequence,
        BodySnapshot body,
        List<VisibleEntity> visibleEntities,
        List<VisibleBlockFace> visibleBlockFaces,
        List<ClearSightRay> clearSightRays,
        List<DangerSignal> dangers,
        Optional<OpenMenuSnapshot> openMenu,
        Optional<CraftingAffordanceSnapshot> craftingAffordances,
        PerceptionBudget budget,
        ObservationBudgetUsage budgetUsage,
        Set<PerceptionProvenance> provenance
) {
    public SemanticObservation {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(body, "body");
        visibleEntities = List.copyOf(
                Objects.requireNonNull(visibleEntities, "visibleEntities")
        );
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(visibleBlockFaces, "visibleBlockFaces")
        );
        clearSightRays = List.copyOf(
                Objects.requireNonNull(clearSightRays, "clearSightRays")
        );
        dangers = List.copyOf(Objects.requireNonNull(dangers, "dangers"));
        openMenu = Objects.requireNonNull(openMenu, "openMenu");
        craftingAffordances = Objects.requireNonNull(
                craftingAffordances,
                "craftingAffordances"
        );
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(budgetUsage, "budgetUsage");
        provenance = Set.copyOf(Objects.requireNonNull(provenance, "provenance"));
        budgetUsage.validateAgainst(budget);
        if (visibleEntities.size() != budgetUsage.visibleEntities()
                || visibleBlockFaces.size() != budgetUsage.visibleBlockFaces()
                || dangers.size() != budgetUsage.dangerSignals()
                || clearSightRays.size()
                    > budgetUsage.blockRaysCast()) {
            throw new IllegalArgumentException("Budget result counts do not match observation");
        }
    }

    public SemanticObservation(
            final long sequence,
            final BodySnapshot body,
            final List<VisibleEntity> visibleEntities,
            final List<VisibleBlockFace> visibleBlockFaces,
            final List<ClearSightRay> clearSightRays,
            final List<DangerSignal> dangers,
            final Optional<OpenMenuSnapshot> openMenu,
            final PerceptionBudget budget,
            final ObservationBudgetUsage budgetUsage,
            final Set<PerceptionProvenance> provenance
    ) {
        this(
            sequence,
            body,
            visibleEntities,
            visibleBlockFaces,
            clearSightRays,
            dangers,
            openMenu,
            Optional.empty(),
            budget,
            budgetUsage,
            provenance
        );
    }

    public SemanticObservation(
            final long sequence,
            final BodySnapshot body,
            final List<VisibleEntity> visibleEntities,
            final List<VisibleBlockFace> visibleBlockFaces,
            final List<DangerSignal> dangers,
            final Optional<OpenMenuSnapshot> openMenu,
            final PerceptionBudget budget,
            final ObservationBudgetUsage budgetUsage,
            final Set<PerceptionProvenance> provenance
    ) {
        this(
            sequence,
            body,
            visibleEntities,
            visibleBlockFaces,
            List.of(),
            dangers,
            openMenu,
            Optional.empty(),
            budget,
            budgetUsage,
            provenance
        );
    }

    public SemanticObservation(
            final long sequence,
            final BodySnapshot body,
            final List<VisibleEntity> visibleEntities,
            final List<VisibleBlockFace> visibleBlockFaces,
            final List<DangerSignal> dangers,
            final PerceptionBudget budget,
            final ObservationBudgetUsage budgetUsage,
            final Set<PerceptionProvenance> provenance
    ) {
        this(
            sequence,
            body,
            visibleEntities,
            visibleBlockFaces,
            List.of(),
            dangers,
            Optional.empty(),
            Optional.empty(),
            budget,
            budgetUsage,
            provenance
        );
    }
}
