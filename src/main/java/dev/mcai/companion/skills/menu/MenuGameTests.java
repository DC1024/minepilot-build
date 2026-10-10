package dev.mcai.companion.skills.menu;

import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.control.PersistedGoalState;
import dev.mcai.companion.progression.FoundationActionAudit;
import dev.mcai.companion.progression.FoundationFixtureKind;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.world.CompanionWorldData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;

/**
 * Integration checks against real vanilla chest and furnace menus.
 */
public final class MenuGameTests {
    private static final long TEST_SESSION_GENERATION = 77;

    private MenuGameTests() {
    }

    /**
     * Runs the composite M1 smelting skill against a naturally ticking
     * vanilla furnace. Fixture setup supplies ordinary player-owned input and
     * fuel; the skill itself must perform every menu transfer, wait for the
     * real cook time, and take the result.
     */
    public static void naturalSmeltingBatch(
            final GameTestHelper helper
    ) {
        naturalSmeltingBatch(
                helper,
                Items.RAW_IRON,
                "minecraft:raw_iron",
                Items.IRON_INGOT,
                "minecraft:iron_ingot",
                Items.COAL,
                "minecraft:coal",
                "iron"
        );
    }

    /**
     * Proves the no-coal shelter fallback against the actual vanilla recipe,
     * normal furnace ticking and the production menu transaction skill.
     */
    public static void naturalCharcoalBatch(
            final GameTestHelper helper
    ) {
        naturalSmeltingBatch(
                helper,
                Items.OAK_LOG,
                "minecraft:oak_log",
                Items.CHARCOAL,
                "minecraft:charcoal",
                Items.OAK_PLANKS,
                "minecraft:oak_planks",
                "charcoal"
        );
    }

    /**
     * Proves the same observed furnace transaction against a vanilla blast
     * furnace, whose menu and accelerated cook implementation remain the
     * server authority.
     */
    public static void naturalBlastFurnaceBatch(
            final GameTestHelper helper
    ) {
        naturalSmeltingBatch(
                helper,
                Items.RAW_IRON,
                "minecraft:raw_iron",
                Items.IRON_INGOT,
                "minecraft:iron_ingot",
                Items.COAL,
                "minecraft:coal",
                Blocks.BLAST_FURNACE,
                "blast_furnace"
        );
    }

    /**
     * Proves the observed furnace transaction against a vanilla smoker using
     * an ordinary player-owned food item and the real cooked-food recipe.
     */
    public static void naturalSmokerBatch(
            final GameTestHelper helper
    ) {
        naturalSmeltingBatch(
                helper,
                Items.BEEF,
                "minecraft:beef",
                Items.COOKED_BEEF,
                "minecraft:cooked_beef",
                Items.COAL,
                "minecraft:coal",
                Blocks.SMOKER,
                "smoker"
        );
    }

    /**
     * Runs one ordinary Nether-wart brew through the real brewing-stand
     * menu.  The fixture provides water bottles, an ingredient and fuel, but
     * every transfer and the final take use the same observed menu actuator
     * as a model-driven skill would use.  The stand's own ticking code is the
     * authority for the resulting potion and timing.
     */
    public static void naturalBrewingStandBatch(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler = new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );
        final AtomicInteger stage = new AtomicInteger(0);
        final AtomicInteger nextBottle = new AtomicInteger(0);
        final BlockPos brewingPos =
                helper.absolutePos(new BlockPos(8, 1, 8));

