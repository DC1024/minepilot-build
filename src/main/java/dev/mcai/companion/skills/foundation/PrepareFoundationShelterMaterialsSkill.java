package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.building.DynamicShelterPlanner;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.exploration.ExploreForObservedTargetSkill;
import dev.mcai.companion.skills.exploration.ExploreForTargetParameters;
import dev.mcai.companion.skills.exploration.SearchTargetKind;
import dev.mcai.companion.skills.gathering.GatherVisibleBlockClusterParameters;
import dev.mcai.companion.skills.gathering.GatherVisibleBlockClusterSkill;
import dev.mcai.companion.skills.gathering.GatheringSkillPolicy;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.menu.CloseMenuParameters;
import dev.mcai.companion.skills.menu.MenuBinding;
import dev.mcai.companion.skills.menu.MenuOperationResult;
import dev.mcai.companion.skills.menu.MenuSkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillFrame;
import dev.mcai.companion.skills.menu.MenuSkillFrameSource;
import dev.mcai.companion.skills.menu.SmeltMenuBatchParameters;
import dev.mcai.companion.skills.menu.SmeltMenuBatchSkill;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Prepares the material boundary required by the dynamic M1 shelter.
 *
 * <p>The language model selects this one high-level transaction. The local
 * state machine then gathers fairly observed wood/coal, performs bounded
 * first-person exploration when the current view is exhausted, converts wood
 * through ordinary recipe-result clicks, reopens the body-verified crafting
 * table, and crafts a door and torches. It never writes inventory, opens an
 * unseen fixture, scans a chunk, or supplies a fixed shelter blueprint.</p>
 */
public final class PrepareFoundationShelterMaterialsSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "prepare_foundation_shelter_materials";

    private static final String CRAFTING_TABLE =
            "minecraft:crafting_table";
    private static final String COAL = "minecraft:coal";
    private static final String CHARCOAL = "minecraft:charcoal";
    private static final String COAL_ORE = "minecraft:coal_ore";
    private static final String DEEPSLATE_COAL_ORE =
            "minecraft:deepslate_coal_ore";
    private static final String FURNACE = "minecraft:furnace";
    private static final String STICK = "minecraft:stick";
    private static final String TORCH = "minecraft:torch";
    private static final String IRON_PICKAXE =
            "minecraft:iron_pickaxe";
    private static final int REQUIRED_STRUCTURAL_BLOCKS =
            DynamicShelterPlanner.structuralBlockCount(3, 3);
    private static final int DOOR_PLANK_COST = 6;
    private static final int STICK_PLANK_COST = 2;
    private static final int CHARCOAL_FUEL_PLANK_COST = 1;
    private static final int PLANKS_PER_WOOD = 4;
    private static final int MAXIMUM_TICKS = 9_000;
    private static final int MAXIMUM_CONFIRM_TICKS = 120;
    /**
     * A remembered workstation is already server-verified, but a stale
     * crosshair can still keep the headless player in an aim loop. Give
     * ordinary look alignment a short window, then reacquire the same
     * verified block through vanilla movement instead of scanning forever.
     */
    private static final int MAXIMUM_TABLE_AIM_TICKS = 24;
    private static final int MAXIMUM_RECIPE_WAIT_TICKS = 120;
    private static final int MAXIMUM_MENU_WAIT_TICKS = 120;
    private static final int MAXIMUM_SCAN_TURNS = 64;
    private static final int MAXIMUM_WOOD_NO_PROGRESS_TICKS = 300;
    /**
     * A failed visible cluster is local evidence, not a reason to keep
     * staring at the same tree. After a few distinct misses, walk the
     * bounded first-person exploration route so a second tree can be found.
     */
    private static final int WOOD_REJECTIONS_BEFORE_EXPLORATION = 3;
    private static final int COAL_SCAN_TURNS_BEFORE_CHARCOAL = 24;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final int RESOURCE_SEARCH_RADIUS = 32;
    private static final int RESOURCE_SEARCH_STEP = 8;
    /*
     * A visible semantic ray can extend far beyond vanilla survival reach.
     * Keep a margin below the ordinary 4.5-block block-interaction range:
     * treating a five-block face as actionable made AIM_TABLE/AIM_FURNACE
     * repeatedly turn toward an object the crosshair could never select.
     */
    private static final double RELIABLE_INTERACTION_DISTANCE = 3.75;
    private static final double TABLE_APPROACH_RADIUS = 3.0;
    private static final float[] SCAN_YAW_OFFSETS = {
            0.0F,
            -45.0F,
            -90.0F,
            -135.0F,
            180.0F,
            135.0F,
            90.0F,
            45.0F
    };
    private static final float[] SCAN_PITCHES = {
            10.0F,
            30.0F,
            50.0F
    };
    private static final Set<String> COAL_BLOCKS = Set.of(
            COAL_ORE,
            DEEPSLATE_COAL_ORE
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;
    private final MenuSkillActuator menus;
    private final MenuSkillFrameSource menuFrames;
    private final GatherVisibleBlockClusterSkill gatherer;
    private final int additionalPlankReserve;
    private final LongFunction<Optional<VerifiedFixtureLocation>>
            knownCraftingTable;
    private final LongFunction<Optional<VerifiedFixtureLocation>>
            knownFurnace;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long nextScanTick = -1;
    private int scanTurns;
    private float scanBaseYaw;
    private String selectedPlankItemId;
    private VisibleBlockFace selectedTable;
    private VisibleBlockFace selectedFurnace;
    private GatherVisibleBlockClusterParameters gatheringParameters;
    private int gatheringStartCount;
    private int gatheringProgressCount;
    private long gatheringProgressAtTick;
    private final Set<GridPos> rejectedWoodSeeds = new HashSet<>();
    private long rejectedWoodObservationRevision;
    private long rejectedCoalObservationRevision;
    private boolean charcoalFallbackRequested;
    private String charcoalInputItemId;
    private ExploreForObservedTargetSkill woodExplorer;
    private ExploreForTargetParameters woodExplorationParameters;
    private ExploreForObservedTargetSkill coalExplorer;
    private ExploreForTargetParameters coalExplorationParameters;
    private MoveToSkill tableMovement;
    private MoveToParameters tableMovementParameters;
    private MoveToSkill furnaceMovement;
    private MoveToParameters furnaceMovementParameters;
    private SmeltMenuBatchSkill smelter;
    private SmeltMenuBatchParameters smeltingParameters;
    private int recipeAttempts;

    public PrepareFoundationShelterMaterialsSkill(
            final UUID expectedPlayerId,
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
                    knownFurnace
    ) {
        this(
                expectedPlayerId,
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
                0
        );
    }

    PrepareFoundationShelterMaterialsSkill(
            final UUID expectedPlayerId,
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
            final int additionalPlankReserve
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.inventory = Objects.requireNonNull(
                inventory,
                "inventory"
        );
        this.menus = Objects.requireNonNull(menus, "menus");
        this.menuFrames = Objects.requireNonNull(
                menuFrames,
                "menuFrames"
        );
        if (additionalPlankReserve < 0
                || additionalPlankReserve > 64) {
            throw new IllegalArgumentException(
                    "additionalPlankReserve must be between 0 and 64"
            );
        }
        this.additionalPlankReserve = additionalPlankReserve;
        gatherer = new GatherVisibleBlockClusterSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                Objects.requireNonNull(
                        resourceInventory,
                        "resourceInventory"
                ),
                GatheringSkillPolicy.defaults()
        );
        this.knownCraftingTable = Objects.requireNonNull(
                knownCraftingTable,
                "knownCraftingTable"
        );
        this.knownFurnace = Objects.requireNonNull(
                knownFurnace,
                "knownFurnace"
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return PrepareFoundationShelterMaterialsSkill::parseNone;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame frame = current.orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!hasCraftingTableEvidence(context, frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".crafting_table_required"
            ));
        }
        if (!ownsItem(frame, IRON_PICKAXE)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".iron_pickaxe_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before shelter preparation"
                )
        );
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanBaseYaw = yaw(frame.lookDirection());
        selectedPlankItemId = bestPlankFamily(frame).orElse(null);
        selectedTable = null;
        selectedFurnace = null;
        gatheringParameters = null;
        gatheringStartCount = 0;
        gatheringProgressCount = 0;
        gatheringProgressAtTick = -1L;
        rejectedWoodSeeds.clear();
        rejectedWoodObservationRevision = -1L;
        rejectedCoalObservationRevision = -1L;
        charcoalFallbackRequested = false;
        charcoalInputItemId = null;
        woodExplorer = null;
        woodExplorationParameters = null;
        coalExplorer = null;
        coalExplorationParameters = null;
        tableMovement = null;
        tableMovementParameters = null;
        furnaceMovement = null;
        furnaceMovementParameters = null;
        smelter = null;
        smeltingParameters = null;
        recipeAttempts = 0;
        beginScan(context, frame, Phase.FIND_WOOD);
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"plankFamily\":\"%s\","
                                + "\"scanTurns\":%d,"
                                + "\"elapsedTicks\":%d,"
                                + "\"gathering\":%s,"
                                + "\"exploringWood\":%s,"
                                + "\"exploringCoal\":%s,"
                                + "\"charcoalFallback\":%s,"
                                + "\"movingToTable\":%s,"
                                + "\"movingToFurnace\":%s,"
                                + "\"smeltingCharcoal\":%s,"
                                + "\"woodChild\":\"%s\"}",
                        phase.name(),
                        selectedPlankItemId == null
                                ? ""
                                : selectedPlankItemId,
                        scanTurns,
                        Math.max(
                                0L,
                                context.gameTick() - startedAtTick
                        ),
                        phase == Phase.GATHER_WOOD
                                || phase == Phase.GATHER_COAL,
                        phase == Phase.EXPLORE_WOOD,
                        phase == Phase.EXPLORE_COAL,
                        charcoalFallbackRequested,
                        phase == Phase.MOVE_TO_TABLE,
                        phase == Phase.MOVE_TO_FURNACE,
                        phase == Phase.SMELT_CHARCOAL
                        , woodChildCheckpoint(context)
                )
        );
    }

    private String woodChildCheckpoint(final SkillContext context) {
        if (woodExplorer == null || woodExplorationParameters == null) {
            return "";
        }
        return woodExplorer.checkpoint(
                        context,
                        woodExplorationParameters
                ).payload()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelChildSkills(context);
        core.stop();
        closeCurrentMenu();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(final SkillContext context) {
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(
                    context,
                    unsafe.orElseThrow().code()
            );
        }
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return fail(context, NAME + ".timeout");
        }
        return switch (phase) {
            case FIND_WOOD -> findWood(context, frame);
            case EXPLORE_WOOD ->
                    exploreWood(context, frame);
            case GATHER_WOOD -> gatherWood(context, frame);
            case CONFIRM_WOOD -> confirmWood(context, frame);
            case PREPARE_PLANKS ->
                    preparePlanks(context, frame);
            case FIND_COAL -> findCoal(context, frame);
            case EXPLORE_COAL -> exploreCoal(context, frame);
            case GATHER_COAL -> gatherCoal(context, frame);
            case CONFIRM_COAL -> confirmCoal(context, frame);
            case FIND_FURNACE -> findFurnace(context, frame);
            case MOVE_TO_FURNACE ->
                    moveToFurnace(context, frame);
            case AIM_FURNACE -> aimFurnace(context, frame);
            case OPEN_FURNACE -> openFurnace(context);
            case CONFIRM_FURNACE_MENU ->
                    confirmFurnaceMenu(context, frame);
            case SMELT_CHARCOAL -> smeltCharcoal(context);
            case CLOSE_FURNACE ->
                    closeFurnace(context, frame);
            case FIND_TABLE -> findTable(context, frame);
            case MOVE_TO_TABLE -> moveToTable(context, frame);
            case AIM_TABLE -> aimTable(context, frame);
            case OPEN_TABLE -> openTable(context);
            case CONFIRM_TABLE_MENU ->
                    confirmTableMenu(context, frame);
            case CRAFT_STICKS ->
                    craftSticks(context, frame);
            case CRAFT_DOOR -> craftDoor(context, frame);
            case CRAFT_TORCH -> craftTorch(context, frame);
            case FINISH -> finish(context, frame);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult findWood(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        refreshPlankFamily(frame);
        if (selectedPlankItemId != null
                && potentialPlanks(
                        frame,
                        selectedPlankItemId
                ) >= requiredPotentialPlanksBeforeComponents(frame)) {
            return transition(context, Phase.PREPARE_PLANKS);
        }
        final Optional<InteractionSkillFrame> interaction =
                ownedInteractionFrame(frame);
        if (interaction.isPresent()) {
            final Optional<VisibleBlockFace> wood =
                    selectVisibleWood(
                            frame,
                            interaction.orElseThrow()
                    );
            if (wood.isPresent()) {
                final VisibleBlockFace seed = wood.orElseThrow();
                final String plankRecipe =
                        PrepareBasicCraftingSkill.plankRecipeFor(
                                seed.blockTypeId()
                        ).orElseThrow();
                if (selectedPlankItemId == null) {
                    selectedPlankItemId = plankRecipe;
                }
                final int deficit = Math.max(
                        1,
                        requiredPotentialPlanksBeforeComponents(frame)
                                - potentialPlanks(
                                        frame,
                                        selectedPlankItemId
                                )
                );
                gatheringParameters =
                        new GatherVisibleBlockClusterParameters(
                                interaction.orElseThrow().dimension(),
                                observedTarget(
                                        interaction.orElseThrow(),
                                        seed
                                ),
                                seed.blockTypeId(),
                                Math.min(
                                        64,
                                        Math.max(
                                                1,
                                                (deficit
                                                        + PLANKS_PER_WOOD
                                                        - 1)
                                                        / PLANKS_PER_WOOD
                                        )
                                ),
                                12.0,
                                "minecraft:air"
                        );
                final Optional<SkillFailure> rejected =
                        gatherer.preconditions(
                                context,
                                gatheringParameters
                        );
                if (rejected.isEmpty()) {
                    gatheringStartCount = potentialPlanks(
                            frame,
                            selectedPlankItemId
                    );
                    gatheringProgressCount = gatheringStartCount;
                    gatheringProgressAtTick = context.gameTick();
                    gatherer.start(context, gatheringParameters);
                    phase = Phase.GATHER_WOOD;
                    phaseStartedAtTick = context.gameTick();
                    return SkillTickResult.running(true, true);
                }
            }
        }
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            return beginWoodExploration(context, frame);
        }
        return scan(context, frame, Phase.FIND_WOOD);
    }

    private SkillTickResult beginWoodExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelWoodExplorer(context);
        woodExplorationParameters =
                new ExploreForTargetParameters(
                        frame.dimension(),
                        SearchTargetKind.BLOCK,
                        representativeWoodBlock(
                                selectedPlankItemId
                        ),
                        RESOURCE_SEARCH_RADIUS,
                        RESOURCE_SEARCH_STEP
                );
        woodExplorer = new ExploreForObservedTargetSkill(
                expectedPlayerId,
                core,
                coreFrames,
                () -> interactions.sessionGeneration().orElse(-1L),
                (candidate, ignored) ->
                        candidate.visibleBlockFaces().stream()
                                .anyMatch(this::acceptableExplorationWood)
        );
        final Optional<SkillFailure> rejected =
                woodExplorer.preconditions(
                        context,
                        woodExplorationParameters
                );
        if (rejected.isPresent()) {
            cancelWoodExplorer(context);
            return fail(
                    context,
                    NAME + ".wood_exploration_rejected"
            );
        }
        woodExplorer.start(context, woodExplorationParameters);
        phase = Phase.EXPLORE_WOOD;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult exploreWood(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedPlankItemId != null
                && potentialPlanks(
                        frame,
                        selectedPlankItemId
                ) >= requiredPotentialPlanksBeforeComponents(frame)) {
            cancelWoodExplorer(context);
            return transition(context, Phase.PREPARE_PLANKS);
        }
        if (woodExplorer == null
                || woodExplorationParameters == null) {
            return fail(
                    context,
                    NAME + ".wood_exploration_binding_missing"
            );
        }
        final SkillTickResult result = woodExplorer.tick(
                context,
                woodExplorationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelWoodExplorer(context);
            beginScan(context, frame, Phase.FIND_WOOD);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String childFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse(
                            ExploreForObservedTargetSkill.NAME
                                    + ".unknown_failure"
                    );
            final String childCheckpoint = woodExplorer.checkpoint(
                    context,
                    woodExplorationParameters
            ).payload();
            MinecraftAiCompanion.LOGGER.warn(
                    "Shelter wood exploration failed: "
                            + "cause={}, child={}, "
                            + "position=[{},{},{}], look=[{},{},{}]",
                    childFailure,
                    childCheckpoint,
                    frame.position().x(),
                    frame.position().y(),
                    frame.position().z(),
                    frame.lookDirection().x(),
                    frame.lookDirection().y(),
                    frame.lookDirection().z()
            );
            cancelWoodExplorer(context);
            return fail(context, NAME + ".wood_not_found");
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult gatherWood(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (gatheringParameters == null) {
            return fail(context, NAME + ".wood_binding_missing");
        }
        final SkillTickResult child = gatherer.tick(
                context,
                gatheringParameters
        );
        final CoreSkillFrame current = ownedFrame().orElse(frame);
        final int currentPotential = selectedPlankItemId == null
                ? 0
                : potentialPlanks(
                        current,
                        selectedPlankItemId
                );
        if (currentPotential > gatheringProgressCount) {
            gatheringProgressCount = currentPotential;
            gatheringProgressAtTick = context.gameTick();
        }
        if (child.status() == SkillTickResult.Status.FAILED) {
            final String childFailure = child.failure()
                    .map(SkillFailure::code)
                    .orElse(NAME + ".wood_gather_failed");
            if (selectedPlankItemId != null
                    && currentPotential > gatheringStartCount) {
                gatheringParameters = null;
                beginScan(context, current, Phase.CONFIRM_WOOD);
                return SkillTickResult.running(true, true);
            }
            if (recoverableWoodGatherFailure(childFailure)) {
                return recoverWoodGathering(
                        context,
                        current,
                        childFailure
                );
            }
            return fail(context, childFailure);
        }
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            gatheringParameters = null;
            beginScan(context, frame, Phase.CONFIRM_WOOD);
            return SkillTickResult.running(true, true);
        }
        if (woodGatheringProgressExpired(
                context.gameTick(),
                gatheringProgressAtTick
        )) {
            return recoverWoodGathering(
                    context,
                    current,
                    NAME + ".wood_no_inventory_progress"
            );
        }
        return SkillTickResult.running(
                child.madeProgress(),
                child.safeCheckpoint()
        );
    }

    private SkillTickResult recoverWoodGathering(
            final SkillContext context,
            final CoreSkillFrame frame,
            final String reason
    ) {
        final GatherVisibleBlockClusterParameters active =
                Objects.requireNonNull(gatheringParameters);
        final ObservedBlockTarget seed = active.seed();
        rejectedWoodSeeds.add(new GridPos(
                seed.x(),
                seed.y(),
                seed.z()
        ));
        gatherer.cancel(context, active);
        gatheringParameters = null;
        gatheringProgressAtTick = -1L;
        MinecraftAiCompanion.LOGGER.info(
                "Recovering shelter wood gathering locally: "
                        + "seed=[{},{},{}], rejectedSeeds={}, "
                        + "potentialPlanks={}, reason={}",
                seed.x(),
                seed.y(),
                seed.z(),
                rejectedWoodSeeds.size(),
                gatheringProgressCount,
                reason
        );
        ownedInteractionFrame(frame).ifPresent(
                interaction ->
                        rejectedWoodObservationRevision =
                                Math.max(
                                        rejectedWoodObservationRevision,
                                        interaction
                                                .observationRevision()
                                )
        );
        if (shouldExploreAfterRepeatedWoodRejection(
                rejectedWoodSeeds.size()
        )) {
            return beginWoodExploration(context, frame);
        } else {
            beginScan(context, frame, Phase.FIND_WOOD);
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult confirmWood(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedPlankItemId != null
                && potentialPlanks(
                        frame,
                        selectedPlankItemId
                ) >= requiredPotentialPlanksBeforeComponents(frame)) {
            return transition(context, Phase.PREPARE_PLANKS);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            beginScan(context, frame, Phase.FIND_WOOD);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult preparePlanks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        refreshPlankFamily(frame);
        if (selectedPlankItemId == null) {
            return transition(context, Phase.FIND_WOOD);
        }
        final int required = requiredPlanksBeforeComponents(frame);
        if (itemCount(frame, selectedPlankItemId) >= required) {
            if (charcoalFallbackRequested
                    && needsTorchFuel(frame)) {
                charcoalInputItemId =
                        burnableWoodForPlank(
                                frame,
                                selectedPlankItemId
                        ).orElse(null);
                if (charcoalInputItemId == null) {
                    beginScan(context, frame, Phase.FIND_WOOD);
                    return SkillTickResult.running(true, true);
                }
                beginScan(context, frame, Phase.FIND_FURNACE);
                return SkillTickResult.running(true, true);
            }
            beginScan(
                    context,
                    frame,
                    needsTorchFuel(frame)
                            ? Phase.FIND_COAL
                            : Phase.FIND_TABLE
            );
            return SkillTickResult.running(true, true);
        }
        final Optional<String> source = frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .filter(item -> PrepareBasicCraftingSkill
                        .plankRecipeFor(item)
                        .filter(selectedPlankItemId::equals)
                        .isPresent())
                .filter(item -> !charcoalFallbackRequested
                        || !needsTorchFuel(frame)
                        || convertibleWoodCount(
                                frame,
                                selectedPlankItemId
                        ) > 1)
                .findFirst();
        if (source.isEmpty()) {
            if (charcoalFallbackRequested
                    && needsTorchFuel(frame)) {
                beginScan(context, frame, Phase.FIND_WOOD);
                return SkillTickResult.running(true, true);
            }
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_RECIPE_WAIT_TICKS) {
                return fail(
                        context,
                        NAME + ".wood_conversion_unavailable"
                );
            }
            return SkillTickResult.running(false, true);
        }
        final InventoryOperationResult crafted =
                inventory.craftOnce(
                        new CraftRecipeParameters(
                                selectedPlankItemId,
                                1
                        )
                );
        if (!crafted.succeeded()) {
            return fail(
                    context,
                    NAME + ".plank_recipe_failed"
            );
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult findCoal(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!needsTorchFuel(frame)) {
            return transition(context, Phase.FIND_TABLE);
        }
        if (scanTurns >= COAL_SCAN_TURNS_BEFORE_CHARCOAL
                && hasFurnaceEvidence(context, frame)) {
            return beginCharcoalFallback(context, frame);
        }
        final Optional<InteractionSkillFrame> interaction =
                ownedInteractionFrame(frame);
        if (interaction.isPresent()) {
            final Optional<VisibleBlockFace> coal =
                    interaction.orElseThrow()
                            .visibleBlockFaces()
                            .stream()
                            .filter(face -> COAL_BLOCKS.contains(
                                    face.blockTypeId()
                            ))
                            .filter(face -> interaction.orElseThrow()
                                    .observationRevision()
                                    > rejectedCoalObservationRevision)
                            .min(Comparator.comparingDouble(
                                    VisibleBlockFace::distance
                            ));
            if (coal.isPresent()) {
                final VisibleBlockFace seed = coal.orElseThrow();
                gatheringParameters =
                        new GatherVisibleBlockClusterParameters(
                                interaction.orElseThrow().dimension(),
                                observedTarget(
                                        interaction.orElseThrow(),
                                        seed
                                ),
                                seed.blockTypeId(),
                                1,
                                4.0,
                                IRON_PICKAXE
                        );
                final Optional<SkillFailure> rejected =
                        gatherer.preconditions(
                                context,
                                gatheringParameters
                        );
                if (rejected.isEmpty()) {
                    gatheringStartCount = itemCount(frame, COAL);
                    gatherer.start(context, gatheringParameters);
                    phase = Phase.GATHER_COAL;
                    phaseStartedAtTick = context.gameTick();
                    return SkillTickResult.running(true, true);
                }
            }
        }
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            return beginCoalExploration(context, frame);
        }
        return scan(context, frame, Phase.FIND_COAL);
    }

    private SkillTickResult beginCoalExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelCoalExplorer(context);
        coalExplorationParameters =
                new ExploreForTargetParameters(
                        frame.dimension(),
                        SearchTargetKind.BLOCK,
                        COAL_ORE,
                        RESOURCE_SEARCH_RADIUS,
                        RESOURCE_SEARCH_STEP
                );
        coalExplorer = new ExploreForObservedTargetSkill(
                expectedPlayerId,
                core,
                coreFrames,
                () -> interactions.sessionGeneration().orElse(-1L),
                (candidate, ignored) ->
                        candidate.visibleBlockFaces().stream()
                                .anyMatch(face -> COAL_BLOCKS.contains(
                                        face.blockTypeId()
                                ))
        );
        final Optional<SkillFailure> rejected =
                coalExplorer.preconditions(
                        context,
                        coalExplorationParameters
                );
        if (rejected.isPresent()) {
            cancelCoalExplorer(context);
            return fail(
                    context,
                    NAME + ".coal_exploration_rejected"
            );
        }
        coalExplorer.start(context, coalExplorationParameters);
        phase = Phase.EXPLORE_COAL;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult exploreCoal(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!needsTorchFuel(frame)) {
            cancelCoalExplorer(context);
            return transition(context, Phase.FIND_TABLE);
        }
        if (coalExplorer == null
                || coalExplorationParameters == null) {
            return fail(
                    context,
                    NAME + ".coal_exploration_binding_missing"
            );
        }
        final SkillTickResult result = coalExplorer.tick(
                context,
                coalExplorationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelCoalExplorer(context);
            beginScan(context, frame, Phase.FIND_COAL);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            cancelCoalExplorer(context);
            if (hasFurnaceEvidence(context, frame)) {
                return beginCharcoalFallback(context, frame);
            }
            return fail(context, NAME + ".coal_not_found");
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult gatherCoal(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (gatheringParameters == null) {
            return fail(context, NAME + ".coal_binding_missing");
        }
        final SkillTickResult child = gatherer.tick(
                context,
                gatheringParameters
        );
        if (child.status() == SkillTickResult.Status.FAILED) {
            final String childFailure = child.failure()
                    .map(SkillFailure::code)
                    .orElse(NAME + ".coal_gather_failed");
            final CoreSkillFrame current = ownedFrame().orElse(frame);
            if (itemCount(current, COAL) > gatheringStartCount) {
                gatheringParameters = null;
                beginScan(context, current, Phase.CONFIRM_COAL);
                return SkillTickResult.running(true, true);
            }
            if (recoverableCoalGatherFailure(childFailure)) {
                gatheringParameters = null;
                ownedInteractionFrame(current).ifPresent(
                        interaction ->
                                rejectedCoalObservationRevision =
                                        Math.max(
                                                rejectedCoalObservationRevision,
                                                interaction
                                                        .observationRevision()
                                        )
                );
                if (hasFurnaceEvidence(context, current)) {
                    return beginCharcoalFallback(context, current);
                }
                beginScan(context, current, Phase.FIND_COAL);
                return SkillTickResult.running(true, true);
            }
            return fail(context, childFailure);
        }
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            gatheringParameters = null;
            beginScan(context, frame, Phase.CONFIRM_COAL);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                child.madeProgress(),
                child.safeCheckpoint()
        );
    }

    private SkillTickResult confirmCoal(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!needsTorchFuel(frame)) {
            return transition(context, Phase.FIND_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            beginScan(context, frame, Phase.FIND_COAL);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult beginCharcoalFallback(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelGatherer(context);
        cancelCoalExplorer(context);
        charcoalFallbackRequested = true;
        charcoalInputItemId = null;
        refreshPlankFamily(frame);
        if (selectedPlankItemId == null
                || potentialPlanks(
                        frame,
                        selectedPlankItemId
                ) < requiredPotentialPlanksBeforeComponents(frame)) {
            beginScan(context, frame, Phase.FIND_WOOD);
            return SkillTickResult.running(true, true);
        }
        return transition(context, Phase.PREPARE_PLANKS);
    }

    private SkillTickResult findFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!needsTorchFuel(frame)) {
            return transition(context, Phase.FIND_TABLE);
        }
        if (currentFurnaceMenu().isPresent()) {
            return transition(context, Phase.CONFIRM_FURNACE_MENU);
        }
        charcoalInputItemId = selectedPlankItemId == null
                ? null
                : burnableWoodForPlank(
                        frame,
                        selectedPlankItemId
                ).orElse(null);
        if (charcoalInputItemId == null
                || selectedPlankItemId == null
                || itemCount(
                        frame,
                        selectedPlankItemId
                ) < requiredPlanksBeforeComponents(frame)) {
            return transition(context, Phase.PREPARE_PLANKS);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(
                                PrepareFoundationShelterMaterialsSkill
                                        ::isFurnace
                        );
        if (crosshair.isPresent()) {
            selectedFurnace = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_FURNACE);
        }
        final Optional<VisibleBlockFace> visible =
                visibleFurnace(frame);
        if (visible.isPresent()) {
            final VisibleBlockFace furnace = visible.orElseThrow();
            if (furnace.distance()
                    > RELIABLE_INTERACTION_DISTANCE) {
                return beginFurnaceApproach(
                        context,
                        frame,
                        furnace.block().x(),
                        furnace.block().z()
                );
            }
            selectedFurnace = furnace;
            return transition(context, Phase.AIM_FURNACE);
        }
        final Optional<PerceptionVec3> remembered =
                rememberedFurnaceTarget(context, frame);
        if (remembered.isPresent()) {
            final PerceptionVec3 target = remembered.orElseThrow();
            if (target.subtract(frame.eyePosition()).length()
                    > RELIABLE_INTERACTION_DISTANCE) {
                return beginFurnaceApproach(
                        context,
                        frame,
                        (int) Math.floor(target.x()),
                        (int) Math.floor(target.z())
                );
            }
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_CONFIRM_TICKS) {
                return aimAt(context, frame, target);
            }
        }
        return scan(context, frame, Phase.FIND_FURNACE);
    }

    private SkillTickResult beginFurnaceApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final int x,
            final int z
    ) {
        cancelFurnaceMovement(context);
        furnaceMovementParameters = new MoveToParameters(
                frame.dimension(),
                x + 0.5,
                frame.position().y(),
                z + 0.5,
                TABLE_APPROACH_RADIUS
        );
        furnaceMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected =
                furnaceMovement.preconditions(
                        context,
                        furnaceMovementParameters
                );
        if (rejected.isPresent()) {
            cancelFurnaceMovement(context);
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        furnaceMovement.start(context, furnaceMovementParameters);
        selectedFurnace = null;
        phase = Phase.MOVE_TO_FURNACE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult moveToFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (furnaceMovement == null
                || furnaceMovementParameters == null) {
            return fail(
                    context,
                    NAME + ".furnace_approach_binding_missing"
            );
        }
        final SkillTickResult result = furnaceMovement.tick(
                context,
                furnaceMovementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            cancelFurnaceMovement(context);
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelFurnaceMovement(context);
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult aimFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(
                                PrepareFoundationShelterMaterialsSkill
                                        ::isFurnace
                        );
        if (crosshair.isPresent()) {
            selectedFurnace = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_FURNACE);
        }
        if (selectedFurnace == null) {
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return beginFurnaceApproach(
                    context,
                    frame,
                    selectedFurnace.block().x(),
                    selectedFurnace.block().z()
            );
        }
        return aimAt(
                context,
                frame,
                selectedFurnace.hitPosition()
        );
    }

    private SkillTickResult openFurnace(
            final SkillContext context
    ) {
        if (currentFurnaceMenu().isPresent()) {
            return transition(
                    context,
                    Phase.CONFIRM_FURNACE_MENU
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(
                                PrepareFoundationShelterMaterialsSkill
                                        ::isFurnace
                        );
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_FURNACE);
        }
        final ActionOutcome opened = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        if (!opened.accepted()) {
            return fail(
                    context,
                    NAME + ".furnace_open_rejected"
            );
        }
        return transition(context, Phase.CONFIRM_FURNACE_MENU);
    }

    private SkillTickResult confirmFurnaceMenu(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<MenuSkillFrame> menu =
                currentFurnaceMenu();
        if (menu.isPresent()) {
            if (!needsTorchFuel(frame)) {
                return transition(context, Phase.CLOSE_FURNACE);
            }
            if (selectedPlankItemId == null) {
                return fail(
                        context,
                        NAME + ".charcoal_fuel_missing"
                );
            }
            charcoalInputItemId = burnableWoodForPlank(
                    frame,
                    selectedPlankItemId
            ).orElse(null);
            if (charcoalInputItemId == null
                    || itemCount(
                            frame,
                            selectedPlankItemId
                    ) < 1) {
                return fail(
                        context,
                        NAME + ".charcoal_inputs_missing"
                );
            }
            smeltingParameters = new SmeltMenuBatchParameters(
                    menu.orElseThrow().sampleSequence(),
                    charcoalInputItemId,
                    CHARCOAL,
                    1,
                    selectedPlankItemId,
                    CHARCOAL_FUEL_PLANK_COST
            );
            smelter = new SmeltMenuBatchSkill(
                    expectedPlayerId,
                    menus,
                    menuFrames
            );
            final Optional<SkillFailure> rejected =
                    smelter.preconditions(
                            context,
                            smeltingParameters
                    );
            if (rejected.isPresent()) {
                smelter = null;
                smeltingParameters = null;
                return fail(
                        context,
                        NAME + ".charcoal_smelting_precondition_failed"
                );
            }
            smelter.start(context, smeltingParameters);
            return transition(context, Phase.SMELT_CHARCOAL);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_MENU_WAIT_TICKS) {
            return fail(
                    context,
                    NAME + ".furnace_menu_unconfirmed"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult smeltCharcoal(
            final SkillContext context
    ) {
        if (smelter == null || smeltingParameters == null) {
            return fail(
                    context,
                    NAME + ".charcoal_smelting_binding_missing"
            );
        }
        final SkillTickResult result = smelter.tick(
                context,
                smeltingParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            smelter = null;
            smeltingParameters = null;
            return fail(
                    context,
                    NAME + ".charcoal_smelting_failed"
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            smelter = null;
            smeltingParameters = null;
            return transition(context, Phase.CLOSE_FURNACE);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult closeFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<MenuSkillFrame> current =
                currentFurnaceMenu();
        if (current.isPresent()) {
            final MenuOperationResult closed = menus.close(
                    new CloseMenuParameters(
                            binding(current.orElseThrow())
                    )
            );
            if (!closed.succeeded()) {
                if (context.gameTick() - phaseStartedAtTick
                        < MAXIMUM_MENU_WAIT_TICKS) {
                    return SkillTickResult.running(false, true);
                }
                return fail(
                        context,
                        NAME + ".furnace_menu_close_failed"
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (!needsTorchFuel(frame)) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(
                    context,
                    NAME + ".charcoal_unconfirmed"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult findTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, nextCraftingPhase(frame));
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareFoundationShelterMaterialsSkill
                                ::isCraftingTable);
        if (crosshair.isPresent()) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        final Optional<VisibleBlockFace> visible =
                frame.visibleBlockFaces()
                        .stream()
                        .filter(PrepareFoundationShelterMaterialsSkill
                                ::isCraftingTable)
                        .min(Comparator.comparingDouble(
                                VisibleBlockFace::distance
                        ));
        if (visible.isPresent()) {
            final VisibleBlockFace table = visible.orElseThrow();
            if (table.distance() > RELIABLE_INTERACTION_DISTANCE) {
                return beginTableApproach(
                        context,
                        frame,
                        table.block().x(),
                        table.block().z()
                );
            }
            selectedTable = table;
            return transition(context, Phase.AIM_TABLE);
        }
        final Optional<PerceptionVec3> remembered =
                rememberedTableTarget(context, frame);
        if (remembered.isPresent()) {
            final PerceptionVec3 target = remembered.orElseThrow();
            if (target.subtract(frame.eyePosition()).length()
                    > RELIABLE_INTERACTION_DISTANCE) {
                return beginTableApproach(
                        context,
                        frame,
                        (int) Math.floor(target.x()),
                        (int) Math.floor(target.z())
                );
            }
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_TABLE_AIM_TICKS) {
                return aimAt(context, frame, target);
            }
            return beginTableApproach(
                    context,
                    frame,
                    (int) Math.floor(target.x()),
                    (int) Math.floor(target.z())
            );
        }
        return scan(context, frame, Phase.FIND_TABLE);
    }

    private SkillTickResult beginTableApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final int x,
            final int z
    ) {
        cancelTableMovement(context);
        tableMovementParameters = new MoveToParameters(
                frame.dimension(),
                x + 0.5,
                frame.position().y(),
                z + 0.5,
                TABLE_APPROACH_RADIUS
        );
        tableMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected =
                tableMovement.preconditions(
                        context,
                        tableMovementParameters
                );
        if (rejected.isPresent()) {
            cancelTableMovement(context);
            return fail(
                    context,
                    NAME + ".table_approach_rejected"
            );
        }
        tableMovement.start(context, tableMovementParameters);
        selectedTable = null;
        phase = Phase.MOVE_TO_TABLE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult moveToTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (tableMovement == null
                || tableMovementParameters == null) {
            return fail(
                    context,
                    NAME + ".table_approach_binding_missing"
            );
        }
        final SkillTickResult result = tableMovement.tick(
                context,
                tableMovementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            cancelTableMovement(context);
            return fail(
                    context,
                    NAME + ".table_approach_failed"
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelTableMovement(context);
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult aimTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareFoundationShelterMaterialsSkill
                                ::isCraftingTable);
        if (crosshair.isPresent()) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        if (selectedTable == null) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_TABLE_AIM_TICKS) {
            return beginTableApproach(
                    context,
                    frame,
                    selectedTable.block().x(),
                    selectedTable.block().z()
            );
        }
        return aimAt(
                context,
                frame,
                selectedTable.hitPosition()
        );
    }

    private SkillTickResult openTable(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    nextCraftingPhase(
                            ownedFrame().orElseThrow()
                    )
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareFoundationShelterMaterialsSkill
                                ::isCraftingTable);
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_TABLE);
        }
        final ActionOutcome opened = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        if (!opened.accepted()) {
            return fail(context, NAME + ".table_open_rejected");
        }
        return transition(context, Phase.CONFIRM_TABLE_MENU);
    }

    private SkillTickResult confirmTableMenu(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            recipeAttempts = 0;
            return transition(context, nextCraftingPhase(frame));
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(
                    context,
                    NAME + ".table_menu_unconfirmed"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult craftSticks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (itemCount(frame, STICK) > 0) {
            return transition(context, Phase.CRAFT_DOOR);
        }
        return craftOne(
                context,
                new CraftRecipeParameters(STICK, 1),
                Phase.CRAFT_DOOR,
                NAME + ".stick_recipe_failed"
        );
    }

    private SkillTickResult craftDoor(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (safeDoorCount(frame) > 0) {
            return transition(context, Phase.CRAFT_TORCH);
        }
        if (selectedPlankItemId == null) {
            return fail(context, NAME + ".plank_family_missing");
        }
        return craftOne(
                context,
                new CraftRecipeParameters(
                        doorRecipe(selectedPlankItemId),
                        1
                ),
                Phase.CRAFT_TORCH,
                NAME + ".door_recipe_failed"
        );
    }

    private SkillTickResult craftTorch(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (lightCount(frame) > 0) {
            return transition(context, Phase.FINISH);
        }
        return craftOne(
                context,
                new CraftRecipeParameters(TORCH, 1),
                Phase.FINISH,
                NAME + ".torch_recipe_failed"
        );
    }

    private SkillTickResult craftOne(
            final SkillContext context,
            final CraftRecipeParameters parameters,
            final Phase next,
            final String failureCode
    ) {
        final InventoryOperationResult preflight =
                inventory.checkCraft(parameters);
        if (!preflight.succeeded()) {
            if (++recipeAttempts < MAXIMUM_RECIPE_WAIT_TICKS) {
                return SkillTickResult.running(false, true);
            }
            return fail(context, failureCode);
        }
        final InventoryOperationResult crafted =
                inventory.craftOnce(parameters);
        if (!crafted.succeeded()) {
            return fail(context, failureCode);
        }
        recipeAttempts = 0;
        return transition(context, next);
    }

    private SkillTickResult finish(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedPlankItemId == null
                || itemCount(
                        frame,
                        selectedPlankItemId
                ) < requiredStructuralPlanksAfterComponents()
                || safeDoorCount(frame) < 1
                || lightCount(frame) < 1) {
            return fail(
                    context,
                    NAME + ".materials_unconfirmed"
            );
        }
        if (inventory.hasThreeByThreeCraftingMenu()) {
            final InventoryOperationResult closed =
                    inventory.closeThreeByThreeCraftingMenu();
            if (!closed.succeeded()) {
                return fail(
                        context,
                        NAME + ".table_close_failed"
                );
            }
        }
        core.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult scan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase scanningPhase
    ) {
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            final String suffix = switch (scanningPhase) {
                case FIND_WOOD -> "wood_not_visible";
                case FIND_COAL -> "coal_not_visible";
                case FIND_TABLE -> "table_not_visible";
                case FIND_FURNACE -> "furnace_not_visible";
                default -> "scan_exhausted";
            };
            return fail(context, NAME + "." + suffix);
        }
        final int yawIndex =
                scanTurns % SCAN_YAW_OFFSETS.length;
        final int pitchIndex =
                scanTurns / SCAN_YAW_OFFSETS.length
                        % SCAN_PITCHES.length;
        if (scanTurns == 0) {
            scanBaseYaw = yaw(frame.lookDirection());
        }
        final float targetYaw = normalizeYaw(
                scanBaseYaw + SCAN_YAW_OFFSETS[yawIndex]
        );
        final float targetPitch = SCAN_PITCHES[pitchIndex];
        scanTurns++;
        nextScanTick = context.gameTick()
                + SCAN_INTERVAL_TICKS;
        if (!core.stop().accepted()
                || !core.look(
                        new LookIntent(targetYaw, targetPitch)
                ).accepted()) {
            return fail(context, NAME + ".scan_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private void beginScan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase scanningPhase
    ) {
        phase = scanningPhase;
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanBaseYaw = yaw(frame.lookDirection());
        selectedTable = null;
        selectedFurnace = null;
    }

    private SkillTickResult aimAt(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta =
                target.subtract(frame.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return SkillTickResult.running(false, true);
        }
        final float targetYaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float targetPitch = (float) Math.toDegrees(
                Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                )
        );
        if (!core.stop().accepted()
                || !core.look(
                        new LookIntent(targetYaw, targetPitch)
                ).accepted()) {
            return fail(context, NAME + ".aim_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult transition(
            final SkillContext context,
            final Phase next
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private void refreshPlankFamily(final CoreSkillFrame frame) {
        if (selectedPlankItemId != null
                && potentialPlanks(
                        frame,
                        selectedPlankItemId
                ) > 0) {
            return;
        }
        selectedPlankItemId =
                bestPlankFamily(frame).orElse(selectedPlankItemId);
    }

    private Optional<String> bestPlankFamily(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .map(item -> {
                    if (item.endsWith("_planks")) {
                        return Optional.of(item);
                    }
                    return PrepareBasicCraftingSkill
                            .plankRecipeFor(item);
                })
                .flatMap(Optional::stream)
                .distinct()
                .max(Comparator
                        .comparingInt((String item) ->
                                potentialPlanks(frame, item))
                        .thenComparing(Comparator.naturalOrder()));
    }

    private Optional<VisibleBlockFace> selectVisibleWood(
            final CoreSkillFrame frame,
            final InteractionSkillFrame interaction
    ) {
        return interaction.visibleBlockFaces()
                .stream()
                .filter(face ->
                        interaction.observationRevision()
                                > rejectedWoodObservationRevision)
                .filter(face ->
                        !rejectedWoodSeeds.contains(new GridPos(
                                face.block().x(),
                                face.block().y(),
                                face.block().z()
                        )))
                .filter(face -> {
                    final Optional<String> recipe =
                            PrepareBasicCraftingSkill
                                    .plankRecipeFor(
                                            face.blockTypeId()
                                    );
                    return recipe.isPresent()
                            && (selectedPlankItemId == null
                            || selectedPlankItemId.equals(
                                    recipe.orElseThrow()
                            ));
                })
                .min(Comparator
                        .comparingDouble(
                                (VisibleBlockFace face) ->
                                        Math.abs(
                                                face.block().y()
                                                        - frame.position().y()
                                        )
                        )
                .thenComparingDouble(
                                VisibleBlockFace::distance
                        ));
    }

    private boolean acceptableExplorationWood(
            final VisibleBlockFace face
    ) {
        if (rejectedWoodSeeds.contains(new GridPos(
                face.block().x(),
                face.block().y(),
                face.block().z()
        ))) {
            return false;
        }
        final Optional<String> recipe =
                PrepareBasicCraftingSkill.plankRecipeFor(
                        face.blockTypeId()
                );
        return recipe.isPresent()
                && (selectedPlankItemId == null
                        || selectedPlankItemId.equals(
                                recipe.orElseThrow()
                        ));
    }

    static String representativeWoodBlock(
            final String plankItemId
    ) {
        if (plankItemId == null
                || !plankItemId.endsWith("_planks")) {
            return "minecraft:oak_log";
        }
        final String family = plankItemId.substring(
                0,
                plankItemId.length() - "_planks".length()
        );
        if (family.endsWith(":bamboo")) {
            return family + "_block";
        }
        if (family.endsWith(":crimson")
                || family.endsWith(":warped")) {
            return family + "_stem";
        }
        return family + "_log";
    }

    private Optional<InteractionSkillFrame> ownedInteractionFrame(
            final CoreSkillFrame frame
    ) {
        return interactionFrames.current()
                .filter(interaction ->
                        expectedPlayerId.equals(
                                interaction.playerId()
                        ))
                .filter(interaction ->
                        interaction.dimension().equals(
                                frame.dimension()
                        ))
                .filter(interaction ->
                        interaction.observationAgeTicks()
                                <= GatheringSkillPolicy.defaults()
                                        .maximumObservationAgeTicks());
    }

    private Optional<PerceptionVec3> rememberedTableTarget(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        try {
            return Objects.requireNonNull(
                    knownCraftingTable.apply(
                            context.goalRevision()
                    ),
                    "known crafting table result"
            ).filter(location -> location.dimension().equals(
                    frame.dimension().id()
            )).map(location -> new PerceptionVec3(
                    location.x() + 0.5,
                    location.y() + 0.5,
                    location.z() + 0.5
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<PerceptionVec3> rememberedFurnaceTarget(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        try {
            return Objects.requireNonNull(
                    knownFurnace.apply(context.goalRevision()),
                    "known furnace result"
            ).filter(location -> location.dimension().equals(
                    frame.dimension().id()
            )).map(location -> new PerceptionVec3(
                    location.x() + 0.5,
                    location.y() + 0.5,
                    location.z() + 0.5
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<VisibleBlockFace> visibleFurnace(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(
                        PrepareFoundationShelterMaterialsSkill
                                ::isFurnace
                )
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<MenuSkillFrame> currentFurnaceMenu() {
        return menuFrames.current()
                .filter(frame -> expectedPlayerId.equals(
                        frame.playerId()
                ))
                .filter(frame -> frame.menu().menuType().equals(
                        FURNACE
                ));
    }

    private boolean hasFurnaceEvidence(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (currentFurnaceMenu().isPresent()
                || visibleFurnace(frame).isPresent()) {
            return true;
        }
        try {
            return Objects.requireNonNull(
                    knownFurnace.apply(context.goalRevision()),
                    "known furnace result"
            ).filter(location -> location.dimension().equals(
                    frame.dimension().id()
            )).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasCraftingTableEvidence(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()
                || ownsItem(frame, CRAFTING_TABLE)
                || frame.visibleBlockFaces()
                        .stream()
                        .anyMatch(
                                PrepareFoundationShelterMaterialsSkill
                                        ::isCraftingTable
                        )) {
            return true;
        }
        try {
            return Objects.requireNonNull(
                    knownCraftingTable.apply(
                            context.goalRevision()
                    ),
                    "known crafting table result"
            ).filter(location -> location.dimension().equals(
                    frame.dimension().id()
            )).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int requiredPlanksBeforeComponents(
            final CoreSkillFrame frame
    ) {
        int required = requiredStructuralPlanksAfterComponents();
        if (safeDoorCount(frame) == 0) {
            required += DOOR_PLANK_COST;
        }
        if (itemCount(frame, STICK) == 0
                && lightCount(frame) == 0) {
            required += STICK_PLANK_COST;
        }
        if (charcoalFallbackRequested
                && needsTorchFuel(frame)) {
            required += CHARCOAL_FUEL_PLANK_COST;
        }
        return required;
    }

    private int requiredStructuralPlanksAfterComponents() {
        return Math.addExact(
                REQUIRED_STRUCTURAL_BLOCKS,
                additionalPlankReserve
        );
    }

    private int requiredPotentialPlanksBeforeComponents(
            final CoreSkillFrame frame
    ) {
        final int required = requiredPlanksBeforeComponents(frame);
        return charcoalFallbackRequested
                && needsTorchFuel(frame)
                ? Math.addExact(required, PLANKS_PER_WOOD)
                : required;
    }

    private static int potentialPlanks(
            final CoreSkillFrame frame,
            final String plankItemId
    ) {
        int result = itemCount(frame, plankItemId);
        for (InventoryItemSummary item : frame.inventory()) {
            if (PrepareBasicCraftingSkill
                    .plankRecipeFor(item.itemId())
                    .filter(plankItemId::equals)
                    .isPresent()) {
                result = Math.addExact(
                        result,
                        Math.multiplyExact(
                                item.count(),
                                PLANKS_PER_WOOD
                        )
                );
            }
        }
        return result;
    }

    private static int safeDoorCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> DynamicShelterPlanner
                        .isSafeDoorItem(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static int lightCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item -> DynamicShelterPlanner
                        .isLightItem(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static boolean needsTorchFuel(
            final CoreSkillFrame frame
    ) {
        return lightCount(frame) == 0
                && itemCount(frame, COAL) == 0
                && itemCount(frame, CHARCOAL) == 0;
    }

    private static int convertibleWoodCount(
            final CoreSkillFrame frame,
            final String plankItemId
    ) {
        return frame.inventory().stream()
                .filter(item -> PrepareBasicCraftingSkill
                        .plankRecipeFor(item.itemId())
                        .filter(plankItemId::equals)
                        .isPresent())
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static Optional<String> burnableWoodForPlank(
            final CoreSkillFrame frame,
            final String plankItemId
    ) {
        return frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .filter(item -> PrepareBasicCraftingSkill
                        .plankRecipeFor(item)
                        .filter(plankItemId::equals)
                        .isPresent())
                .filter(
                        PrepareFoundationShelterMaterialsSkill
                                ::isBurnableCharcoalInput
                )
                .findFirst();
    }

    static boolean isBurnableCharcoalInput(
            final String itemId
    ) {
        if (!itemId.startsWith("minecraft:")) {
            return false;
        }
        String path = itemId.substring("minecraft:".length());
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        return !path.startsWith("crimson_")
                && !path.startsWith("warped_")
                && (path.endsWith("_log")
                        || path.endsWith("_wood"));
    }

    static boolean recoverableCoalGatherFailure(
            final String code
    ) {
        return code.endsWith(".cluster_not_rediscovered")
                || code.endsWith(".target_binding_lost")
                || code.endsWith(".mining_timed_out")
                || code.endsWith(".drop_not_collected")
                || code.endsWith(".stuck")
                || code.endsWith(".seed_not_visible");
    }

    static boolean recoverableWoodGatherFailure(
            final String code
    ) {
        return code.endsWith(".cluster_not_rediscovered")
                || code.endsWith(".target_binding_lost")
                || code.endsWith(".mining_timed_out")
                || code.endsWith(".drop_not_collected")
                || code.endsWith(".stuck")
                || code.endsWith(".seed_not_visible");
    }

    static boolean woodGatheringProgressExpired(
            final long currentTick,
            final long progressAtTick
    ) {
        return progressAtTick >= 0L
                && currentTick - progressAtTick
                        >= MAXIMUM_WOOD_NO_PROGRESS_TICKS;
    }

    static boolean shouldExploreAfterRepeatedWoodRejection(
            final int rejectedSeedCount
    ) {
        return rejectedSeedCount >= WOOD_REJECTIONS_BEFORE_EXPLORATION;
    }

    private static int itemCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static boolean ownsItem(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return itemCount(frame, itemId) > 0
                || frame.mainHand().itemId().equals(itemId)
                        && frame.mainHand().count() > 0
                || frame.offHand().itemId().equals(itemId)
                        && frame.offHand().count() > 0;
    }

    private static Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_pose"
            ));
        }
        final double maximumDanger =
                context.hardcore() ? 0.08 : 0.25;
        if (Math.max(context.riskScore(), frame.danger())
                > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        if (frame.health() / frame.maxHealth()
                < (context.hardcore() ? 0.75F : 0.40F)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_too_low"
            ));
        }
        return Optional.empty();
    }

    private static Phase nextCraftingPhase(
            final CoreSkillFrame frame
    ) {
        if (itemCount(frame, STICK) == 0
                && lightCount(frame) == 0) {
            return Phase.CRAFT_STICKS;
        }
        if (safeDoorCount(frame) == 0) {
            return Phase.CRAFT_DOOR;
        }
        return lightCount(frame) == 0
                ? Phase.CRAFT_TORCH
                : Phase.FINISH;
    }

    private static String doorRecipe(
            final String plankItemId
    ) {
        if (!plankItemId.endsWith("_planks")) {
            throw new IllegalArgumentException(
                    "Plank item cannot identify a door family"
            );
        }
        return plankItemId.substring(
                0,
                plankItemId.length() - "_planks".length()
        ) + "_door";
    }

    private static ObservedBlockTarget observedTarget(
            final InteractionSkillFrame frame,
            final VisibleBlockFace face
    ) {
        return new ObservedBlockTarget(
                frame.observationRevision(),
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(
                        face.face().toUpperCase(Locale.ROOT)
                )
        );
    }

    private static boolean isCraftingTable(
            final VisibleBlockFace face
    ) {
        return face.blockTypeId().equals(CRAFTING_TABLE);
    }

    private static boolean isFurnace(
            final VisibleBlockFace face
    ) {
        return face.blockTypeId().equals(FURNACE);
    }

    private static BlockInteractionTarget target(
            final VisibleBlockFace visible
    ) {
        return new BlockInteractionTarget(
                visible.block().x(),
                visible.block().y(),
                visible.block().z(),
                BlockFace.valueOf(
                        visible.face().toUpperCase(Locale.ROOT)
                ),
                new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                )
        );
    }

    private static MenuBinding binding(
            final MenuSkillFrame frame
    ) {
        return new MenuBinding(
                frame.sampleSequence(),
                frame.menu().containerId(),
                frame.menu().stateId()
        );
    }

    private static float yaw(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        );
    }

    private static float normalizeYaw(final float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized <= -180.0F) {
            normalized += 360.0F;
        } else if (normalized > 180.0F) {
            normalized -= 360.0F;
        }
        return normalized;
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private void cancelGatherer(final SkillContext context) {
        if (gatheringParameters != null) {
            gatherer.cancel(context, gatheringParameters);
        }
        gatheringParameters = null;
    }

    private void cancelCoalExplorer(final SkillContext context) {
        if (coalExplorer != null
                && coalExplorationParameters != null) {
            try {
                coalExplorer.cancel(
                        context,
                        coalExplorationParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        coalExplorer = null;
        coalExplorationParameters = null;
    }

    private void cancelWoodExplorer(final SkillContext context) {
        if (woodExplorer != null
                && woodExplorationParameters != null) {
            try {
                woodExplorer.cancel(
                        context,
                        woodExplorationParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        woodExplorer = null;
        woodExplorationParameters = null;
    }

    private void cancelTableMovement(final SkillContext context) {
        if (tableMovement != null
                && tableMovementParameters != null) {
            tableMovement.cancel(context, tableMovementParameters);
        }
        tableMovement = null;
        tableMovementParameters = null;
    }

    private void cancelFurnaceMovement(
            final SkillContext context
    ) {
        if (furnaceMovement != null
                && furnaceMovementParameters != null) {
            furnaceMovement.cancel(
                    context,
                    furnaceMovementParameters
            );
        }
        furnaceMovement = null;
        furnaceMovementParameters = null;
    }

    private void cancelChildSkills(final SkillContext context) {
        cancelGatherer(context);
        cancelWoodExplorer(context);
        cancelCoalExplorer(context);
        cancelTableMovement(context);
        cancelFurnaceMovement(context);
        if (smelter != null && smeltingParameters != null) {
            smelter.cancel(context, smeltingParameters);
        }
        smelter = null;
        smeltingParameters = null;
    }

    private void closeCurrentMenu() {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            inventory.closeThreeByThreeCraftingMenu();
            return;
        }
        menuFrames.current()
                .filter(frame -> expectedPlayerId.equals(
                        frame.playerId()
                ))
                .ifPresent(frame -> menus.close(
                        new CloseMenuParameters(binding(frame))
                ));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        cancelChildSkills(context);
        core.stop();
        closeCurrentMenu();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    static SkillParameterResult<NoParameters> parseNone(
            final List<SkillArgument> arguments
    ) {
        return arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    private enum Phase {
        IDLE(false),
        FIND_WOOD(true),
        EXPLORE_WOOD(true),
        GATHER_WOOD(true),
        CONFIRM_WOOD(true),
        PREPARE_PLANKS(true),
        FIND_COAL(true),
        EXPLORE_COAL(true),
        GATHER_COAL(true),
        CONFIRM_COAL(true),
        FIND_FURNACE(true),
        MOVE_TO_FURNACE(true),
        AIM_FURNACE(true),
        OPEN_FURNACE(true),
        CONFIRM_FURNACE_MENU(true),
        SMELT_CHARCOAL(true),
        CLOSE_FURNACE(true),
        FIND_TABLE(true),
        MOVE_TO_TABLE(true),
        AIM_TABLE(true),
        OPEN_TABLE(true),
        CONFIRM_TABLE_MENU(true),
        CRAFT_STICKS(true),
        CRAFT_DOOR(true),
        CRAFT_TORCH(true),
        FINISH(true),
        COMPLETED(false),
        CANCELLED(false),
        FAILED(false);

        private final boolean active;

        Phase(final boolean active) {
            this.active = active;
        }

        boolean active() {
            return active;
        }
    }
}
