package dev.mcai.companion.skills.menu;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for fair, observed vanilla menu transactions.
 */
public final class MenuSkills {
    public static final String TRANSFER_MENU_ITEM =
            "transfer_menu_item";
    public static final String QUICK_MOVE_OBSERVED_SLOT =
            "quick_move_observed_slot";
    public static final String TAKE_MENU_OUTPUT =
            "take_menu_output";
    public static final String SELECT_MENU_OPTION =
            "select_menu_option";
    public static final String CLOSE_MENU = "close_menu";
    public static final String WAIT_FOR_MENU_CHANGE =
            "wait_for_menu_change";
    public static final String SMELT_MENU_BATCH =
            "smelt_menu_batch";

    private MenuSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final MenuSkillActuator actuator
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(actuator, "actuator");
        return registry
                .register(
                        TRANSFER_MENU_ITEM,
                        new MenuOneShotSkill<>(
                                TRANSFER_MENU_ITEM,
                                MenuSkillParameters::parseTransfer,
                                actuator::checkTransfer,
                                actuator::transfer
                        )
                )
                .register(
                        QUICK_MOVE_OBSERVED_SLOT,
                        new MenuOneShotSkill<>(
                                QUICK_MOVE_OBSERVED_SLOT,
                                MenuSkillParameters::parseQuickMove,
                                parameters -> actuator.checkQuickMove(
                                        parameters,
                                        false
                                ),
                                parameters -> actuator.quickMove(
                                        parameters,
                                        false
                                )
                        )
                )
                .register(
                        TAKE_MENU_OUTPUT,
                        new MenuOneShotSkill<>(
                                TAKE_MENU_OUTPUT,
                                MenuSkillParameters::parseTakeOutput,
                                parameters -> actuator.checkQuickMove(
                                        parameters,
                                        true
                                ),
                                parameters -> actuator.quickMove(
                                        parameters,
                                        true
                                )
                        )
                )
                .register(
                        SELECT_MENU_OPTION,
                        new MenuOneShotSkill<>(
                                SELECT_MENU_OPTION,
                                MenuSkillParameters::parseSelectOption,
                                actuator::checkSelectOption,
                                actuator::selectOption
                        )
                )
                .register(
                        CLOSE_MENU,
                        new MenuOneShotSkill<>(
                                CLOSE_MENU,
                                MenuSkillParameters::parseClose,
                                actuator::checkClose,
                                actuator::close
                        )
                )
                .register(
                        WAIT_FOR_MENU_CHANGE,
                        new WaitForMenuChangeSkill(actuator)
                );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final MenuSkillActuator actuator,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames
    ) {
        registerAll(registry, actuator);
        Objects.requireNonNull(expectedPlayerId, "expectedPlayerId");
        Objects.requireNonNull(frames, "frames");
        return registry.register(
                SMELT_MENU_BATCH,
                new SmeltMenuBatchSkill(
                        expectedPlayerId,
                        actuator,
                        frames
                )
        );
    }

    public static String plannerGuide() {
        return """
            Menu skills bind the latest openMenu sampleSequence/containerId/
            stateId; copy observed slot/option IDs exactly.
            transfer_menu_item uses sourceSlot,destinationSlot,count 1..64
            across PLAYER/MENU partitions. quick_move_observed_slot
            shift-clicks; take_menu_output preserves result callbacks.
            select_menu_option uses an observed optionId. wait_for_menu_change
            uses timeoutTicks 1..1200; close_menu closes the bound menu.
            smelt_menu_batch: sampleSequence,inputItemId,outputItemId,count
            [1,64],fuelItemId,fuelCount [1,64]. It requires an open empty
            furnace-family menu and uses normal loading, cook ticks and exact
            observed output. Stale/inexact actions fail safely.
            Open-menu slot roles are affordance hints. Use the observed role,
            never a remembered slot number: FURNACE_INPUT/FUEL/OUTPUT;
            BREWING_BOTTLE/INGREDIENT/FUEL; CRAFTING_GRID/RESULT;
            CARTOGRAPHY_MAP/ADDITION/OUTPUT; SMITHING_TEMPLATE/BASE/
            ADDITION/OUTPUT; GRINDSTONE_INPUT_A/INPUT_B/OUTPUT;
            ANVIL_INPUT/SACRIFICE/OUTPUT; STONECUTTER_INPUT/OUTPUT;
            ENCHANTMENT_ITEM/LAPIS; LOOM_BANNER/DYE/OUTPUT; and
            MERCHANT_PAYMENT_A/PAYMENT_B/OUTPUT. For a container, use
            CONTAINER and PLAYER_INVENTORY roles with the exact observed
            slot. A role never authorizes an unseen container or bypasses
            vanilla recipe, XP, fuel, durability, or ownership checks.
            """;
    }
}
