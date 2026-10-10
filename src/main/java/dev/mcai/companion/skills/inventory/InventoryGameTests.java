package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.perception.CraftingAffordanceSampler;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTest;
import net.minecraftforge.gametest.GameTestDontPrefix;
import net.minecraftforge.gametest.GameTestNamespace;

/**
 * Integration checks against real vanilla menu and recipe implementations.
 *
 * <p>The development-only registrar discovers this holder directly. The
 * fixture class is excluded from both release JARs.</p>
 */
@GameTestNamespace(MinecraftAiCompanion.MOD_ID)
@GameTestDontPrefix
public final class InventoryGameTests {
    private InventoryGameTests() {
    }

    /**
     * Standalone real-Forge gate for recipe advancement and affordance
     * discovery. The main GameTest registrar can expose this method as its own
     * test instance without coupling it to the long embodiment selector.
     *
     * <p>The timed path starts locked and empty, then uses the ordinary
     * {@link ItemEntity#playerTouch} pickup path plus inventory-menu change
     * broadcast. It never calls {@code recipeBook.add}, an unlock helper, or
     * an advancement grant.</p>
     */
    @GameTest(
        name = "natural_recipe_unlock_after_log_pickup",
        structure = "forge:empty3x3x3",
        maxTicks = 100
    )
    public static void naturalRecipeUnlockAfterLogPickup(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        player.containerMenu = player.inventoryMenu;
        player.getInventory().clearContent();
        final BlockPos pickup = helper.absolutePos(
                new BlockPos(2, 2, 2)
        );
        player.teleportTo(
                pickup.getX() + 0.5,
                pickup.getY(),
                pickup.getZ() + 0.5
        );

        final ResourceKey<Recipe<?>> planksKey = ResourceKey.create(
                Registries.RECIPE,
                Identifier.parse("minecraft:oak_planks")
        );
        helper.assertTrue(
                !player.getRecipeBook().contains(planksKey),
                "Fresh test player unexpectedly knew oak planks"
        );
        final ItemEntity droppedLog = new ItemEntity(
                helper.getLevel(),
                player.getX(),
                player.getY(),
                player.getZ(),
                new ItemStack(Items.OAK_LOG)
        );
        helper.assertTrue(
                helper.getLevel().addFreshEntity(droppedLog),
                "Could not spawn the ordinary dropped log"
        );

        helper.runAtTickTime(1, () -> {
            droppedLog.playerTouch(player);
            player.inventoryMenu.broadcastChanges();
        });
        helper.succeedWhen(() -> {
            player.inventoryMenu.broadcastChanges();
            helper.assertTrue(
                    count(player, Items.OAK_LOG) == 1,
                    "The normal pickup path did not collect the log"
            );
            helper.assertTrue(
                    player.getRecipeBook().contains(planksKey),
                    "Normal inventory advancement did not unlock oak planks"
            );
            final var affordances =
                    new CraftingAffordanceSampler()
                            .sample(player)
                            .orElseThrow(() ->
                                helper.assertionException(
                                    "2x2 crafting affordances unavailable"
                                )
                            );
            helper.assertTrue(
                    affordances.recipes().stream().anyMatch(recipe ->
                        recipe.recipeId().equals(
                            "minecraft:oak_planks"
                        )
                        && recipe.outputItemId().equals(
                            "minecraft:oak_planks"
                        )
                        && recipe.outputCount() == 4
                    ),
                    "Unlocked, owned-material oak planks affordance missing"
            );
        });
    }

    public static void vanillaInventoryTransactions(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        player.containerMenu = player.inventoryMenu;
        final BlockPos transactionOrigin = helper.absolutePos(
                new BlockPos(2, 2, 2)
        );
        final Vec3 transactionCenter = Vec3.atCenterOf(transactionOrigin);
        player.teleportTo(
                transactionCenter.x(),
                transactionOrigin.getY(),
                transactionCenter.z()
        );
        final ServerInventorySkillActuator actuator =
                new ServerInventorySkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.of(player)
                );

        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
        unlock(player, "minecraft:oak_planks");
        final CraftRecipeParameters planks = new CraftRecipeParameters(
                "minecraft:oak_planks",
                1
        );
        helper.assertTrue(
                actuator.checkCraft(planks).succeeded(),
                "2x2 oak-planks recipe was not accepted"
        );
        final InventoryOperationResult plankResult =
                actuator.craftOnce(planks);
        helper.assertTrue(
                plankResult.succeeded() && plankResult.affectedCount() == 4,
                "Vanilla 2x2 result click did not produce four planks"
        );
        helper.assertTrue(
                count(player, Items.OAK_LOG) == 0
                        && count(player, Items.OAK_PLANKS) == 4,
                "2x2 crafting did not consume and produce vanilla amounts"
        );

        player.getInventory().setItem(
                1,
                new ItemStack(Items.IRON_HELMET)
        );
        final InventoryOperationResult equip = actuator.equip(
                new EquipItemParameters(
                        "minecraft:iron_helmet",
                        EquipmentTarget.HEAD
                )
        );
        helper.assertTrue(
                equip.succeeded()
                        && player.getItemBySlot(EquipmentSlot.HEAD)
                        .is(Items.IRON_HELMET),
                "Vanilla inventory clicks did not equip the helmet"
        );

