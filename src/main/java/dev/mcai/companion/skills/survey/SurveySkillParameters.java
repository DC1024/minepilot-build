package dev.mcai.companion.skills.survey;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SurveySkillParameters {
    private static final Set<String> REQUIRED_NAMES = Set.of(
            "dimension",
            "horizontalSteps",
            "includeVertical"
    );
    private static final String WAIT_TICKS = "observationWaitTicks";

    private SurveySkillParameters() {
    }

    static SkillParameterResult<SurveySurroundingsParameters> parse(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null
                || (arguments.size() != REQUIRED_NAMES.size()
                && arguments.size() != REQUIRED_NAMES.size() + 1)) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || (!REQUIRED_NAMES.contains(argument.name())
                    && !WAIT_TICKS.equals(argument.name()))
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        if (!values.keySet().containsAll(REQUIRED_NAMES)
                || values.size() > REQUIRED_NAMES.size() + 1) {
            return invalid();
        }
        try {
            final String steps = values.get("horizontalSteps");
            if (steps == null
                    || !steps.equals(steps.trim())
                    || steps.startsWith("+")
                    || steps.length() > 1 && steps.startsWith("0")) {
                return invalid();
            }
            final boolean vertical = switch (
                    values.get("includeVertical")
            ) {
                case "true" -> true;
                case "false" -> false;
                    default -> throw new IllegalArgumentException(
                        "Invalid boolean"
                );
            };
            final int observationWaitTicks = values.containsKey(WAIT_TICKS)
                    ? parseCanonicalInt(values.get(WAIT_TICKS))
                    : SurveySurroundingsParameters
                            .DEFAULT_OBSERVATION_WAIT_TICKS;
            return SkillParameterResult.valid(
                    new SurveySurroundingsParameters(
                            DimensionRef.parse(values.get("dimension")),
                            Integer.parseInt(steps),
                            vertical,
                            observationWaitTicks
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static int parseCanonicalInt(final String value) {
        if (value == null
                || !value.equals(value.trim())
                || value.isEmpty()
                || value.startsWith("+")
                || value.startsWith("-")
                || value.length() > 1 && value.startsWith("0")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return Integer.parseInt(value);
    }

    private static SkillParameterResult<SurveySurroundingsParameters>
            invalid() {
        return SkillParameterResult.invalid(
                "survey_surroundings.invalid_arguments"
        );
    }
}
