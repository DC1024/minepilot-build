package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.embodiment.AiPlayerManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * Performs inventory operations through the same server-owned menu methods
 * used for ordinary player packets.
 *
 * <p>No branch writes an output stack, decrements an ingredient, or creates
 * an item entity directly. Recipe placement is delegated to the vanilla
 * recipe-book placer, result consumption to {@code ResultSlot}, equipment to
 * vanilla menu clicks, and dropping to the vanilla {@code THROW} click.</p>
 */
public final class ServerInventorySkillActuator
        implements InventorySkillActuator {
    private static final int FIRST_INVENTORY_SLOT = 0;
    private static final int INVENTORY_SLOT_COUNT = 36;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final Supplier<Optional<ServerPlayer>> playerLookup;

    public ServerInventorySkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId
    ) {
        this(
                server,
                expectedPlayerId,
                () -> AiPlayerManager.onlinePlayer(server)
        );
    }

    ServerInventorySkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final Supplier<Optional<ServerPlayer>> playerLookup
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.playerLookup = Objects.requireNonNull(
                playerLookup,
                "playerLookup"
        );
    }

    @Override
    public InventoryOperationResult checkEquip(
            final EquipItemParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final PlayerCheck checked = checkPlayer();
        if (checked.failure().isPresent()) {
            return checked.failure().orElseThrow();
        }
        final ServerPlayer player = checked.player().orElseThrow();
        final Optional<Item> item = resolveItem(parameters.itemId());
        if (item.isEmpty()) {
            return InventoryOperationResult.rejected(
                    "equip_item.unknown_item"
            );
        }
        if (targetStack(player, parameters.slot()).is(item.orElseThrow())) {
            return InventoryOperationResult.success();
        }
        final int source = findOwnedItemForEquip(
                player,
                item.orElseThrow()
        );
        if (source < 0) {
            return InventoryOperationResult.rejected(
                    "equip_item.item_not_found"
            );
        }
        if (isArmor(parameters.slot())) {
            final EquipmentSlot equipmentSlot = equipmentSlot(
                    parameters.slot()
            );
            final ItemStack sourceStack = player.getInventory().getItem(source);
            if (!player.isEquippableInSlot(sourceStack, equipmentSlot)) {
                return InventoryOperationResult.rejected(
                        "equip_item.incompatible_slot"
                );
            }
            final OptionalInt targetIndex = player.inventoryMenu.findSlot(
                    player.getInventory(),
                    equipmentSlot.getIndex(INVENTORY_SLOT_COUNT)
            );
            if (targetIndex.isEmpty()) {
                return InventoryOperationResult.rejected(
                        "equip_item.slot_unavailable"
                );
            }
            final Slot target = player.inventoryMenu.getSlot(
                    targetIndex.getAsInt()
            );
            if ((!target.getItem().isEmpty() && !target.mayPickup(player))
                    || !target.mayPlace(sourceStack)) {
                return InventoryOperationResult.rejected(
                        "equip_item.slot_locked"
                );
            }
        }
        return InventoryOperationResult.success();
    }

    @Override
    public InventoryOperationResult equip(
            final EquipItemParameters parameters
    ) {
        final InventoryOperationResult preflight = checkEquip(parameters);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final ServerPlayer player = playerLookup.get().orElseThrow();
        final Item item = resolveItem(parameters.itemId()).orElseThrow();
        if (targetStack(player, parameters.slot()).is(item)) {
            return InventoryOperationResult.success();
        }

        useInventoryMenu(player);
        final int sourceInventorySlot = findOwnedItemForEquip(
                player,
                item
        );
        if (sourceInventorySlot < 0) {
            return InventoryOperationResult.rejected(
                    "equip_item.item_not_found"
            );
        }
        final AbstractContainerMenu menu = player.inventoryMenu;
        final OptionalInt sourceMenuSlot = menu.findSlot(
                player.getInventory(),
                sourceInventorySlot
        );
        if (sourceMenuSlot.isEmpty()) {
            return InventoryOperationResult.rejected(
                    "equip_item.source_unavailable"
            );
        }

        player.resetLastActionTime();
        if (parameters.slot() == EquipmentTarget.MAINHAND) {
            menu.clicked(
                    sourceMenuSlot.getAsInt(),
                    player.getInventory().getSelectedSlot(),
                    ContainerInput.SWAP,
                    player
            );
        } else if (parameters.slot() == EquipmentTarget.OFFHAND) {
            menu.clicked(
                    sourceMenuSlot.getAsInt(),
                    Inventory.SLOT_OFFHAND,
                    ContainerInput.SWAP,
                    player
            );
        } else {
            final EquipmentSlot equipmentSlot = equipmentSlot(
                    parameters.slot()
            );
            final OptionalInt targetMenuSlot = menu.findSlot(
                    player.getInventory(),
                    equipmentSlot.getIndex(INVENTORY_SLOT_COUNT)
            );
            if (targetMenuSlot.isEmpty()) {
                return InventoryOperationResult.rejected(
                        "equip_item.slot_unavailable"
                );
            }
            menu.clicked(
                    sourceMenuSlot.getAsInt(),
                    0,
                    ContainerInput.PICKUP,
                    player
            );
            menu.clicked(
                    targetMenuSlot.getAsInt(),
                    0,
                    ContainerInput.PICKUP,
                    player
            );
            menu.clicked(
                    sourceMenuSlot.getAsInt(),
                    0,
                    ContainerInput.PICKUP,
                    player
            );
        }
        menu.broadcastChanges();
        if (!menu.getCarried().isEmpty()) {
            return InventoryOperationResult.rejected(
                    "equip_item.cursor_not_cleared"
            );
        }
        return targetStack(player, parameters.slot()).is(item)
                ? InventoryOperationResult.success(1)
                : InventoryOperationResult.rejected(
                        "equip_item.transaction_rejected"
                );
    }

    @Override
    public InventoryOperationResult checkDrop(
            final DropItemParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final PlayerCheck checked = checkPlayer();
        if (checked.failure().isPresent()) {
            return checked.failure().orElseThrow();
        }
        final ServerPlayer player = checked.player().orElseThrow();
        if (!player.canDropItems()) {
            return InventoryOperationResult.rejected(
                    "drop_item.dropping_disabled"
            );
        }
        final Optional<Item> item = resolveItem(parameters.itemId());
        if (item.isEmpty()) {
            return InventoryOperationResult.rejected(
                    "drop_item.unknown_item"
            );
        }
        return countInventoryItem(player, item.orElseThrow())
                        >= parameters.count()
                ? InventoryOperationResult.success()
                : InventoryOperationResult.rejected(
                        "drop_item.insufficient_items"
                );
    }

    @Override
    public InventoryOperationResult drop(
            final DropItemParameters parameters
    ) {
        final InventoryOperationResult preflight = checkDrop(parameters);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final ServerPlayer player = playerLookup.get().orElseThrow();
        final Item item = resolveItem(parameters.itemId()).orElseThrow();
        useInventoryMenu(player);
        final AbstractContainerMenu menu = player.inventoryMenu;
        final int before = countInventoryItem(player, item);
        int remaining = parameters.count();

        player.resetLastActionTime();
        while (remaining > 0) {
            final int sourceInventorySlot = findInventoryItem(player, item);
            if (sourceInventorySlot < 0) {
                break;
            }
            final OptionalInt sourceMenuSlot = menu.findSlot(
                    player.getInventory(),
                    sourceInventorySlot
            );
            if (sourceMenuSlot.isEmpty()) {
                break;
            }
            final int inStack = player.getInventory()
                    .getItem(sourceInventorySlot)
                    .getCount();
            if (inStack <= remaining) {
                menu.clicked(
                        sourceMenuSlot.getAsInt(),
                        1,
                        ContainerInput.THROW,
                        player
                );
                remaining -= inStack;
            } else {
                menu.clicked(
                        sourceMenuSlot.getAsInt(),
                        0,
                        ContainerInput.THROW,
                        player
                );
                remaining--;
            }
        }
        menu.broadcastChanges();
        final int dropped = before - countInventoryItem(player, item);
        return dropped == parameters.count()
                ? InventoryOperationResult.success(dropped)
                : InventoryOperationResult.rejected(
                        "drop_item.transaction_rejected"
                );
    }

    @Override
    public InventoryOperationResult checkCraft(
            final CraftRecipeParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        return checkCraft(parameters, parameters.crafts());
    }

    @Override
    public InventoryOperationResult craftOnce(
            final CraftRecipeParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final InventoryOperationResult preflight = checkCraft(parameters, 1);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final ServerPlayer player = playerLookup.get().orElseThrow();
        final ResolvedCraftingRecipe resolved = resolveCraftingRecipe(
                parameters.recipeId()
        ).orElseThrow();
        final AbstractCraftingMenu menu =
                (AbstractCraftingMenu) player.containerMenu;

        player.resetLastActionTime();
        menu.handlePlacement(
                false,
                false,
                resolved.holder(),
                player.level(),
                player.getInventory()
        );

        final CraftingInput input = craftingInput(menu);
        if (!resolved.recipe().matches(input, player.level())) {
            rollbackGrid(menu, player);
            menu.broadcastChanges();
            return InventoryOperationResult.rejected(
                    "craft_recipe.placement_failed"
            );
        }
        final ItemStack output = menu.getResultSlot().getItem().copy();
        if (output.isEmpty()) {
            rollbackGrid(menu, player);
            menu.broadcastChanges();
            return InventoryOperationResult.rejected(
                    "craft_recipe.result_unavailable"
            );
        }
        if (!canFit(player.getInventory(), output)) {
            final boolean rolledBack = rollbackGrid(menu, player);
            menu.broadcastChanges();
            return InventoryOperationResult.rejected(
                    rolledBack
                            ? "craft_recipe.output_inventory_full"
                            : "craft_recipe.rollback_failed"
            );
        }

        final int before = countMatchingStack(player.getInventory(), output);
        menu.clicked(
                menu.getResultSlot().index,
                0,
                ContainerInput.QUICK_MOVE,
                player
        );
        menu.broadcastChanges();
        final int produced = countMatchingStack(
                player.getInventory(),
                output
        ) - before;
        if (produced != output.getCount()
                || !menu.getCarried().isEmpty()) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.result_move_failed"
            );
        }
        return InventoryOperationResult.success(produced);
    }

    @Override
    public boolean hasThreeByThreeCraftingMenu() {
        if (!server.isSameThread()) {
            return false;
        }
        final PlayerCheck checked = checkPlayer();
        if (checked.failure().isPresent()) {
            return false;
        }
        final ServerPlayer player = checked.player().orElseThrow();
        return player.containerMenu instanceof AbstractCraftingMenu menu
                && menu != player.inventoryMenu
                && menu.getGridWidth() >= 3;
    }

    @Override
    public InventoryOperationResult closeThreeByThreeCraftingMenu() {
        if (!server.isSameThread()) {
            return InventoryOperationResult.rejected(
                    "inventory.wrong_thread"
            );
        }
        final Optional<ServerPlayer> maybePlayer = playerLookup.get();
        if (maybePlayer.isEmpty()
                || !maybePlayer.orElseThrow().getUUID().equals(
                        expectedPlayerId
                )) {
            return InventoryOperationResult.rejected(
                    "inventory.player_offline"
            );
        }
        final ServerPlayer player = maybePlayer.orElseThrow();
        if (!(player.containerMenu
                instanceof AbstractCraftingMenu menu)
                || menu == player.inventoryMenu
                || menu.getGridWidth() < 3) {
            return InventoryOperationResult.rejected(
                    "inventory.crafting_table_menu_not_open"
            );
        }
        if (!menu.getCarried().isEmpty()) {
            return InventoryOperationResult.rejected(
                    "inventory.cursor_not_empty"
            );
        }
        player.resetLastActionTime();
        player.closeContainer();
        return player.containerMenu == player.inventoryMenu
                ? InventoryOperationResult.success()
                : InventoryOperationResult.rejected(
                        "inventory.close_crafting_menu_failed"
                );
    }

    private InventoryOperationResult checkCraft(
            final CraftRecipeParameters parameters,
            final int requiredCrafts
    ) {
        final PlayerCheck checked = checkPlayer();
        if (checked.failure().isPresent()) {
            return checked.failure().orElseThrow();
        }
        final ServerPlayer player = checked.player().orElseThrow();
        final Optional<ResolvedCraftingRecipe> maybeRecipe =
                resolveCraftingRecipe(parameters.recipeId());
        if (maybeRecipe.isEmpty()) {
            return recipeFailure(parameters.recipeId());
        }
        final ResolvedCraftingRecipe resolved = maybeRecipe.orElseThrow();
        if (resolved.recipe().isSpecial()) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.special_recipe_unsupported"
            );
        }
        if (resolved.recipe().placementInfo().isImpossibleToPlace()) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.recipe_not_placeable"
            );
        }
        /*
         * Recipe-book visibility is a UI hint, not a survival crafting
         * permission. Vanilla 26.2 deliberately withholds the chest recipe
         * until ten inventory slots are occupied, but a knowledgeable player
         * may still place the eight-plank pattern manually. handlePlacement
         * below performs ordinary menu-slot placement from owned materials;
         * it does not pre-award the recipe or create ingredients, so
         * rejecting a locked recipe here incorrectly made expert crafting
         * impossible. Vanilla may record the recipe after the real result
         * click, just as it does for a human player.
         */
        if (!(player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.crafting_menu_required"
            );
        }
        if (!gridIsEmpty(menu)) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.grid_not_empty"
            );
        }
        if (!fitsGrid(resolved.recipe(), menu)) {
            return InventoryOperationResult.rejected(
                    menu.getGridWidth() < 3
                            ? "craft_recipe.crafting_table_menu_required"
                            : "craft_recipe.recipe_does_not_fit"
            );
        }

        final StackedItemContents available = new StackedItemContents();
        player.getInventory().fillStackedContents(available);
        return available.canCraft(
                resolved.recipe(),
                requiredCrafts,
                null
        )
                ? InventoryOperationResult.success()
                : InventoryOperationResult.rejected(
                        "craft_recipe.materials_unavailable"
                );
    }

    private PlayerCheck checkPlayer() {
        if (!server.isSameThread()) {
            return PlayerCheck.failed("inventory.wrong_thread");
        }
        final Optional<ServerPlayer> maybePlayer = playerLookup.get();
        if (maybePlayer.isEmpty()
                || !maybePlayer.orElseThrow().getUUID().equals(
                        expectedPlayerId
                )) {
            return PlayerCheck.failed("inventory.player_offline");
        }
        final ServerPlayer player = maybePlayer.orElseThrow();
        if (!player.isAlive()) {
            return PlayerCheck.failed("inventory.player_dead");
        }
        if (player.isSpectator()) {
            return PlayerCheck.failed("inventory.spectator");
        }
        if (!player.containerMenu.stillValid(player)) {
            return PlayerCheck.failed("inventory.menu_invalid");
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            return PlayerCheck.failed("inventory.cursor_not_empty");
        }
        return PlayerCheck.success(player);
    }

    private void useInventoryMenu(final ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private Optional<Item> resolveItem(final String itemId) {
        final Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null
                || !BuiltInRegistries.ITEM.containsKey(identifier)) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(identifier);
    }

    private Optional<ResolvedCraftingRecipe> resolveCraftingRecipe(
            final String recipeId
    ) {
        final Identifier identifier = Identifier.tryParse(recipeId);
        if (identifier == null) {
            return Optional.empty();
        }
        final ResourceKey<Recipe<?>> key = ResourceKey.create(
                Registries.RECIPE,
                identifier
        );
        final Optional<RecipeHolder<?>> untyped =
                server.getRecipeManager().byKey(key);
        if (untyped.isEmpty()
                || !(untyped.orElseThrow().value()
                instanceof CraftingRecipe recipe)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        final RecipeHolder<CraftingRecipe> typed =
                (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>)
                        untyped.orElseThrow();
        return Optional.of(new ResolvedCraftingRecipe(typed, recipe));
    }

    private InventoryOperationResult recipeFailure(final String recipeId) {
        final Identifier identifier = Identifier.tryParse(recipeId);
        if (identifier == null) {
            return InventoryOperationResult.rejected(
                    "craft_recipe.recipe_not_found"
            );
        }
        final ResourceKey<Recipe<?>> key = ResourceKey.create(
                Registries.RECIPE,
                identifier
        );
        return server.getRecipeManager().byKey(key).isPresent()
                ? InventoryOperationResult.rejected(
                        "craft_recipe.not_crafting_recipe"
                )
                : InventoryOperationResult.rejected(
                        "craft_recipe.recipe_not_found"
                );
    }

    private static boolean fitsGrid(
            final CraftingRecipe recipe,
            final AbstractCraftingMenu menu
    ) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= menu.getGridWidth()
                    && shaped.getHeight() <= menu.getGridHeight();
        }
        return recipe.placementInfo().ingredients().size()
                <= menu.getGridWidth() * menu.getGridHeight();
    }

    private static CraftingInput craftingInput(
            final AbstractCraftingMenu menu
    ) {
        final List<ItemStack> stacks = menu.getInputGridSlots().stream()
                .map(slot -> slot.getItem().copy())
                .toList();
        return CraftingInput.of(
                menu.getGridWidth(),
                menu.getGridHeight(),
                stacks
        );
    }

    private static boolean gridIsEmpty(final AbstractCraftingMenu menu) {
        return menu.getInputGridSlots().stream()
                .noneMatch(Slot::hasItem);
    }

    private static boolean rollbackGrid(
            final AbstractCraftingMenu menu,
            final ServerPlayer player
    ) {
        for (Slot slot : menu.getInputGridSlots()) {
            if (slot.hasItem()) {
                menu.clicked(
                        slot.index,
                        0,
                        ContainerInput.QUICK_MOVE,
                        player
                );
            }
        }
        return gridIsEmpty(menu);
    }

    private static boolean canFit(
            final Inventory inventory,
            final ItemStack output
    ) {
        int capacity = 0;
        for (int slot = FIRST_INVENTORY_SLOT;
                slot < INVENTORY_SLOT_COUNT;
                slot++) {
            final ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) {
                capacity += output.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, output)) {
                capacity += Math.max(
                        0,
                        inventory.getMaxStackSize(existing)
                                - existing.getCount()
                );
            }
            if (capacity >= output.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static int findInventoryItem(
            final ServerPlayer player,
            final Item item
    ) {
        for (int slot = FIRST_INVENTORY_SLOT;
                slot < INVENTORY_SLOT_COUNT;
                slot++) {
            if (player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findOwnedItemForEquip(
            final ServerPlayer player,
            final Item item
    ) {
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            if (player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int countInventoryItem(
            final ServerPlayer player,
            final Item item
    ) {
        int count = 0;
        for (int slot = FIRST_INVENTORY_SLOT;
                slot < INVENTORY_SLOT_COUNT;
                slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countMatchingStack(
            final Inventory inventory,
            final ItemStack expected
    ) {
        int count = 0;
        for (int slot = FIRST_INVENTORY_SLOT;
                slot < INVENTORY_SLOT_COUNT;
                slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static ItemStack targetStack(
            final ServerPlayer player,
            final EquipmentTarget target
    ) {
        return switch (target) {
            case MAINHAND -> player.getMainHandItem();
            case OFFHAND -> player.getOffhandItem();
            case HEAD, CHEST, LEGS, FEET ->
                    player.getItemBySlot(equipmentSlot(target));
        };
    }

    private static boolean isArmor(final EquipmentTarget target) {
        return target != EquipmentTarget.MAINHAND
                && target != EquipmentTarget.OFFHAND;
    }

    private static EquipmentSlot equipmentSlot(
            final EquipmentTarget target
    ) {
        return switch (target) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case MAINHAND, OFFHAND -> throw new IllegalArgumentException(
                    "Hand targets do not have an armor slot"
            );
        };
    }

    private record ResolvedCraftingRecipe(
            RecipeHolder<CraftingRecipe> holder,
            CraftingRecipe recipe
    ) {
    }

    private record PlayerCheck(
            Optional<ServerPlayer> player,
            Optional<InventoryOperationResult> failure
    ) {
        private static PlayerCheck success(final ServerPlayer player) {
            return new PlayerCheck(
                    Optional.of(player),
                    Optional.empty()
            );
        }

        private static PlayerCheck failed(final String code) {
            return new PlayerCheck(
                    Optional.empty(),
                    Optional.of(InventoryOperationResult.rejected(code))
            );
        }
    }
}
