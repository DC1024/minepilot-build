package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable plan synthesized from one fair local observation.
 *
 * <p>The geometry is generated from volume, enclosure, entrance, lighting,
 * terrain, and inventory constraints. No literal block blueprint is stored
 * in code or loaded from data.</p>
 */
public record ShelterPlan(
        String planId,
        DimensionRef dimension,
        long sourceRevision,
        ShelterScale scale,
        GridPos origin,
        int interiorWidth,
        int interiorDepth,
        int interiorHeight,
        ShelterFacing entranceFacing,
        GridPos doorLower,
        GridPos lightPosition,
        String structuralItemId,
        String doorItemId,
        String lightItemId,
        int requiredStructuralBlocks,
        List<ShelterBuildStep> steps
) {
    public ShelterPlan {
        if (planId == null || !planId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Plan id is not canonical");
        }
        Objects.requireNonNull(dimension, "dimension");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Source revision is negative");
        }
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(origin, "origin");
        if (interiorWidth < 3
                || interiorDepth < 3
                || interiorHeight < 2) {
            throw new IllegalArgumentException(
                    "Shelter interior is below the survival minimum"
            );
        }
        Objects.requireNonNull(entranceFacing, "entranceFacing");
        Objects.requireNonNull(doorLower, "doorLower");
        Objects.requireNonNull(lightPosition, "lightPosition");
        structuralItemId = itemId(structuralItemId);
        doorItemId = itemId(doorItemId);
        lightItemId = itemId(lightItemId);
        if (requiredStructuralBlocks < 1) {
            throw new IllegalArgumentException(
                    "Structural block count must be positive"
            );
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Shelter plan has no steps");
        }
        Set<GridPos> targets = new HashSet<>();
        int structural = 0;
        int doors = 0;
        int lights = 0;
        for (int index = 0; index < steps.size(); index++) {
            ShelterBuildStep step = steps.get(index);
            if (step.index() != index || !targets.add(step.target())) {
                throw new IllegalArgumentException(
                        "Shelter steps must be indexed and target unique cells"
                );
            }
            if (step.role().usesStructuralMaterial()) {
                structural++;
            } else if (step.role() == ShelterStepRole.DOOR) {
                doors++;
            } else if (step.role() == ShelterStepRole.LIGHT) {
                lights++;
            }
        }
        if (structural != requiredStructuralBlocks
                || doors != 1
                || lights != 1
                || !steps.stream().anyMatch(step ->
                        step.role() == ShelterStepRole.DOOR
                                && step.target().equals(doorLower))
                || !steps.stream().anyMatch(step ->
                        step.role() == ShelterStepRole.LIGHT
                                && step.target().equals(lightPosition))) {
            throw new IllegalArgumentException(
                    "Shelter plan does not satisfy material, door, and light constraints"
            );
        }
    }

    public int exteriorWidth() {
        return interiorWidth + 2;
    }

    public int exteriorDepth() {
        return interiorDepth + 2;
    }

    public long walkableInteriorVolume() {
        return (long) interiorWidth * interiorDepth * interiorHeight;
    }

    public GridPos doorUpper() {
        return doorLower.above();
    }

    private static String itemId(String value) {
        String checked = Objects.requireNonNull(value, "item id");
        if (!checked.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid item id");
        }
        return checked;
    }
}
