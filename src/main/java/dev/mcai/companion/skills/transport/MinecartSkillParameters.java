package dev.mcai.companion.skills.transport;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MinecartSkillParameters {
    private MinecartSkillParameters() {
    }

    static SkillParameterResult<EnterObservedMinecartParameters>
            parseEnter(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                Set.of(
                        "dimension",
                        "sampleSequence",
                        "observationId"
                )
        );
        if (values == null) {
            return invalid(
                    "enter_observed_minecart.invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new EnterObservedMinecartParameters(
                            DimensionRef.parse(
                                    values.get("dimension")
                            ),
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId")
                    )
            );
        } catch (RuntimeException exception) {
            return invalid(
                    "enter_observed_minecart.invalid_arguments"
            );
        }
    }

    static SkillParameterResult<MinecartTravelToParameters>
            parseTravel(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                Set.of(
                        "dimension",
                        "x",
                        "y",
                        "z",
                        "arrivalRadius",
                        "timeoutTicks",
                        "dismountAtArrival"
                )
        );
        if (values == null) {
            return invalid(
                    "minecart_travel_to.invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new MinecartTravelToParameters(
                            DimensionRef.parse(
                                    values.get("dimension")
                            ),
                            decimal(values.get("x")),
                            decimal(values.get("y")),
                            decimal(values.get("z")),
                            decimal(values.get("arrivalRadius")),
                            canonicalInteger(
                                    values.get("timeoutTicks")
                            ),
                            bool(values.get("dismountAtArrival"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid(
                    "minecart_travel_to.invalid_arguments"
            );
        }
    }

    private static Map<String, String> exact(
            final List<SkillArgument> arguments,
            final Set<String> names
    ) {
        if (arguments == null
                || arguments.size() != names.size()) {
            return null;
        }
        final Map<String, String> values = new HashMap<>();
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

    private static long nonNegativeLong(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid long");
        }
        final long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value) || parsed < 0) {
            throw new IllegalArgumentException("Invalid long");
        }
        return parsed;
    }

    private static int canonicalInteger(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return parsed;
    }

    private static double decimal(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        final double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        return parsed == 0.0 ? 0.0 : parsed;
    }

    private static boolean bool(final String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "Invalid boolean"
            );
        };
    }

    private static <P> SkillParameterResult<P> invalid(
            final String code
    ) {
        return SkillParameterResult.invalid(code);
    }
}
