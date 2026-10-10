package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A site-specific plan generated from a coordinate-free MechanismSpec. */
public record MechanismPlan(
        int schemaVersion,
        String planId,
        String specId,
        String purpose,
        DimensionRef dimension,
        long sourceRevision,
        GridPos anchor,
        Orientation serviceFacing,
        int width,
        int depth,
        int productionCells,
        Map<String, String> selectedMaterials,
        MechanismSpec.ExpectedRate expectedRate,
        List<MechanismConstructionStep> steps
) {
    public enum Orientation {
        NORTH,
        EAST,
        SOUTH,
        WEST
    }

    public MechanismPlan {
        if (schemaVersion < 1
                || planId == null
                || !planId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid mechanism plan id");
        }
        Objects.requireNonNull(specId, "specId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(dimension, "dimension");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException(
                    "Source revision must not be negative"
            );
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(serviceFacing, "serviceFacing");
        if (width < 3 || width > 9 || depth < 3 || depth > 9
                || productionCells != width * depth - 1) {
            throw new IllegalArgumentException(
                    "Crop mechanism dimensions are inconsistent"
            );
        }
        selectedMaterials = Map.copyOf(
                Objects.requireNonNull(
                        selectedMaterials,
                        "selectedMaterials"
                )
        );
        if (!selectedMaterials.keySet().containsAll(
                Set.of("water", "tool", "crop")
        )) {
            throw new IllegalArgumentException(
                    "Mechanism material selections are incomplete"
            );
        }
        Objects.requireNonNull(expectedRate, "expectedRate");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Mechanism plan has no steps");
        }
        final Set<Integer> indexes = new HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            final MechanismConstructionStep step = steps.get(index);
            if (step.index() != index || !indexes.add(step.index())) {
                throw new IllegalArgumentException(
                        "Mechanism steps must be canonical and unique"
                );
            }
        }
        if (steps.stream().noneMatch(step ->
                step.action()
                        == MechanismConstructionStep.Action.PLACE_FLUID
        ) || steps.stream().filter(step ->
                step.action() == MechanismConstructionStep.Action.PLANT
        ).count() != productionCells
                || steps.stream().filter(step ->
                    step.phase()
                        == MechanismConstructionStep.Phase.COMMISSION
                ).count() < 3L) {
            throw new IllegalArgumentException(
                    "Mechanism plan lacks production or commissioning nodes"
            );
        }
    }
}