        final ItemStack selectedBefore = player.getMainHandItem().copy();
        player.getInventory().setItem(
                Inventory.SLOT_OFFHAND,
                new ItemStack(Items.WATER_BUCKET)
        );
        final InventoryOperationResult handSwap = actuator.equip(
                new EquipItemParameters(
                        "minecraft:water_bucket",
                        EquipmentTarget.MAINHAND
                )
        );
        helper.assertTrue(
                handSwap.succeeded()
                        && player.getMainHandItem().is(
                                Items.WATER_BUCKET
                        )
                        && ItemStack.isSameItemSameComponents(
                                player.getOffhandItem(),
                                selectedBefore
                        ),
                "Vanilla SWAP did not equip an item owned in the offhand"
        );

        player.getInventory().setItem(
                2,
                new ItemStack(Items.COBBLESTONE, 3)
        );
        final InventoryOperationResult drop = actuator.drop(
                new DropItemParameters("minecraft:cobblestone", 2)
        );
        helper.assertTrue(
                drop.succeeded() && count(player, Items.COBBLESTONE) == 1,
                "Vanilla THROW clicks did not remove exactly two items"
        );
        final AABB droppedArea = new AABB(player.blockPosition()).inflate(4.0);
        final int droppedCobblestone = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, droppedArea)
                .stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(Items.COBBLESTONE))
                .mapToInt(ItemStack::getCount)
                .sum();
        helper.assertTrue(
                droppedCobblestone == 2,
                "THROW clicks did not create the expected vanilla item entity"
        );

        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.OAK_PLANKS, 3)
        );
        player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
        final BlockPos table = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(
                table,
                Blocks.CRAFTING_TABLE.defaultBlockState()
        );
        player.teleportTo(
                table.getX() + 0.5,
                table.getY() + 1.0,
                table.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(table)
                        .getMenuProvider(helper.getLevel(), table)
        );
        helper.assertTrue(
                player.containerMenu instanceof CraftingMenu,
                "The real crafting-table menu did not open"
        );
        unlock(player, "minecraft:wooden_pickaxe");
        final CraftRecipeParameters pickaxe = new CraftRecipeParameters(
                "minecraft:wooden_pickaxe",
                1
        );
        helper.assertTrue(
                actuator.checkCraft(pickaxe).succeeded(),
                "3x3 wooden-pickaxe recipe was not accepted"
        );
        final InventoryOperationResult pickaxeResult =
                actuator.craftOnce(pickaxe);
        helper.assertTrue(
                pickaxeResult.succeeded()
                        && count(player, Items.WOODEN_PICKAXE) == 1,
                "Vanilla 3x3 result click did not produce a wooden pickaxe"
        );
        helper.assertTrue(
                count(player, Items.OAK_PLANKS) == 0
                        && count(player, Items.STICK) == 0,
                "3x3 crafting did not consume the exact ingredients"
        );

        player.closeContainer();
    }

    /**
     * A recipe-book lock must not prevent a knowledgeable companion from
     * arranging an ordinary vanilla recipe through real crafting-menu slots.
     * In 26.2 the chest recipe is intentionally hidden until ten inventory
     * slots are occupied, which is common during a clean survival start.
     */
    @GameTest(
        name = "locked_chest_recipe_uses_vanilla_menu_placement",
        structure = "forge:empty3x3x3",
        maxTicks = 40
    )
    public static void lockedChestRecipeUsesVanillaMenuPlacement(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.OAK_PLANKS, 8)
        );
        final BlockPos table = helper.absolutePos(
                new BlockPos(2, 1, 2)
        );
        helper.getLevel().setBlockAndUpdate(
                table,
                Blocks.CRAFTING_TABLE.defaultBlockState()
        );
        player.teleportTo(
                table.getX() + 0.5,
                table.getY() + 1.0,
                table.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(table)
                        .getMenuProvider(helper.getLevel(), table)
        );
        helper.assertTrue(
                player.containerMenu instanceof CraftingMenu,
                "The real crafting-table menu did not open"
        );
        final ResourceKey<Recipe<?>> chestKey = ResourceKey.create(
                Registries.RECIPE,
                Identifier.parse("minecraft:chest")
        );
        helper.assertTrue(
                !player.getRecipeBook().contains(chestKey),
                "The sparse fixture unexpectedly knew the chest recipe"
        );
        final ServerInventorySkillActuator actuator =
                new ServerInventorySkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.of(player)
                );
        final CraftRecipeParameters chest = new CraftRecipeParameters(
                "minecraft:chest",
                1
        );
        helper.assertTrue(
                actuator.checkCraft(chest).succeeded(),
                "A locked but manually craftable chest was rejected"
        );
        final InventoryOperationResult result = actuator.craftOnce(chest);
        helper.assertTrue(
                result.succeeded()
                    && result.affectedCount() == 1
                    && count(player, Items.CHEST) == 1,
                "Vanilla menu placement did not craft the locked chest"
        );
        helper.assertTrue(
                count(player, Items.OAK_PLANKS) == 0,
                "Locked chest crafting did not consume eight planks"
        );
        player.closeContainer();
        helper.succeed();
    }

    private static void unlock(
            final ServerPlayer player,
            final String recipeId
    ) {
        final ResourceKey<Recipe<?>> key = ResourceKey.create(
                Registries.RECIPE,
                Identifier.parse(recipeId)
        );
        final RecipeHolder<?> recipe = player.level()
                .getServer()
                .getRecipeManager()
                .byKey(key)
                .orElseThrow();
        player.getRecipeBook().add(recipe.id());
    }

    private static int count(
            final ServerPlayer player,
            final Item item
    ) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
