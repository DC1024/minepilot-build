package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillFrameSource;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.LongConsumer;

/**
 * Registration slice for bounded, multi-step early survival transactions.
 */
public final class FoundationCraftingSkills {
    public static final String PREPARE_BASIC_CRAFTING =
            PrepareBasicCraftingSkill.NAME;
    public static final String PREPARE_STONE_TOOLS =
            PrepareStoneToolsSkill.NAME;
    public static final String PREPARE_IRON_TOOLKIT =
            PrepareIronToolkitSkill.NAME;
    public static final String ESTABLISH_FOUNDATION_WORKSTATIONS =
            EstablishFoundationWorkstationsSkill.NAME;
    public static final String PREPARE_FOUNDATION_SHELTER_MATERIALS =
            PrepareFoundationShelterMaterialsSkill.NAME;
    public static final String PREPARE_DISTRIBUTED_LOG_STORAGE =
            PrepareDistributedLogStorageSkill.NAME;
    public static final String PREPARE_CONTAINER_WOOD_DOOR =
            PrepareContainerWoodDoorSkill.NAME;

    private FoundationCraftingSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final ResourceInventorySource resourceInventory,
            final MenuSkillActuator menus,
            final MenuSkillFrameSource menuFrames,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownCraftingTable,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownFurnace,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage,
            final LongConsumer distributedStorageEvidence,
            final LongConsumer containerWoodDoorEvidence
    ) {
        Objects.requireNonNull(registry, "registry");
        registry.register(
                PREPARE_BASIC_CRAFTING,
                new PrepareBasicCraftingSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory
                )
        );
        registry.register(
                PREPARE_STONE_TOOLS,
                new PrepareStoneToolsSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        knownCraftingTable
                )
        );
        registry.register(
                PREPARE_IRON_TOOLKIT,
                new PrepareIronToolkitSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace
                )
        );
        registry.register(
                ESTABLISH_FOUNDATION_WORKSTATIONS,
                new EstablishFoundationWorkstationsSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace,
                        knownStorage
                )
        );
        registry.register(
                PREPARE_DISTRIBUTED_LOG_STORAGE,
                new PrepareDistributedLogStorageSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace,
                        knownStorage,
                        distributedStorageEvidence
                )
        );
        registry.register(
                PREPARE_CONTAINER_WOOD_DOOR,
                new PrepareContainerWoodDoorSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace,
                        knownStorage,
                        containerWoodDoorEvidence
                )
        );
        return registry.register(
                PREPARE_FOUNDATION_SHELTER_MATERIALS,
                new PrepareFoundationShelterMaterialsSkill(
                        playerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace
                )
        );
    }

    public static String plannerGuide() {
        return """
            Foundation compounds take no args: prepare_basic_crafting,
            prepare_stone_tools, prepare_iron_toolkit,
            establish_foundation_workstations,
            prepare_distributed_log_storage,
            prepare_container_wood_door,
            prepare_foundation_shelter_materials. They use bounded legal
            actions. Iron toolkit performs furnace smelting and crafts the
            iron pickaxe/bucket/shield.
            """;
    }
}
