package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

final class InventorySkillParameters {
    private InventorySkillParameters() {
    }

    static SkillParameterResult<EquipItemParameters> parseEquip(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                Set.of("itemId", "slot")
        );
        if (values == null) {
            return SkillParameterResult.invalid("equip_item.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new EquipItemParameters(
                    identifier(values.get("itemId")),
                    EquipmentTarget.parse(values.get("slot"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("equip_item.invalid_arguments");
        }
    }

    static SkillParameterResult<DropItemParameters> parseDrop(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = exact(
                arguments,
                Set.of("itemId", "count")
        );
        if (values == null) {
            return SkillParameterResult.invalid("drop_item.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new DropItemParameters(
                    identifier(values.get("itemId")),
                    positiveBoundedInteger(values.get("count"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("drop_item.invalid_arguments");
        }
    }

    static SkillParameterResult<CraftRecipeParameters> parseCraft(
            final List<SkillArgument> arguments
    ) {
        final Map<String, String> values = requiredWithOptionalCount(
                arguments,
                "recipeId",
                Set.of("crafts", "count")
        );
        if (values == null) {
            return SkillParameterResult.invalid("craft_recipe.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new CraftRecipeParameters(
                    identifier(values.get("recipeId")),
                    positiveBoundedInteger(
                            values.getOrDefault("crafts", "1")
                    )
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("craft_recipe.invalid_arguments");
        }
    }

    /**
     * Accepts one required argument plus at most one optional positive bound
     * supplied under any of {@code aliases}, normalised to {@code crafts}.
     *
     * <p>Providers routinely name the craft count {@code count} and often omit
     * it entirely when they mean "craft once". Requiring the exact pair made
     * correct-intent decisions fail as {@code invalid_skill_arguments}.</p>
     */
    private static Map<String, String> requiredWithOptionalCount(
            final List<SkillArgument> arguments,
            final String required,
            final Set<String> aliases
    ) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null) {
                return null;
            }
            if (required.equals(argument.name())) {
                if (values.putIfAbsent(required, argument.value())
                        != null) {
                    return null;
                }
            } else if (aliases.contains(argument.name())) {
                if (values.putIfAbsent("crafts", argument.value())
                        != null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return values.containsKey(required) ? Map.copyOf(values) : null;
    }

    private static String identifier(final String value) {
        if (value == null || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Identifier is invalid");
        }
        final Identifier parsed = Identifier.tryParse(value);
        if (parsed == null) {
            throw new IllegalArgumentException("Identifier is invalid");
        }
        return parsed.toString();
    }

    private static int positiveBoundedInteger(final String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.charAt(0) == '+') {
            throw new IllegalArgumentException("Integer is invalid");
        }
        final int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 64) {
            throw new IllegalArgumentException("Integer is outside bounds");
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
}
