package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One generated node in a mechanism construction/commissioning DAG. */
public record MechanismConstructionStep(
        int index,
        Phase phase,
        Action action,
        String componentRole,
        GridPos target,
        String itemId,
        Set<Integer> dependencies,
        String expectedObservation
) {
    private static final Pattern ITEM = Pattern.compile(
            "(?:|[a-z0-9_.-]+:[a-z0-9_./-]+)"
    );
    private static final Pattern TOKEN = Pattern.compile(
            "[a-z0-9_.-]{1,64}"
    );

    public enum Phase {
        PREPARE,
        INSTALL,
        COMMISSION
    }

    public enum Action {
        EXCAVATE,
        PLACE_FLUID,
        TILL,
        PLANT,
        PROBE
    }

    public MechanismConstructionStep {
        if (index < 0) {
            throw new IllegalArgumentException("Step index is negative");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(action, "action");
        componentRole = token(componentRole, "component role");
        Objects.requireNonNull(target, "target");
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (!ITEM.matcher(itemId).matches()) {
            throw new IllegalArgumentException("Invalid step item id");
        }
        dependencies = Set.copyOf(
                Objects.requireNonNull(dependencies, "dependencies")
        );
        if (dependencies.stream().anyMatch(value ->
                value == null || value < 0 || value >= index
        )) {
            throw new IllegalArgumentException(
                    "Step dependencies must reference earlier nodes"
            );
        }
        expectedObservation = token(
                expectedObservation,
                "expected observation"
        );
        if ((action == Action.PLACE_FLUID
                || action == Action.TILL
                || action == Action.PLANT)
                && itemId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Interactive construction step requires an item"
            );
        }
    }

    private static String token(final String value, final String label) {
        final String checked = Objects.requireNonNull(value, label);
        if (!TOKEN.matcher(checked).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return checked;
    }
}
