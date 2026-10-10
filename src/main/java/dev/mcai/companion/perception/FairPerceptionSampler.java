package dev.mcai.companion.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-thread sampler restricted to the companion's own body and fair
 * first-person observations.
 */
public final class FairPerceptionSampler {
    private static final double BLOCK_HIT_CLEAR_RETREAT = 1.0E-4;

    private final PerceptionBudget budget;
    private final CraftingAffordanceSampler craftingAffordanceSampler;
    private long nextSequence;

    public FairPerceptionSampler() {
        this(PerceptionBudget.defaults());
    }

    public FairPerceptionSampler(PerceptionBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.craftingAffordanceSampler =
                new CraftingAffordanceSampler();
    }

    public PerceptionBudget budget() {
        return budget;
    }

    public SemanticObservation sample(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ServerLevel level = player.level();
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Fair perception must run on the server thread");
        }

        BodySnapshot body = sampleBody(player, level);
        CandidateBatch candidateBatch = collectEntityCandidates(player, level);
        EntitySample entitySample = sampleVisibleEntities(
                player,
                level,
                body,
                candidateBatch.entities()
        );
        BlockSample blockSample = sampleVisibleBlockFaces(player, level, body);
        DangerSample dangerSample = sampleDangers(
                player,
                body,
                candidateBatch.entities(),
                entitySample.visible().stream()
                    .map(VisibleEntity::entityId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet())
        );
        Optional<OpenMenuSnapshot> openMenu = sampleOpenMenu(player);
        Optional<CraftingAffordanceSnapshot> craftingAffordances =
                craftingAffordanceSampler.sample(player);

        ObservationBudgetUsage usage = new ObservationBudgetUsage(
                candidateBatch.entities().size(),
                entitySample.losChecks(),
                dangerSample.candidatesInspected(),
                blockSample.raysCast(),
                entitySample.visible().size(),
                blockSample.faces().size(),
                dangerSample.signals().size(),
                candidateBatch.truncated(),
                entitySample.truncated(),
                blockSample.truncated(),
                dangerSample.truncated()
        );
        Set<PerceptionProvenance> provenance = EnumSet.copyOf(body.provenance());
        entitySample.visible().forEach(value -> provenance.add(value.provenance()));
        blockSample.faces().forEach(value -> provenance.add(value.provenance()));
        blockSample.clearRays().forEach(
            value -> provenance.add(value.provenance())
        );
        dangerSample.signals().forEach(value -> provenance.add(value.provenance()));
        if (openMenu.isPresent()) {
            provenance.add(PerceptionProvenance.OPEN_MENU_CONTENTS);
        }
        if (craftingAffordances.isPresent()) {
            provenance.add(PerceptionProvenance.OWN_RECIPE_BOOK);
        }

