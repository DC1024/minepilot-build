package dev.mcai.companion.skills.menu;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MenuSkillParameters {
    private static final int MAX_SLOT = 4_096;
    private static final Set<String> BINDING_ARGUMENTS = Set.of(
            "sampleSequence",
            "containerId",
            "stateId"
    );

    private MenuSkillParameters() {
    }

    static SkillParameterResult<TransferMenuItemParameters> parseTransfer(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                union(
                        BINDING_ARGUMENTS,
                        "sourceSlot",
                        "destinationSlot",
                        "count"
                )
        );
        if (values == null) {
            return invalid("transfer_menu_item.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new TransferMenuItemParameters(
                            binding(values),
                            integer(
                                    values.get("sourceSlot"),
                                    0,
                                    MAX_SLOT
                            ),
                            integer(
                                    values.get("destinationSlot"),
                                    0,
                                    MAX_SLOT
                            ),
                            integer(values.get("count"), 1, 64)
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("transfer_menu_item.invalid_arguments");
        }
    }

    static SkillParameterResult<ObservedMenuSlotParameters> parseQuickMove(
            final List<SkillArgument> arguments
    ) {
        return parseObservedSlot(
                arguments,
                "quick_move_observed_slot.invalid_arguments"
        );
    }

    static SkillParameterResult<ObservedMenuSlotParameters> parseTakeOutput(
            final List<SkillArgument> arguments
    ) {
        return parseObservedSlot(
                arguments,
                "take_menu_output.invalid_arguments"
        );
    }

    static SkillParameterResult<CloseMenuParameters> parseClose(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                BINDING_ARGUMENTS
        );
        if (values == null) {
            return invalid("close_menu.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new CloseMenuParameters(binding(values))
            );
        } catch (RuntimeException exception) {
            return invalid("close_menu.invalid_arguments");
        }
    }

    static SkillParameterResult<SelectMenuOptionParameters>
            parseSelectOption(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                union(BINDING_ARGUMENTS, "optionId")
        );
        if (values == null) {
            return invalid("select_menu_option.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new SelectMenuOptionParameters(
                            binding(values),
                            integer(values.get("optionId"), 0, MAX_SLOT)
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("select_menu_option.invalid_arguments");
        }
    }

    static SkillParameterResult<WaitForMenuChangeParameters> parseWait(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                union(BINDING_ARGUMENTS, "timeoutTicks")
        );
        if (values == null) {
            return invalid("wait_for_menu_change.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new WaitForMenuChangeParameters(
                            binding(values),
                            integer(
                                    values.get("timeoutTicks"),
                                    1,
                                    1_200
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("wait_for_menu_change.invalid_arguments");
        }
    }

    static SkillParameterResult<SmeltMenuBatchParameters>
            parseSmeltBatch(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                Set.of(
                        "sampleSequence",
                        "inputItemId",
                        "outputItemId",
                        "count",
                        "fuelItemId",
                        "fuelCount"
                )
        );
        if (values == null) {
            return invalid("smelt_menu_batch.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(
                    new SmeltMenuBatchParameters(
                            nonNegativeLong(
                                    values.get("sampleSequence")
                            ),
                            values.get("inputItemId"),
                            values.get("outputItemId"),
                            integer(values.get("count"), 1, 64),
                            values.get("fuelItemId"),
                            integer(
                                    values.get("fuelCount"),
                                    1,
                                    64
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("smelt_menu_batch.invalid_arguments");
        }
    }

    private static SkillParameterResult<ObservedMenuSlotParameters>
            parseObservedSlot(
                    final List<SkillArgument> arguments,
                    final String failure
            ) {
        final Map<String, String> values = exact(
                arguments,
                union(BINDING_ARGUMENTS, "slot")
        );
        if (values == null) {
            return invalid(failure);
        }
        try {
            return SkillParameterResult.valid(
                    new ObservedMenuSlotParameters(
                            binding(values),
                            integer(values.get("slot"), 0, MAX_SLOT)
                    )
            );
        } catch (RuntimeException exception) {
            return invalid(failure);
        }
    }

    private static MenuBinding binding(
            final Map<String, String> values
    ) {
        return new MenuBinding(
                nonNegativeLong(values.get("sampleSequence")),
                integer(values.get("containerId"), 0, Integer.MAX_VALUE),
                integer(values.get("stateId"), 0, 32_767)
        );
    }

    private static int integer(
            final String value,
            final int minimum,
            final int maximum
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)
                || parsed < minimum
                || parsed > maximum) {
            throw new IllegalArgumentException(
                    "Integer is outside bounds"
            );
        }
        return parsed;
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
            throw new IllegalArgumentException(
                    "Long must be canonical and non-negative"
            );
        }
        return parsed;
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
        return values.keySet().equals(names) ? Map.copyOf(values) : null;
    }

    private static Set<String> union(
            final Set<String> names,
            final String... extra
    ) {
        final var copy = new java.util.HashSet<>(names);
        java.util.Collections.addAll(copy, extra);
        return Set.copyOf(copy);
    }

    private static <P> SkillParameterResult<P> invalid(
            final String failure
    ) {
        return SkillParameterResult.invalid(failure);
    }
}
