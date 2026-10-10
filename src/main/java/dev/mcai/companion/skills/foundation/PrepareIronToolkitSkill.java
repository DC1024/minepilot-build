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
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
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
import dev.mcai.companion.skills.gathering.GatherVisibleBlockClusterSkill.DropCollectionDebt;
import dev.mcai.companion.skills.gathering.GatheringSkillPolicy;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.loot.CollectObservedItemParameters;
import dev.mcai.companion.skills.loot.CollectObservedItemSkill;
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
 * Produces the minimum M1 iron survival toolkit through ordinary visible
 * mining, recipe placement, block use and furnace menu transactions.
 *
 * <p>The high-level model chooses this skill once. The latency-sensitive
 * resource, workstation and smelting sequence then remains local and
 * checkpointed. No block, recipe output or inventory stack is written
 * directly. Resource seeds must be present in the companion's current
 * first-person semantic frame, while remembered fixtures are used only to
 * walk back toward locations this same body previously opened. A remembered
 * fixture must become visible again before it can be used.</p>
 */
public final class PrepareIronToolkitSkill
        implements Skill<NoParameters> {
    public static final String NAME = "prepare_iron_toolkit";

    private static final String AIR = "minecraft:air";
    private static final String STONE_BLOCK = "minecraft:stone";
    private static final String COAL_ORE = "minecraft:coal_ore";
    private static final String DEEPSLATE_COAL_ORE =
            "minecraft:deepslate_coal_ore";
    private static final String IRON_ORE = "minecraft:iron_ore";
    private static final String DEEPSLATE_IRON_ORE =
            "minecraft:deepslate_iron_ore";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String COAL = "minecraft:coal";
    private static final String CHARCOAL = "minecraft:charcoal";
    private static final String RAW_IRON = "minecraft:raw_iron";
    private static final String IRON_INGOT = "minecraft:iron_ingot";
    private static final String CRAFTING_TABLE =
            "minecraft:crafting_table";
    private static final String FURNACE = "minecraft:furnace";
    private static final String STICK = "minecraft:stick";
    private static final String IRON_PICKAXE =
            "minecraft:iron_pickaxe";
    private static final String BUCKET = "minecraft:bucket";
    private static final String WATER_BUCKET =
            "minecraft:water_bucket";
    private static final String LAVA_BUCKET = "minecraft:lava_bucket";
    private static final String SHIELD = "minecraft:shield";

    private static final Set<String> STONE_OR_BETTER_PICKAXES = Set.of(
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );
    private static final int FURNACE_COBBLESTONE = 8;
    private static final int CHARCOAL_FUEL_PLANKS = 1;
    private static final int CHARCOAL_INPUT_POTENTIAL_PLANKS = 4;
    private static final int MAXIMUM_TICKS = 12_000;
    private static final int MAXIMUM_SCAN_TURNS = 32;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAXIMUM_AIM_TICKS = 100;
    private static final int MAXIMUM_MENU_WAIT_TICKS = 100;
    private static final int MAXIMUM_RECIPE_WAIT_TICKS = 100;
    private static final int MAXIMUM_FIXTURE_AIM_TICKS = 120;
    private static final int MAXIMUM_FIXTURE_MOVE_TICKS = 240;
    private static final int MAXIMUM_FIXTURE_NO_PROGRESS_TICKS = 100;
    private static final int MAXIMUM_CONFIRM_TICKS = 120;
    private static final int MAXIMUM_RESOURCE_DROP_RECOVERY_TICKS = 240;
    private static final int RESOURCE_DROP_SCAN_INTERVAL_TICKS = 5;
    private static final int RESOURCE_SEARCH_RADIUS = 48;
    private static final int RESOURCE_SEARCH_STEP = 8;
    private static final double RELIABLE_INTERACTION_DISTANCE = 3.75;
    private static final double APPROACH_RADIUS = 3.0;
    private static final double MINIMUM_PLACEMENT_DISTANCE = 1.35;
    private static final double MAXIMUM_PLACEMENT_DISTANCE = 4.25;
    private static final float[] SCAN_YAW_OFFSETS = {
            0.0F,
            45.0F,
            -45.0F,
            90.0F,
            -90.0F,
            135.0F,
            -135.0F,
            180.0F
    };
    private static final float[] SCAN_PITCHES = {
            15.0F,
            -20.0F,
            40.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;
    private final MenuSkillActuator menus;
    private final MenuSkillFrameSource menuFrames;
    private final GatherVisibleBlockClusterSkill gatherer;
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
    private ResourceKind activeResource;
    private GatherVisibleBlockClusterParameters gatheringParameters;
    private int gatheringStartCount;
    private long rejectedResourceObservationRevision;
    private ExploreForObservedTargetSkill resourceExplorer;
    private ExploreForTargetParameters resourceExplorationParameters;
    private DropCollectionDebt resourceDropDebt;
    private CollectObservedItemSkill resourceDropCollector;
    private CollectObservedItemParameters resourceDropCollectorParameters;
    private MoveToSkill resourceDropMovement;
    private MoveToParameters resourceDropMovementParameters;
    private long resourceDropRecoveryStartedAtTick = -1L;
    private long nextResourceDropScanTick = -1L;
    private int resourceDropScanTurns;
    private float resourceDropScanBaseYaw;
    private boolean resourceDropMovementAttempted;
    private UUID resourceDropCollectorEntityId;
    private final Set<UUID> rejectedResourceDropEntities =
            new HashSet<>();
    private VisibleBlockFace selectedFixture;
    private VisibleBlockFace selectedSupport;
    private BlockCoordinate expectedFurnacePosition;
    private MoveToSkill fixtureMovement;
    private MoveToParameters fixtureMovementParameters;
    private FixtureKind movementTarget;
    private double fixtureMovementBestDistance =
            Double.POSITIVE_INFINITY;
    private long fixtureMovementLastProgressTick = -1L;
    private SmeltMenuBatchSkill smelter;
    private SmeltMenuBatchParameters smeltingParameters;
    private long charcoalCompletedMenuSample;
    private final Set<BlockCoordinate> rejectedFurnaceSupports =
            new HashSet<>();
    private final Set<GridPos> attemptedFixtureVantages =
            new HashSet<>();
    private boolean fixtureOcclusionRecovery;

    public PrepareIronToolkitSkill(
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
        return PrepareIronToolkitSkill::parseNone;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> maybeFrame = ownedFrame();
        if (maybeFrame.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame frame = maybeFrame.orElseThrow();
        final Optional<SkillFailure> unsafe = safetyFailure(
                context,
                frame
        );
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (toolkitComplete(frame)) {
            return Optional.empty();
        }
        if (selectMiningTool(frame).isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stone_pickaxe_required"
            ));
        }
        if (!hasCraftingTableEvidence(context, frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".crafting_table_required"
            ));
        }
        if (potentialPlanks(frame) < requiredPlanks(frame)
                || itemCount(frame, STICK) < requiredSticks(frame)
                    && potentialPlanks(frame)
                        < requiredPlanks(frame) + 2) {
            return Optional.of(SkillFailure.of(
                    NAME + ".wood_components_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before iron preparation"
                )
        );
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        activeResource = null;
        gatheringParameters = null;
        gatheringStartCount = 0;
        rejectedResourceObservationRevision = -1L;
        resourceExplorer = null;
        resourceExplorationParameters = null;
        clearResourceDropRecovery();
        selectedFixture = null;
        selectedSupport = null;
        expectedFurnacePosition = null;
        fixtureMovement = null;
        fixtureMovementParameters = null;
        movementTarget = null;
        fixtureMovementBestDistance = Double.POSITIVE_INFINITY;
        fixtureMovementLastProgressTick = -1L;
        smelter = null;
        smeltingParameters = null;
        charcoalCompletedMenuSample = -1L;
        rejectedFurnaceSupports.clear();
        attemptedFixtureVantages.clear();
        fixtureOcclusionRecovery = false;
        beginScan(
                context,
                frame,
                toolkitComplete(frame)
                        ? Phase.FINISH
                        : Phase.FIND_RESOURCE
        );
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
        final String gatheringChild = phase == Phase.GATHER_RESOURCE
                && gatheringParameters != null
                ? gatherer.checkpoint(
                        context,
                        gatheringParameters
                ).payload()
                : "null";
        final String movementChild =
                phase == Phase.MOVE_TO_FIXTURE
                        && fixtureMovement != null
                        && fixtureMovementParameters != null
                ? fixtureMovement.checkpoint(
                        context,
                        fixtureMovementParameters
                ).payload()
                : "null";
        final String explorationChild =
                phase == Phase.EXPLORE_RESOURCE
                        && resourceExplorer != null
                        && resourceExplorationParameters != null
                ? resourceExplorer.checkpoint(
                        context,
                        resourceExplorationParameters
                ).payload()
                : "null";
        final String dropRecoveryChild =
                phase == Phase.RECOVER_RESOURCE_DROP
                        && resourceDropCollector != null
                        && resourceDropCollectorParameters != null
                ? resourceDropCollector.checkpoint(
                        context,
                        resourceDropCollectorParameters
                ).payload()
                : phase == Phase.RECOVER_RESOURCE_DROP
                        && resourceDropMovement != null
                        && resourceDropMovementParameters != null
                ? resourceDropMovement.checkpoint(
                        context,
                        resourceDropMovementParameters
                ).payload()
                : "null";
        final double checkpointMovementDistance =
                Double.isFinite(fixtureMovementBestDistance)
                        ? fixtureMovementBestDistance
                        : -1.0;
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"resource\":\"%s\","
                                + "\"scanTurns\":%d,"
                                + "\"fixtureVantages\":%d,"
                                + "\"fixtureMovementBestDistance\":%.3f,"
                                + "\"fixtureMovementChild\":%s,"
                                + "\"explorationChild\":%s,"
                                + "\"dropRecovery\":%s,"
                                + "\"dropRecoveryOrigin\":\"%s\","
                                + "\"dropRecoveryChild\":%s,"
                                + "\"elapsedTicks\":%d,"
                                + "\"gathering\":%s,"
                                + "\"smelting\":%s,"
                                + "\"smeltingCharcoal\":%s,"
                                + "\"child\":%s}",
                        phase.name(),
                        activeResource == null
                                ? ""
                                : activeResource.name(),
                        scanTurns,
                        attemptedFixtureVantages.size(),
                        checkpointMovementDistance,
                        movementChild,
                        explorationChild,
                        phase == Phase.RECOVER_RESOURCE_DROP,
                        resourceDropDebt == null
                                ? ""
                                : resourceDropDebt.origin(),
                        dropRecoveryChild,
                        Math.max(
                                0L,
                                context.gameTick() - startedAtTick
                        ),
                        phase == Phase.GATHER_RESOURCE,
                        phase == Phase.SMELT_IRON,
                        phase == Phase.SMELT_CHARCOAL,
                        gatheringChild
                )
        );
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
            default -> SkillResult.failed(SkillFailure.of(
                    NAME + ".invalid_state"
            ));
        };
    }

    private SkillTickResult tickSafely(final SkillContext context) {
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        final Optional<SkillFailure> unsafe = safetyFailure(
                context,
                frame
        );
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow().code());
        }
        /*
         * A normal pursuit, step-up, block pickup, or knockback can leave the
         * body between ground-contact samples. That is not a failed survival
         * plan. Let child movement continue while it owns the body; otherwise
         * wait locally for a stable stance instead of throwing the whole
         * high-level task back to the model.
         */
        if (!frame.onGround()
                && phase != Phase.GATHER_RESOURCE
                && phase != Phase.EXPLORE_RESOURCE
                && phase != Phase.MOVE_TO_FIXTURE) {
            core.stop();
            return SkillTickResult.running(false, true);
        }

        return switch (phase) {
            case FIND_RESOURCE -> findResource(context, frame);
            case EXPLORE_RESOURCE ->
                    exploreResource(context, frame);
            case GATHER_RESOURCE -> gatherResource(context, frame);
            case RECOVER_RESOURCE_DROP ->
                    recoverResourceDrop(context, frame);
            case CONFIRM_RESOURCE ->
                    confirmResource(context, frame);
            case FIND_TABLE -> findFixture(
                    context,
                    frame,
                    FixtureKind.TABLE
            );
            case MOVE_TO_FIXTURE ->
                    moveToFixture(context, frame);
            case AIM_FIXTURE -> aimFixture(context, frame);
            case OPEN_TABLE -> openTable(context);
            case CONFIRM_TABLE_MENU ->
                    confirmTableMenu(context, frame);
            case PREPARE_FUEL_COMPONENTS ->
                    prepareFuelComponents(context, frame);
            case PREPARE_COMPONENTS ->
                    prepareComponents(context, frame);
            case CRAFT_FURNACE -> craftFurnace(context, frame);
            case CLOSE_TABLE_FOR_FURNACE ->
                    closeTableForFurnace(context, frame);
            case EQUIP_FURNACE -> equipFurnace(context, frame);
            case FIND_FURNACE_SUPPORT ->
                    findFurnaceSupport(context, frame);
            case AIM_FURNACE_SUPPORT ->
                    aimFurnaceSupport(context, frame);
            case PLACE_FURNACE -> placeFurnace(context);
            case CONFIRM_FURNACE ->
                    confirmFurnace(context, frame);
            case FIND_FURNACE -> findFixture(
                    context,
                    frame,
                    FixtureKind.FURNACE
            );
            case OPEN_FURNACE -> openFurnace(context);
            case CONFIRM_FURNACE_MENU ->
                    confirmFurnaceMenu(context, frame);
            case SMELT_CHARCOAL -> smeltCharcoal(context);
            case SMELT_IRON -> smeltIron(context);
            case CLOSE_FURNACE -> closeFurnace(context, frame);
            case CRAFT_IRON_PICKAXE ->
                    craftMissingTool(
                            context,
                            frame,
                            IRON_PICKAXE,
                            Phase.CRAFT_BUCKET
                    );
            case CRAFT_BUCKET -> craftMissingBucket(
                    context,
                    frame
            );
            case CRAFT_SHIELD ->
                    craftMissingTool(
                            context,
                            frame,
                            SHIELD,
                            Phase.FINISH
                    );
            case FINISH -> finish(context, frame);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult findResource(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<ResourceKind> needed =
                nextResource(context, frame);
        if (needed.isEmpty()) {
            activeResource = null;
            if (needsSmelting(frame)
                    && knownUsableFurnace(context, frame).isPresent()) {
                beginScan(context, frame, Phase.FIND_FURNACE);
            } else {
                beginScan(context, frame, Phase.FIND_TABLE);
            }
            return SkillTickResult.running(true, true);
        }
        activeResource = needed.orElseThrow();
        final Optional<InteractionSkillFrame> interaction =
                ownedInteractionFrame(frame);
        if (interaction.isPresent()
                && interaction.orElseThrow().observationRevision()
                        > rejectedResourceObservationRevision) {
            final Optional<VisibleBlockFace> visible =
                    selectResourceFace(
                            interaction.orElseThrow(),
                            activeResource
                    );
            if (visible.isPresent()) {
                final VisibleBlockFace seed = visible.orElseThrow();
                final String tool = selectMiningTool(frame)
                        .orElse(null);
                if (tool == null) {
                    return fail(
                            context,
                            NAME + ".stone_pickaxe_required"
                    );
                }
                gatheringParameters =
                        new GatherVisibleBlockClusterParameters(
                                interaction.orElseThrow().dimension(),
                                new ObservedBlockTarget(
                                        interaction.orElseThrow()
                                            .observationRevision(),
                                        seed.block().x(),
                                        seed.block().y(),
                                        seed.block().z(),
                                        BlockFace.valueOf(
                                                seed.face()
                                                    .toUpperCase(
                                                        Locale.ROOT
                                                    )
                                        )
                                ),
                                seed.blockTypeId(),
                                resourceDeficit(
                                        context,
                                        frame,
                                        activeResource
                                ),
                                8.0,
                                tool
                        );
                final Optional<SkillFailure> rejected =
                        gatherer.preconditions(
                                context,
                                gatheringParameters
                        );
                if (rejected.isEmpty()) {
                    gatheringStartCount =
                            resourceCount(frame, activeResource);
                    gatherer.start(context, gatheringParameters);
                    phase = Phase.GATHER_RESOURCE;
                    phaseStartedAtTick = context.gameTick();
                    return SkillTickResult.running(true, true);
                }
            }
        }
        return scan(context, frame, Phase.FIND_RESOURCE);
    }

    private SkillTickResult gatherResource(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeResource == null || gatheringParameters == null) {
            return fail(
                    context,
                    NAME + ".resource_binding_missing"
            );
        }
        final SkillTickResult child = gatherer.tick(
                context,
                gatheringParameters
        );
        if (child.status() == SkillTickResult.Status.FAILED) {
            final String childFailure = child.failure()
                    .map(SkillFailure::code)
                    .orElse(NAME + ".resource_gather_failed");
            final CoreSkillFrame current = ownedFrame().orElse(frame);
            final boolean satisfied = resourceSatisfied(
                    context,
                    current,
                    activeResource
            );
            final Optional<DropCollectionDebt> dropDebt =
                    gatherer.uncollectedDropDebt();
            if (shouldRecoverMinedDrop(
                    childFailure,
                    dropDebt,
                    satisfied
            )) {
                gatheringParameters = null;
                gatheringStartCount = 0;
                return beginResourceDropRecovery(
                        context,
                        current,
                        dropDebt.orElseThrow()
                );
            }
            if (resourceCount(current, activeResource)
                        > gatheringStartCount
                    || satisfied) {
                gatheringParameters = null;
                gatheringStartCount = 0;
                beginScan(
                        context,
                        current,
                        Phase.CONFIRM_RESOURCE
                );
                return SkillTickResult.running(true, true);
            }
            if (recoverableGatherFailure(childFailure)) {
                gatheringParameters = null;
                gatheringStartCount = 0;
                ownedInteractionFrame(current).ifPresent(
                        interaction ->
                                rejectedResourceObservationRevision =
                                        Math.max(
                                                rejectedResourceObservationRevision,
                                                interaction
                                                        .observationRevision()
                                        )
                );
                if (activeResource == ResourceKind.COAL
                        && canPrepareCharcoal(current)) {
                    activeResource = null;
                }
                beginScan(
                        context,
                        current,
                        Phase.FIND_RESOURCE
                );
                return SkillTickResult.running(true, true);
            }
            return fail(
                    context,
                    NAME + "."
                            + activeResource.failureStem()
                            + "_gather_failed"
            );
        }
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            gatheringParameters = null;
            gatheringStartCount = 0;
            beginScan(
                    context,
                    frame,
                    Phase.CONFIRM_RESOURCE
            );
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                child.madeProgress(),
                child.safeCheckpoint()
        );
    }

    private SkillTickResult beginResourceDropRecovery(
            final SkillContext context,
            final CoreSkillFrame frame,
            final DropCollectionDebt debt
    ) {
        if (activeResource == null
                || !resourceItemId(activeResource).equals(
                        debt.itemId()
                )) {
            return fail(
                    context,
                    NAME + ".resource_drop_binding_mismatch"
            );
        }
        clearResourceDropRecovery();
        resourceDropDebt = debt;
        resourceDropRecoveryStartedAtTick = context.gameTick();
        nextResourceDropScanTick = context.gameTick();
        resourceDropScanBaseYaw = yaw(frame.lookDirection());
        phase = Phase.RECOVER_RESOURCE_DROP;
        phaseStartedAtTick = context.gameTick();
        MinecraftAiCompanion.LOGGER.info(
                "Recovering causally mined resource drop before exploring "
                        + "resource={} origin={} owned={} required={}",
                activeResource,
                debt.origin(),
                debt.observedOwnedCount(),
                debt.requiredOwnedCount()
        );
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult recoverResourceDrop(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeResource == null || resourceDropDebt == null) {
            return fail(
                    context,
                    NAME + ".resource_drop_binding_missing"
            );
        }
        final DropCollectionDebt debt = resourceDropDebt;
        if (itemCount(frame, debt.itemId())
                    >= debt.requiredOwnedCount()
                || resourceSatisfied(
                        context,
                        frame,
                        activeResource
                )) {
            cancelResourceDropRecovery(context);
            beginScan(
                    context,
                    frame,
                    Phase.CONFIRM_RESOURCE
            );
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - resourceDropRecoveryStartedAtTick
                >= MAXIMUM_RESOURCE_DROP_RECOVERY_TICKS) {
            final String stem = activeResource.failureStem();
            cancelResourceDropRecovery(context);
            return fail(
                    context,
                    NAME + "." + stem + "_drop_not_collected"
            );
        }

        if (resourceDropCollector != null
                && resourceDropCollectorParameters != null) {
            final SkillTickResult result =
                    resourceDropCollector.tick(
                            context,
                            resourceDropCollectorParameters
                    );
            if (result.status()
                    == SkillTickResult.Status.COMPLETED) {
                resourceDropCollector = null;
                resourceDropCollectorParameters = null;
                resourceDropCollectorEntityId = null;
                return SkillTickResult.running(true, true);
            }
            if (result.status() == SkillTickResult.Status.FAILED) {
                if (resourceDropCollectorEntityId != null) {
                    rejectedResourceDropEntities.add(
                            resourceDropCollectorEntityId
                    );
                }
                resourceDropCollector = null;
                resourceDropCollectorParameters = null;
                resourceDropCollectorEntityId = null;
                return SkillTickResult.running(true, true);
            }
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }

        final Optional<InteractionSkillFrame> interaction =
                ownedInteractionFrame(frame);
        if (interaction.isPresent()
                && startVisibleResourceDropCollector(
                        context,
                        interaction.orElseThrow(),
                        debt
                )) {
            cancelResourceDropMovement(context);
            return SkillTickResult.running(true, true);
        }

        if (!resourceDropMovementAttempted) {
            if (resourceDropMovement == null
                    || resourceDropMovementParameters == null) {
                resourceDropMovementParameters =
                        new MoveToParameters(
                                frame.dimension(),
                                debt.origin().x() + 0.5,
                                frame.position().y(),
                                debt.origin().z() + 0.5,
                                0.5
                        );
                resourceDropMovement = new MoveToSkill(
                        expectedPlayerId,
                        core,
                        coreFrames
                );
                final Optional<SkillFailure> rejected =
                        resourceDropMovement.preconditions(
                                context,
                                resourceDropMovementParameters
                        );
                if (rejected.isPresent()) {
                    resourceDropMovement = null;
                    resourceDropMovementParameters = null;
                    resourceDropMovementAttempted = true;
                } else {
                    resourceDropMovement.start(
                            context,
                            resourceDropMovementParameters
                    );
                }
            }
            if (resourceDropMovement != null
                    && resourceDropMovementParameters != null) {
                final SkillTickResult movement =
                        resourceDropMovement.tick(
                                context,
                                resourceDropMovementParameters
                        );
                if (movement.status()
                        == SkillTickResult.Status.RUNNING) {
                    return SkillTickResult.running(
                            movement.madeProgress(),
                            movement.safeCheckpoint()
                    );
                }
                resourceDropMovement = null;
                resourceDropMovementParameters = null;
                resourceDropMovementAttempted = true;
                /*
                 * A new stance changes which item rays and floor cells can be
                 * observed. Permit one fresh collection binding after the
                 * ordinary movement attempt.
                 */
                rejectedResourceDropEntities.clear();
                return SkillTickResult.running(true, true);
            }
        }

        if (context.gameTick() < nextResourceDropScanTick) {
            core.stop();
            return SkillTickResult.running(false, true);
        }
        final int view = Math.floorMod(
                resourceDropScanTurns,
                SCAN_YAW_OFFSETS.length * SCAN_PITCHES.length
        );
        final int yawIndex = view / SCAN_PITCHES.length;
        final int pitchIndex = view % SCAN_PITCHES.length;
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        resourceDropScanBaseYaw
                                + SCAN_YAW_OFFSETS[yawIndex],
                        SCAN_PITCHES[pitchIndex]
                )).accepted()) {
            return fail(
                    context,
                    NAME + ".resource_drop_scan_rejected"
            );
        }
        resourceDropScanTurns++;
        nextResourceDropScanTick =
                context.gameTick() + RESOURCE_DROP_SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private boolean startVisibleResourceDropCollector(
            final SkillContext context,
            final InteractionSkillFrame interaction,
            final DropCollectionDebt debt
    ) {
        int selectedIndex = -1;
        double selectedDistance = Double.POSITIVE_INFINITY;
        for (int index = 0;
                index < interaction.visibleEntities().size();
                index++) {
            final VisibleEntity entity =
                    interaction.visibleEntities().get(index);
            if (!"minecraft:item".equals(entity.entityTypeId())
                    || !debt.itemId().equals(
                            entity.visibleProperties().get("itemId")
                    )
                    || rejectedResourceDropEntities.contains(
                            entity.entityId()
                    )
                    || entity.position()
                        .subtract(new PerceptionVec3(
                                debt.origin().x() + 0.5,
                                debt.origin().y() + 0.5,
                                debt.origin().z() + 0.5
                        ))
                        .length() > 6.0
                    || entity.distance() >= selectedDistance) {
                continue;
            }
            selectedIndex = index;
            selectedDistance = entity.distance();
        }
        if (selectedIndex < 0) {
            return false;
        }
        final VisibleEntity selected =
                interaction.visibleEntities().get(selectedIndex);
        final int remaining = (int) Math.max(
                20L,
                Math.min(
                        200L,
                        MAXIMUM_RESOURCE_DROP_RECOVERY_TICKS
                                - (context.gameTick()
                                    - resourceDropRecoveryStartedAtTick)
                )
        );
        final CollectObservedItemParameters parameters =
                new CollectObservedItemParameters(
                        interaction.observationRevision(),
                        "visible-" + selectedIndex,
                        remaining
                );
        final CollectObservedItemSkill collector =
                new CollectObservedItemSkill(
                        expectedPlayerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames
                );
        if (collector.preconditions(
                context,
                parameters
        ).isPresent()) {
            rejectedResourceDropEntities.add(selected.entityId());
            return false;
        }
        collector.start(context, parameters);
        resourceDropCollector = collector;
        resourceDropCollectorParameters = parameters;
        resourceDropCollectorEntityId = selected.entityId();
        return true;
    }

    private SkillTickResult beginResourceExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeResource == null) {
            return fail(
                    context,
                    NAME + ".resource_binding_missing"
            );
        }
        cancelResourceExplorer(context);
        resourceExplorationParameters =
                new ExploreForTargetParameters(
                        frame.dimension(),
                        SearchTargetKind.BLOCK,
                        activeResource.explorationBlockId(),
                        RESOURCE_SEARCH_RADIUS,
                        RESOURCE_SEARCH_STEP
                );
        resourceExplorer = new ExploreForObservedTargetSkill(
                expectedPlayerId,
                core,
                coreFrames,
                () -> interactions.sessionGeneration()
                        .orElse(-1L)
        );
        final Optional<SkillFailure> rejected =
                resourceExplorer.preconditions(
                        context,
                        resourceExplorationParameters
                );
        if (rejected.isPresent()) {
            cancelResourceExplorer(context);
            return fail(
                    context,
                    NAME + "."
                            + activeResource.failureStem()
                            + "_exploration_rejected"
            );
        }
        resourceExplorer.start(
                context,
                resourceExplorationParameters
        );
        phase = Phase.EXPLORE_RESOURCE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult exploreResource(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeResource == null
                || resourceExplorer == null
                || resourceExplorationParameters == null) {
            return fail(
                    context,
                    NAME + ".resource_exploration_binding_missing"
            );
        }
        if (resourceSatisfied(context, frame, activeResource)) {
            cancelResourceExplorer(context);
            activeResource = null;
            beginScan(context, frame, Phase.FIND_RESOURCE);
            return SkillTickResult.running(true, true);
        }
        final SkillTickResult result = resourceExplorer.tick(
                context,
                resourceExplorationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelResourceExplorer(context);
            beginScan(context, frame, Phase.FIND_RESOURCE);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String stem = activeResource.failureStem();
            if (activeResource == ResourceKind.COAL
                    && canPrepareCharcoal(frame)) {
                cancelResourceExplorer(context);
                activeResource = null;
                beginScan(context, frame, Phase.FIND_RESOURCE);
                return SkillTickResult.running(true, true);
            }
            cancelResourceExplorer(context);
            return fail(
                    context,
                    NAME + "." + stem + "_not_found"
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult confirmResource(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeResource == null) {
            return fail(
                    context,
                    NAME + ".resource_binding_missing"
            );
        }
        if (resourceSatisfied(context, frame, activeResource)) {
            activeResource = null;
            beginScan(context, frame, Phase.FIND_RESOURCE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            beginScan(context, frame, Phase.FIND_RESOURCE);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult findFixture(
            final SkillContext context,
            final CoreSkillFrame frame,
            final FixtureKind fixture
    ) {
        if (fixture == FixtureKind.TABLE
                && inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    toolkitCraftingPhase(context, frame)
            );
        }
        /*
         * The narrow crosshair ray is sampled independently from the
         * lower-frequency semantic face fan.  After aiming at a remembered
         * workstation, the ray can already have re-verified the exact block
         * while the semantic fan still contains the previous view.  Ignoring
         * that stronger current observation made the skill rotate for an
         * entire scan and report table_not_visible even though its eyes were
         * on the table.  Accept only a presently identified crosshair block;
         * remembered coordinates alone still cannot open anything.
         */
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(face, fixture));
        if (crosshair.isPresent()) {
            selectedFixture = crosshair.orElseThrow();
            movementTarget = fixture;
            fixtureOcclusionRecovery = false;
            return transition(
                    context,
                    fixture == FixtureKind.TABLE
                            ? Phase.OPEN_TABLE
                            : Phase.OPEN_FURNACE
            );
        }
        final Optional<VisibleBlockFace> visible =
                visibleFixture(frame, fixture);
        if (visible.isPresent()) {
            final VisibleBlockFace target = visible.orElseThrow();
            fixtureOcclusionRecovery = false;
            if (target.distance() > RELIABLE_INTERACTION_DISTANCE) {
                return beginFixtureApproach(
                        context,
                        frame,
                        fixture,
                        target.block().x(),
                        target.block().y(),
                        target.block().z()
                );
            }
            selectedFixture = target;
            movementTarget = fixture;
            return transition(context, Phase.AIM_FIXTURE);
        }
        final Optional<PerceptionVec3> remembered =
                rememberedFixtureTarget(
                        context,
                        frame,
                        fixture
                );
        if (remembered.isPresent()) {
            final PerceptionVec3 target = remembered.orElseThrow();
            final double targetDistance =
                    target.subtract(frame.eyePosition()).length();
            if (fixtureOcclusionRecovery) {
                final Optional<SkillTickResult> repositioned =
                        beginFixtureVantageApproach(
                                context,
                                frame,
                                fixture,
                                (int) Math.floor(target.x()),
                                (int) Math.floor(target.y()),
                                (int) Math.floor(target.z())
                        );
                if (repositioned.isPresent()) {
                    return repositioned.orElseThrow();
                }
            }
            if (targetDistance > RELIABLE_INTERACTION_DISTANCE
                    && !fixtureOcclusionRecovery) {
                return beginFixtureApproach(
                        context,
                        frame,
                        fixture,
                        (int) Math.floor(target.x()),
                        (int) Math.floor(target.y()),
                        (int) Math.floor(target.z())
                );
            }
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_FIXTURE_AIM_TICKS) {
                movementTarget = fixture;
                return aimAt(context, frame, target);
            }
            fixtureOcclusionRecovery = true;
            final Optional<SkillTickResult> repositioned =
                    beginFixtureVantageApproach(
                            context,
                            frame,
                            fixture,
                            (int) Math.floor(target.x()),
                            (int) Math.floor(target.y()),
                            (int) Math.floor(target.z())
                    );
            if (repositioned.isPresent()) {
                return repositioned.orElseThrow();
            }
        }
        return scan(
                context,
                frame,
                fixture == FixtureKind.TABLE
                        ? Phase.FIND_TABLE
                        : Phase.FIND_FURNACE
        );
    }

    private SkillTickResult beginFixtureApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final FixtureKind fixture,
            final int x,
            final int y,
            final int z
    ) {
        cancelMovement(context);
        fixtureMovementParameters = new MoveToParameters(
                frame.dimension(),
                x + 0.5,
                frame.position().y(),
                z + 0.5,
                APPROACH_RADIUS
        );
        fixtureMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected =
                fixtureMovement.preconditions(
                        context,
                        fixtureMovementParameters
                );
        if (rejected.isPresent()) {
            fixtureMovement = null;
            fixtureMovementParameters = null;
            return fail(
                    context,
                    NAME + "." + fixture.failureStem()
                            + "_approach_rejected"
            );
        }
        fixtureMovement.start(context, fixtureMovementParameters);
        movementTarget = fixture;
        selectedFixture = null;
        beginFixtureMovementProgress(context, frame);
        phase = Phase.MOVE_TO_FIXTURE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    /**
     * A remembered workstation can be within raw reach yet hidden behind the
     * furnace that this same compound just placed. Rotating in place cannot
     * reveal it. Select a different, fairly observed standing cell with a
     * clear observed aim corridor, then use the ordinary movement skill.
     */
    private Optional<SkillTickResult> beginFixtureVantageApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final FixtureKind fixture,
            final int x,
            final int y,
            final int z
    ) {
        final Optional<GridPos> selected =
                selectFixtureVantage(
                        frame,
                        new GridPos(x, y, z),
                        attemptedFixtureVantages,
                        context.hardcore() ? 0.08 : 0.25
                ).or(() -> selectFixtureSurveyStand(
                        frame,
                        new GridPos(x, y, z),
                        attemptedFixtureVantages,
                        context.hardcore() ? 0.08 : 0.25
                ));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        final GridPos stand = selected.orElseThrow();
        cancelMovement(context);
        fixtureMovementParameters = new MoveToParameters(
                frame.dimension(),
                stand.x() + 0.5,
                stand.y(),
                stand.z() + 0.5,
                0.5
        );
        fixtureMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected =
                fixtureMovement.preconditions(
                        context,
                        fixtureMovementParameters
                );
        if (rejected.isPresent()) {
            fixtureMovement = null;
            fixtureMovementParameters = null;
            attemptedFixtureVantages.add(stand);
            return Optional.empty();
        }
        fixtureMovement.start(
                context,
                fixtureMovementParameters
        );
        attemptedFixtureVantages.add(stand);
        movementTarget = fixture;
        selectedFixture = null;
        beginFixtureMovementProgress(context, frame);
        phase = Phase.MOVE_TO_FIXTURE;
        phaseStartedAtTick = context.gameTick();
        MinecraftAiCompanion.LOGGER.info(
                "Repositioning to fairly observed {} occlusion "
                        + "recovery stand {}; directAim={}, attempted={}",
                fixture.failureStem(),
                stand,
                hasObservedFixtureAimLine(
                        frame,
                        stand,
                        new GridPos(x, y, z)
                ),
                attemptedFixtureVantages.size()
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    static Optional<GridPos> selectFixtureVantage(
            final CoreSkillFrame frame,
            final GridPos fixture,
            final Set<GridPos> excluded,
            final double maximumDanger
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(excluded, "excluded");
        if (!Double.isFinite(maximumDanger)
                || maximumDanger < 0.0
                || maximumDanger > 1.0) {
            throw new IllegalArgumentException(
                    "maximumDanger is outside [0,1]"
            );
        }
        final List<GridPos> candidates =
                new java.util.ArrayList<>();
        for (int deltaY = -1; deltaY <= 1; deltaY++) {
            for (int deltaX = -3; deltaX <= 3; deltaX++) {
                for (int deltaZ = -3; deltaZ <= 3; deltaZ++) {
                    final int horizontalDistance =
                            Math.abs(deltaX) + Math.abs(deltaZ);
                    if (horizontalDistance < 2
                            || horizontalDistance > 4) {
                        continue;
                    }
                    final GridPos stand = new GridPos(
                            fixture.x() + deltaX,
                            fixture.y() + deltaY,
                            fixture.z() + deltaZ
                    );
                    if (excluded.contains(stand)
                            || stand.equals(frame.feet())
                            || !isObservedSafeFixtureStand(
                                    frame,
                                    stand,
                                    maximumDanger
                            )
                            || !hasObservedFixtureAimLine(
                                    frame,
                                    stand,
                                    fixture
                            )) {
                        continue;
                    }
                    final double eyeX = stand.x() + 0.5;
                    final double eyeY = stand.y() + 1.62;
                    final double eyeZ = stand.z() + 0.5;
                    final double dx =
                            fixture.x() + 0.5 - eyeX;
                    final double dy =
                            fixture.y() + 0.5 - eyeY;
                    final double dz =
                            fixture.z() + 0.5 - eyeZ;
                    if (dx * dx + dy * dy + dz * dz
                            <= RELIABLE_INTERACTION_DISTANCE
                                * RELIABLE_INTERACTION_DISTANCE) {
                        candidates.add(stand);
                    }
                }
            }
        }
        return candidates.stream()
                .min(Comparator
                        .comparingDouble(
                                frame.feet()::euclideanDistance
                        )
                        .thenComparingInt(GridPos::y)
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z));
    }

    /**
     * When the ideal far side of an obstacle has not yet been observed, take
     * a bounded lateral step in the currently observed region. The next fair
     * semantic sample can then reveal new floor and a real line of sight.
     */
    static Optional<GridPos> selectFixtureSurveyStand(
            final CoreSkillFrame frame,
            final GridPos fixture,
            final Set<GridPos> excluded,
            final double maximumDanger
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(excluded, "excluded");
        final GridPos body = frame.feet();
        final double bodyX = body.x() - fixture.x();
        final double bodyZ = body.z() - fixture.z();
        final double baselineLength = Math.max(
                1.0,
                Math.hypot(bodyX, bodyZ)
        );
        final List<GridPos> candidates =
                new java.util.ArrayList<>();
        for (int deltaY = -1; deltaY <= 1; deltaY++) {
            for (int deltaX = -2; deltaX <= 2; deltaX++) {
                for (int deltaZ = -2; deltaZ <= 2; deltaZ++) {
                    final int stepDistance =
                            Math.abs(deltaX) + Math.abs(deltaZ);
                    if (stepDistance < 1 || stepDistance > 2) {
                        continue;
                    }
                    final GridPos stand = new GridPos(
                            body.x() + deltaX,
                            body.y() + deltaY,
                            body.z() + deltaZ
                    );
                    if (excluded.contains(stand)
                            || !isObservedSafeFixtureStand(
                                    frame,
                                    stand,
                                    maximumDanger
                            )) {
                        continue;
                    }
                    candidates.add(stand);
                }
            }
        }
        return candidates.stream()
                .min(Comparator
                        .comparingDouble((GridPos stand) -> {
                            final double candidateX =
                                    stand.x() - fixture.x();
                            final double candidateZ =
                                    stand.z() - fixture.z();
                            final double lateralGain = Math.abs(
                                    candidateX * bodyZ
                                            - candidateZ * bodyX
                            ) / baselineLength;
                            final double fixtureDistance =
                                    Math.hypot(
                                            candidateX,
                                            candidateZ
                                    );
                            return fixtureDistance * 0.25
                                    - lateralGain * 2.0
                                    + body.euclideanDistance(stand)
                                        * 0.1;
                        })
                        .thenComparingInt(GridPos::y)
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z));
    }

    private static boolean isObservedSafeFixtureStand(
            final CoreSkillFrame frame,
            final GridPos stand,
            final double maximumDanger
    ) {
        final Optional<ObservedVoxel> feet =
                frame.navigation().voxelAt(stand);
        final Optional<ObservedVoxel> head =
                frame.navigation().voxelAt(stand.above());
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(stand.below());
        return feet.isPresent()
                && head.isPresent()
                && support.isPresent()
                && NavigationEvidence.hasTraversalClearance(
                        feet.orElseThrow()
                )
                && NavigationEvidence.hasTraversalClearance(
                        head.orElseThrow()
                )
                && support.orElseThrow().kind().supportsWeight()
                && support.orElseThrow().topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP
                && Math.max(
                        feet.orElseThrow().effectiveDanger(),
                        Math.max(
                                head.orElseThrow().effectiveDanger(),
                                support.orElseThrow()
                                        .effectiveDanger()
                        )
                ) <= maximumDanger;
    }

    private static boolean hasObservedFixtureAimLine(
            final CoreSkillFrame frame,
            final GridPos stand,
            final GridPos fixture
    ) {
        final double startX = stand.x() + 0.5;
        final double startY = stand.y() + 1.62;
        final double startZ = stand.z() + 0.5;
        final double dx = fixture.x() + 0.5 - startX;
        final double dy = fixture.y() + 0.5 - startY;
        final double dz = fixture.z() + 0.5 - startZ;
        final int samples = Math.max(
                2,
                (int) Math.ceil(Math.sqrt(
                        dx * dx + dy * dy + dz * dz
                ) * 6.0)
        );
        for (int sample = 1; sample < samples; sample++) {
            final double fraction =
                    sample / (double) samples;
            final GridPos position = new GridPos(
                    floorToGrid(startX + dx * fraction),
                    floorToGrid(startY + dy * fraction),
                    floorToGrid(startZ + dz * fraction)
            );
            if (position.equals(fixture)) {
                continue;
            }
            final Optional<ObservedVoxel> observed =
                    frame.navigation().voxelAt(position);
            if (observed.isPresent()
                    && !observed.orElseThrow()
                            .kind().isPassable()) {
                return false;
            }
        }
        return true;
    }

    private static int floorToGrid(final double coordinate) {
        final double floor = Math.floor(coordinate);
        if (floor < Integer.MIN_VALUE
                || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Fixture aim coordinate is outside the grid"
            );
        }
        return (int) floor;
    }

    private SkillTickResult moveToFixture(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (fixtureMovement == null
                || fixtureMovementParameters == null
                || movementTarget == null) {
            return fail(
                    context,
                    NAME + ".fixture_approach_binding_missing"
            );
        }
        final double distance = frame.position()
                .subtract(fixtureMovementParameters.target())
                .length();
        if (distance + 0.05 < fixtureMovementBestDistance) {
            fixtureMovementBestDistance = distance;
            fixtureMovementLastProgressTick = context.gameTick();
        }
        if (fixtureMovementExpired(
                context.gameTick(),
                phaseStartedAtTick,
                fixtureMovementLastProgressTick
        )) {
            return recoverFixtureMovement(
                    context,
                    frame,
                    "bounded_progress_timeout"
            );
        }
        final SkillTickResult result = fixtureMovement.tick(
                context,
                fixtureMovementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String childFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse("move_to.unknown_failure");
            if (recoverableFixtureMovementFailure(childFailure)) {
                return recoverFixtureMovement(
                        context,
                        frame,
                        childFailure
                );
            }
            final FixtureKind failedTarget =
                    Objects.requireNonNull(movementTarget);
            cancelMovement(context);
            return fail(
                    context,
                    NAME + "." + failedTarget.failureStem()
                            + "_approach_failed"
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            final FixtureKind arrived = movementTarget;
            cancelMovement(context);
            beginScan(
                    context,
                    frame,
                    arrived == FixtureKind.TABLE
                            ? Phase.FIND_TABLE
                            : Phase.FIND_FURNACE
            );
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private void beginFixtureMovementProgress(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        fixtureMovementBestDistance = frame.position()
                .subtract(
                        Objects.requireNonNull(
                                fixtureMovementParameters
                        ).target()
                )
                .length();
        fixtureMovementLastProgressTick = context.gameTick();
    }

    private SkillTickResult recoverFixtureMovement(
            final SkillContext context,
            final CoreSkillFrame frame,
            final String reason
    ) {
        final FixtureKind failedTarget =
                Objects.requireNonNull(movementTarget);
        final MoveToParameters failedParameters =
                Objects.requireNonNull(fixtureMovementParameters);
        final String childCheckpoint =
                Objects.requireNonNull(fixtureMovement)
                        .checkpoint(
                                context,
                                failedParameters
                        ).payload();
        MinecraftAiCompanion.LOGGER.warn(
                "Recovering stalled {} approach: reason={}, "
                        + "position={}, target={}, bestDistance={}, "
                        + "elapsed={}, child={}",
                failedTarget.failureStem(),
                reason,
                frame.position(),
                failedParameters.target(),
                fixtureMovementBestDistance,
                context.gameTick() - phaseStartedAtTick,
                childCheckpoint
        );
        cancelMovement(context);
        fixtureOcclusionRecovery = true;
        beginScan(
                context,
                frame,
                failedTarget == FixtureKind.TABLE
                        ? Phase.FIND_TABLE
                        : Phase.FIND_FURNACE
        );
        return SkillTickResult.running(true, true);
    }

    static boolean fixtureMovementExpired(
            final long gameTick,
            final long movementStartedAtTick,
            final long lastProgressTick
    ) {
        return gameTick - movementStartedAtTick
                    >= MAXIMUM_FIXTURE_MOVE_TICKS
                || gameTick - lastProgressTick
                    >= MAXIMUM_FIXTURE_NO_PROGRESS_TICKS;
    }

    static boolean recoverableFixtureMovementFailure(
            final String code
    ) {
        return code.equals("move_to.route_unknown")
                || code.equals("move_to.stuck")
                || code.equals("move_to.unsupported_micro_vertical")
                || code.equals("move_to.planning_budget_exceeded");
    }

    private SkillTickResult aimFixture(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (movementTarget == null) {
            return fail(
                    context,
                    NAME + ".fixture_binding_missing"
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                movementTarget
                        ));
        if (crosshair.isPresent()) {
            selectedFixture = crosshair.orElseThrow();
            return transition(
                    context,
                    movementTarget == FixtureKind.TABLE
                            ? Phase.OPEN_TABLE
                            : Phase.OPEN_FURNACE
            );
        }
        if (selectedFixture == null) {
            beginScan(
                    context,
                    frame,
                    movementTarget == FixtureKind.TABLE
                            ? Phase.FIND_TABLE
                            : Phase.FIND_FURNACE
            );
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            selectedFixture = null;
            beginScan(
                    context,
                    frame,
                    movementTarget == FixtureKind.TABLE
                            ? Phase.FIND_TABLE
                            : Phase.FIND_FURNACE
            );
            return SkillTickResult.running(true, true);
        }
        return aimAt(context, frame, selectedFixture.hitPosition());
    }

    private SkillTickResult openTable(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    toolkitCraftingPhase(
                            context,
                            ownedFrame().orElseThrow()
                    )
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                FixtureKind.TABLE
                        ));
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_TABLE);
        }
        final ActionOutcome opened = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        if (!opened.accepted()) {
            return fail(
                    context,
                    NAME + ".table_open_rejected"
            );
        }
        return transition(context, Phase.CONFIRM_TABLE_MENU);
    }

    private SkillTickResult confirmTableMenu(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    toolkitCraftingPhase(context, frame)
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_MENU_WAIT_TICKS) {
            return fail(
                    context,
                    NAME + ".table_menu_unconfirmed"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private Phase toolkitCraftingPhase(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (needsNewFurnace(context, frame)) {
            return Phase.CRAFT_FURNACE;
        }
        if (needsSmelting(frame)) {
            if (selectedFuel(frame).isEmpty()
                    && canPrepareCharcoal(frame)) {
                return Phase.PREPARE_FUEL_COMPONENTS;
            }
            return Phase.CLOSE_TABLE_FOR_FURNACE;
        }
        return Phase.PREPARE_COMPONENTS;
    }

    private SkillTickResult prepareFuelComponents(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!inventory.hasThreeByThreeCraftingMenu()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (selectedFuel(frame).isPresent()) {
            return transition(
                    context,
                    Phase.CLOSE_TABLE_FOR_FURNACE
            );
        }
        final int requiredPlanks = Math.addExact(
                componentPlankReserve(frame),
                CHARCOAL_FUEL_PLANKS
        );
        if (plankCount(frame) >= requiredPlanks
                && burnableWoodForCharcoal(frame).isPresent()
                && burnablePlankCount(frame) > 0) {
            return transition(
                    context,
                    Phase.CLOSE_TABLE_FOR_FURNACE
            );
        }
        if (!canPrepareCharcoal(frame)) {
            return fail(
                    context,
                    NAME + ".charcoal_materials_unavailable"
            );
        }
        final InventoryOperationResult crafted =
                craftOnePlankPreservingCharcoalInput(frame);
        if (crafted.succeeded()) {
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_RECIPE_WAIT_TICKS) {
            return fail(
                    context,
                    NAME + ".charcoal_plank_recipe_unavailable"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult prepareComponents(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!inventory.hasThreeByThreeCraftingMenu()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        final int requiredPlanks = requiredPlanks(frame);
        if (plankCount(frame) < requiredPlanks) {
            final InventoryOperationResult crafted =
                    craftOnePlank(frame);
            if (crafted.succeeded()) {
                phaseStartedAtTick = context.gameTick();
                return SkillTickResult.running(true, true);
            }
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_RECIPE_WAIT_TICKS) {
                return fail(
                        context,
                        NAME + ".plank_recipe_unavailable"
                );
            }
            return SkillTickResult.running(false, true);
        }
        if (itemCount(frame, STICK) < requiredSticks(frame)) {
            final CraftRecipeParameters recipe =
                    new CraftRecipeParameters(STICK, 1);
            if (inventory.checkCraft(recipe).succeeded()
                    && inventory.craftOnce(recipe).succeeded()) {
                phaseStartedAtTick = context.gameTick();
                return SkillTickResult.running(true, true);
            }
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_RECIPE_WAIT_TICKS) {
                return fail(
                        context,
                        NAME + ".stick_recipe_unavailable"
                );
            }
            return SkillTickResult.running(false, true);
        }
        return transition(
                context,
                ownsItem(frame, IRON_PICKAXE)
                        ? Phase.CRAFT_BUCKET
                        : Phase.CRAFT_IRON_PICKAXE
        );
    }

    private SkillTickResult craftFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!needsNewFurnace(context, frame)) {
            return transition(
                    context,
                    needsSmelting(frame)
                            ? selectedFuel(frame).isEmpty()
                                    && canPrepareCharcoal(frame)
                                    ? Phase.PREPARE_FUEL_COMPONENTS
                                    : Phase.CLOSE_TABLE_FOR_FURNACE
                            : Phase.PREPARE_COMPONENTS
            );
        }
        if (!inventory.hasThreeByThreeCraftingMenu()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(FURNACE, 1);
        if (inventory.checkCraft(recipe).succeeded()
                && inventory.craftOnce(recipe).succeeded()) {
            return transition(
                    context,
                    selectedFuel(frame).isEmpty()
                            && canPrepareCharcoal(frame)
                            ? Phase.PREPARE_FUEL_COMPONENTS
                            : Phase.CLOSE_TABLE_FOR_FURNACE
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_RECIPE_WAIT_TICKS) {
            return fail(
                    context,
                    NAME + ".furnace_recipe_unavailable"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult closeTableForFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            final InventoryOperationResult closed =
                    inventory.closeThreeByThreeCraftingMenu();
            if (!closed.succeeded()) {
                return fail(
                        context,
                        NAME + ".table_menu_close_failed"
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (!needsSmelting(frame)) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (knownUsableFurnace(context, frame).isPresent()
                && !ownsItem(frame, FURNACE)) {
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        return transition(context, Phase.EQUIP_FURNACE);
    }

    private SkillTickResult equipFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (visibleFixture(frame, FixtureKind.FURNACE).isPresent()) {
            beginScan(context, frame, Phase.FIND_FURNACE);
            return SkillTickResult.running(true, true);
        }
        if (!ownsItem(frame, FURNACE)) {
            return fail(
                    context,
                    NAME + ".furnace_item_missing"
            );
        }
        if (frame.mainHand().itemId().equals(FURNACE)
                && frame.mainHand().count() > 0) {
            beginScan(
                    context,
                    frame,
                    Phase.FIND_FURNACE_SUPPORT
            );
            return SkillTickResult.running(true, true);
        }
        final InventoryOperationResult equipped = inventory.equip(
                new EquipItemParameters(
                        FURNACE,
                        EquipmentTarget.MAINHAND
                )
        );
        if (!equipped.succeeded()) {
            return fail(
                    context,
                    NAME + ".furnace_equip_failed"
            );
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult findFurnaceSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> support =
                selectPlacementSupport(frame);
        if (support.isPresent()) {
            selectedSupport = support.orElseThrow();
            return transition(
                    context,
                    Phase.AIM_FURNACE_SUPPORT
            );
        }
        return scan(
                context,
                frame,
                Phase.FIND_FURNACE_SUPPORT
        );
    }

    private SkillTickResult aimFurnaceSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedSupport == null) {
            return transition(
                    context,
                    Phase.FIND_FURNACE_SUPPORT
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            selectedSupport = crosshair.orElseThrow();
            return transition(context, Phase.PLACE_FURNACE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            selectedSupport = null;
            return transition(
                    context,
                    Phase.FIND_FURNACE_SUPPORT
            );
        }
        return aimAt(context, frame, selectedSupport.hitPosition());
    }

    private SkillTickResult placeFurnace(
            final SkillContext context
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isEmpty()
                || selectedSupport == null
                || !sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            return transition(
                    context,
                    Phase.AIM_FURNACE_SUPPORT
            );
        }
        final VisibleBlockFace actual = crosshair.orElseThrow();
        final ActionOutcome placed = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(actual)
        );
        if (!placed.accepted()) {
            return fail(
                    context,
                    NAME + ".furnace_place_rejected"
            );
        }
        expectedFurnacePosition = new BlockCoordinate(
                actual.block().x(),
                actual.block().y() + 1,
                actual.block().z()
        );
        selectedFixture = null;
        movementTarget = FixtureKind.FURNACE;
        return transition(context, Phase.CONFIRM_FURNACE);
    }

    private SkillTickResult confirmFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> visible =
                visibleExpectedFurnace(frame);
        if (visible.isPresent()) {
            selectedFixture = visible.orElseThrow();
            movementTarget = FixtureKind.FURNACE;
            return transition(context, Phase.AIM_FIXTURE);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                FixtureKind.FURNACE
                        ));
        if (crosshair.isPresent()) {
            selectedFixture = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_FURNACE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            /*
             * Packet dispatch is not proof that a block was placed. For
             * example, using a furnace item on a crafting table opens the
             * table unless the player is sneaking. If the item is still
             * owned, reject that support and select another visible surface
             * rather than repeatedly claiming that placement occurred.
             */
            if (ownsItem(frame, FURNACE)
                    && selectedSupport != null
                    && rejectedFurnaceSupports.size() < 8) {
                rejectedFurnaceSupports.add(
                        selectedSupport.block()
                );
                selectedSupport = null;
                expectedFurnacePosition = null;
                beginScan(
                        context,
                        frame,
                        Phase.FIND_FURNACE_SUPPORT
                );
                return SkillTickResult.running(true, true);
            }
            return fail(
                    context,
                    NAME + ".furnace_placement_unconfirmed"
            );
        }
        if (expectedFurnacePosition != null) {
            return aimAt(
                    context,
                    frame,
                    new PerceptionVec3(
                            expectedFurnacePosition.x() + 0.5,
                            expectedFurnacePosition.y() + 0.5,
                            expectedFurnacePosition.z() + 0.5
                    )
            );
        }
        return SkillTickResult.running(false, true);
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
                        .filter(face -> isFixture(
                                face,
                                FixtureKind.FURNACE
                        ));
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
            final MenuSkillFrame currentMenu = menu.orElseThrow();
            if (currentMenu.sampleSequence()
                    <= charcoalCompletedMenuSample) {
                return SkillTickResult.running(false, true);
            }
            if (!needsSmelting(frame)) {
                return transition(context, Phase.CLOSE_FURNACE);
            }
            final Optional<String> observedFuel =
                    selectedFuel(frame).or(() ->
                            observedPlayerItem(
                                    currentMenu,
                                    Set.of(COAL, CHARCOAL)
                            )
                    );
            if (observedFuel.isEmpty()) {
                final String charcoalInput =
                        burnableWoodForCharcoal(frame)
                                .or(() ->
                                        observedCharcoalInput(
                                                currentMenu
                                        )
                                )
                                .orElse(null);
                final String plankFuel =
                        observedPlankFuel(currentMenu)
                                .orElse(null);
                if (charcoalInput == null || plankFuel == null) {
                    return fail(
                            context,
                            NAME + ".charcoal_inputs_missing"
                    );
                }
                smeltingParameters = new SmeltMenuBatchParameters(
                        currentMenu.sampleSequence(),
                        charcoalInput,
                        CHARCOAL,
                        1,
                        plankFuel,
                        CHARCOAL_FUEL_PLANKS
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
            final int count = smeltCount(frame);
            if (count < 1) {
                return fail(
                        context,
                        NAME + ".smelting_inputs_missing"
                );
            }
            smeltingParameters = new SmeltMenuBatchParameters(
                    menu.orElseThrow().sampleSequence(),
                    RAW_IRON,
                    IRON_INGOT,
                    count,
                    observedFuel.orElseThrow(),
                    1
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
                        NAME + ".smelting_precondition_failed"
                );
            }
            smelter.start(context, smeltingParameters);
            return transition(context, Phase.SMELT_IRON);
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
            charcoalCompletedMenuSample = menuFrames.current()
                    .filter(frame -> expectedPlayerId.equals(
                            frame.playerId()
                    ))
                    .map(MenuSkillFrame::sampleSequence)
                    .orElse(-1L);
            smelter = null;
            smeltingParameters = null;
            return transition(
                    context,
                    Phase.CONFIRM_FURNACE_MENU
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult smeltIron(
            final SkillContext context
    ) {
        if (smelter == null || smeltingParameters == null) {
            return fail(
                    context,
                    NAME + ".smelting_binding_missing"
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
                    NAME + ".smelting_failed"
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
                menuFrames.current()
                        .filter(menu -> expectedPlayerId.equals(
                                menu.playerId()
                        ));
        if (current.isPresent()) {
            final MenuOperationResult closed = menus.close(
                    new CloseMenuParameters(
                            binding(current.orElseThrow())
                    )
            );
            if (!closed.succeeded()) {
                /*
                 * The output quick-move legitimately increments the vanilla
                 * menu state before the next fair menu observation arrives.
                 * Retrying against a fresh binding is required; restarting
                 * the entire mining/smelting transaction is not.
                 */
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
        beginScan(context, frame, Phase.FIND_TABLE);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult craftMissingTool(
            final SkillContext context,
            final CoreSkillFrame frame,
            final String itemId,
            final Phase next
    ) {
        if (ownsItem(frame, itemId)) {
            return transition(context, next);
        }
        if (!inventory.hasThreeByThreeCraftingMenu()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(itemId, 1);
        if (inventory.checkCraft(recipe).succeeded()
                && inventory.craftOnce(recipe).succeeded()) {
            return transition(context, next);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_RECIPE_WAIT_TICKS) {
            return fail(
                    context,
                    NAME + "."
                            + itemId.substring(
                                    itemId.indexOf(':') + 1
                            )
                            + "_recipe_unavailable"
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult craftMissingBucket(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownsBucket(frame)) {
            return transition(context, Phase.CRAFT_SHIELD);
        }
        return craftMissingTool(
                context,
                frame,
                BUCKET,
                Phase.CRAFT_SHIELD
        );
    }

    private SkillTickResult finish(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!toolkitComplete(frame)) {
            if (needsSmelting(frame)) {
                beginScan(context, frame, Phase.FIND_FURNACE);
            } else {
                beginScan(context, frame, Phase.FIND_TABLE);
            }
            return SkillTickResult.running(true, true);
        }
        if (inventory.hasThreeByThreeCraftingMenu()) {
            final InventoryOperationResult closed =
                    inventory.closeThreeByThreeCraftingMenu();
            if (!closed.succeeded()) {
                return fail(
                        context,
                        NAME + ".table_menu_close_failed"
                );
            }
            return SkillTickResult.running(true, true);
        }
        core.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private Optional<ResourceKind> nextResource(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (needsNewFurnace(context, frame)
                && itemCount(frame, COBBLESTONE)
                    < FURNACE_COBBLESTONE) {
            return Optional.of(ResourceKind.STONE);
        }
        if (needsSmelting(frame)
                && selectedFuel(frame).isEmpty()
                && !canPrepareCharcoal(frame)) {
            return Optional.of(ResourceKind.COAL);
        }
        if (ironAvailable(frame) < ironNeeded(frame)) {
            return Optional.of(ResourceKind.IRON);
        }
        return Optional.empty();
    }

    private int resourceDeficit(
            final SkillContext context,
            final CoreSkillFrame frame,
            final ResourceKind resource
    ) {
        return switch (resource) {
            case STONE -> Math.max(
                    1,
                    FURNACE_COBBLESTONE
                            - itemCount(frame, COBBLESTONE)
            );
            case COAL -> 1;
            case IRON -> Math.max(
                    1,
                    ironNeeded(frame) - ironAvailable(frame)
            );
        };
    }

    private boolean resourceSatisfied(
            final SkillContext context,
            final CoreSkillFrame frame,
            final ResourceKind resource
    ) {
        return switch (resource) {
            case STONE -> !needsNewFurnace(context, frame)
                    || itemCount(frame, COBBLESTONE)
                        >= FURNACE_COBBLESTONE;
            case COAL -> !needsSmelting(frame)
                    || selectedFuel(frame).isPresent()
                    || canPrepareCharcoal(frame);
            case IRON -> ironAvailable(frame) >= ironNeeded(frame);
        };
    }

    private int resourceCount(
            final CoreSkillFrame frame,
            final ResourceKind resource
    ) {
        return switch (resource) {
            case STONE -> itemCount(frame, COBBLESTONE);
            case COAL -> itemCount(frame, COAL)
                    + itemCount(frame, CHARCOAL);
            case IRON -> itemCount(frame, RAW_IRON)
                    + itemCount(frame, IRON_INGOT);
        };
    }

    private Optional<VisibleBlockFace> selectResourceFace(
            final InteractionSkillFrame interaction,
            final ResourceKind resource
    ) {
        return nearestResourceFace(
                interaction.visibleBlockFaces(),
                resource.blockIds
        );
    }

    /*
     * Do not impose interaction reach here. The cluster gatherer binds only
     * a genuinely visible ray hit and owns the legal walk-to-reach phase
     * before mining. Capping this selector at six blocks contradicted the
     * exploration child, which correctly completed as soon as a farther
     * resource became visible, and produced an endless
     * scan -> explore -> reject-the-same-target loop.
     */
    static Optional<VisibleBlockFace> nearestResourceFace(
            final List<VisibleBlockFace> visibleFaces,
            final Set<String> acceptedBlockIds
    ) {
        Objects.requireNonNull(visibleFaces, "visibleFaces");
        Objects.requireNonNull(
                acceptedBlockIds,
                "acceptedBlockIds"
        );
        return visibleFaces.stream()
                .filter(face -> acceptedBlockIds.contains(
                        face.blockTypeId()
                ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private SkillTickResult scan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase samePhase
    ) {
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            if (samePhase == Phase.FIND_RESOURCE
                    && activeResource != null) {
                return beginResourceExploration(context, frame);
            }
            return fail(
                    context,
                    NAME + "." + switch (samePhase) {
                        case FIND_RESOURCE ->
                                activeResource == null
                                    ? "resource_not_visible"
                                    : activeResource.failureStem()
                                        + "_not_visible";
                        case FIND_TABLE -> "table_not_visible";
                        case FIND_FURNACE -> "furnace_not_visible";
                        case FIND_FURNACE_SUPPORT ->
                                "furnace_support_not_visible";
                        default -> "scan_exhausted";
                    }
            );
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = scanTurns
                / SCAN_PITCHES.length;
        final int pitchIndex = scanTurns
                % SCAN_PITCHES.length;
        final float yaw = scanBaseYaw
                + SCAN_YAW_OFFSETS[
                        yawIndex % SCAN_YAW_OFFSETS.length
                ];
        final float pitch = SCAN_PITCHES[pitchIndex];
        if (!core.stop().accepted()
                || !core.look(
                        new LookIntent(yaw, pitch)
                ).accepted()) {
            return fail(context, NAME + ".scan_rejected");
        }
        scanTurns++;
        nextScanTick =
                context.gameTick() + SCAN_INTERVAL_TICKS;
        phase = samePhase;
        return SkillTickResult.running(true, true);
    }

    private void beginScan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase next
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanBaseYaw = yaw(frame.lookDirection());
        selectedFixture = null;
        selectedSupport = null;
    }

    private SkillTickResult transition(
            final SkillContext context,
            final Phase next
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult aimAt(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(
                frame.eyePosition()
        );
        if (delta.lengthSquared() <= 1.0E-12) {
            return SkillTickResult.running(false, true);
        }
        final float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, pitch)).accepted()) {
            return fail(context, NAME + ".aim_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private Optional<InteractionSkillFrame> ownedInteractionFrame(
            final CoreSkillFrame frame
    ) {
        return interactionFrames.current()
                .filter(interaction ->
                        expectedPlayerId.equals(
                                interaction.playerId()
                        )
                                && interaction.dimension().equals(
                                    frame.dimension()
                                )
                                && interaction.sessionGeneration()
                                    == interactions
                                        .sessionGeneration()
                                        .orElse(-1L)
                );
    }

    private Optional<MenuSkillFrame> currentFurnaceMenu() {
        return menuFrames.current()
                .filter(frame -> expectedPlayerId.equals(
                        frame.playerId()
                ))
                .filter(frame -> {
                    final String type =
                            frame.menu().menuType();
                    final String clazz =
                            frame.menu().menuClass();
                    return type.contains("furnace")
                            || clazz.contains("Furnace");
                });
    }

    private Optional<VisibleBlockFace> visibleFixture(
            final CoreSkillFrame frame,
            final FixtureKind fixture
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> isFixture(face, fixture))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<VisibleBlockFace> visibleExpectedFurnace(
            final CoreSkillFrame frame
    ) {
        if (expectedFurnacePosition == null) {
            return visibleFixture(frame, FixtureKind.FURNACE);
        }
        return frame.visibleBlockFaces().stream()
                .filter(face -> isFixture(
                        face,
                        FixtureKind.FURNACE
                ))
                .filter(face -> face.block().equals(
                        expectedFurnacePosition
                ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<PerceptionVec3> rememberedFixtureTarget(
            final SkillContext context,
            final CoreSkillFrame frame,
            final FixtureKind fixture
    ) {
        final Optional<VerifiedFixtureLocation> remembered;
        try {
            remembered = Objects.requireNonNull(
                    (fixture == FixtureKind.TABLE
                            ? knownCraftingTable
                            : knownFurnace)
                        .apply(context.goalRevision()),
                    "known fixture result"
            );
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return remembered
                .filter(location -> location.dimension().equals(
                        frame.dimension().id()
                ))
                .map(location -> new PerceptionVec3(
                        location.x() + 0.5,
                        location.y() + 0.5,
                        location.z() + 0.5
                ));
    }

    private Optional<VerifiedFixtureLocation> knownUsableFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        try {
            return Objects.requireNonNull(
                    knownFurnace.apply(context.goalRevision()),
                    "known furnace result"
            ).filter(location -> location.dimension().equals(
                    frame.dimension().id()
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean hasCraftingTableEvidence(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()
                || ownsItem(frame, CRAFTING_TABLE)
                || visibleFixture(
                        frame,
                        FixtureKind.TABLE
                ).isPresent()) {
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

    private Optional<VisibleBlockFace> selectPlacementSupport(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.face().equals("up"))
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .filter(face ->
                        !rejectedFurnaceSupports.contains(
                                face.block()
                        ))
                /*
                 * A normal right click on these blocks performs their own
                 * interaction before held-block placement. The companion
                 * does not silently fake sneak-placement, so choose ordinary
                 * terrain/support instead.
                 */
                .filter(face ->
                        !isInteractivePlacementSupport(
                                face.blockTypeId()
                        ))
                .filter(face ->
                        face.distance() >= MINIMUM_PLACEMENT_DISTANCE
                                && face.distance()
                                    <= MAXIMUM_PLACEMENT_DISTANCE)
                .filter(face -> {
                    final double centerX =
                            face.block().x() + 0.5;
                    final double centerZ =
                            face.block().z() + 0.5;
                    return Math.hypot(
                            centerX - frame.position().x(),
                            centerZ - frame.position().z()
                    ) >= MINIMUM_PLACEMENT_DISTANCE;
                })
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    static boolean isInteractivePlacementSupport(
            final String blockId
    ) {
        return blockId.equals(CRAFTING_TABLE)
                || blockId.equals(FURNACE)
                || blockId.equals("minecraft:blast_furnace")
                || blockId.equals("minecraft:smoker")
                || blockId.equals("minecraft:chest")
                || blockId.equals("minecraft:trapped_chest")
                || blockId.equals("minecraft:barrel")
                || blockId.equals("minecraft:cartography_table")
                || blockId.equals("minecraft:smithing_table")
                || blockId.equals("minecraft:loom")
                || blockId.equals("minecraft:stonecutter")
                || blockId.equals("minecraft:grindstone")
                || blockId.equals("minecraft:enchanting_table")
                || blockId.equals("minecraft:anvil")
                || blockId.equals("minecraft:chipped_anvil")
                || blockId.equals("minecraft:damaged_anvil")
                || blockId.equals("minecraft:brewing_stand")
                || blockId.equals("minecraft:cauldron")
                || blockId.equals("minecraft:fletching_table")
                || blockId.equals("minecraft:lectern")
                || blockId.equals("minecraft:composter")
                || blockId.equals("minecraft:respawn_anchor")
                || blockId.equals("minecraft:hopper")
                || blockId.equals("minecraft:dispenser")
                || blockId.equals("minecraft:dropper")
                || blockId.equals("minecraft:observer")
                || blockId.equals("minecraft:piston")
                || blockId.equals("minecraft:sticky_piston")
                || blockId.equals("minecraft:repeater")
                || blockId.equals("minecraft:comparator")
                || blockId.equals("minecraft:redstone_lamp")
                || blockId.endsWith("_shulker_box");
    }

    private InventoryOperationResult craftOnePlank(
            final CoreSkillFrame frame
    ) {
        for (InventoryItemSummary item : frame.inventory().stream()
                .sorted(Comparator.comparing(
                        InventoryItemSummary::itemId
                ))
                .toList()) {
            final Optional<String> recipe =
                    PrepareBasicCraftingSkill.plankRecipeFor(
                            item.itemId()
                    );
            if (recipe.isEmpty()) {
                continue;
            }
            final CraftRecipeParameters parameters =
                    new CraftRecipeParameters(
                            recipe.orElseThrow(),
                            1
                    );
            if (inventory.checkCraft(parameters).succeeded()) {
                return inventory.craftOnce(parameters);
            }
        }
        return InventoryOperationResult.rejected(
                NAME + ".plank_recipe_unavailable"
        );
    }

    private InventoryOperationResult
            craftOnePlankPreservingCharcoalInput(
                    final CoreSkillFrame frame
            ) {
        final int burnableWood = burnableWoodCount(frame);
        for (InventoryItemSummary item : frame.inventory().stream()
                .sorted(Comparator.comparing(
                        InventoryItemSummary::itemId
                ))
                .toList()) {
            if (isBurnableCharcoalInput(item.itemId())
                    && burnableWood <= 1) {
                continue;
            }
            final Optional<String> recipe =
                    PrepareBasicCraftingSkill.plankRecipeFor(
                            item.itemId()
                    );
            if (recipe.isEmpty()
                    || !isBurnablePlank(recipe.orElseThrow())
                            && burnablePlankCount(frame) == 0) {
                continue;
            }
            final CraftRecipeParameters parameters =
                    new CraftRecipeParameters(
                            recipe.orElseThrow(),
                            1
                    );
            if (inventory.checkCraft(parameters).succeeded()) {
                return inventory.craftOnce(parameters);
            }
        }
        return InventoryOperationResult.rejected(
                NAME + ".charcoal_plank_recipe_unavailable"
        );
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

    private static boolean toolkitComplete(
            final CoreSkillFrame frame
    ) {
        return ownsItem(frame, IRON_PICKAXE)
                && ownsBucket(frame)
                && ownsItem(frame, SHIELD);
    }

    private boolean needsNewFurnace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return needsSmelting(frame)
                && !ownsItem(frame, FURNACE)
                && knownUsableFurnace(context, frame).isEmpty();
    }

    private static boolean needsSmelting(
            final CoreSkillFrame frame
    ) {
        return itemCount(frame, IRON_INGOT) < ironNeeded(frame);
    }

    private static int smeltCount(final CoreSkillFrame frame) {
        return Math.min(
                itemCount(frame, RAW_IRON),
                Math.max(
                        0,
                        ironNeeded(frame)
                                - itemCount(frame, IRON_INGOT)
                )
        );
    }

    private static int ironAvailable(
            final CoreSkillFrame frame
    ) {
        return itemCount(frame, IRON_INGOT)
                + itemCount(frame, RAW_IRON);
    }

    private static int ironNeeded(final CoreSkillFrame frame) {
        int needed = 0;
        if (!ownsItem(frame, IRON_PICKAXE)) {
            needed += 3;
        }
        if (!ownsBucket(frame)) {
            needed += 3;
        }
        if (!ownsItem(frame, SHIELD)) {
            needed += 1;
        }
        return needed;
    }

    private static int requiredPlanks(
            final CoreSkillFrame frame
    ) {
        return ownsItem(frame, SHIELD) ? 0 : 6;
    }

    private static int requiredSticks(
            final CoreSkillFrame frame
    ) {
        return ownsItem(frame, IRON_PICKAXE) ? 0 : 2;
    }

    private static int componentPlankReserve(
            final CoreSkillFrame frame
    ) {
        return requiredPlanks(frame)
                + (itemCount(frame, STICK)
                        < requiredSticks(frame) ? 2 : 0);
    }

    private static int plankCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item ->
                        item.itemId().endsWith("_planks"))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static int potentialPlanks(
            final CoreSkillFrame frame
    ) {
        return plankCount(frame)
                + frame.inventory().stream()
                    .filter(item ->
                            PrepareBasicCraftingSkill
                                .plankRecipeFor(
                                        item.itemId()
                                )
                                .isPresent())
                    .mapToInt(item -> item.count() * 4)
                    .sum();
    }

    private static boolean canPrepareCharcoal(
            final CoreSkillFrame frame
    ) {
        if (!needsSmelting(frame)
                || selectedFuel(frame).isPresent()) {
            return false;
        }
        final int burnableWood = burnableWoodCount(frame);
        final boolean canMakeFuelPlank =
                burnablePlankCount(frame) > 0
                        || burnableWood > 1;
        return burnableWood > 0
                && canMakeFuelPlank
                && potentialPlanks(frame)
                        >= componentPlankReserve(frame)
                                + CHARCOAL_FUEL_PLANKS
                                + CHARCOAL_INPUT_POTENTIAL_PLANKS;
    }

    private static int burnableWoodCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> isBurnableCharcoalInput(
                        item.itemId()
                ))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static int burnablePlankCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> isBurnablePlank(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static Optional<String> burnableWoodForCharcoal(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .filter(
                        PrepareIronToolkitSkill
                                ::isBurnableCharcoalInput
                )
                .findFirst();
    }

    private static boolean isBurnableCharcoalInput(
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

    private static boolean isBurnablePlank(
            final String itemId
    ) {
        return itemId.startsWith("minecraft:")
                && itemId.endsWith("_planks")
                && !itemId.equals("minecraft:crimson_planks")
                && !itemId.equals("minecraft:warped_planks");
    }

    private static Optional<String> observedPlayerItem(
            final MenuSkillFrame frame,
            final Set<String> accepted
    ) {
        return frame.menu().slots().stream()
                .filter(slot -> slot.playerInventory()
                        && slot.count() > 0
                        && accepted.contains(slot.itemId()))
                .map(slot -> slot.itemId())
                .findFirst();
    }

    private static Optional<String> observedCharcoalInput(
            final MenuSkillFrame frame
    ) {
        return frame.menu().slots().stream()
                .filter(slot -> slot.playerInventory()
                        && slot.count() > 0
                        && isBurnableCharcoalInput(
                                slot.itemId()
                        ))
                .map(slot -> slot.itemId())
                .findFirst();
    }

    private static Optional<String> observedPlankFuel(
            final MenuSkillFrame frame
    ) {
        return frame.menu().slots().stream()
                .filter(slot -> slot.playerInventory()
                        && slot.count() > 0
                        && isBurnablePlank(slot.itemId()))
                .map(slot -> slot.itemId())
                .findFirst();
    }

    private static Optional<String> selectedFuel(
            final CoreSkillFrame frame
    ) {
        if (itemCount(frame, COAL) > 0) {
            return Optional.of(COAL);
        }
        if (itemCount(frame, CHARCOAL) > 0) {
            return Optional.of(CHARCOAL);
        }
        return Optional.empty();
    }

    private static Optional<String> selectMiningTool(
            final CoreSkillFrame frame
    ) {
        if (STONE_OR_BETTER_PICKAXES.contains(
                frame.mainHand().itemId()
        ) && frame.mainHand().count() > 0) {
            return Optional.of(frame.mainHand().itemId());
        }
        return frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .filter(STONE_OR_BETTER_PICKAXES::contains)
                .sorted()
                .findFirst();
    }

    static boolean recoverableGatherFailure(
            final String code
    ) {
        return code.endsWith(".cluster_not_rediscovered")
                || code.endsWith(".target_binding_lost")
                || code.endsWith(".mining_timed_out")
                || code.endsWith(".drop_not_collected")
                || code.endsWith(".stuck")
                || code.endsWith(".seed_not_visible");
    }

    static boolean shouldRecoverMinedDrop(
            final String childFailure,
            final Optional<DropCollectionDebt> debt,
            final boolean resourceSatisfied
    ) {
        Objects.requireNonNull(childFailure, "childFailure");
        Objects.requireNonNull(debt, "debt");
        return !resourceSatisfied
                && childFailure.endsWith(".drop_not_collected")
                && debt.isPresent();
    }

    private static String resourceItemId(
            final ResourceKind resource
    ) {
        return switch (resource) {
            case STONE -> COBBLESTONE;
            case COAL -> COAL;
            case IRON -> RAW_IRON;
        };
    }

    private static boolean ownsBucket(
            final CoreSkillFrame frame
    ) {
        return ownsItem(frame, BUCKET)
                || ownsItem(frame, WATER_BUCKET)
                || ownsItem(frame, LAVA_BUCKET);
    }

    private static boolean ownsItem(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        if (frame.mainHand().itemId().equals(itemId)
                && frame.mainHand().count() > 0
                || frame.offHand().itemId().equals(itemId)
                    && frame.offHand().count() > 0) {
            return true;
        }
        return itemCount(frame, itemId) > 0;
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

    private static boolean isFixture(
            final VisibleBlockFace face,
            final FixtureKind fixture
    ) {
        return face.blockTypeId().equals(fixture.blockId());
    }

    private static boolean sameBlockAndFace(
            final VisibleBlockFace first,
            final VisibleBlockFace second
    ) {
        return first.block().equals(second.block())
                && first.face().equals(second.face());
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

    private static float yaw(
            final PerceptionVec3 direction
    ) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        );
    }

    private void cancelMovement(final SkillContext context) {
        if (fixtureMovement != null
                && fixtureMovementParameters != null) {
            fixtureMovement.cancel(
                    context,
                    fixtureMovementParameters
            );
        }
        fixtureMovement = null;
        fixtureMovementParameters = null;
        movementTarget = null;
        fixtureMovementBestDistance = Double.POSITIVE_INFINITY;
        fixtureMovementLastProgressTick = -1L;
    }

    private void cancelResourceExplorer(
            final SkillContext context
    ) {
        if (resourceExplorer != null
                && resourceExplorationParameters != null) {
            try {
                resourceExplorer.cancel(
                        context,
                        resourceExplorationParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        resourceExplorer = null;
        resourceExplorationParameters = null;
    }

    private void cancelResourceDropMovement(
            final SkillContext context
    ) {
        if (resourceDropMovement != null
                && resourceDropMovementParameters != null) {
            try {
                resourceDropMovement.cancel(
                        context,
                        resourceDropMovementParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        resourceDropMovement = null;
        resourceDropMovementParameters = null;
    }

    private void cancelResourceDropRecovery(
            final SkillContext context
    ) {
        if (resourceDropCollector != null
                && resourceDropCollectorParameters != null) {
            try {
                resourceDropCollector.cancel(
                        context,
                        resourceDropCollectorParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        cancelResourceDropMovement(context);
        clearResourceDropRecovery();
    }

    private void clearResourceDropRecovery() {
        resourceDropDebt = null;
        resourceDropCollector = null;
        resourceDropCollectorParameters = null;
        resourceDropCollectorEntityId = null;
        resourceDropMovement = null;
        resourceDropMovementParameters = null;
        resourceDropRecoveryStartedAtTick = -1L;
        nextResourceDropScanTick = -1L;
        resourceDropScanTurns = 0;
        resourceDropScanBaseYaw = 0.0F;
        resourceDropMovementAttempted = false;
        rejectedResourceDropEntities.clear();
    }

    private void cancelChildSkills(final SkillContext context) {
        if (gatheringParameters != null) {
            gatherer.cancel(context, gatheringParameters);
        }
        gatheringParameters = null;
        gatheringStartCount = 0;
        cancelResourceExplorer(context);
        cancelResourceDropRecovery(context);
        cancelMovement(context);
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

    private enum ResourceKind {
        STONE(Set.of(STONE_BLOCK), "stone", STONE_BLOCK),
        COAL(
                Set.of(COAL_ORE, DEEPSLATE_COAL_ORE),
                "coal",
                COAL_ORE
        ),
        IRON(
                Set.of(IRON_ORE, DEEPSLATE_IRON_ORE),
                "iron",
                IRON_ORE
        );

        private final Set<String> blockIds;
        private final String failureStem;
        private final String explorationBlockId;

        ResourceKind(
                final Set<String> blockIds,
                final String failureStem,
                final String explorationBlockId
        ) {
            this.blockIds = blockIds;
            this.failureStem = failureStem;
            this.explorationBlockId = explorationBlockId;
        }

        boolean accepts(final String blockId) {
            return blockIds.contains(blockId);
        }

        String failureStem() {
            return failureStem;
        }

        String explorationBlockId() {
            return explorationBlockId;
        }
    }

    private enum FixtureKind {
        TABLE(CRAFTING_TABLE, "table"),
        FURNACE(PrepareIronToolkitSkill.FURNACE, "furnace");

        private final String blockId;
        private final String failureStem;

        FixtureKind(
                final String blockId,
                final String failureStem
        ) {
            this.blockId = blockId;
            this.failureStem = failureStem;
        }

        String blockId() {
            return blockId;
        }

        String failureStem() {
            return failureStem;
        }
    }

    private enum Phase {
        IDLE(false),
        FIND_RESOURCE(true),
        EXPLORE_RESOURCE(true),
        GATHER_RESOURCE(true),
        RECOVER_RESOURCE_DROP(true),
        CONFIRM_RESOURCE(true),
        FIND_TABLE(true),
        MOVE_TO_FIXTURE(true),
        AIM_FIXTURE(true),
        OPEN_TABLE(true),
        CONFIRM_TABLE_MENU(true),
        PREPARE_FUEL_COMPONENTS(true),
        PREPARE_COMPONENTS(true),
        CRAFT_FURNACE(true),
        CLOSE_TABLE_FOR_FURNACE(true),
        EQUIP_FURNACE(true),
        FIND_FURNACE_SUPPORT(true),
        AIM_FURNACE_SUPPORT(true),
        PLACE_FURNACE(true),
        CONFIRM_FURNACE(true),
        FIND_FURNACE(true),
        OPEN_FURNACE(true),
        CONFIRM_FURNACE_MENU(true),
        SMELT_CHARCOAL(true),
        SMELT_IRON(true),
        CLOSE_FURNACE(true),
        CRAFT_IRON_PICKAXE(true),
        CRAFT_BUCKET(true),
        CRAFT_SHIELD(true),
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
