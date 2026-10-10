package dev.mcai.companion.skills.exploration;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ExplorationSkillParameters {
    private static final Set<String> NAMES = Set.of(
            "dimension",
            "targetKind",
            "targetId",
            "maximumDistance",
            "stepDistance"
    );

    private ExplorationSkillParameters() {
    }

    static SkillParameterResult<ExploreForTargetParameters> parse(
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
            return SkillParameterResult.valid(
                    new ExploreForTargetParameters(
                            DimensionRef.parse(values.get("dimension")),
                            SearchTargetKind.parse(
                                    values.get("targetKind")
                            ),
                            values.get("targetId"),
                            integer(values.get("maximumDistance")),
                            integer(values.get("stepDistance"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static int integer(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final int result = Integer.parseInt(value);
        if (!Integer.toString(result).equals(value)) {
            throw new IllegalArgumentException("Non-canonical integer");
        }
        return result;
    }

    private static SkillParameterResult<ExploreForTargetParameters>
    invalid() {
        return SkillParameterResult.invalid(
                ExploreForObservedTargetSkill.NAME
                    + ".invalid_arguments"
        );
    }
}
