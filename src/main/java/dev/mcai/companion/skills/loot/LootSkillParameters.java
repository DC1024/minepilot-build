package dev.mcai.companion.skills.loot;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LootSkillParameters {
    private static final Set<String> COLLECT_NAMES = Set.of(
            "sampleSequence",
            "observationId",
            "maximumTicks"
    );
    private static final Set<String> ENGAGE_AND_COLLECT_NAMES = Set.of(
            "sampleSequence",
            "observationId",
            "expectedItemId",
            "maximumTicks"
    );
    private static final Set<String> FOOD_ANIMAL_HUNT_NAMES = Set.of(
            "sampleSequence",
            "observationId",
            "expectedItemId",
            "maximumTicks"
    );
    private static final Set<String> NETHER_BLAZE_ROD_NAMES = Set.of(
            "sampleSequence",
            "observationId",
            "maximumTicks"
    );
    private static final Set<String> SHELTERED_ENDER_PEARL_NAMES =
            Set.of(
                    "sampleSequence",
                    "observationId",
                    "maximumTicks"
            );

    private LootSkillParameters() {
    }

    static SkillParameterResult<CollectObservedItemParameters> parse(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                COLLECT_NAMES
        );
        if (values == null) {
            return invalid();
        }
        try {
            return SkillParameterResult.valid(
                    new CollectObservedItemParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            integer(values.get("maximumTicks"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    static SkillParameterResult<EngageAndCollectParameters>
            parseEngageAndCollect(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                ENGAGE_AND_COLLECT_NAMES
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    EngageAndCollectObservedDropSkill.NAME
                        + ".invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new EngageAndCollectParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            values.get("expectedItemId"),
                            integer(values.get("maximumTicks"))
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    EngageAndCollectObservedDropSkill.NAME
                        + ".invalid_arguments"
            );
        }
    }

    static SkillParameterResult<HuntObservedFoodAnimalParameters>
            parseFoodAnimalHunt(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                FOOD_ANIMAL_HUNT_NAMES
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    HuntObservedFoodAnimalSkill.NAME
                        + ".invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new HuntObservedFoodAnimalParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            values.get("expectedItemId"),
                            integer(values.get("maximumTicks"))
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    HuntObservedFoodAnimalSkill.NAME
                        + ".invalid_arguments"
            );
        }
    }

    static SkillParameterResult<AcquireNetherBlazeRodParameters>
            parseNetherBlazeRod(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                NETHER_BLAZE_ROD_NAMES
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    AcquireNetherBlazeRodSkill.NAME
                        + ".invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new AcquireNetherBlazeRodParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            integer(values.get("maximumTicks"))
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    AcquireNetherBlazeRodSkill.NAME
                        + ".invalid_arguments"
            );
        }
    }

    static SkillParameterResult<AcquireShelteredEnderPearlParameters>
            parseShelteredEnderPearl(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                SHELTERED_ENDER_PEARL_NAMES
        );
        if (values == null) {
            return SkillParameterResult.invalid(
                    AcquireShelteredEnderPearlSkill.NAME
                        + ".invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                    new AcquireShelteredEnderPearlParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            integer(values.get("maximumTicks"))
                    )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    AcquireShelteredEnderPearlSkill.NAME
                        + ".invalid_arguments"
            );
        }
    }

    private static Map<String, String> exact(
            final List<SkillArgument> arguments,
            final Set<String> names
    ) {
        if (arguments == null || arguments.size() != names.size()) {
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
                || value.startsWith("+")
                || value.length() > 1 && value.startsWith("0")) {
            throw new IllegalArgumentException("Invalid long");
        }
        final long parsed = Long.parseLong(value);
        if (parsed < 0 || !Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Invalid long");
        }
        return parsed;
    }

    private static int integer(final String value) {
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

    private static SkillParameterResult<CollectObservedItemParameters>
    invalid() {
        return SkillParameterResult.invalid(
                CollectObservedItemSkill.NAME
                    + ".invalid_arguments"
        );
    }
}
