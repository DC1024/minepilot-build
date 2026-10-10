package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WaterClutchDescendSkillParameters {
    private static final Set<String> NAMES = Set.of(
            "dimension",
            "x",
            "y",
            "z",
            "arrivalRadius",
            "maximumDropBlocks"
    );

    private WaterClutchDescendSkillParameters() {
    }

    static SkillParameterResult<WaterClutchDescendParameters> parse(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null
                || arguments.size() != NAMES.size()) {
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
            final String maximum =
                    values.get("maximumDropBlocks");
            if (maximum == null
                    || !maximum.equals(maximum.trim())
                    || maximum.startsWith("+")
                    || maximum.length() > 1
                        && maximum.startsWith("0")) {
                return invalid();
            }
            return SkillParameterResult.valid(
                    new WaterClutchDescendParameters(
                            DimensionRef.parse(
                                    values.get("dimension")
                            ),
                            decimal(values.get("x")),
                            decimal(values.get("y")),
                            decimal(values.get("z")),
                            decimal(values.get("arrivalRadius")),
                            Integer.parseInt(maximum)
                    )
            );
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

    private static SkillParameterResult<WaterClutchDescendParameters>
            invalid() {
        return SkillParameterResult.invalid(
                WaterClutchDescendSkill.NAME + ".invalid_arguments"
        );
    }
}
