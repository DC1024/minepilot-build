package dev.mcai.companion.skills.parkour;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ParkourSkillParameters {
    private static final Set<String> NAMES = Set.of(
            "dimension",
            "x",
            "y",
            "z",
            "arrivalRadius",
            "maxJumps",
            "maxGap"
    );

    private ParkourSkillParameters() {
    }

    static SkillParameterResult<ParkourToParameters> parse(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != NAMES.size()) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !NAMES.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        if (!values.keySet().equals(NAMES)) {
            return invalid();
        }
        try {
            return SkillParameterResult.valid(new ParkourToParameters(
                    DimensionRef.parse(values.get("dimension")),
                    decimal(values.get("x")),
                    decimal(values.get("y")),
                    decimal(values.get("z")),
                    decimal(values.get("arrivalRadius")),
                    integer(values.get("maxJumps")),
                    integer(values.get("maxGap"))
            ));
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static double decimal(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        final double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        return result == 0.0 ? 0.0 : result;
    }

    private static int integer(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")
                || value.length() > 1 && value.startsWith("0")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return Integer.parseInt(value);
    }

    private static SkillParameterResult<ParkourToParameters> invalid() {
        return SkillParameterResult.invalid(
                ParkourToSkill.NAME + ".invalid_arguments"
        );
    }
}
