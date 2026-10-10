package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class InteractionSkillParameters {
    private static final int MAX_HORIZONTAL = 29_999_984;
    private static final int MAX_VERTICAL = 2_048;
    private static final Set<String> BLOCK_ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );

    private InteractionSkillParameters() {
    }

    static SkillParameterResult<BreakBlockParameters> parseBreakBlock(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(arguments, BLOCK_ARGUMENTS);
        if (values == null) {
            return invalid("break_block.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new BreakBlockParameters(
                    DimensionRef.parse(values.get("dimension")),
                    blockTarget(values)
            ));
        } catch (RuntimeException exception) {
            return invalid("break_block.invalid_arguments");
        }
    }

    static SkillParameterResult<UseBlockParameters> parseUseBlock(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                union(BLOCK_ARGUMENTS, "hand")
        );
        if (values == null) {
            return invalid("use_block.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new UseBlockParameters(
                    DimensionRef.parse(values.get("dimension")),
                    blockTarget(values),
                    hand(values.get("hand"))
            ));
        } catch (RuntimeException exception) {
            return invalid("use_block.invalid_arguments");
        }
    }

    static SkillParameterResult<AttackEntityParameters> parseAttackEntity(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                Set.of("dimension", "sampleSequence", "observationId")
        );
        if (values == null) {
            return invalid("attack_entity.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new AttackEntityParameters(
                    DimensionRef.parse(values.get("dimension")),
                    nonNegativeLong(values.get("sampleSequence")),
                    values.get("observationId")
            ));
        } catch (RuntimeException exception) {
            return invalid("attack_entity.invalid_arguments");
        }
    }

    static SkillParameterResult<InteractEntityParameters>
            parseInteractEntity(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                Set.of(
                        "dimension",
                        "sampleSequence",
                        "observationId",
                        "hand"
                )
        );
        if (values == null) {
            return invalid("interact_entity.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new InteractEntityParameters(
                            DimensionRef.parse(values.get("dimension")),
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("observationId"),
                            hand(values.get("hand"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("interact_entity.invalid_arguments");
        }
    }

    static SkillParameterResult<UseItemParameters> parseUseItem(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(
                arguments,
                Set.of("dimension", "hand", "holdTicks")
        );
        if (values == null) {
            return invalid("use_item.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new UseItemParameters(
                    DimensionRef.parse(values.get("dimension")),
                    hand(values.get("hand")),
                    integer(values.get("holdTicks"), 0,
                            InteractionSkillPolicy.HARD_MAXIMUM_TIMEOUT_TICKS)
            ));
        } catch (RuntimeException exception) {
            return invalid("use_item.invalid_arguments");
        }
    }

    static SkillParameterResult<ConsumeOwnedFoodParameters>
            parseConsumeOwnedFood(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                Set.of("dimension", "itemId")
        );
        if (values == null) {
            return invalid("consume_owned_food.invalid_arguments");
        }
        try {
            final String itemId = values.get("itemId");
            if (itemId == null
                    || itemId.isBlank()
                    || !itemId.equals(itemId.strip())) {
                return invalid(
                        "consume_owned_food.invalid_arguments"
                );
            }
            return SkillParameterResult.valid(
                    new ConsumeOwnedFoodParameters(
                            DimensionRef.parse(
                                    values.get("dimension")
                            ),
                            itemId
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("consume_owned_food.invalid_arguments");
        }
    }

    private static ObservedBlockTarget blockTarget(
            Map<String, String> values
    ) {
        int x = integer(values.get("x"), -MAX_HORIZONTAL, MAX_HORIZONTAL);
        int y = integer(values.get("y"), -MAX_VERTICAL, MAX_VERTICAL);
        int z = integer(values.get("z"), -MAX_HORIZONTAL, MAX_HORIZONTAL);
        BlockFace face = blockFace(values.get("face"));
        return new ObservedBlockTarget(
                nonNegativeLong(values.get("sampleSequence")),
                x,
                y,
                z,
                face
        );
    }

    private static ActionHand hand(String value) {
        return switch (value) {
            case "main_hand" -> ActionHand.MAIN_HAND;
            case "off_hand" -> ActionHand.OFF_HAND;
            default -> throw new IllegalArgumentException("Invalid hand");
        };
    }

    private static BlockFace blockFace(String value) {
        if (value == null
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid block face");
        }
        return BlockFace.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static int integer(String value, int minimum, int maximum) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)
                || parsed < minimum
                || parsed > maximum) {
            throw new IllegalArgumentException("Integer is outside bounds");
        }
        return parsed;
    }

    private static long nonNegativeLong(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid long");
        }
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value) || parsed < 0) {
            throw new IllegalArgumentException(
                    "Long must be canonical and non-negative"
            );
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
        return values.keySet().equals(names) ? Map.copyOf(values) : null;
    }

    private static Set<String> union(Set<String> names, String extra) {
        var copy = new java.util.HashSet<>(names);
        copy.add(extra);
        return Set.copyOf(copy);
    }

    private static <P> SkillParameterResult<P> invalid(String code) {
        return SkillParameterResult.invalid(code);
    }
}
