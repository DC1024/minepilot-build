package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded public contract for gathering one connected, visibly discovered
 * block cluster.
 */
public record GatherVisibleBlockClusterParameters(
        DimensionRef dimension,
        ObservedBlockTarget seed,
        String blockId,
        int maxBlocks,
        double clusterRadius,
        String toolItemId
) {
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"
    );

    public GatherVisibleBlockClusterParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(seed, "seed");
        blockId = identifier(blockId, "blockId");
        toolItemId = identifier(toolItemId, "toolItemId");
        if ("minecraft:air".equals(blockId)) {
            throw new IllegalArgumentException("blockId cannot be air");
        }
        if (maxBlocks < 1 || maxBlocks > 64) {
            throw new IllegalArgumentException(
                    "maxBlocks must be in [1, 64]"
            );
        }
        if (!Double.isFinite(clusterRadius)
                || clusterRadius < 1.0
                || clusterRadius > 16.0) {
            throw new IllegalArgumentException(
                    "clusterRadius must be in [1, 16]"
            );
        }
        coordinate(seed.x(), false, "x");
        coordinate(seed.y(), true, "y");
        coordinate(seed.z(), false, "z");
    }

    public boolean keepsCurrentHand() {
        return "minecraft:air".equals(toolItemId);
    }

    private static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a namespaced identifier"
            );
        }
        return value;
    }

    private static void coordinate(int value, boolean vertical, String name) {
        int maximum = vertical ? 2_048 : 29_999_984;
        if (Math.abs((long) value) > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside world bounds"
            );
        }
    }
}
