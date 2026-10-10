package dev.mcai.companion.skills.sleeping;

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

final class SleepSkillParameters {
    private static final int MAX_HORIZONTAL = 29_999_984;
    private static final int MAX_VERTICAL = 2_048;
    private static final Set<String> ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );

    private SleepSkillParameters() {
    }

    static SkillParameterResult<SleepInObservedBedParameters> parse(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(arguments);
        if (values == null) {
            return invalid();
        }
        try {
            return SkillParameterResult.valid(
                    new SleepInObservedBedParameters(
                            DimensionRef.parse(
                                    values.get("dimension")
                            ),
                            new ObservedBlockTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face(values.get("face"))
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static Map<String, String> exact(
            List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != ARGUMENTS.size()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !ARGUMENTS.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return null;
            }
        }
        return values.keySet().equals(ARGUMENTS)
                ? Map.copyOf(values)
                : null;
    }

    private static BlockFace face(String value) {
        if (value == null
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid face");
        }
        return BlockFace.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static int integer(
            String value,
            int minimum,
            int maximum
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)
                || parsed < minimum
                || parsed > maximum) {
            throw new IllegalArgumentException(
                    "Integer is outside bounds"
            );
        }
        return parsed;
    }

    private static long nonNegativeLong(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid sequence");
        }
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value) || parsed < 0) {
            throw new IllegalArgumentException("Invalid sequence");
        }
        return parsed;
    }

    private static <P> SkillParameterResult<P> invalid() {
        return SkillParameterResult.invalid(
                "sleep_in_observed_bed.invalid_arguments"
        );
    }
}
