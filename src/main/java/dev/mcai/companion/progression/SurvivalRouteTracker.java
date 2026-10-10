package dev.mcai.companion.progression;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.skills.building.DynamicShelterPlanner;
import dev.mcai.companion.skills.core.VanillaFoodItems;
import dev.mcai.companion.skills.foundation.PrepareBasicCraftingSkill;
import dev.mcai.companion.skills.stronghold.EyeTraceHistorySnapshot;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Updates route evidence from the companion's own inventory/dimension and
 * event-backed world audit. It never locates structures or scans blocks.
 */
public final class SurvivalRouteTracker {
    private static final List<SurvivalMilestone> FOUNDATION_GUIDANCE_ORDER =
            List.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.WOOD_OBTAINED,
                    SurvivalMilestone.BASIC_CRAFTING_READY,
                    SurvivalMilestone.STONE_TOOL_OBTAINED,
                    SurvivalMilestone.FOOD_SECURED,
                    SurvivalMilestone.IRON_OBTAINED,
                    SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                    SurvivalMilestone.WORKSTATIONS_ESTABLISHED,
                    SurvivalMilestone.SUPPLIES_STORED,
                    SurvivalMilestone.SHELTER_MATERIALS_PREPARED,
                    SurvivalMilestone.SHELTER_COMPLETED,
                    SurvivalMilestone.FIRST_NIGHT_SURVIVED
            );
    private static final List<SurvivalMilestone> COMPLETION_GUIDANCE_ORDER =
            List.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.WOOD_OBTAINED,
                    SurvivalMilestone.BASIC_CRAFTING_READY,
                    SurvivalMilestone.STONE_TOOL_OBTAINED,
                    SurvivalMilestone.FOOD_SECURED,
                    SurvivalMilestone.IRON_OBTAINED,
                    SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                    SurvivalMilestone.NETHER_ENTERED,
                    SurvivalMilestone.BLAZE_MATERIAL_OBTAINED,
                    SurvivalMilestone.ENDER_PEARL_OBTAINED,
                    SurvivalMilestone.EYE_OF_ENDER_CRAFTED,
                    SurvivalMilestone.STRONGHOLD_BEARING_MEASURED,
                    SurvivalMilestone
                            .STRONGHOLD_SEARCH_AREA_TRIANGULATED,
                    SurvivalMilestone.END_LOADOUT_PREPARED,
                    SurvivalMilestone.END_ENTERED,
                    SurvivalMilestone.END_ISLAND_REACHED,
                    SurvivalMilestone.DRAGON_KILLED,
                    SurvivalMilestone.RETURNED_FROM_END
    );
    private static final int FOUNDATION_FOOD_RESERVE = 8;
    /**
     * One log is not a usable foundation handoff: the next vanilla phase
     * needs a table, sticks, and a wooden pickaxe while retaining a small
     * reserve.  Advancing the route on the first pickup made the model
     * planner retire its gatherer before the body had enough material for
     * basic crafting.
     */
    private static final int FOUNDATION_WOOD_RESERVE = 5;
    private static final int FOUNDATION_CHEST_PLANKS = 8;
    static final int COMPLETION_BLAZE_ROUTE_UNITS =
            CompletionResourceReadiness.BLAZE_ROUTE_UNITS;
    static final int COMPLETION_ENDER_ROUTE_UNITS =
            CompletionResourceReadiness.ENDER_ROUTE_UNITS;
    static final int COMPLETION_EYES_READY =
            CompletionResourceReadiness.EYES_READY;
    static final int COMPLETION_END_BUILDING_BLOCKS =
            CompletionResourceReadiness.END_BUILDING_BLOCKS;
    static final int COMPLETION_END_BOWS = CompletionResourceReadiness.END_BOWS;
    static final int COMPLETION_END_ARROWS =
            CompletionResourceReadiness.END_ARROWS;
    private static final int MAX_PROJECTED_INVENTORY_COUNT = 36 * 64;
    private static final Set<String> BUILDING_BLOCK_IDS = Set.of(
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:netherrack",
            "minecraft:blackstone"
    );
    private static final Set<String> STRONG_PICKAXES = Set.of(
            "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );
    private static final Set<String> STONE_OR_BETTER_PICKAXES = Set.of(
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );
    private static final Set<String> WOOD_OR_BETTER_PICKAXES = Set.of(
            "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );

    private final CompanionWorldData worldData;

    public SurvivalRouteTracker(final CompanionWorldData worldData) {
        this.worldData = Objects.requireNonNull(worldData, "worldData");
    }

    public Optional<SurvivalRouteSnapshot> snapshot(
            final GoalSnapshot goal,
            final ServerPlayer player,
            final Optional<EyeTraceHistorySnapshot> eyeHistory
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(eyeHistory, "eyeHistory");
        final Optional<SurvivalRouteProfile> selectedProfile =
                profile(goal);
        if (selectedProfile.isEmpty()) {
            return Optional.empty();
        }
        final SurvivalRouteProfile profile =
                selectedProfile.orElseThrow();
        if (!worldData.companionUuid().equals(player.getUUID())) {
            throw new IllegalArgumentException(
                    "Route tracker received another player"
            );
        }
        final Map<String, Integer> inventory = inventory(player);
        final Map<String, Integer> criticalCounts =
                criticalCounts(inventory);
        final EnumSet<SurvivalMilestone> observed =
                EnumSet.of(SurvivalMilestone.BODY_ACTIVE);
        if (hasWood(inventory, requiredWoodReserve(goal))) {
            observed.add(SurvivalMilestone.WOOD_OBTAINED);
        }
        final boolean basicCraftingReady = hasAny(
                inventory,
                WOOD_OR_BETTER_PICKAXES
        ) && (
                count(inventory, "minecraft:crafting_table") > 0
                || worldData
                    .verifiedFoundationEvidence(goal.revision())
                    .flatMap(VerifiedFoundationEvidence::craftingTable)
                    .isPresent()
        );
        if (basicCraftingReady) {
            observed.add(SurvivalMilestone.BASIC_CRAFTING_READY);
        }
        if (safeFoodCount(inventory) >= FOUNDATION_FOOD_RESERVE) {
            observed.add(SurvivalMilestone.FOOD_SECURED);
        }
        if (hasAny(inventory, STONE_OR_BETTER_PICKAXES)) {
            observed.add(SurvivalMilestone.STONE_TOOL_OBTAINED);
        }
        if (hasIronMaterialOrProduct(inventory)) {
            observed.add(SurvivalMilestone.IRON_OBTAINED);
        }
        if (hasAny(inventory, STRONG_PICKAXES)
                && count(inventory, "minecraft:bucket")
                    + count(inventory, "minecraft:water_bucket")
                    + count(inventory, "minecraft:lava_bucket") > 0
                && count(inventory, "minecraft:shield") > 0) {
            observed.add(SurvivalMilestone.IRON_TOOLKIT_OBTAINED);
        }
        if (criticalCounts.get("same_structural_item")
                        >= DynamicShelterPlanner
                                .structuralBlockCount(3, 3)
                && criticalCounts.get("safe_doors") >= 1
                && criticalCounts.get("shelter_lights") >= 1) {
            observed.add(
                    SurvivalMilestone.SHELTER_MATERIALS_PREPARED
            );
        }
        if (player.level().dimension().equals(Level.NETHER)) {
            observed.add(SurvivalMilestone.NETHER_ENTERED);
        }
        if (blazeRouteUnits(inventory)
                >= COMPLETION_BLAZE_ROUTE_UNITS) {
            observed.add(
                    SurvivalMilestone.BLAZE_MATERIAL_OBTAINED
            );
        }
        if (enderRouteUnits(inventory)
                >= COMPLETION_ENDER_ROUTE_UNITS) {
            observed.add(SurvivalMilestone.ENDER_PEARL_OBTAINED);
        }
        if (count(inventory, "minecraft:ender_eye")
                >= COMPLETION_EYES_READY) {
            observed.add(SurvivalMilestone.EYE_OF_ENDER_CRAFTED);
        }
        eyeHistory.ifPresent(history -> {
            observed.add(
                    SurvivalMilestone.STRONGHOLD_BEARING_MEASURED
            );
            if (history.estimatedIntersection().isPresent()) {
                observed.add(
                        SurvivalMilestone
                                .STRONGHOLD_SEARCH_AREA_TRIANGULATED
                );
            }
        });
        final boolean triangulatedStronghold = observed.contains(
                SurvivalMilestone.STRONGHOLD_SEARCH_AREA_TRIANGULATED
        ) || worldData.verifiedRouteProgress(goal.revision())
                .milestones()
                .contains(SurvivalMilestone.STRONGHOLD_SEARCH_AREA_TRIANGULATED);
        if (triangulatedStronghold && endLoadoutReady(player)) {
            observed.add(SurvivalMilestone.END_LOADOUT_PREPARED);
        }
        if (player.level().dimension().equals(Level.END)) {
            observed.add(SurvivalMilestone.END_ENTERED);
        }
        final boolean inEnd = player.level().dimension().equals(Level.END);
        if (worldData.evaluationDragonKilled()) {
            observed.add(SurvivalMilestone.DRAGON_KILLED);
        }
        if (worldData.evaluationReturnedFromEnd()) {
            observed.add(SurvivalMilestone.RETURNED_FROM_END);
        }
        if (profile == SurvivalRouteProfile.FOUNDATION) {
            final long currentDay = Math.floorDiv(
                    player.level().getServer()
                            .overworld()
                            .getOverworldClockTime(),
                    24_000L
            );
            final long startedDay = worldData.initializeRouteStartDay(
                    goal.revision(),
                    currentDay
            );
            if (currentDay > startedDay) {
                observed.add(
                        SurvivalMilestone.FIRST_NIGHT_SURVIVED
                );
            }
            final boolean shelterStillValid = worldData
                    .verifiedShelterEvidence(goal.revision())
                    .filter(evidence ->
                            ServerShelterEvidenceVerifier.verify(
                                    player.level().getServer(),
                                    evidence
                            )
                    )
                    .isPresent();
            worldData.updateShelterVerification(
                    goal.revision(),
                    shelterStillValid
            );
            final ServerFoundationEvidenceVerifier.Result
                    foundationVerification = worldData
                            .verifiedFoundationEvidence(goal.revision())
                            .map(evidence ->
                                    ServerFoundationEvidenceVerifier.verify(
                                            player.level().getServer(),
                                            evidence
                                    )
                            )
                            .orElseGet(() ->
                                    new ServerFoundationEvidenceVerifier
                                            .Result(false, false)
                            );
            worldData.updateFoundationVerification(
                    goal.revision(),
                    foundationVerification.workstationsEstablished(),
                    foundationVerification.suppliesStored()
            );
            worldData.updateFoundationInventoryVerification(
                    goal.revision(),
                    basicCraftingReady,
                    observed.contains(SurvivalMilestone.FOOD_SECURED),
                    observed.contains(
                            SurvivalMilestone.STONE_TOOL_OBTAINED
                    ),
                    observed.contains(
                            SurvivalMilestone.IRON_TOOLKIT_OBTAINED
                    )
            );
        }
        worldData.markVerifiedRouteMilestones(
                goal.revision(),
                observed
        );
        final VerifiedRouteProgress progress =
                worldData.verifiedRouteProgress(goal.revision());
        final List<SurvivalMilestone> verified =
                progress.milestones().stream().sorted().toList();
        final List<SurvivalMilestone> fullGuidanceOrder =
                profile == SurvivalRouteProfile.FOUNDATION
                        ? FOUNDATION_GUIDANCE_ORDER
                        : COMPLETION_GUIDANCE_ORDER;
        final List<SurvivalMilestone> guidanceOrder =
                guidanceThroughTerminal(
                        goal,
                        fullGuidanceOrder
                );
        final Optional<SurvivalMilestone> next =
                nextMilestone(
                        profile,
                        guidanceOrder,
                        progress.milestones(),
                        inEnd,
                        progress.milestones().contains(
                                SurvivalMilestone.DRAGON_KILLED
                        ),
                        progress.milestones().contains(
                                SurvivalMilestone.RETURNED_FROM_END
                        ),
                        endLoadoutReady(player)
                );
        return Optional.of(new SurvivalRouteSnapshot(
                goal.revision(),
                profile,
                DimensionRef.parse(
                        player.level().dimension().identifier().toString()
                ),
                verified,
                next,
                safetyDeficits(
                        profile,
                        progress.milestones(),
                        inventory,
                        player
                ),
                next.map(SurvivalRouteTracker::objectiveFor)
                        .map(List::of)
                        .orElseGet(List::of),
                criticalCounts,
                minimumTargets(profile, progress.milestones()),
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.level().getServer().isHardcore(),
                worldData.evaluationElapsedTicks(
                        player.level().getServer()
                                .overworld()
                                .getGameTime()
                )
        ));
    }

    /**
     * Irreversible late-game state controls the active completion phase
     * without manufacturing earlier resource milestones. This matters when a
     * player creates or resumes a goal while the companion is already in the
     * End, after consumable prerequisites have legitimately disappeared from
     * inventory.
     */
    static Optional<SurvivalMilestone> nextMilestone(
            final SurvivalRouteProfile profile,
            final List<SurvivalMilestone> guidanceOrder,
            final Set<SurvivalMilestone> verified,
            final boolean currentlyInEnd,
            final boolean dragonKilled,
            final boolean returnedFromEnd,
            final boolean endLoadoutCurrentlyReady
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(guidanceOrder, "guidanceOrder");
        Objects.requireNonNull(verified, "verified");
        if (profile == SurvivalRouteProfile.COMPLETION) {
            if (returnedFromEnd) {
                return Optional.empty();
            }
            if (dragonKilled) {
                return Optional.of(
                        SurvivalMilestone.RETURNED_FROM_END
                );
            }
            if (currentlyInEnd) {
                if (!verified.contains(
                        SurvivalMilestone.END_LOADOUT_PREPARED
                )) {
                    return Optional.of(
                            SurvivalMilestone.END_LOADOUT_PREPARED
                    );
                }
                if (!verified.contains(
                        SurvivalMilestone.END_ISLAND_REACHED
                )) {
                    return Optional.of(
                            SurvivalMilestone.END_ISLAND_REACHED
                    );
                }
                return Optional.of(
                        SurvivalMilestone.DRAGON_KILLED
                );
            }
            if (verified.contains(
                        SurvivalMilestone.END_LOADOUT_PREPARED
                    )
                    && !endLoadoutCurrentlyReady) {
                return Optional.of(
                        SurvivalMilestone.END_LOADOUT_PREPARED
                );
            }
        }
        return guidanceOrder.stream()
                .filter(milestone -> !verified.contains(milestone))
                .findFirst();
    }

    private static List<SurvivalSafetyDeficit> safetyDeficits(
            final SurvivalRouteProfile profile,
            final Set<SurvivalMilestone> progress,
            final Map<String, Integer> inventory,
            final ServerPlayer player
    ) {
        final EnumSet<SurvivalSafetyDeficit> result =
                EnumSet.noneOf(SurvivalSafetyDeficit.class);
        if (player.getHealth() < 10.0F) {
            result.add(SurvivalSafetyDeficit.LOW_HEALTH);
        }
        if (player.getFoodData().getFoodLevel() < 8) {
            result.add(SurvivalSafetyDeficit.LOW_HUNGER);
        }
        if (progress.contains(SurvivalMilestone.STONE_TOOL_OBTAINED)
                && safeFoodCount(inventory)
                        < (profile == SurvivalRouteProfile.FOUNDATION
                                ? FOUNDATION_FOOD_RESERVE
                                : 8)) {
            result.add(SurvivalSafetyDeficit.FOOD_RESERVE_LOW);
        }
        if (progress.contains(SurvivalMilestone.IRON_TOOLKIT_OBTAINED)) {
            if (count(inventory, "minecraft:shield") == 0) {
                result.add(SurvivalSafetyDeficit.SHIELD_MISSING);
            }
            if (count(inventory, "minecraft:water_bucket") == 0) {
                result.add(
                        SurvivalSafetyDeficit.WATER_BUCKET_MISSING
                );
            }
        }
        if (profile == SurvivalRouteProfile.COMPLETION
                && progress.contains(
                        SurvivalMilestone.IRON_TOOLKIT_OBTAINED
                )
                && !progress.contains(
                        SurvivalMilestone.END_LOADOUT_PREPARED
                )
                && buildingBlocks(inventory)
                        < COMPLETION_END_BUILDING_BLOCKS) {
            result.add(
                    SurvivalSafetyDeficit
                            .BUILDING_BLOCK_RESERVE_LOW
            );
        }
        if (profile == SurvivalRouteProfile.COMPLETION
                && progress.contains(
                        SurvivalMilestone.EYE_OF_ENDER_CRAFTED
                )
                && criticalCounts(inventory).get("armor_pieces") < 3) {
            result.add(SurvivalSafetyDeficit.END_ARMOR_LOW);
        }
        return result.stream().sorted().toList();
    }

    private static SurvivalRouteObjective objectiveFor(
            final SurvivalMilestone milestone
    ) {
        return switch (milestone) {
            case BODY_ACTIVE, WOOD_OBTAINED ->
                    SurvivalRouteObjective.GATHER_VISIBLE_WOOD;
            case BASIC_CRAFTING_READY ->
                    SurvivalRouteObjective.PREPARE_BASIC_CRAFTING;
            case FOOD_SECURED ->
                    SurvivalRouteObjective.SECURE_FOOD_RESERVE;
            case STONE_TOOL_OBTAINED ->
                    SurvivalRouteObjective.CRAFT_AND_MINE_STONE;
            case LOG_STORAGE_DISTRIBUTED ->
                    SurvivalRouteObjective.DISTRIBUTE_LOG_STORAGE;
            case CONTAINER_WOOD_DOOR_PLACED ->
                    SurvivalRouteObjective
                            .RETRIEVE_CONTAINER_WOOD_AND_PLACE_DOOR;
            case IRON_OBTAINED ->
                    SurvivalRouteObjective.ACQUIRE_IRON_TOOLKIT;
            case IRON_TOOLKIT_OBTAINED ->
                    SurvivalRouteObjective.ACQUIRE_IRON_TOOLKIT;
            case WORKSTATIONS_ESTABLISHED ->
                    SurvivalRouteObjective
                            .ESTABLISH_FOUNDATION_WORKSTATIONS;
            case SUPPLIES_STORED ->
                    SurvivalRouteObjective.STORE_SURPLUS_SUPPLIES;
            case SHELTER_MATERIALS_PREPARED,
                    SHELTER_COMPLETED ->
                    SurvivalRouteObjective.BUILD_DYNAMIC_SHELTER;
            case FIRST_NIGHT_SURVIVED ->
                    SurvivalRouteObjective
                            .SURVIVE_OR_SLEEP_THROUGH_NIGHT;
            case NETHER_ENTERED ->
                    SurvivalRouteObjective
                            .BUILD_AND_VERIFY_NETHER_ROUTE;
            case BLAZE_MATERIAL_OBTAINED ->
                    SurvivalRouteObjective
                            .FIND_AND_ACQUIRE_BLAZE_MATERIAL;
            case ENDER_PEARL_OBTAINED ->
                    SurvivalRouteObjective.ACQUIRE_ENDER_PEARLS;
            case EYE_OF_ENDER_CRAFTED ->
                    SurvivalRouteObjective.CRAFT_EYES_OF_ENDER;
            case STRONGHOLD_BEARING_MEASURED ->
                    SurvivalRouteObjective
                            .TRACE_STRONGHOLD_BEARING;
            case STRONGHOLD_SEARCH_AREA_TRIANGULATED ->
                    SurvivalRouteObjective
                            .TRIANGULATE_STRONGHOLD_SEARCH_AREA;
            case END_LOADOUT_PREPARED ->
                    SurvivalRouteObjective.PREPARE_END_LOADOUT;
            case END_ENTERED ->
                    SurvivalRouteObjective
                            .ACTIVATE_AND_ENTER_END_PORTAL;
            case END_ISLAND_REACHED ->
                    SurvivalRouteObjective.REACH_END_ISLAND;
            case DRAGON_KILLED ->
                    SurvivalRouteObjective.DEFEAT_ENDER_DRAGON;
            case RETURNED_FROM_END ->
                    SurvivalRouteObjective.ENTER_RETURN_PORTAL;
        };
    }

    public static boolean isCompletionGoal(final GoalSnapshot goal) {
        final Optional<GoalExecutionPlan> plan = GoalExecutionPlan
                .fromDetailCode(goal.detailCode());
        if (plan.isPresent()) {
            return plan.orElseThrow().route()
                    == GoalExecutionPlan.Route.COMPLETION;
        }
        if (goal.source() == GoalSource.HARDCORE_EVALUATION) {
            return true;
        }
        final String normalized = goal.goal()
                .toLowerCase(Locale.ROOT);
        return normalized.contains("通关")
                || normalized.contains("末影龙")
                || normalized.contains("beat minecraft")
                || normalized.contains("ender dragon");
    }

    public static boolean isFoundationGoal(final GoalSnapshot goal) {
        final Optional<GoalExecutionPlan> plan = GoalExecutionPlan
                .fromDetailCode(goal.detailCode());
        if (plan.isPresent()) {
            return plan.orElseThrow().route()
                    == GoalExecutionPlan.Route.FOUNDATION;
        }
        return isFoundationGoalText(goal.goal());
    }

    public static boolean isFoundationGoalText(final String goal) {
        final String normalized = Objects.requireNonNull(goal, "goal")
                .toLowerCase(Locale.ROOT);
        return normalized.contains("基础生存")
                || normalized.contains("安全据点")
                || normalized.contains("生存到第二天")
                || normalized.contains("建立庇护所")
                || normalized.contains("safe base")
                || normalized.contains("survive until the second day")
                || normalized.contains("survive to the second day")
                || normalized.contains("establish a shelter");
    }

    public static Optional<SurvivalRouteProfile> profile(
            final GoalSnapshot goal
    ) {
        if (isFoundationGoal(goal)) {
            return Optional.of(SurvivalRouteProfile.FOUNDATION);
        }
        if (isCompletionGoal(goal)) {
            return Optional.of(SurvivalRouteProfile.COMPLETION);
        }
        return Optional.empty();
    }

    public static Optional<SurvivalMilestone> terminalMilestone(
            final GoalSnapshot goal
    ) {
        return GoalExecutionPlan.fromDetailCode(goal.detailCode())
                .filter(plan -> plan.route()
                        != GoalExecutionPlan.Route.NONE)
                .map(GoalExecutionPlan::terminalTarget)
                .filter(target -> target
                        != GoalExecutionPlan.Target.NONE)
                .map(target -> SurvivalMilestone.valueOf(
                        target.name()
                ));
    }

    static List<SurvivalMilestone> guidanceThroughTerminal(
            final GoalSnapshot goal,
            final List<SurvivalMilestone> guidanceOrder
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(guidanceOrder, "guidanceOrder");
        final Optional<SurvivalMilestone> terminal =
                terminalMilestone(goal);
        if (terminal.isEmpty()) {
            return List.copyOf(guidanceOrder);
        }
        if (terminal.orElseThrow()
                == SurvivalMilestone.CONTAINER_WOOD_DOOR_PLACED) {
            return List.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.CONTAINER_WOOD_DOOR_PLACED
            );
        }
        if (terminal.orElseThrow()
                == SurvivalMilestone.LOG_STORAGE_DISTRIBUTED) {
            /*
             * Balanced four-chest storage is an explicit player-authored
             * terminal, not a prerequisite for ordinary foundation or
             * completion routes. Keep its tool dependencies local to that
             * route so unrelated iron and shelter goals do not inherit this
             * expensive storage exercise.
             */
            return List.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.WOOD_OBTAINED,
                    SurvivalMilestone.BASIC_CRAFTING_READY,
                    SurvivalMilestone.STONE_TOOL_OBTAINED,
                    SurvivalMilestone.LOG_STORAGE_DISTRIBUTED
            );
        }
        final int terminalIndex = guidanceOrder.indexOf(
                terminal.orElseThrow()
        );
        if (terminalIndex < 0) {
            return List.copyOf(guidanceOrder);
        }
        final List<SurvivalMilestone> bounded = guidanceOrder.subList(
                0,
                terminalIndex + 1
        );
        if (terminal.orElseThrow()
                == SurvivalMilestone.IRON_OBTAINED) {
            /*
             * Food is a survival-route safety reserve, not a physical
             * prerequisite for mining the first iron ore.  A player's
             * explicitly bounded iron-material request must not turn into a
             * full foundation bootstrap or stall behind an unrelated hunt.
             * Later terminals retain FOOD_SECURED in their dependency chain.
             */
            return bounded.stream()
                    .filter(milestone -> milestone
                            != SurvivalMilestone.FOOD_SECURED)
                    .toList();
        }
        return List.copyOf(bounded);
    }

    static Optional<Set<SurvivalMilestone>> explicitlyRequiredMilestones(
            final GoalSnapshot goal
    ) {
        final Optional<GoalExecutionPlan> plan = GoalExecutionPlan
                .fromDetailCode(goal.detailCode())
                .filter(value -> value.route()
                        != GoalExecutionPlan.Route.NONE);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        final List<SurvivalMilestone> fullOrder =
                plan.orElseThrow().route()
                        == GoalExecutionPlan.Route.FOUNDATION
                        ? FOUNDATION_GUIDANCE_ORDER
                        : COMPLETION_GUIDANCE_ORDER;
        return Optional.of(Set.copyOf(
                guidanceThroughTerminal(goal, fullOrder)
        ));
    }

    private static Map<String, Integer> inventory(
            final ServerPlayer player
    ) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem())
                            .toString(),
                    stack.getCount(),
                    Math::addExact
            );
        }
        return counts;
    }

    static Map<String, Integer> criticalCounts(
            final Map<String, Integer> inventory
    ) {
        final Map<String, Integer> result = new TreeMap<>();
        result.put("food", safeFoodCount(inventory));
        result.put("building_blocks", buildingBlocks(inventory));
        result.put("iron_ingots", count(
                inventory,
                "minecraft:iron_ingot"
        ));
        result.put("obsidian", count(inventory, "minecraft:obsidian"));
        result.put("blaze_rods", count(
                inventory,
                "minecraft:blaze_rod"
        ));
        result.put("blaze_powder", count(
                inventory,
                "minecraft:blaze_powder"
        ));
        result.put("ender_pearls", count(
                inventory,
                "minecraft:ender_pearl"
        ));
        result.put("eyes_of_ender", count(
                inventory,
                "minecraft:ender_eye"
        ));
        result.put(
                "blaze_route_units",
                blazeRouteUnits(inventory)
        );
        result.put(
                "ender_route_units",
                enderRouteUnits(inventory)
        );
        result.put("beds", inventory.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_bed"))
                .mapToInt(Map.Entry::getValue)
                .sum());
        result.put("arrows", count(inventory, "minecraft:arrow"));
        result.put("bows", count(inventory, "minecraft:bow"));
        result.put("water_buckets", count(
                inventory,
                "minecraft:water_bucket"
        ));
        result.put("shields", count(inventory, "minecraft:shield"));
        result.put(
                "crafting_tables",
                count(inventory, "minecraft:crafting_table")
        );
        result.put(
                "chest_plank_potential",
                chestPlankPotential(inventory)
        );
        result.put("buckets", count(inventory, "minecraft:bucket")
                + count(inventory, "minecraft:water_bucket")
                + count(inventory, "minecraft:lava_bucket"));
        result.put(
                "wood_or_better_pickaxes",
                sum(inventory, WOOD_OR_BETTER_PICKAXES)
        );
        result.put(
                "stone_or_better_pickaxes",
                sum(inventory, STONE_OR_BETTER_PICKAXES)
        );
        result.put(
                "iron_or_better_pickaxes",
                sum(inventory, STRONG_PICKAXES)
        );
        result.put(
                "same_structural_item",
                inventory.entrySet().stream()
                        .filter(entry ->
                                DynamicShelterPlanner.isStructuralItem(
                                        entry.getKey()
                                )
                        )
                        .mapToInt(Map.Entry::getValue)
                        .max()
                        .orElse(0)
        );
        result.put(
                "safe_doors",
                inventory.entrySet().stream()
                        .filter(entry ->
                                DynamicShelterPlanner.isSafeDoorItem(
                                        entry.getKey()
                                )
                        )
                        .mapToInt(Map.Entry::getValue)
                        .sum()
        );
        result.put(
                "shelter_lights",
                inventory.entrySet().stream()
                        .filter(entry ->
                                DynamicShelterPlanner.isLightItem(
                                        entry.getKey()
                                )
                        )
                        .mapToInt(Map.Entry::getValue)
                        .sum()
        );
        result.put("armor_pieces", inventory.entrySet().stream()
                .filter(entry ->
                        entry.getKey().endsWith("_helmet")
                            || entry.getKey().endsWith("_chestplate")
                            || entry.getKey().endsWith("_leggings")
                            || entry.getKey().endsWith("_boots")
                )
                .mapToInt(Map.Entry::getValue)
                .sum());
        return result;
    }

    /**
     * One Blaze rod supplies two powder units, while every already-crafted eye
     * is proof that one powder unit was lawfully consumed earlier. Counting
     * both prevents early crafting from revoking route readiness without
     * treating one lucky rod as enough for a full unknown-seed run.
     */
    static int blazeRouteUnits(
            final Map<String, Integer> inventory
    ) {
        return CompletionResourceReadiness.blazeRouteUnits(
                count(inventory, "minecraft:blaze_rod"),
                count(inventory, "minecraft:blaze_powder"),
                count(inventory, "minecraft:ender_eye")
        );
    }

    /**
     * Crafted eyes retain the evidence of a consumed pearl. The route still
     * requires fourteen total pearl-derived units before stronghold work, so
     * the body has a twelve-eye portal reserve plus bounded tracing slack.
     */
    static int enderRouteUnits(
            final Map<String, Integer> inventory
    ) {
        return CompletionResourceReadiness.enderRouteUnits(
                count(inventory, "minecraft:ender_pearl"),
                count(inventory, "minecraft:ender_eye")
        );
    }

    static Map<String, Integer> minimumTargets(
            final SurvivalRouteProfile profile
    ) {
        return minimumTargets(profile, Set.of());
    }

    static Map<String, Integer> minimumTargets(
            final SurvivalRouteProfile profile,
            final Set<SurvivalMilestone> verifiedMilestones
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(
                verifiedMilestones,
                "verifiedMilestones"
        );
        if (profile == SurvivalRouteProfile.FOUNDATION) {
            final Map<String, Integer> targets = new TreeMap<>();
            targets.put("food", FOUNDATION_FOOD_RESERVE);
            if (!verifiedMilestones.contains(
                    SurvivalMilestone.BASIC_CRAFTING_READY
            )) {
                targets.put("crafting_tables", 1);
                targets.put("wood_or_better_pickaxes", 1);
            }
            targets.put("stone_or_better_pickaxes", 1);
            targets.put("iron_or_better_pickaxes", 1);
            targets.put("buckets", 1);
            targets.put("shields", 1);
            if (!verifiedMilestones.contains(
                    SurvivalMilestone.WORKSTATIONS_ESTABLISHED
            )) {
                targets.put(
                        "chest_plank_potential",
                        FOUNDATION_CHEST_PLANKS
                );
            }
            /*
             * Door, light and structural blocks are construction inputs, so
             * the running build consumes them by design. Once the route has
             * independently verified SHELTER_MATERIALS_PREPARED, requiring
             * all 55 blocks to remain in inventory creates an impossible
             * replenish-after-every-placement loop. The persistent builder
             * owns the exact plan and its confirmed placements from this
             * milestone until SHELTER_COMPLETED.
             */
            if (!verifiedMilestones.contains(
                        SurvivalMilestone.SHELTER_MATERIALS_PREPARED
                    )
                    && !verifiedMilestones.contains(
                        SurvivalMilestone.SHELTER_COMPLETED
                    )) {
                targets.put(
                        "same_structural_item",
                        DynamicShelterPlanner.structuralBlockCount(3, 3)
                );
                targets.put("safe_doors", 1);
                targets.put("shelter_lights", 1);
            }
            return Map.copyOf(targets);
        }
        final Map<String, Integer> targets = new TreeMap<>();
        targets.put("food", 8);
        if (!verifiedMilestones.contains(
                    SurvivalMilestone.END_LOADOUT_PREPARED
                )
                || !verifiedMilestones.contains(
                    SurvivalMilestone.END_ENTERED
                )) {
            targets.put(
                    "building_blocks",
                    COMPLETION_END_BUILDING_BLOCKS
            );
            targets.put("bows", COMPLETION_END_BOWS);
            targets.put("arrows", COMPLETION_END_ARROWS);
        }
        targets.put("iron_or_better_pickaxes", 1);
        targets.put("buckets", 1);
        targets.put("shields", 1);
        targets.put("armor_pieces", 3);
        if (!verifiedMilestones.contains(
                SurvivalMilestone.BLAZE_MATERIAL_OBTAINED
        )) {
            targets.put(
                    "blaze_route_units",
                    COMPLETION_BLAZE_ROUTE_UNITS
            );
        }
        if (!verifiedMilestones.contains(
                SurvivalMilestone.ENDER_PEARL_OBTAINED
        )) {
            targets.put(
                    "ender_route_units",
                    COMPLETION_ENDER_ROUTE_UNITS
            );
        }
        if (!verifiedMilestones.contains(
                SurvivalMilestone.EYE_OF_ENDER_CRAFTED
        )) {
            targets.put(
                    "eyes_of_ender",
                    COMPLETION_EYES_READY
            );
        }
        return Map.copyOf(targets);
    }

    static boolean endLoadoutReady(
            final Map<String, Integer> criticalCounts
    ) {
        Objects.requireNonNull(criticalCounts, "criticalCounts");
        return criticalCounts.getOrDefault("building_blocks", 0)
                        >= COMPLETION_END_BUILDING_BLOCKS
                && criticalCounts.getOrDefault("bows", 0)
                        >= COMPLETION_END_BOWS
                && criticalCounts.getOrDefault("arrows", 0)
                        >= COMPLETION_END_ARROWS;
    }

    /**
     * Current inventory readiness used at the irreversible End handoff. A
     * count-only snapshot cannot prove that a nearly broken bow is usable,
     * so the live player inventory must contain a bow with a bounded reserve
     * of durability as well as the shared arrow target.
     */
    static boolean endLoadoutReady(final ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!endLoadoutReady(criticalCounts(inventory(player)))) {
            return false;
        }
        return java.util.stream.IntStream.range(
                        0,
                        player.getInventory().getContainerSize()
                )
                .mapToObj(player.getInventory()::getItem)
                .filter(stack -> stack.is(net.minecraft.world.item.Items.BOW))
                .anyMatch(stack -> stack.getMaxDamage() <= 0
                        || stack.getMaxDamage() - stack.getDamageValue()
                            >= COMPLETION_END_ARROWS);
    }

    private static boolean hasWood(
            final Map<String, Integer> inventory,
            final int requiredReserve
    ) {
        final long rawLogs = inventory.entrySet().stream()
                .filter(entry -> {
                    final String id = entry.getKey();
                    return id.endsWith("_log")
                            || id.endsWith("_stem")
                            || id.endsWith("_hyphae");
                })
                .mapToLong(Map.Entry::getValue)
                .sum();
        final long planks = inventory.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_planks"))
                .mapToLong(Map.Entry::getValue)
                .sum();
        return rawLogs >= requiredReserve
                || planks >= requiredReserve * 4L;
    }

    /**
     * Keeps full-route resource slack without over-gathering for a bounded
     * early terminal. Three logs cover a crafting table, both pickaxe
     * recipes, and their sticks. A request that ends at obtaining wood needs
     * one physically owned log; later survival routes retain the established
     * five-log reserve.
     */
    static int requiredWoodReserve(final GoalSnapshot goal) {
        return GoalExecutionPlan.fromDetailCode(goal.detailCode())
                .map(GoalExecutionPlan::terminalTarget)
                .map(target -> switch (target) {
                    case WOOD_OBTAINED -> 1;
                    case BASIC_CRAFTING_READY, STONE_TOOL_OBTAINED -> 3;
                    case LOG_STORAGE_DISTRIBUTED -> 30;
                    case CONTAINER_WOOD_DOOR_PLACED -> 0;
                    default -> FOUNDATION_WOOD_RESERVE;
                })
                .orElse(FOUNDATION_WOOD_RESERVE);
    }

    private static int chestPlankPotential(
            final Map<String, Integer> inventory
    ) {
        final long ownedPlanks = inventory.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_planks"))
                .mapToLong(Map.Entry::getValue)
                .sum();
        final long convertibleWood = inventory.entrySet().stream()
                .filter(entry ->
                        PrepareBasicCraftingSkill.plankRecipeFor(
                                entry.getKey()
                        ).isPresent()
                )
                .mapToLong(Map.Entry::getValue)
                .sum();
        return (int) Math.min(
                MAX_PROJECTED_INVENTORY_COUNT,
                ownedPlanks + convertibleWood * 4L
        );
    }

    private static boolean hasIronMaterialOrProduct(
            final Map<String, Integer> inventory
    ) {
        return count(inventory, "minecraft:raw_iron") > 0
                || count(inventory, "minecraft:iron_ingot") > 0
                || inventory.entrySet().stream().anyMatch(entry ->
                        entry.getValue() > 0
                            && (entry.getKey().startsWith("minecraft:iron_")
                                || entry.getKey().equals(
                                    "minecraft:bucket"
                                )
                                || entry.getKey().equals(
                                    "minecraft:shield"
                                ))
                );
    }

    private static boolean hasAny(
            final Map<String, Integer> inventory,
            final Set<String> ids
    ) {
        return ids.stream().anyMatch(id -> count(inventory, id) > 0);
    }

    private static int buildingBlocks(
            final Map<String, Integer> inventory
    ) {
        return inventory.entrySet().stream()
                .filter(entry ->
                        BUILDING_BLOCK_IDS.contains(entry.getKey())
                            || entry.getKey().endsWith("_planks")
                )
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private static int safeFoodCount(
            final Map<String, Integer> inventory
    ) {
        return inventory.entrySet().stream()
                .filter(entry ->
                        VanillaFoodItems.isSafeFood(entry.getKey())
                )
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private static int sum(
            final Map<String, Integer> inventory,
            final Set<String> ids
    ) {
        return ids.stream()
                .mapToInt(id -> count(inventory, id))
                .sum();
    }

    private static int count(
            final Map<String, Integer> inventory,
            final String id
    ) {
        return inventory.getOrDefault(id, 0);
    }
}