        return new SemanticObservation(
                nextSequence++,
                body,
                entitySample.visible(),
                blockSample.faces(),
                blockSample.clearRays(),
                dangerSample.signals(),
                openMenu,
                craftingAffordances,
                budget,
                usage,
                provenance
        );
    }

    private static Optional<OpenMenuSnapshot> sampleOpenMenu(
            final ServerPlayer player
    ) {
        final var menu = player.containerMenu;
        if (menu == player.inventoryMenu
                || !menu.stillValid(player)
                || menu.slots.size() > OpenMenuSnapshot.MAX_VISIBLE_SLOTS) {
            return Optional.empty();
        }
        final List<MenuSlotSummary> slots = new ArrayList<>(
            menu.slots.size()
        );
        for (int index = 0; index < menu.slots.size(); index++) {
            final var slot = menu.getSlot(index);
            final ItemStack stack = slot.getItem();
            slots.add(new MenuSlotSummary(
                index,
                stack.isEmpty()
                    ? "minecraft:air"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem())
                        .toString(),
                stack.getCount(),
                stack.getDamageValue(),
                stack.getMaxDamage(),
                slot.container == player.getInventory(),
                slot.mayPickup(player),
                menuSlotRole(menu, index, slot.container == player.getInventory())
            ));
        }
        final ItemStack carried = menu.getCarried();
        final HeldItemSummary carriedSummary = carried.isEmpty()
            ? HeldItemSummary.empty()
            : heldItem(carried);
        final String menuType;
        try {
            menuType = BuiltInRegistries.MENU.getKey(menu.getType())
                .toString();
        } catch (UnsupportedOperationException exception) {
            return Optional.empty();
        }
        return Optional.of(new OpenMenuSnapshot(
            menuType,
            menu.getClass().getSimpleName(),
            menu.containerId,
            menu.getStateId(),
            slots,
            carriedSummary,
            sampleMenuOptions(menu, player)
        ));
    }

    /**
     * Labels only slots of the menu that is already open. The label is an
     * affordance hint; the action still binds the exact observed slot and
     * vanilla menu state before it can mutate anything.
     */
    private static String menuSlotRole(
            final AbstractContainerMenu menu,
            final int slot,
            final boolean playerInventory
    ) {
        if (playerInventory) {
            return "PLAYER_INVENTORY";
        }
        if (menu instanceof FurnaceMenu) {
            return switch (slot) {
                case 0 -> "FURNACE_INPUT";
                case 1 -> "FURNACE_FUEL";
                case 2 -> "FURNACE_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof BrewingStandMenu) {
            return switch (slot) {
                case 0, 1, 2 -> "BREWING_BOTTLE";
                case 3 -> "BREWING_INGREDIENT";
                case 4 -> "BREWING_FUEL";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof CraftingMenu) {
            return slot == 0
                    ? "CRAFTING_RESULT"
                    : slot <= 9 ? "CRAFTING_GRID" : "CONTAINER";
        }
        if (menu instanceof StonecutterMenu) {
            return slot == 0
                    ? "STONECUTTER_INPUT"
                    : slot == 1 ? "STONECUTTER_OUTPUT" : "CONTAINER";
        }
        if (menu instanceof EnchantmentMenu) {
            return slot == 0
                    ? "ENCHANTMENT_ITEM"
                    : slot == 1 ? "ENCHANTMENT_LAPIS" : "CONTAINER";
        }
        if (menu instanceof MerchantMenu) {
            return switch (slot) {
                case 0 -> "MERCHANT_PAYMENT_A";
                case 1 -> "MERCHANT_PAYMENT_B";
                case 2 -> "MERCHANT_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof LoomMenu) {
            return switch (slot) {
                case 0 -> "LOOM_BANNER";
                case 1 -> "LOOM_DYE";
                case 2 -> "LOOM_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof CartographyTableMenu) {
            return switch (slot) {
                case 0 -> "CARTOGRAPHY_MAP";
                case 1 -> "CARTOGRAPHY_ADDITION";
                case 2 -> "CARTOGRAPHY_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof SmithingMenu) {
            return switch (slot) {
                case 0 -> "SMITHING_TEMPLATE";
                case 1 -> "SMITHING_BASE";
                case 2 -> "SMITHING_ADDITION";
                case 3 -> "SMITHING_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof GrindstoneMenu) {
            return switch (slot) {
                case 0 -> "GRINDSTONE_INPUT_A";
                case 1 -> "GRINDSTONE_INPUT_B";
                case 2 -> "GRINDSTONE_OUTPUT";
                default -> "CONTAINER";
            };
        }
        if (menu instanceof AnvilMenu) {
            return switch (slot) {
                case 0 -> "ANVIL_INPUT";
                case 1 -> "ANVIL_SACRIFICE";
                case 2 -> "ANVIL_OUTPUT";
                default -> "CONTAINER";
            };
        }
        return "CONTAINER";
    }

    /**
     * Exposes controls already visible in the open vanilla GUI. This method
     * never reads a closed block entity or an unobserved villager.
     */
    private static List<MenuOptionSummary> sampleMenuOptions(
            final AbstractContainerMenu menu,
            final ServerPlayer player
    ) {
        final List<MenuOptionSummary> result = new ArrayList<>();
        if (menu instanceof EnchantmentMenu enchantment) {
            final var enchantments = player.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .asHolderIdMap();
            for (int index = 0; index < enchantment.costs.length; index++) {
                if (enchantment.costs[index] <= 0) {
                    continue;
                }
                final Map<String, String> properties =
                        new LinkedHashMap<>();
                properties.put(
                        "displayedLevelCost",
                        Integer.toString(enchantment.costs[index])
                );
                properties.put(
                        "lapisCost",
                        Integer.toString(index + 1)
                );
                properties.put(
                        "experienceLevelCost",
                        Integer.toString(index + 1)
                );
                properties.put(
                        "clueLevel",
                        Integer.toString(enchantment.levelClue[index])
                );
                final int clueId = enchantment.enchantClue[index];
                if (clueId >= 0) {
                    final var clue = enchantments.byId(clueId);
                    if (clue != null) {
                        clue.unwrapKey().ifPresent(key ->
                                properties.put(
                                        "clue",
                                        key.identifier().toString()
                                )
                        );
                    }
                }
                result.add(new MenuOptionSummary(
                        index,
                        "enchantment",
                        player.experienceLevel >= enchantment.costs[index]
                                && player.experienceLevel >= index + 1
                                && enchantment.getGoldCount() >= index + 1,
                        properties
                ));
            }
        } else if (menu instanceof MerchantMenu merchant) {
            for (int index = 0;
                    index < merchant.getOffers().size()
                            && result.size()
                            < OpenMenuSnapshot.MAX_VISIBLE_OPTIONS;
                    index++) {
                final MerchantOffer offer =
                        merchant.getOffers().get(index);
                final Map<String, String> properties =
                        new LinkedHashMap<>();
                putStack(properties, "costA", offer.getCostA());
                if (!offer.getCostB().isEmpty()) {
                    putStack(properties, "costB", offer.getCostB());
                }
                putStack(properties, "result", offer.getResult());
                properties.put(
                        "uses",
                        Integer.toString(offer.getUses())
                );
                properties.put(
                        "maxUses",
                        Integer.toString(offer.getMaxUses())
                );
                result.add(new MenuOptionSummary(
                        index,
                        "merchant_offer",
                        !offer.isOutOfStock(),
                        properties
                ));
            }
        } else if (menu instanceof StonecutterMenu stonecutter) {
            final ItemStack input = stonecutter.getSlot(0).getItem();
            for (int index = 0;
                    index < stonecutter.getVisibleRecipes().entries().size()
                            && result.size()
                            < OpenMenuSnapshot.MAX_VISIBLE_OPTIONS;
                    index++) {
                final var entry =
                        stonecutter.getVisibleRecipes().entries().get(index);
                final Optional<RecipeHolder<StonecutterRecipe>> holder =
                        entry.recipe().recipe();
                if (holder.isEmpty()) {
                    continue;
                }
                final ItemStack output;
                try {
                    output = holder.orElseThrow()
                            .value()
                            .assemble(new SingleRecipeInput(input));
                } catch (RuntimeException exception) {
                    continue;
                }
                final Map<String, String> properties =
                        new LinkedHashMap<>();
                putStack(properties, "result", output);
                result.add(new MenuOptionSummary(
                        index,
                        "stonecutter_recipe",
                        !output.isEmpty(),
                        properties
                ));
            }
        } else if (menu instanceof LoomMenu loom) {
            for (int index = 0;
                    index < loom.getSelectablePatterns().size()
                            && result.size()
                            < OpenMenuSnapshot.MAX_VISIBLE_OPTIONS;
                    index++) {
                final Map<String, String> properties =
                        new LinkedHashMap<>();
                loom.getSelectablePatterns()
                        .get(index)
                        .unwrapKey()
                        .ifPresent(key -> properties.put(
                                "pattern",
                                key.identifier().toString()
                        ));
                result.add(new MenuOptionSummary(
                        index,
                        "loom_pattern",
                        true,
                        properties
                ));
            }
        }
        return List.copyOf(result);
    }

    private static void putStack(
            final Map<String, String> properties,
            final String prefix,
            final ItemStack stack
    ) {
        if (stack.isEmpty()) {
            properties.put(prefix + "Item", "minecraft:air");
            properties.put(prefix + "Count", "0");
            return;
        }
        properties.put(
                prefix + "Item",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
        );
        properties.put(
                prefix + "Count",
                Integer.toString(stack.getCount())
        );
    }

    private BodySnapshot sampleBody(ServerPlayer player, ServerLevel level) {
        Vec3 position = player.position();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        FoodData food = player.getFoodData();
        List<InventoryItemSummary> inventory = summarizeInventory(player);
        List<EffectSummary> effects = summarizeEffects(player);
        return new BodySnapshot(
                player.getUUID(),
                level.dimension().identifier().toString(),
                level.getGameTime(),
                vector(position),
                vector(eye),
                vector(look),
                player.getHealth(),
                player.getMaxHealth(),
                player.getAbsorptionAmount(),
                food.getFoodLevel(),
                food.getSaturationLevel(),
                player.getAirSupply(),
                player.getMaxAirSupply(),
                player.onGround(),
                player.isInWater(),
                player.isOnFire(),
                player.fallDistance,
                heldItem(player.getMainHandItem()),
                heldItem(player.getOffhandItem()),
                inventory,
                effects,
                EnumSet.of(
                        PerceptionProvenance.SELF_PLAYER_STATE,
                        PerceptionProvenance.OWN_INVENTORY,
                        PerceptionProvenance.OWN_STATUS_EFFECT
                )
        );
    }

    private CandidateBatch collectEntityCandidates(ServerPlayer player, ServerLevel level) {
        double range = Math.max(budget.entityRange(), budget.dangerRange());
        AABB searchBox = player.getBoundingBox().inflate(range);
        List<Entity> candidates = new ArrayList<>(
                budget.maxEntityCandidates()
        );
        level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                searchBox,
                entity -> entity != player
                        && entity.isAlive()
                        && !entity.isSpectator()
                        && level.isLoaded(entity.blockPosition())
                        && player.distanceToSqr(entity) <= range * range,
                candidates,
                budget.maxEntityCandidates()
        );
        /*
         * The EntityTypeTest overload above intentionally stays on the
         * bounded entity index, but Minecraft's generic Level#getEntities
         * path does not append multipart entities. EnderDragon parts are
         * exposed only by the Entity/predicate overload, so without this
         * explicit bounded merge a dragon can disappear from fair perception
         * as soon as its root hitbox is outside the current view cone. The
         * loaded/range gates are applied immediately before publication, and
         * the existing FOV and block-clip checks decide whether a part is
         * actually visible.
         */
        for (EnderDragonPart part : level.dragonParts()) {
            if (part.isAlive()) {
                candidates.add(part);
            }
        }
        /*
         * A dragon can be recreated by EnderDragonFight while its root is
         * already in the loaded entity getter but before the level's
         * auxiliary dragonParts map has been repopulated. This occurs during
         * a legitimate dimension-entry/relogin transition and made a live
         * companion report zero entity candidates despite eight loaded
         * colliders. Enumerate only the server's currently loaded dragon
         * roots as a bounded fallback. The index is deliberately broad during
         * a dimension handoff; range, loaded, FOV, and block-clip checks remain
         * in sampleVisibleEntities before any fact is published.
         */
        for (EnderDragon dragon : level.getDragons()) {
            mergeCurrentDragonParts(candidates, dragon);
        }
        /*
         * EnderDragonFight can own a live root before the convenience
         * getDragons() collection is refreshed. Query the ordinary loaded
         * entity index as a second bounded root source. This is still a
         * normal server-side entity query inside the companion's range; it
         * does not inspect the fight manager's hidden target state.
         */
        final List<Entity> loadedDragonRoots = new ArrayList<>();
        level.getEntities(
                EntityTypeTest.forClass(EnderDragon.class),
                searchBox,
                entity -> entity.isAlive() && !entity.isSpectator(),
                loadedDragonRoots,
                budget.maxEntityCandidates()
        );
        for (Entity root : loadedDragonRoots) {
            if (root instanceof EnderDragon dragon) {
                mergeCurrentDragonParts(candidates, dragon);
            }
        }
        if (level.getDragonFight() != null) {
            final java.util.UUID dragonId =
                    level.getDragonFight().dragonUUID();
            if (dragonId != null) {
                final Entity trackedDragon = level.getEntity(dragonId);
                if (trackedDragon instanceof EnderDragon dragon) {
                    mergeCurrentDragonParts(candidates, dragon);
                }
            }
        }
        /*
         * Keep the bounded first-person budget useful during a dragon fight.
         * Ender-dragon breath leaves several short-lived AreaEffectCloud
         * entities around the body.  A pure distance sort lets those neutral
         * clouds consume all LOS checks before the dragon's multipart
         * colliders are reached, producing the false "no target" failure seen
         * in the live stronghold chain.  Threats stay in the same loaded,
         * distance-bounded candidate set; this only orders the finite work so
         * a hostile boss is not starved by its own visual aftermath.
         */
        candidates.sort(
                Comparator
                        .comparingInt((Entity entity) ->
                                isCurrentThreat(player, entity) ? 0 : 1)
                        .thenComparingInt(entity ->
                                canonicalPerceivedEntity(entity)
                                        instanceof EnderDragon ? 0 : 1)
                        .thenComparingDouble(player::distanceToSqr)
        );
        boolean truncated = candidates.size() >= budget.maxEntityCandidates();
        if (candidates.size() > budget.maxEntityCandidates()) {
            candidates.subList(
                    budget.maxEntityCandidates(),
                    candidates.size()
            ).clear();
        }
        return new CandidateBatch(
                List.copyOf(candidates),
                truncated
        );
    }

    private static void mergeCurrentDragonParts(
            final List<Entity> candidates,
            final EnderDragon dragon
    ) {
        /* Keep the canonical root in the same bounded candidate set.  The
         * multipart colliders are still preferred when one is the first
         * visible hit, but the root's own body box is a legitimate first-
         * person visual target when every narrow tail/wing ray is occluded. */
        candidates.removeIf(existing ->
                existing.getUUID().equals(dragon.getUUID())
        );
        candidates.add(dragon);
        for (EnderDragonPart part : dragon.getSubEntities()) {
            if (!part.isAlive()) {
                continue;
            }
            /*
             * The level multipart index can retain an old collider instance
             * across dimension entry while the authoritative dragon root
             * already owns freshly positioned parts. Replace by UUID instead
             * of keeping the stale index entry: otherwise the semantic
             * candidate can be 90+ blocks from the visible dragon even though
             * both objects report the same identity.
             */
            candidates.removeIf(existing ->
                    existing.getUUID().equals(part.getUUID())
            );
            candidates.add(part);
        }
    }

    private EntitySample sampleVisibleEntities(
            ServerPlayer player,
            ServerLevel level,
            BodySnapshot body,
            List<Entity> candidates
    ) {
        List<VisibleEntity> visible = new ArrayList<>(budget.maxVisibleEntities());
        int losChecks = 0;
        boolean truncated = false;
        Vec3 eye = minecraftVector(body.eyePosition());
        PerceptionVec3 look = body.lookDirection();
        Set<UUID> emittedEntityIds = new LinkedHashSet<>();

        for (Entity candidate : candidates) {
            if (visible.size() >= budget.maxVisibleEntities()) {
                truncated = true;
                break;
            }
            /*
             * Multipart entities are indexed as physical colliders but are
             * published as one semantic parent. Once one dragon part has
             * passed the ordinary FOV/LOS gate, do not spend the remaining
             * finite ray budget rechecking its sibling colliders before
             * other visible entities such as end crystals. A part that has
             * not yet passed the gate is still evaluated normally, so this
             * cannot make an unseen dragon appear.
             */
            if (candidate instanceof EnderDragonPart part
                    && part.parentMob != null
                    && emittedEntityIds.contains(part.parentMob.getUUID())) {
                continue;
            }
            double distanceSquared = player.distanceToSqr(candidate);
            if (!level.isLoaded(candidate.blockPosition())
                    || distanceSquared > budget.entityRange()
                        * budget.entityRange()
                    || candidate.isInvisibleTo(player)) {
                continue;
            }
            /*
             * A collision is a first-person sensory fact even when the body
             * is looking away or a hostile has pushed into the camera.  The
             * old path required the entity's eye to be inside the normal
             * view cone and then required a visual block clip, so a zombie
             * that was literally hitting the companion could remain absent
             * from visibleEntities.  The emergency lane consequently had a
             * damage signal but no legal target to turn toward or attack and
             * could spend the whole encounter looking/guarding.
             *
             * Contact does not authorize a hidden-world scan: candidates
             * still came from the bounded loaded-entity index, and the
             * ordinary actuator re-checks crosshair, reach and obstruction
             * before dispatching an attack.  It only gives the body a
             * collision-derived target that a real player can react to.
             */
            final boolean physicalContact = isCurrentThreat(player, candidate)
                    && player.getBoundingBox()
                        .inflate(0.1D)
                        .intersects(candidate.getBoundingBox());
            final List<Vec3> visualSamplePoints =
                    entityVisualSamplePoints(candidate);
            final boolean anyVisualPointInView = visualSamplePoints.stream()
                    .map(point -> vector(point.subtract(eye)))
                    .anyMatch(toTarget -> PerceptionGeometry.isInsideViewCone(
                            look,
                            toTarget,
                            budget.entityFieldOfViewDegrees()
                    ));
            /*
             * A player's view cone intersects a collider, not an abstract
             * entity eye. Tall mobs and dragon parts can have their eye above
             * the fovea while their torso is plainly on screen. Requiring the
             * eye alone caused a nearby dragon to disappear even when every
             * bounded center/offset ray was an unobstructed MISS.
             */
            if (!physicalContact && !anyVisualPointInView) {
                continue;
            }
            if (losChecks + 1
                    >= budget.maxEntityLosChecks()) {
                truncated = true;
                break;
            }
            Vec3 visiblePoint = null;
            boolean interactionLineClear = false;
            if (physicalContact) {
                /*
                 * Use the first bounded body point as an aim hint only.  Do
                 * not claim visual line-of-sight here; FairPlayerActuator
                 * will perform the real crosshair/clip check immediately
                 * before any entity interaction or attack packet.
                 */
                visiblePoint = visualSamplePoints.stream()
                        .findFirst()
                        .orElse(candidate.getEyePosition());
            } else {
                for (Vec3 candidatePoint : visualSamplePoints) {
                    if (losChecks + 1
                            >= budget.maxEntityLosChecks()) {
                        truncated = true;
                        break;
                    }
                    losChecks++;
                    if (!allHorizontalChunksLoaded(
                            level,
                            eye,
                            candidatePoint
                    )) {
                        continue;
                    }
                    final BlockHitResult visualObstruction =
                            level.clip(new ClipContext(
                                    eye,
                                    candidatePoint,
                                    ClipContext.Block.VISUAL,
                                    ClipContext.Fluid.NONE,
                                    player
                            ));
                    if (visualObstruction.getType()
                            == HitResult.Type.MISS) {
                        visiblePoint = candidatePoint;
                        break;
                    }
                }
                if (visiblePoint != null) {
                    losChecks++;
                    /*
                     * A tall entity's eye can be hidden by a two-block
                     * shelter while its lower body remains both visible and
                     * reachable. Reuse the first fairly visible point
                     * instead of inventing a second, eye-only interaction
                     * requirement. The collider clip still rejects
                     * transparent-looking solid obstructions.
                     */
                    final BlockHitResult interactionObstruction =
                            level.clip(new ClipContext(
                                    eye,
                                    visiblePoint,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    player
                            ));
                    interactionLineClear = interactionObstruction.getType()
                            == HitResult.Type.MISS;
                }
            }
            if (visiblePoint == null) {
                if (truncated) {
                    break;
                }
                continue;
            }

            /*
             * Dragon parts are individually pickable client entities, but
             * they are not independent semantic actors. Publish one stable
             * parent UUID while retaining the part as the fair visual/LOS
             * evidence. The action layer will resolve that parent UUID back
             * to whichever real part remains under the crosshair.
             */
            final Entity perceived =
                    canonicalPerceivedEntity(candidate);
            if (!emittedEntityIds.add(perceived.getUUID())) {
                continue;
            }
            final double perceivedDistanceSquared =
                    player.distanceToSqr(perceived);
            Vec3 candidatePosition = perceived.position();
            final Map<String, String> visibleProperties =
                    new TreeMap<>(visibleEntityProperties(
                            player,
                            perceived,
                            interactionLineClear
                    ));
            /*
             * Expose the exact point that passed the player's visual ray
             * checks. Entity.position() is the feet position; aiming there
             * can miss a hostile that has walked inside the player's
             * horizontal footprint. This point is ordinary first-person
             * evidence already computed above, not hidden entity state.
             */
            visibleProperties.put(
                    "interactionAimX",
                    fairCoordinate(visiblePoint.x)
            );
            visibleProperties.put(
                    "interactionAimY",
                    fairCoordinate(visiblePoint.y)
            );
            visibleProperties.put(
                    "interactionAimZ",
                    fairCoordinate(visiblePoint.z)
            );
            if (candidate instanceof EnderDragonPart) {
                visibleProperties.put(
                        "multipartParent",
                        "true"
                );
            }
            visible.add(new VisibleEntity(
                    perceived.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE
                        .getKey(perceived.getType())
                        .toString(),
                    vector(candidatePosition),
                    vector(candidatePosition.subtract(player.position())),
                    Math.sqrt(perceivedDistanceSquared),
                    isHostilePerceivedEntity(perceived),
                    perceived instanceof Projectile,
                    physicalContact
                            ? PerceptionProvenance.PHYSICAL_CONTACT
                            : PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                    Map.copyOf(visibleProperties)
            ));
        }
        return new EntitySample(List.copyOf(visible), losChecks, truncated);
    }

    private static Entity canonicalPerceivedEntity(
            final Entity candidate
    ) {
        if (candidate instanceof EnderDragonPart part
                && part.parentMob != null) {
            return part.parentMob;
        }
        return candidate;
    }

    private static String fairCoordinate(final double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static List<Vec3> entityVisualSamplePoints(
            final Entity entity
    ) {
        final AABB box = entity.getBoundingBox();
        final double centerX = (box.minX + box.maxX) * 0.5;
        final double centerY = (box.minY + box.maxY) * 0.5;
        final double centerZ = (box.minZ + box.maxZ) * 0.5;
        final double offsetX = Math.max(
                0.04,
                box.getXsize() * 0.32
        );
        final double offsetZ = Math.max(
                0.04,
                box.getZsize() * 0.32
        );
        if (entity instanceof EnderDragonPart) {
            /*
             * EnderDragonPart#getEyePosition() is an entity-wide eye
             * offset, not a point guaranteed to remain inside the small
             * tail/wing collider.  Publishing that point as the first
             * interaction aim can leave a real player looking just above a
             * visible part: the semantic dragon is present, but the vanilla
             * crosshair ray never selects a part to attack.  Start with the
             * part's collision-box center and bounded horizontal samples;
             * every point still passes the ordinary first-person LOS clip
             * below, and keep the eye only as a fair fallback for occlusion.
             */
            return List.of(
                    new Vec3(centerX, centerY, centerZ),
                    new Vec3(
                            centerX + offsetX,
                            centerY,
                            centerZ
                    ),
                    new Vec3(
                            centerX - offsetX,
                            centerY,
                            centerZ
                    ),
                    new Vec3(
                            centerX,
                            centerY,
                            centerZ + offsetZ
                    ),
                    new Vec3(
                            centerX,
                            centerY,
                            centerZ - offsetZ
                    ),
                    entity.getEyePosition()
            );
        }
        if (entity instanceof net.minecraft.world.entity.monster.EnderMan) {
            /*
             * Endermen teleport when a nearby player looks at their eyes.
             * A normal player therefore aims melee at the torso/legs. The
             * center and offsets below pass the exact same first-person block
             * clips as every other sample; changing their order exposes no
             * extra entity or world state. Keep the eye as a final fallback
             * for partial occlusion instead of turning every emergency swing
             * into an accidental stare-and-teleport loop.
             */
            return List.of(
                    new Vec3(centerX, centerY, centerZ),
                    new Vec3(
                            centerX + offsetX,
                            centerY,
                            centerZ
                    ),
                    new Vec3(
                            centerX - offsetX,
                            centerY,
                            centerZ
                    ),
                    new Vec3(
                            centerX,
                            centerY,
                            centerZ + offsetZ
                    ),
                    new Vec3(
                            centerX,
                            centerY,
                            centerZ - offsetZ
                    ),
                    entity.getEyePosition()
            );
        }
        return List.of(
                entity.getEyePosition(),
                new Vec3(centerX, centerY, centerZ),
                new Vec3(
                        centerX + offsetX,
                        centerY,
                        centerZ
                ),
                new Vec3(
                        centerX - offsetX,
                        centerY,
                        centerZ
                ),
                new Vec3(
                        centerX,
                        centerY,
                        centerZ + offsetZ
                ),
                new Vec3(
                        centerX,
                        centerY,
                        centerZ - offsetZ
                )
        );
    }

    private static Map<String, String> visibleEntityProperties(
            final ServerPlayer player,
            final Entity entity,
            final boolean interactionLineClear
    ) {
        final Map<String, String> properties =
                new java.util.TreeMap<>();
        properties.put(
                "interactionLineClear",
                Boolean.toString(interactionLineClear)
        );
        properties.put(
                "customNamed",
                Boolean.toString(entity.hasCustomName())
        );
        if (entity instanceof Projectile) {
            /*
             * The local emergency lane must not dodge arrows fired by the
             * companion itself.  They remain ordinary visible entities, but
             * vanilla arrows cannot damage their owner and are not a threat
             * signal.  Preserve that distinction as semantic provenance so a
             * combat skill does not mistake its own projectile for hostile
             * fire while still using only the visible entity list.
             */
            properties.put(
                    "projectileThreat",
                    Boolean.toString(isCurrentThreat(player, entity))
            );
        }
        if (entity instanceof AgeableMob ageable) {
            properties.put(
                    "baby",
                    Boolean.toString(ageable.isBaby())
            );
        }
        if (entity instanceof Mob mob) {
            properties.put(
                    "leashed",
                    Boolean.toString(mob.isLeashed())
            );
        }
        if (entity instanceof TamableAnimal tamable) {
            properties.put(
                    "tamed",
                    Boolean.toString(tamable.isTame())
            );
        }
        if (entity instanceof Player visiblePlayer) {
            properties.put(
                    "playerName",
                    visiblePlayer.getGameProfile()
                            .name()
                            .toLowerCase(Locale.ROOT)
            );
            properties.put(
                    "crouching",
                    Boolean.toString(visiblePlayer.isCrouching())
            );
            properties.put(
                    "sprinting",
                    Boolean.toString(visiblePlayer.isSprinting())
            );
        }
        if (entity instanceof ItemEntity dropped) {
            final ItemStack stack = dropped.getItem();
            if (!stack.isEmpty()) {
                properties.put(
                        "itemId",
                        BuiltInRegistries.ITEM.getKey(
                            stack.getItem()
                        ).toString()
                );
            }
        }
        if (entity instanceof EnderDragon dragon) {
            final double ratio = dragon.getHealth()
                    / dragon.getMaxHealth();
            final String band;
            if (ratio <= 0.15) {
                band = "critical";
            } else if (ratio <= 0.40) {
                band = "low";
            } else if (ratio <= 0.75) {
                band = "medium";
            } else {
                band = "high";
            }
            properties.put("bossHealthBand", band);
        }
        return Map.copyOf(properties);
    }

    private BlockSample sampleVisibleBlockFaces(
            ServerPlayer player,
            ServerLevel level,
            BodySnapshot body
    ) {
        List<PerceptionVec3> directions = centerFirst(
                PerceptionGeometry.rayFan(
                        player.getYRot(),
                        player.getXRot(),
                        budget.blockHorizontalFieldOfViewDegrees(),
                        budget.blockVerticalFieldOfViewDegrees(),
                        budget.blockRayColumns(),
                        budget.blockRayRows()
                ),
                body.lookDirection()
        );
        Vec3 eye = minecraftVector(body.eyePosition());
        Map<BlockFaceKey, VisibleBlockFace> uniqueFaces = new LinkedHashMap<>();
        List<ClearSightRay> clearRays = new ArrayList<>();
        int raysCast = 0;
        boolean truncated = false;

        for (PerceptionVec3 direction : directions) {
            if (uniqueFaces.size() >= budget.maxVisibleBlockFaces()) {
                truncated = true;
                break;
            }
            Vec3 end = eye.add(minecraftVector(direction).scale(budget.blockRange()));
            if (!allHorizontalChunksLoaded(level, eye, end)) {
                continue;
            }
            raysCast++;
            /*
             * Block-face observations model what the player's crosshair can
             * actually select. VISUAL is an occlusion shape, not a picking
             * shape; vanilla deliberately gives blocks such as iron bars an
             * empty visual shape even though their thin outline is plainly
             * rendered and targetable. OUTLINE follows the normal player
             * block-picking path while remaining a finite first-person ray.
             */
            BlockHitResult hit = clipFirstOpaqueVisibleSurface(
                level,
                new ClipContext(
                    eye,
                    end,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.ANY,
                    player
                )
            );
            final Vec3 unobstructedEnd =
                hit.getType() == HitResult.Type.BLOCK
                    ? hit.getLocation()
                    : end;
            final Optional<RayVisibleBlock> rayPortal =
                firstRayVisiblePortal(level, eye, unobstructedEnd);
            if (rayPortal.isPresent()
                    && uniqueFaces.size()
                        < budget.maxVisibleBlockFaces()) {
                final RayVisibleBlock visual =
                    rayPortal.orElseThrow();
                    final BlockFaceKey key = new BlockFaceKey(
                        visual.position().getX(),
                        visual.position().getY(),
                        visual.position().getZ(),
                        visual.face()
                    );
                    uniqueFaces.putIfAbsent(
                        key,
                        new VisibleBlockFace(
                            new BlockCoordinate(
                                visual.position().getX(),
                                visual.position().getY(),
                                visual.position().getZ()
                            ),
                            visual.blockTypeId(),
                            visual.face(),
                            vector(visual.hitPosition()),
                            visual.hitPosition().distanceTo(eye),
                            PerceptionProvenance
                                .BLOCK_TRANSLUCENT_RAY_SAMPLE,
                            visibleStateProperties(visual.state()),
                            TopSupportAffordance.UNKNOWN,
                            collisionAffordance(
                                    visual.state(),
                                    level,
                                    visual.position()
                            ),
                            -1
                        )
                    );
            }
            /*
             * One ray can now yield both a transparent portal and the first
             * opaque surface behind it. Re-check the result limit between
             * those two insertions; the loop-entry guard alone can otherwise
             * grow a 24-face budget to 25.
             */
            if (uniqueFaces.size()
                    >= budget.maxVisibleBlockFaces()) {
                truncated = true;
                break;
            }
            if (hit.getType() == HitResult.Type.MISS) {
                clearRays.add(new ClearSightRay(
                    vector(end),
                    end.distanceTo(eye),
                    PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
                ));
                continue;
            }
            if (hit.getType() != HitResult.Type.BLOCK
                    || !level.isLoaded(hit.getBlockPos())) {
                continue;
            }
            Vec3 location = hit.getLocation();
            clearSegmentBeforeHit(
                    vector(eye),
                    vector(location)
            ).ifPresent(clearRays::add);
            BlockPos blockPos = hit.getBlockPos();
            String face = hit.getDirection().getName();
            BlockFaceKey key = new BlockFaceKey(
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    face
            );
            if (uniqueFaces.containsKey(key)) {
                continue;
            }
            BlockState state = level.getBlockState(blockPos);
            uniqueFaces.put(key, new VisibleBlockFace(
                    new BlockCoordinate(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    face,
                    vector(location),
                    location.distanceTo(eye),
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    visibleStateProperties(state),
                    topSupportAffordance(
                        state,
                        level,
                        blockPos,
                        hit.getDirection()
                    ),
                    collisionAffordance(state, level, blockPos),
                    level.getRawBrightness(
                            blockPos.relative(hit.getDirection()),
                            0
                    )
            ));
        }
        return new BlockSample(
                List.copyOf(uniqueFaces.values()),
                List.copyOf(clearRays),
                raysCast,
                truncated
        );
    }

    /**
     * Mirrors {@link BlockGetter#clip(ClipContext)} while treating only the
     * translucent Nether-portal sheet as non-occluding.
     *
     * <p>In current vanilla versions a Nether portal has a thin OUTLINE
     * shape. A ray whose origin is inside that sheet therefore hits at
     * effectively zero distance, even though the player can plainly see the
     * terrain through the animated portal. That left a newly arrived player
     * with body evidence but no visible landing route. The portal itself is
     * still emitted separately by {@link #firstRayVisiblePortal}; this method
     * merely continues the same finite, loaded-only first-person ray to the
     * first opaque block or fluid behind it.</p>
     */
    static BlockHitResult clipFirstOpaqueVisibleSurface(
            final ServerLevel level,
            final ClipContext context
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(context, "context");
        return BlockGetter.traverseBlocks(
                context.getFrom(),
                context.getTo(),
                context,
                (clip, position) -> {
                    final BlockState blockState =
                            level.getBlockState(position);
                    final FluidState fluidState =
                            level.getFluidState(position);
                    final BlockHitResult blockResult;
                    if (blockState.is(Blocks.NETHER_PORTAL)) {
                        blockResult = null;
                    } else {
                        final VoxelShape blockShape =
                                clip.getBlockShape(
                                        blockState,
                                        level,
                                        position
                                );
                        blockResult =
                                level.clipWithInteractionOverride(
                                        clip.getFrom(),
                                        clip.getTo(),
                                        position,
                                        blockShape,
                                        blockState
                                );
                    }
                    final VoxelShape fluidShape =
                            clip.getFluidShape(
                                    fluidState,
                                    level,
                                    position
                            );
                    final BlockHitResult fluidResult =
                            fluidShape.clip(
                                    clip.getFrom(),
                                    clip.getTo(),
                                    position
                            );
                    final double blockDistance =
                            blockResult == null
                                    ? Double.MAX_VALUE
                                    : clip.getFrom().distanceToSqr(
                                            blockResult.getLocation()
                                    );
                    final double fluidDistance =
                            fluidResult == null
                                    ? Double.MAX_VALUE
                                    : clip.getFrom().distanceToSqr(
                                            fluidResult.getLocation()
                                    );
                    return blockDistance <= fluidDistance
                            ? blockResult
                            : fluidResult;
                },
                clip -> {
                    final Vec3 delta = clip.getFrom()
                            .subtract(clip.getTo());
                    return BlockHitResult.miss(
                            clip.getTo(),
                            Direction.getApproximateNearest(
                                    delta.x(),
                                    delta.y(),
                                    delta.z()
                            ),
                            BlockPos.containing(clip.getTo())
                    );
                }
        );
    }

    /**
     * The first hit for a block face becomes its canonical action evidence.
     * Process the player's crosshair before peripheral rays so an observed
     * target can be replayed by the vanilla crosshair check without replacing
     * it with a nearby off-axis sample. This only reorders the same bounded
     * ray fan; it performs no additional world query.
     */
    static List<PerceptionVec3> centerFirst(
            final List<PerceptionVec3> directions,
            final PerceptionVec3 look
    ) {
        Objects.requireNonNull(directions, "directions");
        Objects.requireNonNull(look, "look");
        final PerceptionVec3 forward = look.normalized();
        final List<PerceptionVec3> ordered =
                new ArrayList<>(directions);
        ordered.sort(Comparator.comparingDouble(
                direction -> -direction.dot(forward)
        ));
        return List.copyOf(ordered);
    }

    /**
     * Portals are plainly rendered to a player but intentionally have no
     * ordinary selectable VISUAL/OUTLINE surface. Sample only the finite,
     * already line-of-sight-cleared segment of an existing first-person ray
     * so those rendered blocks can be reported without scanning neighbouring
     * or occluded voxels.
     */
    private static Optional<RayVisibleBlock> firstRayVisiblePortal(
            final ServerLevel level,
            final Vec3 eye,
            final Vec3 unobstructedEnd
    ) {
        final Vec3 delta = unobstructedEnd.subtract(eye);
        final double distance = delta.length();
        if (distance <= 1.0E-6) {
            return Optional.empty();
        }
        final int samples = Math.max(
            1,
            (int) Math.ceil(distance / 0.10)
        );
        BlockPos previous = null;
        for (int sample = 1; sample < samples; sample++) {
            final double interpolation =
                (double) sample / samples;
            final Vec3 point = eye.add(delta.scale(interpolation));
            final BlockPos position = BlockPos.containing(point);
            if (position.equals(previous)
                    || !level.isLoaded(position)) {
                continue;
            }
            previous = position;
            final BlockState state = level.getBlockState(position);
            final String id = BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .toString();
            if (id.equals("minecraft:nether_portal")
                    || id.equals("minecraft:end_portal")
                    || id.equals("minecraft:end_gateway")) {
                return Optional.of(new RayVisibleBlock(
                    position.immutable(),
                    id,
                    entryFace(delta),
                    point,
                    state
                ));
            }
        }
        return Optional.empty();
    }

    private static String entryFace(final Vec3 direction) {
        final double x = Math.abs(direction.x());
        final double y = Math.abs(direction.y());
        final double z = Math.abs(direction.z());
        if (x >= y && x >= z) {
            return direction.x() >= 0.0 ? "west" : "east";
        }
        if (y >= z) {
            return direction.y() >= 0.0 ? "down" : "up";
        }
        return direction.z() >= 0.0 ? "north" : "south";
    }

    static Optional<ClearSightRay> clearSegmentBeforeHit(
            final PerceptionVec3 eye,
            final PerceptionVec3 hit
    ) {
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(hit, "hit");
        final PerceptionVec3 delta = hit.subtract(eye);
        final double distance = delta.length();
        if (distance <= BLOCK_HIT_CLEAR_RETREAT) {
            return Optional.empty();
        }
        final double clearDistance =
                distance - BLOCK_HIT_CLEAR_RETREAT;
        return Optional.of(new ClearSightRay(
                eye.add(delta.scale(clearDistance / distance)),
                clearDistance,
                PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
        ));
    }

    private DangerSample sampleDangers(
            ServerPlayer player,
            BodySnapshot body,
            List<Entity> candidates,
            Set<UUID> fairlyVisibleEntityIds
    ) {
        List<DangerSignal> signals = new ArrayList<>(budget.maxDangerSignals());
        Set<DangerKind> emitted = EnumSet.noneOf(DangerKind.class);
        boolean truncated = false;

        truncated |= addDanger(
                signals,
                emitted,
                body.onFire()
                        ? Optional.of(new DangerSignal(
                                DangerKind.ON_FIRE,
                                1.0,
                                0.0,
                                Optional.empty(),
                                PerceptionProvenance.BODY_HAZARD
                        ))
                        : Optional.empty()
        );
        double airRatio = (double) body.airSupply() / body.maxAirSupply();
        truncated |= addDanger(
                signals,
                emitted,
                airRatio < 0.25
                        ? Optional.of(new DangerSignal(
                                DangerKind.LOW_AIR,
                                Math.min(1.0, Math.max(0.25, 1.0 - airRatio)),
                                0.0,
                                Optional.empty(),
                                PerceptionProvenance.BODY_HAZARD
                        ))
                        : Optional.empty()
        );
        truncated |= addDanger(
                signals,
                emitted,
                !body.onGround() && !body.inWater() && body.fallDistance() > 3.0
                        ? Optional.of(new DangerSignal(
                                DangerKind.FALLING,
                                Math.min(1.0, body.fallDistance() / 20.0),
                                0.0,
                                Optional.empty(),
                                PerceptionProvenance.BODY_HAZARD
                        ))
                        : Optional.empty()
        );

        int inspected = 0;
        AABB contactBox = player.getBoundingBox().inflate(0.1);
        for (Entity candidate : candidates) {
            if (signals.size() >= budget.maxDangerSignals()) {
                truncated = true;
                break;
            }
            inspected++;
            boolean threat = isCurrentThreat(player, candidate);
            if (!threat) {
                continue;
            }
            double distance = player.distanceTo(candidate);
            if (contactBox.intersects(candidate.getBoundingBox())) {
                PerceptionVec3 direction = vector(
                        candidate.position().subtract(player.position())
                );
                Optional<PerceptionVec3> normalizedDirection =
                        direction.lengthSquared() <= 1.0E-12
                                ? Optional.empty()
                                : Optional.of(direction.normalized());
                truncated |= addDanger(
                        signals,
                        emitted,
                        Optional.of(new DangerSignal(
                                DangerKind.THREAT_CONTACT,
                                1.0,
                                distance,
                                normalizedDirection,
                                PerceptionProvenance.PHYSICAL_CONTACT
                        ))
                );
                continue;
            }
            if (distance > budget.dangerRange()) {
                continue;
            }
            /*
             * AABB membership is only a bounded candidate index; it is never
             * itself a sensory fact. Ordinary proximity remains limited to an
             * entity that passed the same first-person FOV and visual
             * block-clip gate as the public semantic entity list. A separate
             * server sound-event channel may add only a short-lived,
             * identity-free auditory cue through the core frame source.
             * Physical contact remains fair without sight and was handled
             * above.
             */
            /*
             * Multipart entities publish one semantic parent UUID in the
             * visible-entity list.  The danger pass still iterates the
             * bounded physical part candidates, so comparing the raw part
             * UUID here silently discarded every dragon proximity cue.  Use
             * the same parent identity that the public observation exposes;
             * distance, contact, and direction remain measured from the
             * actual loaded collider below.
             */
            if (!fairlyVisibleEntityIds.contains(
                    canonicalPerceivedEntity(candidate).getUUID()
            )) {
                continue;
            }
            DangerKind kind = candidate instanceof Projectile
                    ? DangerKind.PROJECTILE_PROXIMITY
                    : DangerKind.HOSTILE_PROXIMITY;
            truncated |= addDanger(
                    signals,
                    emitted,
                    Optional.of(new DangerSignal(
                            kind,
                            proximitySeverity(distance, budget.dangerRange()),
                            proximityDistanceBand(distance),
                            Optional.empty(),
                            PerceptionProvenance.PROXIMITY_THREAT
                    ))
            );
        }
        return new DangerSample(List.copyOf(signals), inspected, truncated);
    }

    /**
     * A projectile is not automatically a threat to the companion. Vanilla
     * arrows shot by this same player cannot damage their owner, and embedded
     * arrows remain entities for a long time after becoming inert. They stay
     * visible semantic entities, but must not poison movement risk.
     */
    private static boolean isCurrentThreat(
            final ServerPlayer player,
            final Entity candidate
    ) {
        if (candidate instanceof EnderDragon dragon) {
            /*
             * The dragon root is not an Enemy marker in vanilla, but it is
             * the authoritative hostile boss represented by every
             * EnderDragonPart. Treating the root as a threat keeps it ahead
             * of neutral aftermath entities in the bounded candidate budget
             * and lets the emergency lane publish a fair proximity signal
             * when the narrow multipart colliders are between samples.
             */
            return dragon.isAlive() && !dragon.isRemoved();
        }
        if (candidate instanceof Enemy) {
            return true;
        }
        if (candidate instanceof EnderDragonPart part) {
            /*
             * EnderDragonPart is a dedicated vanilla damage collider rather
             * than a Mob and its parent marker can be stale across a
             * dimension handoff. The part is hostile iff its live parent is
             * still alive; no world lookup or hidden target scan is needed.
             */
            return part.parentMob != null
                    && part.parentMob.isAlive()
                    && !part.parentMob.isRemoved();
        }
        if (candidate instanceof AreaEffectCloud cloud) {
            /*
             * Ender-dragon breath is a real, short-lived area hazard. It is
             * fair to classify it only while the visible cloud is active and
             * has a positive radius; waiting/expired clouds are retained as
             * semantic entities but must not trigger an invented threat.
             */
            return cloud.isAlive()
                    && !cloud.isRemoved()
                    && !cloud.isWaiting()
                    && cloud.getDuration() > 0
                    && cloud.getRadius() > 0.1F;
        }
        if (!(candidate instanceof Projectile projectile)
                || projectile.getOwner() == player) {
            return false;
        }
        return !(projectile instanceof AbstractArrow arrow)
                || arrow.isPickable();
    }

    private static boolean isHostilePerceivedEntity(
            final Entity entity
    ) {
        return entity instanceof Enemy
                || entity instanceof EnderDragon;
    }

    /**
     * @return true if a signal existed but could not be added due to budget.
     */
    private boolean addDanger(
            List<DangerSignal> signals,
            Set<DangerKind> emitted,
            Optional<DangerSignal> candidate
    ) {
        if (candidate.isEmpty()) {
            return false;
        }
        DangerSignal signal = candidate.orElseThrow();
        if (emitted.contains(signal.kind())) {
            return false;
        }
        if (signals.size() >= budget.maxDangerSignals()) {
            return true;
        }
        signals.add(signal);
        emitted.add(signal.kind());
        return false;
    }

    private static List<InventoryItemSummary> summarizeInventory(ServerPlayer player) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Math::addExact);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new InventoryItemSummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Block-state properties affect the pixels of the surface that was
     * actually ray-hit (crop age, door openness, facing, and so on), so they
     * are fair semantic perception rather than a scan of hidden blocks.
     */
    private static Map<String, String> visibleStateProperties(
            BlockState state
    ) {
        Map<String, String> properties = new TreeMap<>();
        for (Property<?> property : state.getProperties().stream()
                .sorted(Comparator.comparing(Property::getName))
                .limit(VisibleBlockFace.MAX_STATE_PROPERTIES)
                .toList()) {
            final String name = property.getName();
            final String value = visibleStateValue(state, property);
            if (VisibleBlockFace.isSafeStateToken(name)
                    && VisibleBlockFace.isSafeStateToken(value)) {
                properties.put(name, value);
            }
        }
        return Map.copyOf(properties);
    }

    /**
     * Reduces the already ray-visible surface to one non-geometric support
     * affordance.  No shape coordinates, neighboring states, or hidden blocks
     * cross the perception boundary.
     */
    private static TopSupportAffordance topSupportAffordance(
            final BlockState state,
            final ServerLevel level,
            final BlockPos position,
            final Direction hitFace
    ) {
        return BlockShapeAffordances.topSupport(
                state,
                level,
                position,
                hitFace
        );
    }

    private static CollisionAffordance collisionAffordance(
            final BlockState state,
            final ServerLevel level,
            final BlockPos position
    ) {
        return state.getCollisionShape(level, position).isEmpty()
                ? CollisionAffordance.EMPTY
                : CollisionAffordance.OBSTRUCTED_OR_PARTIAL;
    }

    private static <T extends Comparable<T>> String visibleStateValue(
            BlockState state,
            Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    private static List<EffectSummary> summarizeEffects(ServerPlayer player) {
        return player.getActiveEffects().stream()
                .map(FairPerceptionSampler::effect)
                .sorted(Comparator.comparing(EffectSummary::effectId))
                .toList();
    }

    private static EffectSummary effect(MobEffectInstance instance) {
        return new EffectSummary(
                BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()).toString(),
                instance.getAmplifier(),
                instance.getDuration(),
                instance.isAmbient()
        );
    }

    private static HeldItemSummary heldItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return HeldItemSummary.empty();
        }
        return new HeldItemSummary(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.getDamageValue(),
                stack.getMaxDamage()
        );
    }

    /**
     * Conservative loaded-only guard: checking the whole horizontal chunk
     * rectangle can reject a ray near an unloaded corner, but can never cause
     * the subsequent vanilla clip to touch an unchecked chunk.
     */
    private static boolean allHorizontalChunksLoaded(
            ServerLevel level,
            Vec3 from,
            Vec3 to
    ) {
        int minimumChunkX = blockToChunk(Math.min(from.x, to.x));
        int maximumChunkX = blockToChunk(Math.max(from.x, to.x));
        int minimumChunkZ = blockToChunk(Math.min(from.z, to.z));
        int maximumChunkZ = blockToChunk(Math.max(from.z, to.z));
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int blockToChunk(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), 16);
    }

    private static double proximitySeverity(double distance, double range) {
        double ratio = distance / range;
        if (ratio <= 0.25) {
            return 1.0;
        }
        if (ratio <= 0.5) {
            return 0.75;
        }
        return 0.5;
    }

    private static double proximityDistanceBand(double distance) {
        return Math.max(2.0, Math.ceil(distance / 2.0) * 2.0);
    }

    private static PerceptionVec3 vector(Vec3 vector) {
        return new PerceptionVec3(vector.x, vector.y, vector.z);
    }

    private static Vec3 minecraftVector(PerceptionVec3 vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private record CandidateBatch(List<Entity> entities, boolean truncated) {
    }

    private record EntitySample(
            List<VisibleEntity> visible,
            int losChecks,
            boolean truncated
    ) {
    }

    private record BlockSample(
            List<VisibleBlockFace> faces,
            List<ClearSightRay> clearRays,
            int raysCast,
            boolean truncated
    ) {
    }

    private record DangerSample(
            List<DangerSignal> signals,
            int candidatesInspected,
            boolean truncated
    ) {
    }

    private record BlockFaceKey(int x, int y, int z, String face) {
    }

    private record RayVisibleBlock(
            BlockPos position,
            String blockTypeId,
            String face,
            Vec3 hitPosition,
            BlockState state
    ) {
    }
}
