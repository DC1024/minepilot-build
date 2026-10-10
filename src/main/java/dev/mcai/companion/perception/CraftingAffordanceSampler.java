package dev.mcai.companion.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraftforge.common.crafting.IShapedRecipe;

/**
 * Reads only the companion's own recipe book, inventory, and currently active
 * crafting menu. It never unlocks a recipe, reads world containers, or crafts
 * an item.
 */
public final class CraftingAffordanceSampler {
    public Optional<CraftingAffordanceSnapshot> sample(
            final ServerPlayer player
    ) {
        Objects.requireNonNull(player, "player");
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException(
                    "Crafting affordances must be sampled on the server thread"
            );
        }
        if (!player.isAlive()
                || player.isSpectator()
                || !player.containerMenu.stillValid(player)
                || !player.containerMenu.getCarried().isEmpty()
                || !(player.containerMenu
                        instanceof AbstractCraftingMenu menu)
                || menu.getInputGridSlots().stream().anyMatch(Slot::hasItem)) {
            return Optional.empty();
        }

        final int currentGridWidth = menu.getGridWidth();
        final int currentGridHeight = menu.getGridHeight();
        if (currentGridWidth < 1 || currentGridWidth > 3
                || currentGridHeight < 1
                || currentGridHeight > 3) {
            return Optional.empty();
        }
        final StackedItemContents available = new StackedItemContents();
        player.getInventory().fillStackedContents(available);
        final ContextMap displayContext =
                SlotDisplayContext.fromLevel(player.level());
        final List<CraftingAffordanceSelector.Candidate> candidates =
                new ArrayList<>();

        player.getRecipeBook()
                .pack()
                .known()
                .stream()
                .sorted(Comparator.comparing(key ->
                        key.identifier().toString()))
                .forEach(recipeKey -> {
                    try {
                    /*
                     * Recheck the live recipe book before resolving anything.
                     * A datapack reload or recipe revocation cannot turn this
                     * own-book enumeration into a stale unlocked claim.
                     */
                    if (!player.getRecipeBook().contains(recipeKey)) {
                        return;
                    }
                    final Optional<RecipeHolder<?>> maybeHolder =
                            player.level()
                                    .getServer()
                                    .getRecipeManager()
                                    .byKey(recipeKey);
                    if (maybeHolder.isEmpty()
                            || !(maybeHolder.orElseThrow().value()
                                    instanceof CraftingRecipe recipe)
                            || recipe.isSpecial()
                            || recipe.placementInfo()
                                    .isImpossibleToPlace()) {
                        return;
                    }
                    final GridSize requiredGrid = requiredGrid(recipe);
                    if (requiredGrid.width() < 1
                            || requiredGrid.height() < 1
                            || requiredGrid.width() > 3
                            || requiredGrid.height() > 3
                            || requiredGrid.width() > currentGridWidth
                            || requiredGrid.height()
                                    > currentGridHeight
                            || !available.canCraft(recipe, 1, null)) {
                        return;
                    }
                    final Optional<ItemStack> output =
                            visibleOutput(
                                    recipe,
                                    displayContext,
                                    player
                            );
                    if (output.isEmpty()) {
                        return;
                    }
                    final ItemStack stack = output.orElseThrow();
                    candidates.add(
                        new CraftingAffordanceSelector.Candidate(
                            new CraftingAffordance(
                                recipeKey.identifier().toString(),
                                BuiltInRegistries.ITEM
                                    .getKey(stack.getItem())
                                    .toString(),
                                stack.getCount(),
                                requiredGrid.width(),
                                requiredGrid.height(),
                                requiredGrid.width() > 2
                                    || requiredGrid.height() > 2
                            ),
                            true,
                            true
                        )
                    );
                    } catch (RuntimeException exception) {
                        /*
                         * A broken third-party recipe must not take down the
                         * fair-perception loop. Omit it; never infer output.
                         */
                        return;
                    }
                });

        return Optional.of(CraftingAffordanceSelector.select(
                currentGridWidth,
                currentGridHeight,
                candidates
        ));
    }

    private static GridSize requiredGrid(final CraftingRecipe recipe) {
        if (recipe instanceof IShapedRecipe<?> shaped) {
            return new GridSize(
                    shaped.getRecipeWidth(),
                    shaped.getRecipeHeight()
            );
        }
        final int ingredients =
                recipe.placementInfo().ingredients().size();
        final int width = ingredients <= 4
                ? Math.min(2, ingredients)
                : Math.min(3, ingredients);
        final int height = (ingredients + width - 1) / width;
        return new GridSize(width, height);
    }

    private static Optional<ItemStack> visibleOutput(
            final CraftingRecipe recipe,
            final ContextMap displayContext,
            final ServerPlayer player
    ) {
        for (RecipeDisplay display : recipe.display()) {
            final ItemStack output;
            try {
                output = display.result()
                        .resolveForFirstStack(displayContext);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!output.isEmpty()
                    && output.isItemEnabled(
                            player.level().enabledFeatures())) {
                return Optional.of(output.copy());
            }
        }
        return Optional.empty();
    }

    private record GridSize(int width, int height) {
    }
}
