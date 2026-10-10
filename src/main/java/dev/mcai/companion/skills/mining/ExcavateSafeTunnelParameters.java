package dev.mcai.companion.skills.mining;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict, bounded public contract for one observation-driven mining run.
 */
public record ExcavateSafeTunnelParameters(
        DimensionRef dimension,
        long sampleSequence,
        TunnelDirection direction,
        TunnelMode mode,
        int maximumSteps,
        int torchInterval,
        String pickaxeItemId,
        List<String> targetBlockIds
) {
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"
    );

    public ExcavateSafeTunnelParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(mode, "mode");
        if (maximumSteps < 1 || maximumSteps > 48) {
            throw new IllegalArgumentException(
                    "maximumSteps must be in [1, 48]"
            );
        }
        if (torchInterval < 4 || torchInterval > 8) {
            throw new IllegalArgumentException(
                    "torchInterval must be in [4, 8]"
            );
        }
        pickaxeItemId = identifier(pickaxeItemId, "pickaxeItemId");
        if (!pickaxeItemId.endsWith("_pickaxe")) {
            throw new IllegalArgumentException(
                    "pickaxeItemId must identify a pickaxe"
            );
        }
        Objects.requireNonNull(targetBlockIds, "targetBlockIds");
        if (targetBlockIds.isEmpty() || targetBlockIds.size() > 8) {
            throw new IllegalArgumentException(
                    "targetBlockIds must contain 1..8 identifiers"
            );
        }
        final Set<String> unique = new LinkedHashSet<>();
        for (String target : targetBlockIds) {
            final String checked = identifier(target, "targetBlockId");
            if (isAir(checked)
                    || isFluid(checked)
                    || !unique.add(checked)) {
                throw new IllegalArgumentException(
                    "targetBlockIds must be unique non-air, non-fluid identifiers"
                );
            }
        }
        targetBlockIds = List.copyOf(unique);
    }

    public boolean isTarget(final String blockId) {
        return targetBlockIds.contains(blockId);
    }

    private static String identifier(
            final String value,
            final String name
    ) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a namespaced identifier"
            );
        }
        return value;
    }

    private static boolean isAir(final String value) {
        return value.equals("minecraft:air")
                || value.equals("minecraft:cave_air")
                || value.equals("minecraft:void_air");
    }

    private static boolean isFluid(final String value) {
        return value.equals("minecraft:water")
                || value.equals("minecraft:lava")
                || value.endsWith(":water")
                || value.endsWith(":lava");
    }
}
