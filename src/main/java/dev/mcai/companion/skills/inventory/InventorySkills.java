package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;

public final class InventorySkills {
    public static final String EQUIP_ITEM = "equip_item";
    public static final String DROP_ITEM = "drop_item";
    public static final String CRAFT_RECIPE = "craft_recipe";

    private InventorySkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final InventorySkillActuator actuator
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(actuator, "actuator");
        return registry
                .register(EQUIP_ITEM, new EquipItemSkill(actuator))
                .register(DROP_ITEM, new DropItemSkill(actuator))
                .register(CRAFT_RECIPE, new CraftRecipeSkill(actuator));
    }
}