        player.getInventory().clearContent();
        for (int slot = 0; slot < 3; slot++) {
            player.getInventory().setItem(
                    slot,
                    PotionContents.createItemStack(
                            Items.POTION,
                            Potions.WATER
                    )
            );
        }
        player.getInventory().setItem(3, new ItemStack(Items.NETHER_WART));
        player.getInventory().setItem(4, new ItemStack(Items.BLAZE_POWDER));
        helper.getLevel().setBlockAndUpdate(
                brewingPos,
                Blocks.BREWING_STAND.defaultBlockState()
        );
        player.teleportTo(
                brewingPos.getX() + 0.5,
                brewingPos.getY() + 1.0,
                brewingPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(brewingPos)
                        .getMenuProvider(helper.getLevel(), brewingPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof BrewingStandMenu,
                "The real brewing stand menu did not open"
        );
        final BrewingStandMenu brewingMenu =
                (BrewingStandMenu) player.containerMenu;
        helper.addCleanup(ignored -> {
            player.closeContainer();
            player.discard();
        });

        helper.onEachTick(() -> {
            publish(sampler, player, currentFrame);
            if (stage.get() == 0) {
                final var observedSlots = currentFrame.get().menu().slots();
                helper.assertTrue(
                        observedSlots.get(0).role().equals("BREWING_BOTTLE")
                                && observedSlots.get(1).role().equals("BREWING_BOTTLE")
                                && observedSlots.get(2).role().equals("BREWING_BOTTLE")
                                && observedSlots.get(3).role().equals("BREWING_INGREDIENT")
                                && observedSlots.get(4).role().equals("BREWING_FUEL"),
                        "Brewing menu roles were not derived from the open vanilla menu"
                );
            }
            final MenuBinding binding = binding(currentFrame.get());
            switch (stage.get()) {
                case 0 -> {
                    final int inventorySlot = nextBottle.getAndIncrement();
                    final MenuOperationResult result = actuator.transfer(
                            new TransferMenuItemParameters(
                                    binding,
                                    brewingMenu.findSlot(
                                            player.getInventory(),
                                            inventorySlot
                                    ).orElseThrow(),
                                    inventorySlot,
                                    1
                            )
                    );
                    helper.assertTrue(
                            result.succeeded(),
                            "Brewing water bottle transfer was rejected"
                    );
                    if (nextBottle.get() == 3) {
                        stage.set(1);
                    }
                }
                case 1 -> {
                    final MenuOperationResult ingredient = actuator.transfer(
                            new TransferMenuItemParameters(
                                    binding,
                                    brewingMenu.findSlot(
                                            player.getInventory(),
                                            3
                                    ).orElseThrow(),
                                    3,
                                    1
                            )
                    );
                    helper.assertTrue(
                            ingredient.succeeded(),
                            "Brewing ingredient transfer was rejected"
                    );
                    publish(sampler, player, currentFrame);
                    final MenuOperationResult fuel = actuator.transfer(
                            new TransferMenuItemParameters(
                                    binding(currentFrame.get()),
                                    brewingMenu.findSlot(
                                            player.getInventory(),
                                            4
                                    ).orElseThrow(),
                                    4,
                                    1
                            )
                    );
                    helper.assertTrue(
                            fuel.succeeded(),
                            "Brewing fuel transfer was rejected"
                    );
                    stage.set(2);
                }
                case 2 -> {
                    boolean brewed = true;
                    for (int slot = 0; slot < 3; slot++) {
                        final ItemStack result = brewingMenu.getSlot(slot)
                                .getItem();
                        final PotionContents contents = result.get(
                                DataComponents.POTION_CONTENTS
                        );
                        brewed &= result.is(Items.POTION)
                                && contents != null
                                && contents.is(Potions.AWKWARD);
                    }
                    helper.assertTrue(
                            brewingMenu.getBrewingTicks() >= 0,
                            "Brewing stand reported an invalid brew timer"
                    );
                    if (!brewed) {
                        return;
                    }
                    stage.set(3);
                }
                case 3 -> {
                    final MenuOperationResult output = actuator.quickMove(
                            new ObservedMenuSlotParameters(binding, 0),
                            false
                    );
                    helper.assertTrue(
                            output.succeeded(),
                            "Brewing first potion did not use output quick-move"
                    );
                    stage.set(4);
                }
                case 4 -> {
                    final MenuOperationResult output = actuator.quickMove(
                            new ObservedMenuSlotParameters(binding, 1),
                            false
                    );
                    helper.assertTrue(
                            output.succeeded(),
                            "Brewing second potion did not use output quick-move"
                    );
                    stage.set(5);
                }
                case 5 -> {
                    final MenuOperationResult output = actuator.quickMove(
                            new ObservedMenuSlotParameters(binding, 2),
                            false
                    );
                    helper.assertTrue(
                            output.succeeded(),
                            "Brewing third potion did not use output quick-move"
                    );
                    helper.assertTrue(
                            countPotion(player, Potions.AWKWARD) == 3
                                    && count(player, Items.NETHER_WART) == 0
                                    && count(player, Items.BLAZE_POWDER) == 0,
                            "Brewing did not consume inputs and return three"
                                + " awkward potions"
                    );
                    helper.succeed();
                    stage.set(6);
                }
                default -> {
                    // The test is complete; cleanup is still handled by the
                    // GameTest lifecycle.
                }
            }
        });
    }

    private static void naturalSmeltingBatch(
            final GameTestHelper helper,
            final Item inputItem,
            final String inputItemId,
            final Item outputItem,
            final String outputItemId,
            final Item fuelItem,
            final String fuelItemId,
            final String label
    ) {
        naturalSmeltingBatch(
                helper,
                inputItem,
                inputItemId,
                outputItem,
                outputItemId,
                fuelItem,
                fuelItemId,
                Blocks.FURNACE,
                label
        );
    }

