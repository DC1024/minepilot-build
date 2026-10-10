package dev.mcai.companion.skills.exploration;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.regex.Pattern;

public record ExploreForTargetParameters(
        DimensionRef dimension,
        SearchTargetKind targetKind,
        String targetId,
        int maximumDistance,
        int stepDistance
) {
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"
    );

    public ExploreForTargetParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(targetKind, "targetKind");
        targetId = Objects.requireNonNull(targetId, "targetId");
        if (!IDENTIFIER.matcher(targetId).matches()
                || maximumDistance < 16
                || maximumDistance > 512
                || stepDistance < 8
                || stepDistance > 32
                || stepDistance > maximumDistance) {
            throw new IllegalArgumentException(
                    "Invalid exploration parameters"
            );
        }
    }
}
