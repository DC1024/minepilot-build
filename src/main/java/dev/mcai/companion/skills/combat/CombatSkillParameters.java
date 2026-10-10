package dev.mcai.companion.skills.combat;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CombatSkillParameters {
    private static final Set<String> ENGAGE_ARGUMENTS = Set.of(
            "sampleSequence",
            "observationId"
    );
    private static final Set<String> SHOOT_ARGUMENTS = Set.of(
            "sampleSequence",
            "observationId",
            "hand",
            "shots"
    );

    private CombatSkillParameters() {
    }

    static SkillParameterResult<EngageObservedEntityParameters> parseEngage(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(arguments, ENGAGE_ARGUMENTS);
        if (values == null) {
            return SkillParameterResult.invalid(
                    "engage_observed_entity.invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new EngageObservedEntityParameters(
                            canonicalNonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId")
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    "engage_observed_entity.invalid_arguments"
            );
        }
    }

    static SkillParameterResult<ShootObservedEntityParameters> parseShoot(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                SHOOT_ARGUMENTS
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    "shoot_observed_entity.invalid_arguments"
            );
        }
        try {
            final ActionHand hand = switch (values.get("hand")) {
                case "main_hand" -> ActionHand.MAIN_HAND;
                case "off_hand" -> ActionHand.OFF_HAND;
                default -> throw new IllegalArgumentException(
                        "Invalid hand"
                );
            };
            final String shotsValue = values.get("shots");
            final int shots = Integer.parseInt(shotsValue);
            if (!Integer.toString(shots).equals(shotsValue)) {
                throw new IllegalArgumentException(
                        "Invalid shots"
                );
            }
            return SkillParameterResult.valid(
                    new ShootObservedEntityParameters(
                            canonicalNonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            hand,
                            shots
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    "shoot_observed_entity.invalid_arguments"
            );
        }
    }

    private static long canonicalNonNegativeLong(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")
                || (value.length() > 1 && value.startsWith("0"))) {
            throw new IllegalArgumentException("Invalid sample sequence");
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0 || !Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Invalid sample sequence");
        }
        return parsed;
    }

    private static Map<String, String> exact(
            List<SkillArgument> arguments,
            Set<String> names
    ) {
        if (arguments == null || arguments.size() != names.size()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !names.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return null;
            }
        }
        return values.keySet().equals(names)
                ? Map.copyOf(values)
                : null;
    }
}
