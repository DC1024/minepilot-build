package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StrongholdSkillParameters {
    private static final Set<String> TRACE_NAMES = Set.of(
            "dimension",
            "sampleSequence",
            "hand"
    );

    private StrongholdSkillParameters() {
    }

    static SkillParameterResult<TraceStrongholdEyeParameters> parseTrace(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null
                || arguments.size() != TRACE_NAMES.size()) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !TRACE_NAMES.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        if (!values.keySet().equals(TRACE_NAMES)) {
            return invalid();
        }
        try {
            final String sequence = values.get("sampleSequence");
            if (sequence == null
                    || !sequence.equals(sequence.trim())
                    || sequence.startsWith("+")
                    || sequence.length() > 1
                        && sequence.startsWith("0")) {
                return invalid();
            }
            final ActionHand hand = switch (values.get("hand")) {
                case "main_hand" -> ActionHand.MAIN_HAND;
                case "off_hand" -> ActionHand.OFF_HAND;
                default -> throw new IllegalArgumentException(
                        "Invalid hand"
                );
            };
            return SkillParameterResult.valid(
                    new TraceStrongholdEyeParameters(
                            DimensionRef.parse(values.get("dimension")),
                            Long.parseLong(sequence),
                            hand
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static SkillParameterResult<TraceStrongholdEyeParameters>
            invalid() {
        return SkillParameterResult.invalid(
                TraceStrongholdEyeSkill.NAME + ".invalid_arguments"
        );
    }
}