    private static void naturalSmeltingBatch(
            final GameTestHelper helper,
            final Item inputItem,
            final String inputItemId,
            final Item outputItem,
            final String outputItemId,
            final Item fuelItem,
            final String fuelItemId,
            final net.minecraft.world.level.block.Block furnaceBlock,
            final String label
    ) {
        final ServerPlayer player =
                helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );

        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(inputItem)
        );
        player.getInventory().setItem(
                1,
                new ItemStack(fuelItem)
        );
        final BlockPos furnacePos =
                helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(
                furnacePos,
                furnaceBlock.defaultBlockState()
        );
        player.teleportTo(
                furnacePos.getX() + 0.5,
                furnacePos.getY() + 1.0,
                furnacePos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(furnacePos)
                        .getMenuProvider(
                                helper.getLevel(),
                                furnacePos
                        )
        );
        publish(sampler, player, currentFrame);
        final SmeltMenuBatchParameters parameters =
                new SmeltMenuBatchParameters(
                        currentFrame.get().sampleSequence(),
                        inputItemId,
                        outputItemId,
                        1,
                        fuelItemId,
                        1
                );
        final SmeltMenuBatchSkill skill =
                new SmeltMenuBatchSkill(
                        player.getUUID(),
                        actuator,
                        () -> Optional.ofNullable(
                                currentFrame.get()
                        )
                );
        final long startedAt =
                helper.getLevel().getGameTime();
        final SkillContext initial = new SkillContext(
                0,
                0,
                startedAt,
                true,
                true,
                0.0
        );
        helper.assertTrue(
                skill.preconditions(initial, parameters).isEmpty(),
                "Natural smelting preconditions were rejected"
        );
        skill.start(initial, parameters);
        helper.addCleanup(ignored -> {
            player.closeContainer();
            player.discard();
        });

        helper.onEachTick(() -> {
            publish(sampler, player, currentFrame);
            final long tick = helper.getLevel().getGameTime();
            final SkillTickResult result;
            try {
                result = skill.tick(
                        new SkillContext(
                                0,
                                0,
                                tick,
                                true,
                                true,
                                0.0
                        ),
                        parameters
                );
            } catch (Exception exception) {
                throw helper.assertionException(
                        "Natural smelting skill threw"
                );
            }
            helper.assertTrue(
                    result.status()
                            != SkillTickResult.Status.FAILED,
                    "Natural smelting failed: "
                            + result.failure()
                                    .map(failure -> failure.code())
                                    .orElse("unknown")
            );
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                return;
            }
            final Container furnace = (Container) helper.getLevel()
                    .getBlockEntity(furnacePos);
            helper.assertTrue(
                    count(player, outputItem) == 1
                            && count(player, inputItem) == 0
                            && furnace.getItem(0).isEmpty()
                            && furnace.getItem(2).isEmpty(),
                    "Composite " + label
                            + " smelting did not complete through "
                            + "the real furnace/result slot"
            );
            helper.succeed();
        });
    }

    /**
     * Runs the ordinary map-scaling transaction through the vanilla
     * cartography menu.  The filled map is created by the server's normal map
     * allocator, and the paper/result slots are changed only by menu clicks.
     */
    public static void cartographyTableTransaction(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler = new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );
        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                MapItem.create(
                        helper.getLevel(),
                        0,
                        0,
                        (byte) 0,
                        true,
                        false
                )
        );
        player.getInventory().setItem(1, new ItemStack(Items.PAPER));
        final BlockPos cartographyPos =
                helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(
                cartographyPos,
                Blocks.CARTOGRAPHY_TABLE.defaultBlockState()
        );
        player.teleportTo(
                cartographyPos.getX() + 0.5,
                cartographyPos.getY() + 1.0,
                cartographyPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(cartographyPos)
                        .getMenuProvider(helper.getLevel(), cartographyPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof CartographyTableMenu,
                "The real cartography table menu did not open"
        );
        final CartographyTableMenu menu =
                (CartographyTableMenu) player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                currentFrame.get().menu().slots().get(0).role()
                                .equals("CARTOGRAPHY_MAP")
                        && currentFrame.get().menu().slots().get(1).role()
                                .equals("CARTOGRAPHY_ADDITION")
                        && currentFrame.get().menu().slots().get(2).role()
                                .equals("CARTOGRAPHY_OUTPUT"),
                "Cartography menu roles were not derived from the open vanilla menu"
        );
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        menu.findSlot(player.getInventory(), 0).orElseThrow(),
                        CartographyTableMenu.MAP_SLOT,
                        1
                )).succeeded(),
                "Cartography map input did not use an observed transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        menu.findSlot(player.getInventory(), 1).orElseThrow(),
                        CartographyTableMenu.ADDITIONAL_SLOT,
                        1
                )).succeeded(),
                "Cartography paper input did not use an observed transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                menu.getSlot(CartographyTableMenu.RESULT_SLOT)
                        .getItem()
                        .is(Items.FILLED_MAP),
                "Cartography menu did not expose the vanilla scaled map"
        );
        helper.assertTrue(
                actuator.quickMove(new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        CartographyTableMenu.RESULT_SLOT
                ), true).succeeded()
                        && count(player, Items.FILLED_MAP) == 1
                        && count(player, Items.PAPER) == 0,
                "Cartography result did not return through vanilla quick-move"
        );
        player.closeContainer();
        player.discard();
        helper.succeed();
    }

    /**
     * Runs one stone-to-stone-slab conversion through the vanilla
     * StonecutterMenu.  The recipe button is selected only after it appears
     * in the observed menu frame; the result and its count remain owned by
     * the vanilla menu.
     */
    public static void stonecutterTransaction(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler = new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 2));
        final BlockPos stonecutterPos =
                helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(
                stonecutterPos,
                Blocks.STONECUTTER.defaultBlockState()
        );
        player.teleportTo(
                stonecutterPos.getX() + 0.5,
                stonecutterPos.getY() + 1.0,
                stonecutterPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(stonecutterPos)
                        .getMenuProvider(helper.getLevel(), stonecutterPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof StonecutterMenu,
                "The real stonecutter menu did not open"
        );
        final StonecutterMenu menu = (StonecutterMenu) player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                currentFrame.get().menu().slots().get(0).role()
                                .equals("STONECUTTER_INPUT")
                        && currentFrame.get().menu().slots().get(1).role()
                                .equals("STONECUTTER_OUTPUT"),
                "Stonecutter menu roles were not derived from the open vanilla menu"
        );
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        menu.findSlot(player.getInventory(), 0).orElseThrow(),
                        0,
                        1
                )).succeeded(),
                "Stonecutter input did not use an observed transfer"
        );
        publish(sampler, player, currentFrame);
        final var options = currentFrame.get().menu().options();
        helper.assertTrue(
                !options.isEmpty()
                        && options.stream().allMatch(option ->
                                option.kind().equals("stonecutter_recipe")),
                "Stonecutter recipes were not fairly observed"
        );
        final int optionId = options.getFirst().optionId();
        helper.assertTrue(
                actuator.selectOption(new SelectMenuOptionParameters(
                        binding(currentFrame.get()),
                        optionId
                )).succeeded()
                        && !menu.getSlot(1).getItem().isEmpty(),
                "Observed stonecutter recipe selection was rejected"
        );
        final Item outputItem = menu.getSlot(1).getItem().getItem();
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        1
                ), true).succeeded()
                        && count(player, outputItem) > 0
                        && count(player, Items.STONE) == 1,
                "Stonecutter result did not return through vanilla output"
        );
        player.closeContainer();
        player.discard();
        helper.succeed();
    }

    /**
     * Exercises a storage block through the same observed-slot contract as
     * the model-facing container skill.  The block entity owns its inventory;
     * every mutation below is an ordinary menu click.
     */
    public static void barrelTransaction(
            final GameTestHelper helper
    ) {
        storageContainerTransaction(helper, Blocks.BARREL, "barrel");
    }

    /**
     * A shulker box is a portable storage container, but its server menu must
     * still obey the same stale-frame and partition checks as a chest/barrel.
     */
    public static void shulkerBoxTransaction(
            final GameTestHelper helper
    ) {
        storageContainerTransaction(
                helper,
                Blocks.SHULKER_BOX,
                "shulker_box"
        );
    }

    /**
     * Hoppers expose a five-slot vanilla container and are the storage side
     * of many redstone item-routing builds.  Keep the transaction on the
     * observed menu path so the executor proves it can use a hopper without
     * writing its block entity directly.
     */
    public static void hopperTransaction(
            final GameTestHelper helper
    ) {
        storageContainerTransaction(helper, Blocks.HOPPER, "hopper");
    }

    /**
     * A dispenser is both a redstone device and a nine-slot container.  This
     * gate only covers its legal menu inventory transaction; firing and
     * redstone timing remain separate physical skills.
     */
    public static void dispenserTransaction(
            final GameTestHelper helper
    ) {
        storageContainerTransaction(helper, Blocks.DISPENSER, "dispenser");
    }

    /**
     * Ender chests expose the player's EnderChestInventory through a vanilla
     * menu; the block entity itself is not the storage owner.  Keep that
     * distinction explicit while retaining the same observed transfer path.
     */
    public static void enderChestTransaction(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler = new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
        player.getEnderChestInventory().setItem(
                0,
                new ItemStack(Items.COBBLESTONE, 4)
        );
        final BlockPos enderChestPos =
                helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(
                enderChestPos,
                Blocks.ENDER_CHEST.defaultBlockState()
        );
        player.teleportTo(
                enderChestPos.getX() + 0.5,
                enderChestPos.getY() + 1.0,
                enderChestPos.getZ() + 0.5
        );
        helper.getLevel()
                .getBlockState(enderChestPos)
                .useWithoutItem(
                        helper.getLevel(),
                        player,
                        new BlockHitResult(
                                new Vec3(
                                        enderChestPos.getX() + 0.5D,
                                        enderChestPos.getY() + 0.5D,
                                        enderChestPos.getZ() + 0.5D
                                ),
                                Direction.UP,
                                enderChestPos,
                                false
                        )
                );
        final AbstractContainerMenu menu = player.containerMenu;
        final Container enderInventory = player.getEnderChestInventory();
        helper.assertTrue(
                menu != player.inventoryMenu,
                "The ender chest menu did not open"
        );
        publish(sampler, player, currentFrame);
        final int inventorySource = menu.findSlot(
                player.getInventory(),
                0
        ).orElseThrow();
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        inventorySource,
                        1,
                        2
                )).succeeded()
                        && enderInventory.getItem(1).is(Items.OAK_LOG)
                        && enderInventory.getItem(1).getCount() == 2,
                "Observed ender chest deposit did not use an exact transfer"
        );
        publish(sampler, player, currentFrame);
        final int inventoryTarget = menu.findSlot(
                player.getInventory(),
                1
        ).orElseThrow();
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        0,
                        inventoryTarget,
                        2
                )).succeeded()
                        && enderInventory.getItem(0).getCount() == 2
                        && player.getInventory().getItem(1).is(Items.COBBLESTONE)
                        && player.getInventory().getItem(1).getCount() == 2,
                "Observed ender chest withdrawal did not use an exact transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        1
                ), false).succeeded()
                        && enderInventory.getItem(1).isEmpty()
                        && count(player, Items.OAK_LOG) == 2,
                "Vanilla ender chest quick-move did not return the deposit"
        );
        player.closeContainer();
        player.discard();
        helper.succeed();
    }

    private static void storageContainerTransaction(
            final GameTestHelper helper,
            final Block containerBlock,
            final String label
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final FairPerceptionSampler sampler = new FairPerceptionSampler();
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        () -> Optional.ofNullable(currentFrame.get()),
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION
                );
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
        final BlockPos containerPos =
                helper.absolutePos(new BlockPos(8, 1, 8));
        helper.getLevel().setBlockAndUpdate(
                containerPos,
                containerBlock.defaultBlockState()
        );
        final Container container = (Container) helper.getLevel()
                .getBlockEntity(containerPos);
        helper.assertTrue(
                container != null,
                "The " + label + " block entity did not create a container"
        );
        container.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        player.teleportTo(
                containerPos.getX() + 0.5,
                containerPos.getY() + 1.0,
                containerPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(containerPos)
                        .getMenuProvider(helper.getLevel(), containerPos)
        );
        final AbstractContainerMenu menu = player.containerMenu;
        helper.assertTrue(
                menu != player.inventoryMenu,
                "The " + label + " menu did not open"
        );
        publish(sampler, player, currentFrame);
        final int inventorySource = menu.findSlot(
                player.getInventory(),
                0
        ).orElseThrow();
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        inventorySource,
                        1,
                        2
                )).succeeded()
                        && container.getItem(1).is(Items.OAK_LOG)
                        && container.getItem(1).getCount() == 2,
                "Observed " + label + " deposit did not use an exact transfer"
        );
        publish(sampler, player, currentFrame);
        final int inventoryTarget = menu.findSlot(
                player.getInventory(),
                1
        ).orElseThrow();
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        0,
                        inventoryTarget,
                        2
                )).succeeded()
                        && container.getItem(0).getCount() == 2
                        && player.getInventory().getItem(1).is(Items.COBBLESTONE)
                        && player.getInventory().getItem(1).getCount() == 2,
                "Observed " + label + " withdrawal did not use an exact transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        1
                ), false).succeeded()
                        && container.getItem(1).isEmpty()
                        && count(player, Items.OAK_LOG) == 2,
                "Vanilla " + label + " quick-move did not return the deposit"
        );
        player.closeContainer();
        player.discard();
        helper.succeed();
    }

    public static void vanillaMenuTransactions(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = helper.makeMockServerPlayer(false);
        player.initInventoryMenu();
        final AtomicReference<MenuSkillFrame> currentFrame =
                new AtomicReference<>();
        final AtomicReference<MenuSkillFrame> retainedFrame =
                new AtomicReference<>();
        final MenuSkillFrameSource frameSource =
                new MenuSkillFrameSource() {
                    @Override
                    public Optional<MenuSkillFrame> current() {
                        return Optional.ofNullable(
                                currentFrame.get()
                        );
                    }

                    @Override
                    public Optional<MenuSkillFrame> retained(
                            final long sampleSequence
                    ) {
                        final MenuSkillFrame retained =
                                retainedFrame.get();
                        if (retained != null
                                && retained.sampleSequence()
                                    == sampleSequence) {
                            return Optional.of(retained);
                        }
                        return current().filter(frame ->
                                frame.sampleSequence()
                                    == sampleSequence
                        );
                    }
                };
        final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        final CompanionWorldData foundationData =
                new CompanionWorldData();
        foundationData.updateGoalState(new PersistedGoalState(
                0L,
                Optional.of(UUID.randomUUID()),
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "Please handle that useful setup we discussed.",
                GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target.STONE_TOOL_OBTAINED
                ).detailCode(),
                Instant.EPOCH,
                false
        ));
        final ServerMenuSkillActuator actuator =
                new ServerMenuSkillActuator(
                        helper.getLevel().getServer(),
                        player.getUUID(),
                        frameSource,
                        () -> Optional.of(player),
                        () -> TEST_SESSION_GENERATION,
                        new FoundationActionAudit(foundationData)
                );

        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.OAK_LOG, 8)
        );
        final BlockPos chestPos =
                helper.absolutePos(new BlockPos(6, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                chestPos,
                Blocks.CHEST.defaultBlockState()
        );
        final Container chest = (Container) helper.getLevel()
                .getBlockEntity(chestPos);
        foundationData.recordFoundationFixture(
                0L,
                FoundationFixtureKind.STORAGE,
                new VerifiedFixtureLocation(
                        helper.getLevel().dimension()
                                .identifier()
                                .toString(),
                        chestPos.getX(),
                        chestPos.getY(),
                        chestPos.getZ()
                )
        );
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        player.teleportTo(
                chestPos.getX() + 0.5,
                chestPos.getY() + 1.0,
                chestPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(chestPos)
                        .getMenuProvider(helper.getLevel(), chestPos)
        );
        final AbstractContainerMenu chestMenu = player.containerMenu;
        final int logSource = chestMenu.findSlot(
                player.getInventory(),
                0
        ).orElseThrow();
        publish(sampler, player, currentFrame);
        retainedFrame.set(currentFrame.get());
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                currentFrame.get().sampleSequence()
                    > retainedFrame.get().sampleSequence(),
                "Menu sample did not advance during simulated model latency"
        );
        final MenuBinding chestBinding =
                binding(retainedFrame.get());

        final MenuOperationResult deposit = actuator.transfer(
                new TransferMenuItemParameters(
                        chestBinding,
                        logSource,
                        1,
                        3
                )
        );
        helper.assertTrue(
                deposit.succeeded()
                        && player.getInventory()
                                .getItem(0)
                                .getCount() == 5
                        && chest.getItem(1).is(Items.OAK_LOG)
                        && chest.getItem(1).getCount() == 3
                        && foundationData
                                .verifiedFoundationEvidence(0L)
                                .orElseThrow()
                                .suppliesDeposited(),
                "Observed chest transaction did not deposit exactly three"
        );

        publish(sampler, player, currentFrame);
        final int emptyInventorySlot = chestMenu.findSlot(
                player.getInventory(),
                1
        ).orElseThrow();
        final MenuOperationResult withdraw = actuator.transfer(
                new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        1,
                        emptyInventorySlot,
                        2
                )
        );
        helper.assertTrue(
                withdraw.succeeded()
                        && chest.getItem(1).getCount() == 1
                        && player.getInventory()
                                .getItem(1)
                                .getCount() == 2,
                "Observed chest transaction did not withdraw exactly two"
        );

        publish(sampler, player, currentFrame);
        final MenuOperationResult quickMove = actuator.quickMove(
                new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        0
                ),
                false
        );
        helper.assertTrue(
                quickMove.succeeded()
                        && chest.getItem(0).isEmpty()
                        && count(player, Items.COBBLESTONE) == 4,
                "Vanilla quick-move did not transfer the chest stack"
        );

        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.close(
                        new CloseMenuParameters(
                                binding(currentFrame.get())
                        )
                ).succeeded(),
                "Observed chest menu did not close"
        );

        player.getInventory().setItem(
                2,
                new ItemStack(Items.RAW_IRON, 2)
        );
        player.getInventory().setItem(
                3,
                new ItemStack(Items.COAL, 2)
        );
        final BlockPos furnacePos =
                helper.absolutePos(new BlockPos(8, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                furnacePos,
                Blocks.FURNACE.defaultBlockState()
        );
        final Container furnace = (Container) helper.getLevel()
                .getBlockEntity(furnacePos);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
        player.teleportTo(
                furnacePos.getX() + 0.5,
                furnacePos.getY() + 1.0,
                furnacePos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(furnacePos)
                        .getMenuProvider(helper.getLevel(), furnacePos)
        );
        final AbstractContainerMenu furnaceMenu = player.containerMenu;

        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        furnaceMenu.findSlot(
                                player.getInventory(),
                                2
                        ).orElseThrow(),
                        0,
                        2
                )).succeeded()
                        && furnace.getItem(0).getCount() == 2,
                "Furnace input loading did not use an exact menu transfer"
        );

        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        furnaceMenu.findSlot(
                                player.getInventory(),
                                3
                        ).orElseThrow(),
                        1,
                        1
                )).succeeded()
                        && furnace.getItem(1).getCount() == 1,
                "Furnace fuel loading did not use an exact menu transfer"
        );

        publish(sampler, player, currentFrame);
        final MenuOperationResult output = actuator.quickMove(
                new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        2
                ),
                true
        );
        helper.assertTrue(
                output.succeeded()
                        && furnace.getItem(2).isEmpty()
                        && count(player, Items.IRON_INGOT) == 1,
                "Furnace output did not pass through vanilla quick-move"
        );
        player.closeContainer();

        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.STONE, 2)
        );
        final BlockPos stonecutterPos =
                helper.absolutePos(new BlockPos(10, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                stonecutterPos,
                Blocks.STONECUTTER.defaultBlockState()
        );
        player.teleportTo(
                stonecutterPos.getX() + 0.5,
                stonecutterPos.getY() + 1.0,
                stonecutterPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(stonecutterPos)
                        .getMenuProvider(
                                helper.getLevel(),
                                stonecutterPos
                        )
        );
        helper.assertTrue(
                player.containerMenu instanceof StonecutterMenu,
                "The real stonecutter menu did not open"
        );
        final AbstractContainerMenu stonecutterMenu =
                player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        stonecutterMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        0,
                        1
                )).succeeded(),
                "Stonecutter input did not use an observed menu transfer"
        );

        publish(sampler, player, currentFrame);
        final var options = currentFrame.get().menu().options();
        helper.assertTrue(
                !options.isEmpty()
                        && options.stream().allMatch(option ->
                                option.kind().equals(
                                        "stonecutter_recipe"
                                )
                        ),
                "Open stonecutter options were not fairly observed"
        );
        final int optionId = options.getFirst().optionId();
        helper.assertTrue(
                actuator.selectOption(new SelectMenuOptionParameters(
                        binding(currentFrame.get()),
                        optionId
                )).succeeded()
                        && !stonecutterMenu.getSlot(1)
                                .getItem()
                                .isEmpty(),
                "Vanilla stonecutter button selection was rejected"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                1
                        ),
                        true
                ).succeeded(),
                "Selected stonecutter result did not use vanilla output"
        );
        player.closeContainer();

        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.EMERALD, 2)
        );
        final var villager = helper.spawn(
                EntityTypes.VILLAGER,
                new Vec3(12.5, 2.0, 2.5)
        );
        villager.getOffers().clear();
        villager.getOffers().add(new MerchantOffer(
                new ItemCost(Items.EMERALD, 2),
                new ItemStack(Items.BREAD, 3),
                12,
                1,
                0.0F
        ));
        player.teleportTo(
                villager.getX(),
                villager.getY(),
                villager.getZ() + 1.5
        );
        villager.setTradingPlayer(player);
        villager.openTradingScreen(
                player,
                villager.getDisplayName(),
                1
        );
        helper.assertTrue(
                player.containerMenu instanceof MerchantMenu,
                "The real villager merchant menu did not open"
        );
        publish(sampler, player, currentFrame);
        final var merchantOptions =
                currentFrame.get().menu().options();
        helper.assertTrue(
                merchantOptions.size() == 1
                        && merchantOptions.getFirst()
                                .kind()
                                .equals("merchant_offer")
                        && merchantOptions.getFirst()
                                .properties()
                                .get("costAItem")
                                .equals("minecraft:emerald")
                        && merchantOptions.getFirst()
                                .properties()
                                .get("resultItem")
                                .equals("minecraft:bread"),
                "Villager costs/results were not fairly observed"
        );
        helper.assertTrue(
                actuator.selectOption(
                        new SelectMenuOptionParameters(
                                binding(currentFrame.get()),
                                0
                        )
                ).succeeded()
                        && player.containerMenu
                                .getSlot(2)
                                .getItem()
                                .is(Items.BREAD),
                "Observed villager offer selection did not move payment"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                2
                        ),
                        true
                ).succeeded()
                        && count(player, Items.EMERALD) == 0
                        && count(player, Items.BREAD) == 3
                        && villager.getOffers()
                                .getFirst()
                                .getUses() == 1,
                "Villager trade did not complete through vanilla output"
        );
        player.closeContainer();
        villager.discard();

        /*
         * Enchanting is deliberately exercised through the same generic
         * observe/select/take path as the other workstations.  The fixture
         * only supplies a sword, lapis and XP; the vanilla menu remains the
         * authority for the offered enchantments, costs and result.
         */
        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.DIAMOND_SWORD)
        );
        player.getInventory().setItem(
                1,
                new ItemStack(Items.LAPIS_LAZULI, 3)
        );
        player.experienceLevel = 30;
        final BlockPos enchantingPos =
                helper.absolutePos(new BlockPos(14, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                enchantingPos,
                Blocks.ENCHANTING_TABLE.defaultBlockState()
        );
        // Keep the bookshelf ring ordinary and local; no fixed structure or
        // hidden world data is used by the transaction.
        helper.getLevel().setBlockAndUpdate(
                helper.absolutePos(new BlockPos(12, 1, 2)),
                Blocks.BOOKSHELF.defaultBlockState()
        );
        helper.getLevel().setBlockAndUpdate(
                helper.absolutePos(new BlockPos(16, 1, 2)),
                Blocks.BOOKSHELF.defaultBlockState()
        );
        helper.getLevel().setBlockAndUpdate(
                helper.absolutePos(new BlockPos(14, 1, 0)),
                Blocks.BOOKSHELF.defaultBlockState()
        );
        helper.getLevel().setBlockAndUpdate(
                helper.absolutePos(new BlockPos(14, 1, 4)),
                Blocks.BOOKSHELF.defaultBlockState()
        );
        player.teleportTo(
                enchantingPos.getX() + 0.5,
                enchantingPos.getY() + 1.0,
                enchantingPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(enchantingPos)
                        .getMenuProvider(
                                helper.getLevel(),
                                enchantingPos
                        )
        );
        helper.assertTrue(
                player.containerMenu instanceof EnchantmentMenu,
                "The real enchanting table menu did not open"
        );
        final AbstractContainerMenu enchantmentMenu =
                player.containerMenu;
        publish(sampler, player, currentFrame);
        final MenuBinding emptyEnchantmentBinding =
                binding(currentFrame.get());
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        emptyEnchantmentBinding,
                        enchantmentMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        0,
                        1
                )).succeeded(),
                "Enchanting item input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        enchantmentMenu.findSlot(
                                player.getInventory(),
                                1
                        ).orElseThrow(),
                        1,
                        3
                )).succeeded(),
                "Enchanting lapis input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        final var enchantmentOptions = currentFrame.get()
                .menu()
                .options();
        helper.assertTrue(
                enchantmentOptions.stream().anyMatch(option ->
                        option.kind().equals("enchantment")
                                && option.available()),
                "The enchanting table did not expose an available observed option"
        );
        final int enchantmentOption = enchantmentOptions.stream()
                .filter(option -> option.kind().equals("enchantment")
                        && option.available())
                .findFirst()
                .orElseThrow()
                .optionId();
        helper.assertTrue(
                actuator.selectOption(new SelectMenuOptionParameters(
                        binding(currentFrame.get()),
                        enchantmentOption
                )).succeeded()
                        && enchantmentMenu.getSlot(0)
                                .getItem()
                                .isEnchanted(),
                "Observed enchanting option did not enchant the vanilla input"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(
                new ObservedMenuSlotParameters(
                        binding(currentFrame.get()),
                        0
                ),
                        false
                ).succeeded()
                        && countEnchanted(player, Items.DIAMOND_SWORD) == 1,
                "Enchanted output did not return through vanilla quick-move"
        );
        player.closeContainer();

        /*
         * Loom pattern selection uses the same fair menu contract.  A banner
         * and dye are ordinary player-owned inputs; the selectable pattern
         * list and output remain entirely controlled by the vanilla LoomMenu.
         */
        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.BANNER.white())
        );
        player.getInventory().setItem(
                1,
                new ItemStack(Items.DYE.blue())
        );
        final BlockPos loomPos =
                helper.absolutePos(new BlockPos(18, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                loomPos,
                Blocks.LOOM.defaultBlockState()
        );
        player.teleportTo(
                loomPos.getX() + 0.5,
                loomPos.getY() + 1.0,
                loomPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(loomPos)
                        .getMenuProvider(helper.getLevel(), loomPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof LoomMenu,
                "The real loom menu did not open"
        );
        final AbstractContainerMenu loomMenu = player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        loomMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        0,
                        1
                )).succeeded(),
                "Loom banner input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        loomMenu.findSlot(
                                player.getInventory(),
                                1
                        ).orElseThrow(),
                        1,
                        1
                )).succeeded(),
                "Loom dye input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        final var loomOptions = currentFrame.get().menu().options();
        helper.assertTrue(
                loomOptions.stream().anyMatch(option ->
                        option.kind().equals("loom_pattern")
                                && option.available()),
                "The loom did not expose an observed pattern option"
        );
        final int loomOption = loomOptions.stream()
                .filter(option -> option.kind().equals("loom_pattern")
                        && option.available())
                .findFirst()
                .orElseThrow()
                .optionId();
        helper.assertTrue(
                actuator.selectOption(new SelectMenuOptionParameters(
                        binding(currentFrame.get()),
                        loomOption
                )).succeeded()
                        && !loomMenu.getSlot(3).getItem().isEmpty(),
                "Observed loom pattern did not produce a vanilla output"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                3
                        ),
                        true
                ).succeeded()
                        && count(player, Items.BANNER.white()) == 1,
                "Loom output did not return through vanilla output quick-move"
        );
        player.closeContainer();

        /*
         * Smithing has no button options, but its three input slots and
         * result slot are still a normal observed menu transaction.  The
         * vanilla recipe decides whether these player-owned components can
         * produce a Netherite sword.
         */
        player.getInventory().clearContent();
        player.getInventory().setItem(
                0,
                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
        );
        player.getInventory().setItem(
                1,
                new ItemStack(Items.DIAMOND_SWORD)
        );
        player.getInventory().setItem(
                2,
                new ItemStack(Items.NETHERITE_INGOT)
        );
        final BlockPos smithingPos =
                helper.absolutePos(new BlockPos(22, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                smithingPos,
                Blocks.SMITHING_TABLE.defaultBlockState()
        );
        player.teleportTo(
                smithingPos.getX() + 0.5,
                smithingPos.getY() + 1.0,
                smithingPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(smithingPos)
                        .getMenuProvider(helper.getLevel(), smithingPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof SmithingMenu,
                "The real smithing table menu did not open"
        );
        final AbstractContainerMenu smithingMenu = player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                currentFrame.get().menu().slots().get(0).role()
                                .equals("SMITHING_TEMPLATE")
                        && currentFrame.get().menu().slots().get(1).role()
                                .equals("SMITHING_BASE")
                        && currentFrame.get().menu().slots().get(2).role()
                                .equals("SMITHING_ADDITION")
                        && currentFrame.get().menu().slots().get(3).role()
                                .equals("SMITHING_OUTPUT"),
                "Smithing menu roles were not derived from the open vanilla menu"
        );
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        smithingMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        SmithingMenu.TEMPLATE_SLOT,
                        1
                )).succeeded(),
                "Smithing template did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        smithingMenu.findSlot(
                                player.getInventory(),
                                1
                        ).orElseThrow(),
                        SmithingMenu.BASE_SLOT,
                        1
                )).succeeded(),
                "Smithing base item did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        smithingMenu.findSlot(
                                player.getInventory(),
                                2
                        ).orElseThrow(),
                        SmithingMenu.ADDITIONAL_SLOT,
                        1
                )).succeeded(),
                "Smithing addition did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                smithingMenu.getSlot(SmithingMenu.RESULT_SLOT)
                        .getItem()
                        .is(Items.NETHERITE_SWORD),
                "Smithing menu did not expose the vanilla Netherite result"
        );
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                SmithingMenu.RESULT_SLOT
                        ),
                        true
                ).succeeded()
                        && count(player, Items.NETHERITE_SWORD) == 1
                        && count(player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) == 0
                        && count(player, Items.DIAMOND_SWORD) == 0
                        && count(player, Items.NETHERITE_INGOT) == 0,
                "Smithing result did not return through vanilla output quick-move"
        );
        player.closeContainer();

        /*
         * Grindstone repair is another output-slot transaction with no
         * client-provided recipe.  Two player-owned damaged swords are
         * transferred after observation; the vanilla GrindstoneMenu decides
         * the repaired result and consumes both inputs when the observed
         * result slot is taken.
         */
        player.getInventory().clearContent();
        final ItemStack damagedSword = new ItemStack(Items.DIAMOND_SWORD);
        damagedSword.setDamageValue(80);
        final ItemStack secondDamagedSword = new ItemStack(Items.DIAMOND_SWORD);
        secondDamagedSword.setDamageValue(120);
        player.getInventory().setItem(0, damagedSword);
        player.getInventory().setItem(1, secondDamagedSword);
        final BlockPos grindstonePos =
                helper.absolutePos(new BlockPos(26, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                grindstonePos,
                Blocks.GRINDSTONE.defaultBlockState()
        );
        player.teleportTo(
                grindstonePos.getX() + 0.5,
                grindstonePos.getY() + 1.0,
                grindstonePos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(grindstonePos)
                        .getMenuProvider(helper.getLevel(), grindstonePos)
        );
        helper.assertTrue(
                player.containerMenu instanceof GrindstoneMenu,
                "The real grindstone menu did not open"
        );
        final AbstractContainerMenu grindstoneMenu = player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        grindstoneMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        0,
                        1
                )).succeeded(),
                "Grindstone first input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        grindstoneMenu.findSlot(
                                player.getInventory(),
                                1
                        ).orElseThrow(),
                        1,
                        1
                )).succeeded(),
                "Grindstone second input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                grindstoneMenu.getSlot(2).getItem().is(Items.DIAMOND_SWORD),
                "Grindstone did not expose the vanilla repaired result"
        );
        final int repairedDamage = grindstoneMenu.getSlot(2)
                .getItem()
                .getDamageValue();
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                2
                        ),
                        true
                ).succeeded()
                        && count(player, Items.DIAMOND_SWORD) == 1
                        && player.getInventory().getItem(0)
                                .getDamageValue() == repairedDamage,
                "Grindstone result did not return through vanilla output quick-move"
        );
        player.closeContainer();

        /*
         * Anvil repair uses the same observed two-input/result transaction.
         * The player supplies XP and two damaged swords; AnvilMenu remains
         * authoritative for the repair cost and combined durability.
         */
        player.getInventory().clearContent();
        final ItemStack anvilBase = new ItemStack(Items.DIAMOND_SWORD);
        anvilBase.setDamageValue(80);
        final ItemStack anvilSacrifice = new ItemStack(Items.DIAMOND_SWORD);
        anvilSacrifice.setDamageValue(120);
        player.getInventory().setItem(0, anvilBase);
        player.getInventory().setItem(1, anvilSacrifice);
        player.experienceLevel = 30;
        final BlockPos anvilPos =
                helper.absolutePos(new BlockPos(30, 1, 2));
        helper.getLevel().setBlockAndUpdate(
                anvilPos,
                Blocks.ANVIL.defaultBlockState()
        );
        player.teleportTo(
                anvilPos.getX() + 0.5,
                anvilPos.getY() + 1.0,
                anvilPos.getZ() + 0.5
        );
        player.openMenu(
                helper.getLevel()
                        .getBlockState(anvilPos)
                        .getMenuProvider(helper.getLevel(), anvilPos)
        );
        helper.assertTrue(
                player.containerMenu instanceof AnvilMenu,
                "The real anvil menu did not open"
        );
        final AnvilMenu anvilMenu = (AnvilMenu) player.containerMenu;
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        anvilMenu.findSlot(
                                player.getInventory(),
                                0
                        ).orElseThrow(),
                        AnvilMenu.INPUT_SLOT,
                        1
                )).succeeded(),
                "Anvil base input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                actuator.transfer(new TransferMenuItemParameters(
                        binding(currentFrame.get()),
                        anvilMenu.findSlot(
                                player.getInventory(),
                                1
                        ).orElseThrow(),
                        AnvilMenu.ADDITIONAL_SLOT,
                        1
                )).succeeded(),
                "Anvil additional input did not use an observed menu transfer"
        );
        publish(sampler, player, currentFrame);
        helper.assertTrue(
                anvilMenu.getSlot(AnvilMenu.RESULT_SLOT)
                        .getItem()
                        .is(Items.DIAMOND_SWORD)
                        && anvilMenu.getCost() > 0,
                "Anvil did not expose the vanilla repair result and cost"
        );
        final int anvilResultDamage = anvilMenu
                .getSlot(AnvilMenu.RESULT_SLOT)
                .getItem()
                .getDamageValue();
        helper.assertTrue(
                actuator.quickMove(
                        new ObservedMenuSlotParameters(
                                binding(currentFrame.get()),
                                AnvilMenu.RESULT_SLOT
                        ),
                        true
                ).succeeded()
                        && count(player, Items.DIAMOND_SWORD) == 1
                        && player.getInventory().getItem(0)
                                .getDamageValue() == anvilResultDamage,
                "Anvil result did not return through vanilla output quick-move"
        );
        player.closeContainer();
    }

    private static void publish(
            final FairPerceptionSampler sampler,
            final ServerPlayer player,
            final AtomicReference<MenuSkillFrame> target
    ) {
        final SemanticObservation observation = sampler.sample(player);
        if (observation.openMenu().isEmpty()) {
            final AbstractContainerMenu menu = player.containerMenu;
            throw new IllegalStateException(
                "Fair menu observation missing: menu="
                    + menu.getClass().getName()
                    + ", inventoryMenu="
                    + (menu == player.inventoryMenu)
                    + ", stillValid="
                    + menu.stillValid(player)
                    + ", playerPos="
                    + player.position()
            );
        }
        target.set(MenuSkillFrame.from(
            observation,
            TEST_SESSION_GENERATION
        ));
    }

    private static MenuBinding binding(final MenuSkillFrame frame) {
        return new MenuBinding(
                frame.sampleSequence(),
                frame.menu().containerId(),
                frame.menu().stateId()
        );
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

    private static int countEnchanted(
            final ServerPlayer player,
            final Item item
    ) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item) && stack.isEnchanted()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countPotion(
            final ServerPlayer player,
            final net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>
                    potion
    ) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            final PotionContents contents = stack.get(
                    DataComponents.POTION_CONTENTS
            );
            if (contents != null && contents.is(potion)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
