package dev.mcai.companion.skills.core;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CoreSkillParameters {
    private static final double MAX_HORIZONTAL = 29_999_984.0;
    private static final double MAX_VERTICAL = 2_048.0;

    private CoreSkillParameters() {
    }

    static SkillParameterResult<MoveToParameters> parseMoveTo(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                Set.of("dimension", "x", "y", "z", "arrivalRadius")
        );
        if (values == null) {
            return SkillParameterResult.invalid("move_to.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new MoveToParameters(
                    DimensionRef.parse(values.get("dimension")),
                    decimal(values.get("x")),
                    decimal(values.get("y")),
                    decimal(values.get("z")),
                    decimal(values.get("arrivalRadius"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("move_to.invalid_arguments");
        }
    }

    static SkillParameterResult<LookAtParameters> parseLookAt(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                Set.of("dimension", "x", "y", "z")
        );
        if (values == null) {
            return SkillParameterResult.invalid("look_at.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new LookAtParameters(
                    DimensionRef.parse(values.get("dimension")),
                    decimal(values.get("x")),
                    decimal(values.get("y")),
                    decimal(values.get("z"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("look_at.invalid_arguments");
        }
    }

    static SkillParameterResult<NoParameters> parseNone(
            List<SkillArgument> arguments
    ) {
        return arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid("safe_idle.invalid_arguments");
    }

    static SkillParameterResult<FollowEntityParameters> parseFollowEntity(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                Set.of(
                        "observationId",
                        "sampleSequence",
                        "followDistance",
                        "lostGraceTicks"
                )
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    "follow_entity.invalid_arguments"
            );
        }
        try {
            String grace = canonicalInteger(
                    values.get("lostGraceTicks")
            );
            String revision = canonicalInteger(
                    values.get("sampleSequence")
            );
            return SkillParameterResult.valid(new FollowEntityParameters(
                    values.get("observationId"),
                    Long.parseLong(revision),
                    decimal(values.get("followDistance")),
                    Integer.parseInt(grace)
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    "follow_entity.invalid_arguments"
            );
        }
    }

    private static String canonicalInteger(String value) {
        if (value == null
                || !value.equals(value.trim())
                || value.isEmpty()
                || value.startsWith("+")
                || (value.length() > 1 && value.startsWith("0"))
                || value.startsWith("-0")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return value;
    }

    static void coordinate(double value, boolean vertical, String name) {
        double maximum = vertical ? MAX_VERTICAL : MAX_HORIZONTAL;
        if (!Double.isFinite(value) || Math.abs(value) > maximum) {
            throw new IllegalArgumentException(name + " is outside world bounds");
        }
    }

    static int floorCoordinate(double value) {
        return (int) Math.floor(value);
    }

    private static double decimal(String value) {
        if (value == null || !value.equals(value.trim()) || value.isEmpty()) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Decimal must be finite");
        }
        return parsed == 0.0 ? 0.0 : parsed;
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
                    || values.putIfAbsent(argument.name(), argument.value()) != null) {
                return null;
            }
        }
        return values.keySet().equals(names) ? Map.copyOf(values) : null;
    }
}
