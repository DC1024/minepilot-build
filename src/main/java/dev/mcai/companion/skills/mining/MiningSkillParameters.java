package dev.mcai.companion.skills.mining;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MiningSkillParameters {
    private static final Set<String> NAMES = Set.of(
            "dimension",
            "sampleSequence",
            "direction",
            "mode",
            "maximumSteps",
            "torchInterval",
            "pickaxeItemId",
            "targetBlockIds"
    );
    private static final String PICKAXE = "pickaxeItemId";
    private static final String OWNED_PICKAXE_ALIAS =
            "ownedPickaxeItemId";
    private static final Set<String> ACCEPTED_NAMES =
            java.util.stream.Stream.concat(
                    NAMES.stream(),
                    java.util.stream.Stream.of(
                            OWNED_PICKAXE_ALIAS
                    )
            ).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private MiningSkillParameters() {
    }

    static SkillParameterResult<ExcavateSafeTunnelParameters> parse(
            final List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != NAMES.size()) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !ACCEPTED_NAMES.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        final boolean canonicalPickaxe =
                values.containsKey(PICKAXE);
        final boolean compatibilityPickaxe =
                values.containsKey(OWNED_PICKAXE_ALIAS);
        if (canonicalPickaxe == compatibilityPickaxe) {
            return invalid();
        }
        if (compatibilityPickaxe) {
            values.put(
                    PICKAXE,
                    values.remove(OWNED_PICKAXE_ALIAS)
            );
        }
        if (!values.keySet().equals(NAMES)) {
            return invalid();
        }
        try {
            return SkillParameterResult.valid(
                    new ExcavateSafeTunnelParameters(
                            DimensionRef.parse(values.get("dimension")),
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            TunnelDirection.parse(
                                    values.get("direction")
                            ),
                            TunnelMode.parse(values.get("mode")),
                            integer(values.get("maximumSteps")),
                            integer(values.get("torchInterval")),
                            values.get(PICKAXE),
                            identifiers(values.get("targetBlockIds"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static List<String> identifiers(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid identifier list");
        }
        final String[] split = value.split(",", -1);
        final List<String> result = new ArrayList<>(split.length);
        for (String entry : split) {
            if (entry.isEmpty() || !entry.equals(entry.trim())) {
                throw new IllegalArgumentException(
                        "Invalid identifier list"
                );
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    private static int integer(final String value) {
        final long parsed = canonicalLong(value);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer overflow");
        }
        return (int) parsed;
    }

    private static long nonNegativeLong(final String value) {
        final long parsed = canonicalLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("Negative value");
        }
        return parsed;
    }

    private static long canonicalLong(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Non-canonical integer");
        }
        return parsed;
    }

    private static SkillParameterResult<ExcavateSafeTunnelParameters>
    invalid() {
        return SkillParameterResult.invalid(
                ExcavateSafeTunnelSkill.NAME + ".invalid_arguments"
        );
    }
}
