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
        final Map<String, String> values = exact(
                arguments,
                Set.of("recipeId", "crafts")
        );
        if (values == null) {
            return SkillParameterResult.invalid("craft_recipe.invalid_arguments");
        }
        try {
            return SkillParameterResult.valid(new CraftRecipeParameters(
                    identifier(values.get("recipeId")),
                    positiveBoundedInteger(values.get("crafts"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid("craft_recipe.invalid_arguments");
        }
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
