package dev.mcai.companion.skills.portal;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

final class PortalCastSkillParameters {
    private static final Set<String> COMMON = Set.of(
            "dimension",
            "sampleSequence",
            "anchorX",
            "anchorY",
            "anchorZ",
            "axis",
            "operation"
    );
    private static final Set<String> CAST = Set.of(
            "frameIndex",
            "lavaX",
            "lavaY",
            "lavaZ"
    );

    private PortalCastSkillParameters() {
    }

    static SkillParameterResult<CastObservedNetherPortalParameters> parse(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null
                || arguments.size() != COMMON.size()
                    && arguments.size() != COMMON.size() + CAST.size()) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !COMMON.contains(argument.name())
                        && !CAST.contains(argument.name())
                    || values.putIfAbsent(
                        argument.name(),
                        argument.value()
                    ) != null) {
                return invalid();
            }
        }
        try {
            final PortalCastOperation operation =
                    PortalCastOperation.parse(values.get("operation"));
            final boolean casting =
                    operation == PortalCastOperation.CAST_NEXT;
            final Set<String> expected = new java.util.HashSet<>(COMMON);
            if (casting) {
                expected.addAll(CAST);
            }
            if (!values.keySet().equals(expected)) {
                return invalid();
            }
            return SkillParameterResult.valid(
                    new CastObservedNetherPortalParameters(
                            DimensionRef.parse(values.get("dimension")),
                            canonicalLong(values.get("sampleSequence")),
                            new GridPos(
                                    canonicalInt(values.get("anchorX")),
                                    canonicalInt(values.get("anchorY")),
                                    canonicalInt(values.get("anchorZ"))
                            ),
                            PortalBuildAxis.parse(values.get("axis")),
                            operation,
                            casting
                                    ? OptionalInt.of(canonicalInt(
                                        values.get("frameIndex")
                                    ))
                                    : OptionalInt.empty(),
                            casting
                                    ? Optional.of(new GridPos(
                                        canonicalInt(values.get("lavaX")),
                                        canonicalInt(values.get("lavaY")),
                                        canonicalInt(values.get("lavaZ"))
                                    ))
                                    : Optional.empty()
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static int canonicalInt(final String value) {
        final long parsed = canonicalLong(value);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer is outside its bound");
        }
        return (int) parsed;
    }

    private static long canonicalLong(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.strip())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Non-canonical number");
        }
        final long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Non-canonical number");
        }
        return parsed;
    }

    private static SkillParameterResult<CastObservedNetherPortalParameters>
    invalid() {
        return SkillParameterResult.invalid(
                CastObservedNetherPortalSkill.NAME
                        + ".invalid_arguments"
        );
    }
}
