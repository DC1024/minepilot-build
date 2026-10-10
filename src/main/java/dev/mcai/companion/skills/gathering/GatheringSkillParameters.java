package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GatheringSkillParameters {
    private static final Set<String> NAMES = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face",
            "blockId",
            "maxBlocks",
            "clusterRadius",
            "toolItemId"
    );

    private GatheringSkillParameters() {
    }

    static SkillParameterResult<GatherVisibleBlockClusterParameters> parse(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(arguments);
        if (values == null) {
            return invalid();
        }
        try {
            long sequence = Long.parseLong(
                    canonicalInteger(values.get("sampleSequence"), false)
            );
            int x = Integer.parseInt(
                    canonicalInteger(values.get("x"), true)
            );
            int y = Integer.parseInt(
                    canonicalInteger(values.get("y"), true)
            );
            int z = Integer.parseInt(
                    canonicalInteger(values.get("z"), true)
            );
            int maximum = Integer.parseInt(
                    canonicalInteger(values.get("maxBlocks"), false)
            );
            double radius = decimal(values.get("clusterRadius"));
            return SkillParameterResult.valid(
                    new GatherVisibleBlockClusterParameters(
                            DimensionRef.parse(values.get("dimension")),
                            new ObservedBlockTarget(
                                    sequence,
                                    x,
                                    y,
                                    z,
                                    BlockFace.valueOf(
                                            values.get("face")
                                                    .toUpperCase(Locale.ROOT)
                                    )
                            ),
                            values.get("blockId"),
                            maximum,
                            radius,
                            values.get("toolItemId")
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static SkillParameterResult<GatherVisibleBlockClusterParameters>
            invalid() {
        return SkillParameterResult.invalid(
                "gather_visible_block_cluster.invalid_arguments"
        );
    }

    private static Map<String, String> exact(
            List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != NAMES.size()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !NAMES.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return null;
            }
        }
        return values.keySet().equals(NAMES) ? Map.copyOf(values) : null;
    }

    private static String canonicalInteger(
            String value,
            boolean signed
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")
                || value.startsWith("-0")
                || value.length() > 1
                && value.startsWith("0")
                || !signed && value.startsWith("-")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return value;
    }

    private static double decimal(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        return parsed == 0.0 ? 0.0 : parsed;
    }
}
