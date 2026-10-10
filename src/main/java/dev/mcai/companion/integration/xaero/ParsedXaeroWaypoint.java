package dev.mcai.companion.integration.xaero;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A validated but still untrusted display record from Xaero chat.
 *
 * <p>{@link #displayName()} and {@link #initials()} are labels, never model
 * instructions, commands, or authorization. This type has no command,
 * teleport, or world-mutation behavior.</p>
 */
public record ParsedXaeroWaypoint(
        XaeroShareFormat format,
        String displayName,
        String initials,
        int x,
        OptionalInt y,
        int z,
        int colorIndex,
        boolean rotation,
        int yaw,
        String rawTargetDescription,
        String targetDescription,
        XaeroDestinationKind destinationKind,
        Optional<DimensionRef> dimension
) {
    public ParsedXaeroWaypoint {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(initials, "initials");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(rawTargetDescription, "rawTargetDescription");
        Objects.requireNonNull(targetDescription, "targetDescription");
        Objects.requireNonNull(destinationKind, "destinationKind");
        Objects.requireNonNull(dimension, "dimension");

        if (destinationKind == XaeroDestinationKind.EXPLICIT_DIMENSION && dimension.isEmpty()) {
            throw new IllegalArgumentException("Explicit destination must contain a dimension");
        }
        if (destinationKind != XaeroDestinationKind.EXPLICIT_DIMENSION && dimension.isPresent()) {
            throw new IllegalArgumentException("Only explicit destinations may contain a dimension");
        }
    }

    /**
     * Resolves the routing dimension without silently treating an unknown
     * destination as the receiver's current dimension.
     */
    public Optional<DimensionRef> resolveDimension(
            CurrentDimensionPolicy policy,
            DimensionRef callerCurrentDimension
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(callerCurrentDimension, "callerCurrentDimension");
        if (dimension.isPresent()) {
            return dimension;
        }
        if (destinationKind == XaeroDestinationKind.CALLER_CURRENT_DIMENSION
                && policy == CurrentDimensionPolicy.USE_CALLER_CURRENT) {
            return Optional.of(callerCurrentDimension);
        }
        return Optional.empty();
    }
}
