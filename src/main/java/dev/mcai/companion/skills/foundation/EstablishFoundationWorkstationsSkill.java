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
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.MenuSlotSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
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
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.menu.CloseMenuParameters;
import dev.mcai.companion.skills.menu.MenuBinding;
import dev.mcai.companion.skills.menu.MenuOperationResult;
import dev.mcai.companion.skills.menu.MenuSkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillFrame;
import dev.mcai.companion.skills.menu.MenuSkillFrameSource;
import dev.mcai.companion.skills.menu.TransferMenuItemParameters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.LongConsumer;

/**
 * Establishes the persistent workstation/storage boundary after the iron
 * toolkit is ready.
 *
 * <p>The model makes one high-level choice. This bounded state machine then
 * performs ordinary recipe, aiming, placement, block-use and chest-menu
 * transactions at server tick speed. It never edits a block, inventory or
 * container directly, and it can use only a table or chest that the body
 * previously opened or can currently see through first-person rays.</p>
 */
public final class EstablishFoundationWorkstationsSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "establish_foundation_workstations";

    private static final String CRAFTING_TABLE =
            "minecraft:crafting_table";
    private static final String CHEST = "minecraft:chest";
    private static final int REQUIRED_CHEST_PLANKS = 8;
    private static final int DISTRIBUTED_STORAGE_CHESTS = 4;
    private static final int MAXIMUM_TICKS = 10_200;
    private static final int MAXIMUM_RECIPE_WAIT_TICKS = 100;
    private static final int MAXIMUM_AIM_TICKS = 80;
    private static final int MAXIMUM_CONFIRM_TICKS = 120;
    private static final int MAXIMUM_RECLAIM_TICKS = 180;
    private static final int MAXIMUM_CHEST_OPEN_ATTEMPTS = 2;
    private static final int MAXIMUM_FIXTURE_MOVE_TICKS = 300;
    private static final int MAXIMUM_FIXTURE_NO_PROGRESS_TICKS = 100;
    private static final double RELIABLE_INTERACTION_DISTANCE = 3.75;
    private static final double APPROACH_RADIUS = 3.0;
    private static final double FIXTURE_MOVE_ARRIVAL_RADIUS = 0.75;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final int MAXIMUM_SCAN_TURNS = 48;
    private static final double MINIMUM_PLACEMENT_DISTANCE = 1.35;
    private static final double MAXIMUM_PLACEMENT_DISTANCE = 4.25;
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
            25.0F,
            45.0F,
            65.0F
    };
    private static final List<String> SURPLUS_PRIORITY = List.of(
            "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;
    private final MenuSkillActuator menus;
    private final MenuSkillFrameSource menuFrames;
    private final PrepareFoundationShelterMaterialsSkill materialPreparer;
    private final LongFunction<Optional<VerifiedFixtureLocation>>
            knownCraftingTable;
    private final LongFunction<Optional<VerifiedFixtureLocation>>
            knownStorage;
    private final int requiredChestCount;
    private final boolean distributeOwnedLogs;
    private final boolean retrieveContainerLogsAndPlaceDoor;
    private final LongConsumer completionEvidence;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long nextScanTick = -1;
    private int scanTurns;
    private float scanBaseYaw;
    private VisibleBlockFace selectedTarget;
    private VisibleBlockFace selectedSupport;
    private BlockCoordinate expectedChestPosition;
    private long depositSampleSequence = -1;
    private String depositedItemId = "";
    private int chestOpenAttempts;
    private MoveToSkill fixtureMovement;
    private MoveToParameters fixtureMovementParameters;
    private Fixture movementTarget;
    private Phase movementResumePhase;
    private double fixtureMovementBestDistance = Double.POSITIVE_INFINITY;
    private long fixtureMovementLastProgressTick = -1L;
    private final Set<BlockCoordinate> rejectedSupports =
            new HashSet<>();
    private long lastLiveDiagnosticTick = Long.MIN_VALUE;
    private int initialChestCount;
    private int chestsCraftedForTask;
    private int chestsPlacedForTask;
    private final List<BlockCoordinate> placedChestPositions =
            new ArrayList<>();
    private final List<Integer> logDistributionTargets =
            new ArrayList<>();
    private int logDistributionIndex;
    private int remainingLogDeposit;
    private long distributionTransferSampleSequence = -1L;
    private boolean completionEvidenceRecorded;
    private final List<BlockCoordinate> observedChestPositions =
            new ArrayList<>();
    private final List<BlockCoordinate> withdrawalChestPositions =
            new ArrayList<>();
    private boolean withdrawalAnchorApproachAttempted;
    private final List<String> withdrawnWoodItems = new ArrayList<>();
    private PerceptionVec3 taskStartPosition;
    private int withdrawalChestIndex;
    private int withdrawalSourceSlot = -1;
    private int withdrawalSourceCount = -1;
    private long withdrawalSampleSequence = -1L;
    private String pendingWithdrawnWoodItem = "";
    private int withdrawnPlankCraftIndex;
    private String selectedPlankItemId = "";
    private String selectedDoorItemId = "";
    private int initialDoorCount;
    private BlockCoordinate expectedDoorPosition;
    private int doorOutwardX;
    private int doorOutwardZ;

    public EstablishFoundationWorkstationsSkill(
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
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage
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
                knownStorage,
                1,
                false,
                false,
                ignored -> { }
        );
    }

    static EstablishFoundationWorkstationsSkill distributedLogStorage(
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
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage,
            final LongConsumer completionEvidence
    ) {
        return new EstablishFoundationWorkstationsSkill(
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
                knownStorage,
                DISTRIBUTED_STORAGE_CHESTS,
                true,
                false,
                completionEvidence
        );
    }

    static EstablishFoundationWorkstationsSkill containerWoodDoor(
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
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage,
            final LongConsumer completionEvidence
    ) {
        return new EstablishFoundationWorkstationsSkill(
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
                knownStorage,
                DISTRIBUTED_STORAGE_CHESTS,
                false,
                true,
                completionEvidence
        );
    }

    private EstablishFoundationWorkstationsSkill(
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
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage,
            final int requiredChestCount,
            final boolean distributeOwnedLogs,
            final boolean retrieveContainerLogsAndPlaceDoor,
            final LongConsumer completionEvidence
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
        this.knownCraftingTable = Objects.requireNonNull(
                knownCraftingTable,
                "knownCraftingTable"
        );
        this.knownStorage = Objects.requireNonNull(
                knownStorage,
                "knownStorage"
        );
        if (requiredChestCount < 1 || requiredChestCount > 8) {
            throw new IllegalArgumentException(
                    "requiredChestCount must be in [1, 8]"
            );
        }
        this.requiredChestCount = requiredChestCount;
        this.distributeOwnedLogs = distributeOwnedLogs;
        this.retrieveContainerLogsAndPlaceDoor =
                retrieveContainerLogsAndPlaceDoor;
        if (distributeOwnedLogs && retrieveContainerLogsAndPlaceDoor) {
            throw new IllegalArgumentException(
                    "Storage modes are mutually exclusive"
            );
        }
        this.completionEvidence = Objects.requireNonNull(
                completionEvidence,
                "completionEvidence"
        );
        materialPreparer =
                new PrepareFoundationShelterMaterialsSkill(
                        expectedPlayerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        Objects.requireNonNull(
                                resourceInventory,
                                "resourceInventory"
                        ),
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        Objects.requireNonNull(
                                knownFurnace,
                                "knownFurnace"
                        ),
                        REQUIRED_CHEST_PLANKS * requiredChestCount
                );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return EstablishFoundationWorkstationsSkill::parseNone;
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
        if (retrieveContainerLogsAndPlaceDoor) {
            /*
             * This compound starts by discovering and opening the four
             * observed containers. Requiring the crafting table to already
             * be visible or remembered makes a perfectly valid continuation
             * impossible whenever the preceding use-block action has left a
             * chest menu open. The compound has its own fair FIND_TABLE scan
             * after withdrawal, so absence of table evidence at admission is
             * not a valid precondition failure.
             */
            return Optional.empty();
        }
        if (!distributeOwnedLogs
                && hasStorageEvidence(context, frame)) {
            return Optional.empty();
        }
        if (!hasCraftingTableEvidence(context, frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".crafting_table_required"
            ));
        }
        if (!distributeOwnedLogs && ownsChest(frame)
                || potentialPlanks(frame)
                    >= requiredChestPlanks()) {
            return Optional.empty();
        }
        return materialPreparer.preconditions(
                context,
                NoParameters.INSTANCE
        );
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
                        "Companion body changed before workstation setup"
                )
        );
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        selectedTarget = null;
        selectedSupport = null;
        expectedChestPosition = null;
        depositSampleSequence = -1;
        depositedItemId = "";
        chestOpenAttempts = 0;
        fixtureMovement = null;
        fixtureMovementParameters = null;
        movementTarget = null;
        movementResumePhase = null;
        fixtureMovementBestDistance = Double.POSITIVE_INFINITY;
        fixtureMovementLastProgressTick = -1L;
        rejectedSupports.clear();
        lastLiveDiagnosticTick = Long.MIN_VALUE;
        initialChestCount = itemCount(frame, CHEST);
        chestsCraftedForTask = 0;
        chestsPlacedForTask = 0;
        placedChestPositions.clear();
        logDistributionTargets.clear();
        logDistributionIndex = 0;
        remainingLogDeposit = 0;
        distributionTransferSampleSequence = -1L;
        completionEvidenceRecorded = false;
        observedChestPositions.clear();
        withdrawalChestPositions.clear();
        withdrawalAnchorApproachAttempted = false;
        withdrawnWoodItems.clear();
        taskStartPosition = frame.position();
        withdrawalChestIndex = 0;
        withdrawalSourceSlot = -1;
        withdrawalSourceCount = -1;
        withdrawalSampleSequence = -1L;
        pendingWithdrawnWoodItem = "";
        withdrawnPlankCraftIndex = 0;
        selectedPlankItemId = "";
        selectedDoorItemId = "";
        initialDoorCount = 0;
        expectedDoorPosition = null;
        doorOutwardX = 0;
        doorOutwardZ = 0;
        if (retrieveContainerLogsAndPlaceDoor) {
            /*
             * A player can issue the compound while looking inside one of
             * the source chests. Return to an ordinary first-person world
             * view before sampling the group; the close still goes through
             * the normal bound menu transaction.
             */
            closeOpenMenuBestEffort();
            beginScan(context, frame, Phase.DISCOVER_CHESTS);
        } else if (!distributeOwnedLogs
                && hasStorageEvidence(context, frame)) {
            beginScan(context, frame, Phase.FIND_CHEST);
        } else if (!distributeOwnedLogs && ownsChest(frame)) {
            phase = Phase.EQUIP_CHEST;
        } else if (potentialPlanks(frame)
                < requiredChestPlanks()) {
            materialPreparer.start(
                    context,
                    NoParameters.INSTANCE
            );
            phase = Phase.PREPARE_MATERIALS;
        } else {
            beginScan(context, frame, Phase.PREPARE_PLANKS);
        }
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
            cancelMaterialPreparation(context);
            return fail(NAME + ".internal_failure");
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
                        "{\"phase\":\"%s\",\"scanTurns\":%d,"
                                + "\"depositedItem\":\"%s\","
                                + "\"elapsedTicks\":%d,"
                                + "\"preparingMaterials\":%s,"
                                + "\"materialCheckpoint\":%s}",
                        phase.name(),
                        scanTurns,
                        depositedItemId,
                        Math.max(
                                0L,
                                context.gameTick() - startedAtTick
                        ),
                        phase == Phase.PREPARE_MATERIALS,
                        materialCheckpoint(context)
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelMaterialPreparation(context);
        cancelFixtureMovement(context);
        if (phase == Phase.RECLAIM_CHEST
                || phase == Phase.START_RECLAIM_CHEST) {
            interactions.abortMining();
        }
        cancelFixtureMovement(null);
        core.stop();
        closeOpenMenuBestEffort();
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

    private SkillTickResult tickSafely(
            final SkillContext context
    ) {
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            cancelMaterialPreparation(context);
            return fail(NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            cancelMaterialPreparation(context);
            return fail(NAME + ".body_unavailable");
        }
        if (Boolean.getBoolean("mcai.liveModelTest")
                && (lastLiveDiagnosticTick == Long.MIN_VALUE
                    || context.gameTick() - lastLiveDiagnosticTick >= 100L)) {
            lastLiveDiagnosticTick = context.gameTick();
            MinecraftAiCompanion.LOGGER.info(
                    "Live workstation trace: phase={}, tick={}, logs={}, "
                            + "planks={}, chest={}, tableMenu={}, chestMenu={}, "
                            + "visibleFaces={}, position={}",
                    phase,
                    context.gameTick(),
                    frame.inventory().stream()
                            .filter(item -> item.itemId().endsWith("_log"))
                            .mapToInt(InventoryItemSummary::count)
                            .sum(),
                    plankCount(frame),
                    itemCount(frame, CHEST),
                    inventory.hasThreeByThreeCraftingMenu(),
                    currentChestMenu().isPresent(),
                    frame.visibleBlockFaces().size(),
                    frame.position()
            );
        }
        final Optional<SkillFailure> unsafe = safetyFailure(
                context,
                frame
        );
        if (unsafe.isPresent()) {
            cancelMaterialPreparation(context);
            return fail(unsafe.orElseThrow());
        }
        return switch (phase) {
            case DISCOVER_CHESTS -> discoverWithdrawalChests(
                    context,
                    frame
            );
            case PREPARE_MATERIALS ->
                    prepareMaterials(context);
            case PREPARE_PLANKS -> preparePlanks(context, frame);
            case PREPARE_WITHDRAWN_PLANKS ->
                    prepareWithdrawnPlanks(context, frame);
            case FIND_TABLE -> findTarget(
                    context,
                    frame,
                    Fixture.TABLE
            );
            case MOVE_TO_FIXTURE -> moveToFixture(context, frame);
            case AIM_TABLE -> aimTarget(
                    context,
                    frame,
                    Fixture.TABLE
            );
            case OPEN_TABLE -> openTable(context);
            case CONFIRM_TABLE_MENU ->
                    confirmTableMenu(context);
            case CRAFT_CHEST -> craftChest(context);
            case CRAFT_DOOR -> craftDoor(context);
            case CONFIRM_DOOR_ITEM -> confirmDoorItem(
                    context,
                    frame
            );
            case CONFIRM_CHEST_ITEM ->
                    confirmChestItem(context, frame);
            case CLOSE_TABLE -> closeTable(context);
            case EQUIP_CHEST -> equipChest(context, frame);
            case FIND_SUPPORT -> findSupport(context, frame);
            case AIM_SUPPORT -> aimSupport(context, frame);
            case PLACE_CHEST -> placeChest(context);
            case CONFIRM_CHEST ->
                    confirmChest(context, frame);
            case FIND_CHEST -> findTarget(
                    context,
                    frame,
                    Fixture.CHEST
            );
            case AIM_CHEST -> aimTarget(
                    context,
                    frame,
                    Fixture.CHEST
            );
            case OPEN_CHEST -> openChest(context);
            case CONFIRM_CHEST_MENU ->
                    confirmChestMenu(context, frame);
            case AIM_RECLAIM_CHEST ->
                    aimReclaimChest(context, frame);
            case START_RECLAIM_CHEST ->
                    startReclaimChest(context);
            case RECLAIM_CHEST ->
                    reclaimChest(context, frame);
            case DEPOSIT_SURPLUS -> depositSurplus(context);
            case WITHDRAW_LOG -> withdrawOneLog(context);
            case CONFIRM_WITHDRAW_LOG -> confirmWithdrawnLog(
                    context
            );
            case CLOSE_CHEST -> closeChest(context);
            case FIND_DOOR_SUPPORT -> findDoorSupport(
                    context,
                    frame
            );
            case EQUIP_DOOR -> equipDoor(context, frame);
            case AIM_DOOR_SUPPORT -> aimDoorSupport(
                    context,
                    frame
            );
            case PLACE_DOOR -> placeDoor(context);
            case CONFIRM_DOOR -> confirmDoor(context, frame);
            case FINISH -> complete(context);
            default -> fail(NAME + ".invalid_state");
        };
    }

    private SkillTickResult prepareMaterials(
            final SkillContext context
    ) {
        final SkillTickResult child = materialPreparer.tick(
                context,
                NoParameters.INSTANCE
        );
        if (child.status() == SkillTickResult.Status.FAILED) {
            cancelMaterialPreparation(context);
            return fail(child.failure().orElseGet(() ->
                    SkillFailure.of(
                            NAME + ".material_preparation_failed"
                    )
            ));
        }
        if (child.status()
                == SkillTickResult.Status.COMPLETED) {
            final CoreSkillFrame frame = ownedFrame().orElse(null);
            if (frame == null) {
                return fail(NAME + ".body_unavailable");
            }
            if (!distributeOwnedLogs && ownsChest(frame)) {
                return transition(context, Phase.EQUIP_CHEST);
            }
            beginScan(context, frame, Phase.PREPARE_PLANKS);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                child.madeProgress(),
                child.safeCheckpoint()
        );
    }

    private SkillTickResult preparePlanks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (plankCount(frame) >= requiredChestPlanks()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final InventoryOperationResult crafted =
                craftOnePlank(frame);
        if (crafted.succeeded()) {
            nextScanTick = context.gameTick() + 5L;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                < MAXIMUM_RECIPE_WAIT_TICKS) {
            return SkillTickResult.running(false, true);
        }
        return fail(NAME + ".plank_recipe_unavailable");
    }

    private SkillTickResult findTarget(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Fixture fixture
    ) {
        if (fixture == Fixture.TABLE
                && inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    retrieveContainerLogsAndPlaceDoor
                            ? Phase.CRAFT_DOOR
                            : Phase.CRAFT_CHEST
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(face, fixture))
                        .filter(face -> matchesExpectedFixture(
                                context,
                                frame,
                                face,
                                fixture
                        ));
        if (crosshair.isPresent()) {
            selectedTarget = crosshair.orElseThrow();
            return transition(
                    context,
                    fixture == Fixture.TABLE
                            ? Phase.OPEN_TABLE
                            : Phase.OPEN_CHEST
            );
        }
        final Optional<VisibleBlockFace> visible =
                visibleFixture(context, frame, fixture);
        if (visible.isPresent()) {
            final VisibleBlockFace target = visible.orElseThrow();
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
            selectedTarget = target;
            return transition(
                    context,
                    fixture == Fixture.TABLE
                            ? Phase.AIM_TABLE
                            : Phase.AIM_CHEST
            );
        }
        if (fixture == Fixture.CHEST
                && expectedChestPosition != null) {
            final double distance = center(expectedChestPosition)
                    .subtract(frame.position())
                    .length();
            if (distance > APPROACH_RADIUS) {
                return beginFixtureApproach(
                        context,
                        frame,
                        fixture,
                        expectedChestPosition.x(),
                        expectedChestPosition.y(),
                        expectedChestPosition.z()
                );
            }
        }
        final Optional<VerifiedFixtureLocation> remembered =
                fixture == Fixture.TABLE
                        ? knownCraftingTable(context, frame)
                        : knownStorage(context, frame);
        if (remembered.isPresent()) {
            final VerifiedFixtureLocation location =
                    remembered.orElseThrow();
            final double distance = center(new BlockCoordinate(
                            location.x(),
                            location.y(),
                            location.z()
                    ))
                    .subtract(frame.position())
                    .length();
            if (distance > APPROACH_RADIUS) {
                return beginFixtureApproach(
                        context,
                        frame,
                        fixture,
                        location.x(),
                        location.y(),
                        location.z()
                );
            }
        }
        return scan(
                context,
                frame,
                fixture == Fixture.TABLE
                        ? Phase.FIND_TABLE
                        : Phase.FIND_CHEST,
                fixture.failureStem()
        );
    }

    /**
     * A remembered fixture is a navigation hint, never an interaction
     * authority. Walk toward it with the normal bounded movement skill; the
     * next phase still requires a fresh first-person ray before opening it.
     */
    private SkillTickResult beginFixtureApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Fixture fixture,
            final int x,
            final int y,
            final int z
    ) {
        return beginFixtureApproach(
                context,
                frame,
                fixture,
                x,
                y,
                z,
                findPhase(fixture)
        );
    }

    private SkillTickResult beginFixtureApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Fixture fixture,
            final int x,
            final int y,
            final int z,
            final Phase resumePhase
    ) {
        cancelFixtureMovement(context);
        final PerceptionVec3 target = fixture == Fixture.DOOR_SUPPORT
                ? new PerceptionVec3(x + 0.5, y + 1.0, z + 0.5)
                : adjacentFixtureStand(frame, x, y, z);
        fixtureMovementParameters = new MoveToParameters(
                frame.dimension(),
                target.x(),
                target.y(),
                target.z(),
                FIXTURE_MOVE_ARRIVAL_RADIUS
        );
        fixtureMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected = fixtureMovement.preconditions(
                context,
                fixtureMovementParameters
        );
        if (rejected.isPresent()) {
            cancelFixtureMovement(context);
            return fail(NAME + "." + fixture.failureStem()
                    + "_approach_rejected");
        }
        fixtureMovement.start(context, fixtureMovementParameters);
        movementTarget = fixture;
        movementResumePhase = Objects.requireNonNull(
                resumePhase,
                "resumePhase"
        );
        fixtureMovementBestDistance = frame.position()
                .subtract(fixtureMovementParameters.target())
                .length();
        fixtureMovementLastProgressTick = context.gameTick();
        phase = Phase.MOVE_TO_FIXTURE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    /**
     * A workstation occupies the block at its recorded coordinates; walking
     * to {@code y + 1} targets the top of that block, not an interaction
     * position. Select the cardinal side nearest the current body and keep
     * the target at ordinary feet height. Fresh first-person scanning after
     * arrival still decides whether the fixture can actually be opened.
     */
    private static PerceptionVec3 adjacentFixtureStand(
            final CoreSkillFrame frame,
            final int x,
            final int y,
            final int z
    ) {
        final double deltaX = frame.position().x() - (x + 0.5);
        final double deltaZ = frame.position().z() - (z + 0.5);
        final int standX;
        final int standZ;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            standX = x + (deltaX >= 0.0 ? 1 : -1);
            standZ = z;
        } else {
            standX = x;
            standZ = z + (deltaZ >= 0.0 ? 1 : -1);
        }
        return new PerceptionVec3(
                standX + 0.5,
                y,
                standZ + 0.5
        );
    }

    private SkillTickResult moveToFixture(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (fixtureMovement == null
                || fixtureMovementParameters == null
                || movementTarget == null
                || movementResumePhase == null) {
            return fail(NAME + ".fixture_approach_binding_missing");
        }
        /*
         * Discovery is an observation process, not a stationary camera
         * sweep. Preserve every chest face that the body legitimately sees
         * while walking toward the remembered storage anchor. Otherwise a
         * line of independent chests can occlude itself once the body stops
         * beside the end chest, even though the other containers were in
         * clear first-person view during the approach.
         */
        if (movementResumePhase == Phase.DISCOVER_CHESTS) {
            recordObservedChests(frame);
        }
        final double distance = frame.position()
                .subtract(fixtureMovementParameters.target())
                .length();
        if (distance + 0.05 < fixtureMovementBestDistance) {
            fixtureMovementBestDistance = distance;
            fixtureMovementLastProgressTick = context.gameTick();
        }
        if (context.gameTick() - phaseStartedAtTick
                        >= MAXIMUM_FIXTURE_MOVE_TICKS
                || context.gameTick() - fixtureMovementLastProgressTick
                        >= MAXIMUM_FIXTURE_NO_PROGRESS_TICKS) {
            final Phase resumePhase = movementResumePhase;
            cancelFixtureMovement(context);
            beginScan(
                    context,
                    frame,
                    resumePhase
            );
            return SkillTickResult.running(true, true);
        }
        final SkillTickResult result = fixtureMovement.tick(
                context,
                fixtureMovementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final Phase resumePhase = movementResumePhase;
            cancelFixtureMovement(context);
            beginScan(
                    context,
                    frame,
                    resumePhase
            );
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            final Phase resumePhase = movementResumePhase;
            cancelFixtureMovement(context);
            beginScan(
                    context,
                    frame,
                    resumePhase
            );
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult aimTarget(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Fixture fixture
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(face, fixture))
                        .filter(face -> matchesExpectedFixture(
                                context,
                                frame,
                                face,
                                fixture
                        ));
        if (crosshair.isPresent()) {
            selectedTarget = crosshair.orElseThrow();
            return transition(
                    context,
                    fixture == Fixture.TABLE
                            ? Phase.OPEN_TABLE
                            : Phase.OPEN_CHEST
            );
        }
        if (selectedTarget == null
                || context.gameTick() - phaseStartedAtTick
                        >= MAXIMUM_AIM_TICKS) {
            beginScan(
                    context,
                    frame,
                    fixture == Fixture.TABLE
                            ? Phase.FIND_TABLE
                            : Phase.FIND_CHEST
            );
            return SkillTickResult.running(true, true);
        }
        return aimAt(frame, selectedTarget.hitPosition());
    }

    private SkillTickResult openTable(
            final SkillContext context
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    retrieveContainerLogsAndPlaceDoor
                            ? Phase.CRAFT_DOOR
                            : Phase.CRAFT_CHEST
            );
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                Fixture.TABLE
                        ));
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_TABLE);
        }
        final ActionOutcome opened = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        return opened.accepted()
                ? transition(context, Phase.CONFIRM_TABLE_MENU)
                : fail(NAME + ".table_open_rejected");
    }

    private SkillTickResult confirmTableMenu(
            final SkillContext context
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    retrieveContainerLogsAndPlaceDoor
                            ? Phase.CRAFT_DOOR
                            : Phase.CRAFT_CHEST
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".table_menu_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult craftChest(
            final SkillContext context
    ) {
        if (distributeOwnedLogs
                && chestsCraftedForTask >= requiredChestCount) {
            return transition(context, Phase.CLOSE_TABLE);
        }
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(CHEST, 1);
        final InventoryOperationResult checked =
                inventory.checkCraft(recipe);
        if (checked.succeeded()) {
            final InventoryOperationResult crafted =
                    inventory.craftOnce(recipe);
            if (crafted.succeeded()) {
                if (distributeOwnedLogs) {
                    chestsCraftedForTask++;
                }
                return transition(
                        context,
                        Phase.CONFIRM_CHEST_ITEM
                );
            }
        }
        if (context.gameTick() - phaseStartedAtTick
                < MAXIMUM_RECIPE_WAIT_TICKS) {
            return SkillTickResult.running(false, true);
        }
        return fail(NAME + ".chest_recipe_unavailable");
    }

    private SkillTickResult confirmChestItem(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final int expectedOwnedChests = distributeOwnedLogs
                ? initialChestCount + chestsCraftedForTask
                    - chestsPlacedForTask
                : 1;
        if (itemCount(frame, CHEST) >= expectedOwnedChests
                || frame.mainHand().itemId().equals(CHEST)
                    && frame.mainHand().count()
                        >= expectedOwnedChests) {
            return transition(
                    context,
                    distributeOwnedLogs
                            && chestsCraftedForTask
                                < requiredChestCount
                            ? Phase.CRAFT_CHEST
                            : Phase.CLOSE_TABLE
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".chest_craft_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult closeTable(
            final SkillContext context
    ) {
        if (!inventory.hasThreeByThreeCraftingMenu()) {
            return transition(
                    context,
                    retrieveContainerLogsAndPlaceDoor
                            ? Phase.FIND_DOOR_SUPPORT
                            : Phase.EQUIP_CHEST
            );
        }
        final InventoryOperationResult closed =
                inventory.closeThreeByThreeCraftingMenu();
        return closed.succeeded()
                ? transition(
                        context,
                        retrieveContainerLogsAndPlaceDoor
                                ? Phase.FIND_DOOR_SUPPORT
                                : Phase.EQUIP_CHEST
                )
                : fail(NAME + ".table_close_failed");
    }

    private SkillTickResult equipChest(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (frame.mainHand().itemId().equals(CHEST)
                && frame.mainHand().count() > 0) {
            beginScan(context, frame, Phase.FIND_SUPPORT);
            return SkillTickResult.running(true, true);
        }
        if (itemCount(frame, CHEST) <= 0) {
            return fail(NAME + ".chest_item_missing");
        }
        final InventoryOperationResult equipped = inventory.equip(
                new EquipItemParameters(
                        CHEST,
                        EquipmentTarget.MAINHAND
                )
        );
        return equipped.succeeded()
                ? SkillTickResult.running(true, true)
                : fail(NAME + ".chest_equip_failed");
    }

    private SkillTickResult findSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> support =
                selectPlacementSupport(frame);
        if (support.isPresent()) {
            selectedSupport = support.orElseThrow();
            return transition(context, Phase.AIM_SUPPORT);
        }
        return scan(
                context,
                frame,
                Phase.FIND_SUPPORT,
                "safe_chest_support"
        );
    }

    private SkillTickResult aimSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && selectedSupport != null
                && sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            selectedSupport = crosshair.orElseThrow();
            return transition(context, Phase.PLACE_CHEST);
        }
        if (selectedSupport == null
                || context.gameTick() - phaseStartedAtTick
                        >= MAXIMUM_AIM_TICKS) {
            beginScan(context, frame, Phase.FIND_SUPPORT);
            return SkillTickResult.running(true, true);
        }
        return aimAt(frame, selectedSupport.hitPosition());
    }

    private SkillTickResult placeChest(
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
            return transition(context, Phase.AIM_SUPPORT);
        }
        final VisibleBlockFace actual = crosshair.orElseThrow();
        final ActionOutcome placed = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(actual)
        );
        if (!placed.accepted()) {
            rejectedSupports.add(actual.block());
            selectedSupport = null;
            return transition(context, Phase.FIND_SUPPORT);
        }
        expectedChestPosition = new BlockCoordinate(
                actual.block().x(),
                actual.block().y() + 1,
                actual.block().z()
        );
        selectedTarget = null;
        return transition(context, Phase.CONFIRM_CHEST);
    }

    private SkillTickResult confirmChest(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> chest =
                visibleExpectedChest(frame);
        if (chest.isPresent()) {
            selectedTarget = chest.orElseThrow();
            if (distributeOwnedLogs) {
                return recordPlacedChestAndContinue(context, frame);
            }
            return transition(context, Phase.AIM_CHEST);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                Fixture.CHEST
                        ))
                        .filter(face -> expectedChestPosition == null
                                || face.block().equals(
                                        expectedChestPosition
                                ));
        if (crosshair.isPresent()) {
            selectedTarget = crosshair.orElseThrow();
            if (distributeOwnedLogs) {
                return recordPlacedChestAndContinue(context, frame);
            }
            return transition(context, Phase.OPEN_CHEST);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            if (itemCount(frame, CHEST) > 0
                    && selectedSupport != null) {
                rejectedSupports.add(selectedSupport.block());
                expectedChestPosition = null;
                selectedSupport = null;
                beginScan(context, frame, Phase.FIND_SUPPORT);
                return SkillTickResult.running(true, true);
            }
            return fail(NAME + ".chest_placement_unconfirmed");
        }
        if (expectedChestPosition != null) {
            return aimAt(
                    frame,
                    center(expectedChestPosition)
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult recordPlacedChestAndContinue(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (expectedChestPosition == null) {
            return fail(NAME + ".placed_chest_position_missing");
        }
        if (!placedChestPositions.contains(expectedChestPosition)) {
            placedChestPositions.add(expectedChestPosition);
            chestsPlacedForTask++;
        }
        if (chestsPlacedForTask < requiredChestCount) {
            expectedChestPosition = null;
            selectedTarget = null;
            selectedSupport = null;
            chestOpenAttempts = 0;
            return transition(context, Phase.EQUIP_CHEST);
        }
        final int logs = ownedRawLogCount(frame);
        if (logs <= 0) {
            return fail(NAME + ".no_logs_remaining_to_distribute");
        }
        logDistributionTargets.clear();
        final int base = logs / requiredChestCount;
        final int remainder = logs % requiredChestCount;
        for (int index = 0; index < requiredChestCount; index++) {
            logDistributionTargets.add(
                    base + (index < remainder ? 1 : 0)
            );
        }
        logDistributionIndex = 0;
        remainingLogDeposit = logDistributionTargets.getFirst();
        distributionTransferSampleSequence = -1L;
        expectedChestPosition = placedChestPositions.getFirst();
        beginScan(context, frame, Phase.FIND_CHEST);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult openChest(
            final SkillContext context
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                Fixture.CHEST
                        ));
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_CHEST);
        }
        final ActionOutcome opened = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        if (opened.accepted()) {
            chestOpenAttempts++;
        }
        return opened.accepted()
                ? transition(context, Phase.CONFIRM_CHEST_MENU)
                : fail(NAME + ".chest_open_rejected");
    }

    private SkillTickResult confirmChestMenu(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (currentChestMenu().isPresent()) {
            return transition(
                    context,
                    retrieveContainerLogsAndPlaceDoor
                            ? Phase.WITHDRAW_LOG
                            : Phase.DEPOSIT_SURPLUS
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            /*
             * Dispatch is not proof that the chest opened. A newly placed
             * chest can still be blocked by a full block above it, or an
             * otherwise legal interaction can transiently fail. Retry once;
             * if this skill placed the still-unopenable empty chest, recover
             * that exact block through ordinary mining and choose another
             * observed-clear support. This preserves the crafted item instead
             * of demanding eight new planks after a real placement.
             */
            if (expectedChestPosition != null
                    && (knownChestOpeningObstruction(
                            frame,
                            expectedChestPosition
                        )
                        || chestOpenAttempts
                            >= MAXIMUM_CHEST_OPEN_ATTEMPTS)) {
                selectedTarget = visibleExpectedChest(frame)
                        .orElse(selectedTarget);
                return transition(
                        context,
                        Phase.AIM_RECLAIM_CHEST
                );
            }
            if (chestOpenAttempts
                    < MAXIMUM_CHEST_OPEN_ATTEMPTS) {
                selectedTarget = visibleExpectedChest(frame)
                        .orElse(selectedTarget);
                return transition(context, Phase.AIM_CHEST);
            }
            return fail(NAME + ".chest_menu_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult aimReclaimChest(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (expectedChestPosition == null) {
            return fail(NAME + ".chest_reclaim_target_missing");
        }
        if (ownsChest(frame)) {
            rejectPreviousSupport();
            return transition(context, Phase.EQUIP_CHEST);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                Fixture.CHEST
                        ))
                        .filter(face -> face.block().equals(
                                expectedChestPosition
                        ));
        if (crosshair.isPresent()) {
            selectedTarget = crosshair.orElseThrow();
            return transition(
                    context,
                    Phase.START_RECLAIM_CHEST
            );
        }
        final Optional<VisibleBlockFace> visible =
                visibleExpectedChest(frame);
        if (visible.isPresent()) {
            selectedTarget = visible.orElseThrow();
        }
        if (selectedTarget == null
                || context.gameTick() - phaseStartedAtTick
                        >= MAXIMUM_AIM_TICKS) {
            return fail(NAME + ".chest_reclaim_target_not_visible");
        }
        return aimAt(frame, selectedTarget.hitPosition());
    }

    private SkillTickResult startReclaimChest(
            final SkillContext context
    ) {
        if (expectedChestPosition == null) {
            return fail(NAME + ".chest_reclaim_target_missing");
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> isFixture(
                                face,
                                Fixture.CHEST
                        ))
                        .filter(face -> face.block().equals(
                                expectedChestPosition
                        ));
        if (crosshair.isEmpty()) {
            return transition(
                    context,
                    Phase.AIM_RECLAIM_CHEST
            );
        }
        final ActionOutcome started = interactions.beginMining(
                target(crosshair.orElseThrow())
        );
        return started.accepted()
                ? transition(context, Phase.RECLAIM_CHEST)
                : fail(NAME + ".chest_reclaim_start_rejected");
    }

    private SkillTickResult reclaimChest(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownsChest(frame)) {
            interactions.abortMining();
            rejectPreviousSupport();
            return transition(context, Phase.EQUIP_CHEST);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_RECLAIM_TICKS) {
            interactions.abortMining();
            return fail(NAME + ".chest_reclaim_unconfirmed");
        }
        final ActionOutcome continued =
                interactions.continueMining();
        if (continued.accepted()
                || continued == ActionOutcome.NO_ACTIVE_ACTION) {
            /*
             * NO_ACTIVE_ACTION is normal between vanilla block-break
             * completion and the next semantic inventory sample/pickup.
             */
            return SkillTickResult.running(
                    continued.accepted(),
                    true
            );
        }
        return fail(NAME + ".chest_reclaim_failed");
    }

    private SkillTickResult depositSurplus(
            final SkillContext context
    ) {
        if (distributeOwnedLogs) {
            return depositDistributedLogs(context);
        }
        final Optional<MenuSkillFrame> maybeMenu =
                currentChestMenu();
        if (maybeMenu.isEmpty()) {
            return fail(NAME + ".chest_menu_lost");
        }
        final MenuSkillFrame frame = maybeMenu.orElseThrow();
        final Optional<MenuSlotSummary> source =
                selectSurplusSource(frame);
        final Optional<MenuSlotSummary> destination =
                frame.menu().slots().stream()
                        .filter(slot -> !slot.playerInventory())
                        .filter(slot -> slot.count() == 0)
                        .min(Comparator.comparingInt(
                                MenuSlotSummary::slot
                        ));
        if (source.isEmpty()) {
            return fail(NAME + ".no_surplus_supply");
        }
        if (destination.isEmpty()) {
            return fail(NAME + ".storage_full");
        }
        final MenuBinding binding = binding(frame);
        final TransferMenuItemParameters transfer =
                new TransferMenuItemParameters(
                        binding,
                        source.orElseThrow().slot(),
                        destination.orElseThrow().slot(),
                        1
                );
        final MenuOperationResult moved = menus.transfer(transfer);
        if (!moved.succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_CONFIRM_TICKS) {
                return SkillTickResult.running(false, true);
            }
            return fail(NAME + ".storage_deposit_rejected");
        }
        depositedItemId = source.orElseThrow().itemId();
        depositSampleSequence = frame.sampleSequence();
        return transition(context, Phase.CLOSE_CHEST);
    }

    private SkillTickResult depositDistributedLogs(
            final SkillContext context
    ) {
        final Optional<MenuSkillFrame> maybeMenu = currentChestMenu();
        if (maybeMenu.isEmpty()) {
            return fail(NAME + ".chest_menu_lost");
        }
        final MenuSkillFrame frame = maybeMenu.orElseThrow();
        if (distributionTransferSampleSequence >= 0
                && frame.sampleSequence()
                    <= distributionTransferSampleSequence) {
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_CONFIRM_TICKS) {
                return fail(NAME + ".log_transfer_unconfirmed");
            }
            return SkillTickResult.running(false, true);
        }
        distributionTransferSampleSequence = -1L;
        if (remainingLogDeposit <= 0) {
            depositSampleSequence = frame.sampleSequence() - 1L;
            return transition(context, Phase.CLOSE_CHEST);
        }
        final Optional<MenuSlotSummary> source =
                selectOwnedLogSource(frame);
        final Optional<MenuSlotSummary> destination =
                frame.menu().slots().stream()
                        .filter(slot -> !slot.playerInventory())
                        .filter(slot -> slot.count() == 0)
                        .min(Comparator.comparingInt(
                                MenuSlotSummary::slot
                        ));
        if (source.isEmpty()) {
            return fail(NAME + ".remaining_logs_missing");
        }
        if (destination.isEmpty()) {
            return fail(NAME + ".storage_full");
        }
        final int count = Math.min(
                remainingLogDeposit,
                source.orElseThrow().count()
        );
        final MenuOperationResult moved = menus.transfer(
                new TransferMenuItemParameters(
                        binding(frame),
                        source.orElseThrow().slot(),
                        destination.orElseThrow().slot(),
                        count
                )
        );
        if (!moved.succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_CONFIRM_TICKS) {
                return SkillTickResult.running(false, true);
            }
            return fail(NAME + ".log_distribution_rejected");
        }
        depositedItemId = source.orElseThrow().itemId();
        remainingLogDeposit -= count;
        depositSampleSequence = frame.sampleSequence();
        distributionTransferSampleSequence = frame.sampleSequence();
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult closeChest(
            final SkillContext context
    ) {
        final Optional<MenuSkillFrame> maybeMenu =
                currentChestMenu();
        if (maybeMenu.isEmpty()) {
            if (retrieveContainerLogsAndPlaceDoor) {
                return advanceWithdrawalChest(context);
            }
            return distributeOwnedLogs
                    ? advanceDistributedChest(context)
                    : transition(context, Phase.FINISH);
        }
        final MenuSkillFrame frame = maybeMenu.orElseThrow();
        final MenuBinding binding = binding(frame);
        if (frame.sampleSequence() <= depositSampleSequence
                || !menus.checkBinding(binding).succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_CONFIRM_TICKS) {
                return fail(NAME + ".deposit_observation_unconfirmed");
            }
            return SkillTickResult.running(false, true);
        }
        final MenuOperationResult closed = menus.close(
                new CloseMenuParameters(binding)
        );
        if (!closed.succeeded()) {
            return fail(NAME + ".chest_close_failed");
        }
        if (retrieveContainerLogsAndPlaceDoor) {
            return advanceWithdrawalChest(context);
        }
        return distributeOwnedLogs
                ? advanceDistributedChest(context)
                : transition(context, Phase.FINISH);
    }

    private SkillTickResult advanceDistributedChest(
            final SkillContext context
    ) {
        logDistributionIndex++;
        if (logDistributionIndex >= requiredChestCount) {
            return transition(context, Phase.FINISH);
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(NAME + ".body_unavailable");
        }
        expectedChestPosition = placedChestPositions.get(
                logDistributionIndex
        );
        remainingLogDeposit = logDistributionTargets.get(
                logDistributionIndex
        );
        depositSampleSequence = -1L;
        distributionTransferSampleSequence = -1L;
        chestOpenAttempts = 0;
        beginScan(context, frame, Phase.FIND_CHEST);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult discoverWithdrawalChests(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        recordObservedChests(frame);
        final List<BlockCoordinate> selected =
                selectIndependentChestGroup(
                        observedChestPositions,
                        taskStartPosition,
                        requiredChestCount
                );
        if (selected.size() == requiredChestCount) {
            withdrawalChestPositions.clear();
            withdrawalChestPositions.addAll(selected);
            configureDoorGeometry();
            withdrawalChestIndex = 0;
            expectedChestPosition =
                    withdrawalChestPositions.getFirst();
            beginScan(context, frame, Phase.FIND_CHEST);
            return SkillTickResult.running(true, true);
        }
        if (!withdrawalAnchorApproachAttempted) {
            final Optional<VerifiedFixtureLocation> remembered =
                    knownStorage(context, frame);
            if (remembered.isPresent()) {
                final VerifiedFixtureLocation anchor =
                        remembered.orElseThrow();
                final double distance = center(new BlockCoordinate(
                                anchor.x(),
                                anchor.y(),
                                anchor.z()
                        ))
                        .subtract(frame.position())
                        .length();
                if (distance > APPROACH_RADIUS) {
                    withdrawalAnchorApproachAttempted = true;
                    return beginFixtureApproach(
                            context,
                            frame,
                            Fixture.CHEST,
                            anchor.x(),
                            anchor.y(),
                            anchor.z(),
                            Phase.DISCOVER_CHESTS
                    );
                }
            }
        }
        return scan(
                context,
                frame,
                Phase.DISCOVER_CHESTS,
                "four_independent_chests"
        );
    }

    private void recordObservedChests(final CoreSkillFrame frame) {
        frame.visibleBlockFaces().stream()
                .filter(face -> isFixture(face, Fixture.CHEST))
                .map(VisibleBlockFace::block)
                .filter(position ->
                        !observedChestPositions.contains(position))
                .forEach(observedChestPositions::add);
    }

    static List<BlockCoordinate> selectIndependentChestGroup(
            final List<BlockCoordinate> observed,
            final PerceptionVec3 origin,
            final int required
    ) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(origin, "origin");
        if (required < 1) {
            throw new IllegalArgumentException("required must be positive");
        }
        final List<BlockCoordinate> ordered = observed.stream()
                .distinct()
                .sorted(Comparator
                        .comparingDouble((BlockCoordinate position) ->
                                center(position).subtract(origin).length())
                        .thenComparingInt(BlockCoordinate::y)
                        .thenComparingInt(BlockCoordinate::x)
                        .thenComparingInt(BlockCoordinate::z))
                .toList();
        final List<BlockCoordinate> selected = new ArrayList<>();
        for (BlockCoordinate candidate : ordered) {
            final boolean independent = selected.stream().noneMatch(
                    existing -> existing.y() == candidate.y()
                            && Math.abs(existing.x() - candidate.x())
                                + Math.abs(existing.z() - candidate.z())
                                <= 1
            );
            if (independent) {
                selected.add(candidate);
                if (selected.size() == required) {
                    return List.copyOf(selected);
                }
            }
        }
        return List.of();
    }

    private void configureDoorGeometry() {
        final double averageX = withdrawalChestPositions.stream()
                .mapToInt(BlockCoordinate::x)
                .average()
                .orElseThrow();
        final double averageY = withdrawalChestPositions.stream()
                .mapToInt(BlockCoordinate::y)
                .average()
                .orElseThrow();
        final double averageZ = withdrawalChestPositions.stream()
                .mapToInt(BlockCoordinate::z)
                .average()
                .orElseThrow();
        final double towardBodyX = taskStartPosition.x()
                - (averageX + 0.5);
        final double towardBodyZ = taskStartPosition.z()
                - (averageZ + 0.5);
        if (Math.abs(towardBodyX) >= Math.abs(towardBodyZ)
                && Math.abs(towardBodyX) > 0.25) {
            doorOutwardX = towardBodyX > 0.0 ? 1 : -1;
            doorOutwardZ = 0;
        } else if (Math.abs(towardBodyZ) > 0.25) {
            doorOutwardX = 0;
            doorOutwardZ = towardBodyZ > 0.0 ? 1 : -1;
        } else {
            final PerceptionVec3 look = ownedFrame().orElseThrow()
                    .lookDirection();
            if (Math.abs(look.x()) >= Math.abs(look.z())) {
                doorOutwardX = look.x() > 0.0 ? -1 : 1;
                doorOutwardZ = 0;
            } else {
                doorOutwardX = 0;
                doorOutwardZ = look.z() > 0.0 ? -1 : 1;
            }
        }
        expectedDoorPosition = new BlockCoordinate(
                (int) Math.round(averageX) + doorOutwardX * 3,
                (int) Math.round(averageY),
                (int) Math.round(averageZ) + doorOutwardZ * 3
        );
    }

    private SkillTickResult withdrawOneLog(
            final SkillContext context
    ) {
        final Optional<MenuSkillFrame> maybeMenu = currentChestMenu();
        if (maybeMenu.isEmpty()) {
            return fail(NAME + ".chest_menu_lost");
        }
        final MenuSkillFrame frame = maybeMenu.orElseThrow();
        final Optional<MenuSlotSummary> source = frame.menu().slots()
                .stream()
                .filter(slot -> !slot.playerInventory())
                .filter(MenuSlotSummary::mayPickup)
                .filter(slot -> slot.count() > 0)
                .filter(slot -> PrepareBasicCraftingSkill.plankRecipeFor(
                        slot.itemId()
                ).isPresent())
                .min(Comparator.comparingInt(MenuSlotSummary::slot));
        if (source.isEmpty()) {
            return fail(NAME + ".container_has_no_convertible_wood");
        }
        final MenuSlotSummary sourceSlot = source.orElseThrow();
        final List<MenuSlotSummary> destinations = frame.menu().slots()
                .stream()
                .filter(MenuSlotSummary::playerInventory)
                .filter(MenuSlotSummary::mayPickup)
                .filter(slot -> slot.count() == 0
                        || slot.itemId().equals(sourceSlot.itemId()))
                .sorted(Comparator
                        .comparingInt((MenuSlotSummary slot) ->
                                slot.itemId().equals(sourceSlot.itemId())
                                        ? 0 : 1)
                        .thenComparingInt(MenuSlotSummary::slot))
                .toList();
        for (MenuSlotSummary destination : destinations) {
            final TransferMenuItemParameters transfer =
                    new TransferMenuItemParameters(
                            binding(frame),
                            sourceSlot.slot(),
                            destination.slot(),
                            1
                    );
            if (!menus.checkTransfer(transfer).succeeded()) {
                continue;
            }
            final MenuOperationResult moved = menus.transfer(transfer);
            if (!moved.succeeded()) {
                continue;
            }
            withdrawalSourceSlot = sourceSlot.slot();
            withdrawalSourceCount = sourceSlot.count();
            withdrawalSampleSequence = frame.sampleSequence();
            depositSampleSequence = frame.sampleSequence();
            pendingWithdrawnWoodItem = sourceSlot.itemId();
            return transition(context, Phase.CONFIRM_WITHDRAW_LOG);
        }
        return fail(NAME + ".no_inventory_capacity_for_withdrawal");
    }

    private SkillTickResult confirmWithdrawnLog(
            final SkillContext context
    ) {
        final Optional<MenuSkillFrame> maybeMenu = currentChestMenu();
        if (maybeMenu.isEmpty()) {
            return fail(NAME + ".withdrawal_menu_lost");
        }
        final MenuSkillFrame frame = maybeMenu.orElseThrow();
        if (frame.sampleSequence() <= withdrawalSampleSequence) {
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_CONFIRM_TICKS) {
                return fail(NAME + ".withdrawal_unconfirmed");
            }
            return SkillTickResult.running(false, true);
        }
        final Optional<MenuSlotSummary> sourceAfter = frame.menu().slots()
                .stream()
                .filter(slot -> slot.slot() == withdrawalSourceSlot)
                .findFirst();
        if (sourceAfter.isPresent()
                && sourceAfter.orElseThrow().count()
                    != withdrawalSourceCount - 1) {
            return fail(NAME + ".withdrawal_count_mismatch");
        }
        withdrawnWoodItems.add(pendingWithdrawnWoodItem);
        pendingWithdrawnWoodItem = "";
        return transition(context, Phase.CLOSE_CHEST);
    }

    private SkillTickResult advanceWithdrawalChest(
            final SkillContext context
    ) {
        withdrawalChestIndex++;
        withdrawalSourceSlot = -1;
        withdrawalSourceCount = -1;
        withdrawalSampleSequence = -1L;
        depositSampleSequence = -1L;
        chestOpenAttempts = 0;
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(NAME + ".body_unavailable");
        }
        if (withdrawalChestIndex < requiredChestCount) {
            expectedChestPosition = withdrawalChestPositions.get(
                    withdrawalChestIndex
            );
            beginScan(context, frame, Phase.FIND_CHEST);
            return SkillTickResult.running(true, true);
        }
        final Optional<String> family = withdrawnWoodItems.stream()
                .map(PrepareBasicCraftingSkill::plankRecipeFor)
                .flatMap(Optional::stream)
                .distinct()
                .filter(candidate -> withdrawnWoodItems.stream()
                        .map(PrepareBasicCraftingSkill::plankRecipeFor)
                        .flatMap(Optional::stream)
                        .filter(candidate::equals)
                        .count() >= 2L)
                .sorted()
                .findFirst();
        if (family.isEmpty()) {
            return fail(NAME + ".incompatible_withdrawn_wood_mix");
        }
        selectedPlankItemId = family.orElseThrow();
        selectedDoorItemId = doorRecipeFor(selectedPlankItemId);
        initialDoorCount = itemCount(frame, selectedDoorItemId);
        expectedChestPosition = null;
        return transition(context, Phase.PREPARE_WITHDRAWN_PLANKS);
    }

    private SkillTickResult prepareWithdrawnPlanks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (withdrawnPlankCraftIndex >= withdrawnWoodItems.size()) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final String source = withdrawnWoodItems.get(
                withdrawnPlankCraftIndex
        );
        final String recipeId = PrepareBasicCraftingSkill.plankRecipeFor(
                source
        ).orElseThrow();
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(recipeId, 1);
        if (!inventory.checkCraft(recipe).succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_RECIPE_WAIT_TICKS) {
                return fail(NAME + ".withdrawn_plank_recipe_unavailable");
            }
            return SkillTickResult.running(false, true);
        }
        final InventoryOperationResult crafted = inventory.craftOnce(recipe);
        if (!crafted.succeeded()) {
            return fail(NAME + ".withdrawn_plank_craft_failed");
        }
        withdrawnPlankCraftIndex++;
        nextScanTick = context.gameTick() + 3L;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult craftDoor(final SkillContext context) {
        final CraftRecipeParameters recipe = new CraftRecipeParameters(
                selectedDoorItemId,
                1
        );
        if (inventory.checkCraft(recipe).succeeded()
                && inventory.craftOnce(recipe).succeeded()) {
            return transition(context, Phase.CONFIRM_DOOR_ITEM);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_RECIPE_WAIT_TICKS) {
            return fail(NAME + ".door_recipe_unavailable");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult confirmDoorItem(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (itemCount(frame, selectedDoorItemId) > initialDoorCount
                || frame.mainHand().itemId().equals(selectedDoorItemId)
                    && frame.mainHand().count() > initialDoorCount) {
            return transition(context, Phase.CLOSE_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".door_craft_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult findDoorSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (expectedDoorPosition == null) {
            return fail(NAME + ".door_target_missing");
        }
        final BlockCoordinate supportPosition = new BlockCoordinate(
                expectedDoorPosition.x(),
                expectedDoorPosition.y() - 1,
                expectedDoorPosition.z()
        );
        final Optional<VisibleBlockFace> support = frame.visibleBlockFaces()
                .stream()
                .filter(face -> face.block().equals(supportPosition))
                .filter(face -> face.face().equals("up"))
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .filter(face -> doorClearanceObserved(frame))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        if (support.isPresent()
                && support.orElseThrow().distance()
                    <= MAXIMUM_PLACEMENT_DISTANCE) {
            selectedSupport = support.orElseThrow();
            return transition(context, Phase.EQUIP_DOOR);
        }
        /*
         * The cardinally adjacent cell is the ordinary player position for
         * using a door support.  Two cells outward needlessly crosses a
         * nearby one-block terrace and can put the exact target inside its
         * raised floor even though the adjacent low cell is both safer and
         * comfortably within vanilla reach.
         */
        final int standX = expectedDoorPosition.x()
                + doorOutwardX;
        final int standZ = expectedDoorPosition.z()
                + doorOutwardZ;
        final PerceptionVec3 stand = new PerceptionVec3(
                standX + 0.5,
                expectedDoorPosition.y(),
                standZ + 0.5
        );
        /*
         * Reach alone is not evidence that the top face is observable.  A
         * body 3-4 blocks from the support can legally reach it while the
         * first-person ray fan still sees only the two air cells above it.
         * Always take the explicit two-block-out stand until close to that
         * stand, then require a fresh top-face observation before placing.
         */
        if (stand.subtract(frame.position()).length()
                > FIXTURE_MOVE_ARRIVAL_RADIUS + 0.25) {
            return beginFixtureApproach(
                    context,
                    frame,
                    Fixture.DOOR_SUPPORT,
                    standX,
                    expectedDoorPosition.y() - 1,
                    standZ
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            return fail(NAME + ".door_support_not_observed_clear");
        }
        return aimAt(
                frame,
                new PerceptionVec3(
                        supportPosition.x() + 0.5,
                        supportPosition.y() + 0.95,
                        supportPosition.z() + 0.5
                )
        );
    }

    private boolean doorClearanceObserved(final CoreSkillFrame frame) {
        if (expectedDoorPosition == null) {
            return false;
        }
        final GridPos lower = new GridPos(
                expectedDoorPosition.x(),
                expectedDoorPosition.y(),
                expectedDoorPosition.z()
        );
        /*
         * Door placement needs fresh proof that both occupied cells are air,
         * but it does not need the stronger multi-ray body-traversal proof
         * used by pathfinding. Requiring traversal clearance for the upper
         * door cell made a visibly empty two-block opening impossible to use:
         * an upper air cell commonly has no player-sized occupancy fan of its
         * own. The ordinary use-on-block transaction remains authoritative
         * and will reject a changed or occupied target.
         */
        return observedFreshAir(frame, lower)
                && observedFreshAir(frame, lower.above());
    }

    private static boolean observedFreshAir(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel -> voxel.kind() == VoxelKind.AIR)
                .filter(voxel -> voxel.observationRevision()
                        == frame.navigation().revision())
                .isPresent();
    }

    private SkillTickResult equipDoor(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (frame.mainHand().itemId().equals(selectedDoorItemId)
                && frame.mainHand().count() > 0) {
            return transition(context, Phase.AIM_DOOR_SUPPORT);
        }
        if (itemCount(frame, selectedDoorItemId) <= 0) {
            return fail(NAME + ".door_item_missing");
        }
        final InventoryOperationResult equipped = inventory.equip(
                new EquipItemParameters(
                        selectedDoorItemId,
                        EquipmentTarget.MAINHAND
                )
        );
        return equipped.succeeded()
                ? SkillTickResult.running(true, true)
                : fail(NAME + ".door_equip_failed");
    }

    private SkillTickResult aimDoorSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && selectedSupport != null
                && sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            selectedSupport = crosshair.orElseThrow();
            return transition(context, Phase.PLACE_DOOR);
        }
        if (selectedSupport == null
                || context.gameTick() - phaseStartedAtTick
                    >= MAXIMUM_AIM_TICKS) {
            return transition(context, Phase.FIND_DOOR_SUPPORT);
        }
        return aimAt(frame, selectedSupport.hitPosition());
    }

    private SkillTickResult placeDoor(final SkillContext context) {
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isEmpty()
                || selectedSupport == null
                || !sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            return transition(context, Phase.AIM_DOOR_SUPPORT);
        }
        final ActionOutcome placed = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        return placed.accepted()
                ? transition(context, Phase.CONFIRM_DOOR)
                : fail(NAME + ".door_placement_rejected");
    }

    private SkillTickResult confirmDoor(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (expectedDoorPosition == null) {
            return fail(NAME + ".door_target_missing");
        }
        final BlockCoordinate upperDoor = new BlockCoordinate(
                expectedDoorPosition.x(),
                expectedDoorPosition.y() + 1,
                expectedDoorPosition.z()
        );
        final boolean visible = frame.visibleBlockFaces().stream()
                .filter(face -> face.block().equals(expectedDoorPosition)
                        || face.block().equals(upperDoor))
                .anyMatch(face -> face.blockTypeId().equals(
                        selectedDoorItemId
                ));
        if (visible) {
            return transition(context, Phase.FINISH);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".door_placement_unconfirmed");
        }
        return aimAt(frame, center(expectedDoorPosition));
    }

    private static String doorRecipeFor(final String plankItemId) {
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

    private SkillTickResult scan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase samePhase,
            final String failureStem
    ) {
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            return fail(NAME + "." + failureStem + "_not_visible");
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = scanTurns / SCAN_PITCHES.length;
        final int pitchIndex = scanTurns % SCAN_PITCHES.length;
        final float yaw = scanBaseYaw
                + SCAN_YAW_OFFSETS[
                        yawIndex % SCAN_YAW_OFFSETS.length
                ];
        final float pitch = SCAN_PITCHES[pitchIndex];
        if (!core.stop().accepted()
                || !core.look(
                        new LookIntent(yaw, pitch)
                ).accepted()) {
            return fail(NAME + ".scan_rejected");
        }
        scanTurns++;
        nextScanTick =
                context.gameTick() + SCAN_INTERVAL_TICKS;
        phase = samePhase;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult aimAt(
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
            return fail(NAME + ".aim_rejected");
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
        selectedTarget = null;
        selectedSupport = null;
    }

    private SkillTickResult complete(final SkillContext context) {
        if (!completionEvidenceRecorded) {
            try {
                completionEvidence.accept(context.goalRevision());
                completionEvidenceRecorded = true;
            } catch (RuntimeException rejectedEvidence) {
                return fail(NAME + ".completion_evidence_rejected");
            }
        }
        core.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(final String code) {
        final String stableCode = code.startsWith(NAME + ".")
                ? "foundation_workstations."
                        + code.substring(NAME.length() + 1)
                : code;
        return fail(SkillFailure.of(stableCode));
    }

    private SkillTickResult fail(
            final SkillFailure rejected
    ) {
        if (phase == Phase.RECLAIM_CHEST
                || phase == Phase.START_RECLAIM_CHEST) {
            interactions.abortMining();
        }
        core.stop();
        closeOpenMenuBestEffort();
        failure = rejected;
        phase = Phase.FAILED;
        return SkillTickResult.failed(rejected);
    }

    private String materialCheckpoint(
            final SkillContext context
    ) {
        if (phase != Phase.PREPARE_MATERIALS) {
            return "{}";
        }
        return materialPreparer.checkpoint(
                context,
                NoParameters.INSTANCE
        ).payload();
    }

    private void cancelMaterialPreparation(
            final SkillContext context
    ) {
        if (phase == Phase.PREPARE_MATERIALS) {
            materialPreparer.cancel(
                    context,
                    NoParameters.INSTANCE
            );
        }
    }

    private void cancelFixtureMovement(
            final SkillContext context
    ) {
        if (fixtureMovement != null
                && fixtureMovementParameters != null
                && context != null) {
            fixtureMovement.cancel(context, fixtureMovementParameters);
        }
        fixtureMovement = null;
        fixtureMovementParameters = null;
        movementTarget = null;
        movementResumePhase = null;
        fixtureMovementBestDistance = Double.POSITIVE_INFINITY;
        fixtureMovementLastProgressTick = -1L;
    }

    private void closeOpenMenuBestEffort() {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            inventory.closeThreeByThreeCraftingMenu();
            return;
        }
        currentChestMenu().ifPresent(frame ->
                menus.close(new CloseMenuParameters(binding(frame)))
        );
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private Optional<MenuSkillFrame> currentChestMenu() {
        return menuFrames.current()
                .filter(frame -> expectedPlayerId.equals(
                        frame.playerId()
                ))
                .filter(frame -> {
                    final String type = frame.menu().menuType();
                    final String clazz = frame.menu().menuClass();
                    return type.contains("generic_9x")
                            || clazz.contains("ChestMenu");
                });
    }

    private Optional<VisibleBlockFace> visibleFixture(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Fixture fixture
    ) {
        final Optional<VerifiedFixtureLocation> remembered =
                fixture == Fixture.TABLE
                        ? knownCraftingTable(context, frame)
                        : knownStorage(context, frame);
        return frame.visibleBlockFaces().stream()
                .filter(face -> isFixture(face, fixture))
                .filter(face -> fixture != Fixture.CHEST
                        || expectedChestPosition == null
                        || face.block().equals(expectedChestPosition))
                .filter(face -> remembered.isEmpty()
                        || fixture == Fixture.CHEST
                            && expectedChestPosition != null
                        || matches(
                                face.block(),
                                remembered.orElseThrow()
                        ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private boolean matchesExpectedFixture(
            final SkillContext context,
            final CoreSkillFrame frame,
            final VisibleBlockFace face,
            final Fixture fixture
    ) {
        if (fixture == Fixture.CHEST
                && expectedChestPosition != null) {
            return face.block().equals(expectedChestPosition);
        }
        final Optional<VerifiedFixtureLocation> remembered =
                fixture == Fixture.TABLE
                        ? knownCraftingTable(context, frame)
                        : knownStorage(context, frame);
        return remembered.isEmpty()
                || matches(
                        face.block(),
                        remembered.orElseThrow()
                );
    }

    private Optional<VisibleBlockFace> visibleExpectedChest(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> isFixture(face, Fixture.CHEST))
                .filter(face -> expectedChestPosition == null
                        || face.block().equals(
                                expectedChestPosition
                        ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<VisibleBlockFace> selectPlacementSupport(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.face().equals("up"))
                .filter(face ->
                        !rejectedSupports.contains(face.block()))
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .filter(face ->
                        !face.blockTypeId().equals(CRAFTING_TABLE)
                                && !face.blockTypeId()
                                    .equals("minecraft:furnace")
                                && !isFixture(
                                    face,
                                    Fixture.CHEST
                                ))
                .filter(face ->
                        face.distance() >= MINIMUM_PLACEMENT_DISTANCE
                                && face.distance()
                                    <= MAXIMUM_PLACEMENT_DISTANCE)
                .filter(face ->
                        hasObservedChestPlacementClearance(
                                frame,
                                face
                        ))
                .filter(face -> !distributeOwnedLogs
                        || placementIsSeparateChest(face))
                .filter(face -> {
                    final double centerX = face.block().x() + 0.5;
                    final double centerZ = face.block().z() + 0.5;
                    return Math.hypot(
                            centerX - frame.position().x(),
                            centerZ - frame.position().z()
                    ) >= MINIMUM_PLACEMENT_DISTANCE;
                })
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private boolean placementIsSeparateChest(
            final VisibleBlockFace support
    ) {
        final int x = support.block().x();
        final int y = support.block().y() + 1;
        final int z = support.block().z();
        return placedChestPositions.stream().noneMatch(existing ->
                existing.y() == y
                        && Math.abs(existing.x() - x)
                            + Math.abs(existing.z() - z) <= 1
        );
    }

    static boolean hasObservedChestPlacementClearance(
            final CoreSkillFrame frame,
            final VisibleBlockFace support
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(support, "support");
        final GridPos chest = new GridPos(
                support.block().x(),
                support.block().y() + 1,
                support.block().z()
        );
        return observedAirClearance(frame, chest)
                && observedAirClearance(frame, chest.above());
    }

    private static boolean observedAirClearance(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation()
                .voxelAt(position)
                .filter(voxel -> voxel.kind() == VoxelKind.AIR)
                .filter(NavigationEvidence::hasTraversalClearance)
                .isPresent();
    }

    private static boolean knownChestOpeningObstruction(
            final CoreSkillFrame frame,
            final BlockCoordinate chest
    ) {
        final GridPos above = new GridPos(
                chest.x(),
                chest.y() + 1,
                chest.z()
        );
        return frame.navigation()
                .voxelAt(above)
                .filter(voxel -> !voxel.kind().isPassable())
                .isPresent();
    }

    private boolean hasStorageEvidence(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return knownStorage(context, frame).isPresent()
                || hasVisibleStorage(frame);
    }

    static boolean hasVisibleStorage(
            final CoreSkillFrame frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return frame.visibleBlockFaces().stream()
                .anyMatch(face ->
                        isFixture(face, Fixture.CHEST));
    }

    private static boolean ownsChest(
            final CoreSkillFrame frame
    ) {
        return itemCount(frame, CHEST) > 0
                || frame.mainHand().itemId().equals(CHEST)
                    && frame.mainHand().count() > 0;
    }

    private void rejectPreviousSupport() {
        if (selectedSupport != null) {
            rejectedSupports.add(selectedSupport.block());
        }
        expectedChestPosition = null;
        selectedSupport = null;
        selectedTarget = null;
        chestOpenAttempts = 0;
    }

    private Optional<MenuSlotSummary> selectSurplusSource(
            final MenuSkillFrame frame
    ) {
        for (String itemId : SURPLUS_PRIORITY) {
            final Optional<MenuSlotSummary> priority =
                    frame.menu().slots().stream()
                            .filter(MenuSlotSummary::playerInventory)
                            .filter(MenuSlotSummary::mayPickup)
                            .filter(slot -> slot.count() > 0)
                            .filter(slot -> slot.itemId().equals(itemId))
                            .min(Comparator.comparingInt(
                                    MenuSlotSummary::slot
                            ));
            if (priority.isPresent()) {
                return priority;
            }
        }
        return frame.menu().slots().stream()
                .filter(MenuSlotSummary::playerInventory)
                .filter(MenuSlotSummary::mayPickup)
                .filter(slot -> isSafeSurplus(
                        slot.itemId(),
                        slot.count()
                ))
                .min(Comparator
                        .comparingInt(MenuSlotSummary::count)
                        .reversed()
                        .thenComparingInt(MenuSlotSummary::slot));
    }

    private static Optional<MenuSlotSummary> selectOwnedLogSource(
            final MenuSkillFrame frame
    ) {
        return frame.menu().slots().stream()
                .filter(MenuSlotSummary::playerInventory)
                .filter(MenuSlotSummary::mayPickup)
                .filter(slot -> slot.count() > 0)
                .filter(slot -> isRawLog(slot.itemId()))
                .min(Comparator.comparingInt(MenuSlotSummary::slot));
    }

    private static boolean isSafeSurplus(
            final String itemId,
            final int count
    ) {
        if (count < 2) {
            return false;
        }
        return itemId.equals("minecraft:cobblestone")
                || itemId.equals("minecraft:dirt")
                || itemId.endsWith("_log")
                || itemId.endsWith("_planks");
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

    private Optional<VerifiedFixtureLocation> knownCraftingTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return knownFixture(
                knownCraftingTable,
                context,
                frame
        );
    }

    private Optional<VerifiedFixtureLocation> knownStorage(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return knownFixture(knownStorage, context, frame);
    }

    private static Optional<VerifiedFixtureLocation> knownFixture(
            final LongFunction<Optional<VerifiedFixtureLocation>> source,
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        try {
            return Objects.requireNonNull(
                    source.apply(context.goalRevision()),
                    "known fixture result"
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
        return inventory.hasThreeByThreeCraftingMenu()
                || frame.visibleBlockFaces().stream().anyMatch(
                        face -> isFixture(face, Fixture.TABLE)
                )
                || knownCraftingTable(context, frame).isPresent();
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
                                    .isPresent()
                        )
                        .mapToInt(InventoryItemSummary::count)
                        .sum() * 4;
    }

    private int requiredChestPlanks() {
        return REQUIRED_CHEST_PLANKS * requiredChestCount;
    }

    private static int ownedRawLogCount(final CoreSkillFrame frame) {
        int count = frame.inventory().stream()
                .filter(item -> isRawLog(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
        if (isRawLog(frame.mainHand().itemId())) {
            count = Math.max(count, frame.mainHand().count());
        }
        return count;
    }

    private static boolean isRawLog(final String itemId) {
        return itemId.endsWith("_log")
                || itemId.endsWith("_stem")
                || itemId.endsWith("_hyphae");
    }

    private static int plankCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().endsWith("_planks"))
                .mapToInt(InventoryItemSummary::count)
                .sum();
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
            final Fixture fixture
    ) {
        return switch (fixture) {
            case TABLE -> face.blockTypeId().equals(CRAFTING_TABLE);
            case CHEST -> face.blockTypeId().equals(CHEST)
                    || face.blockTypeId().equals(
                            "minecraft:trapped_chest"
                    );
            case DOOR_SUPPORT -> false;
        };
    }

    private static Phase findPhase(final Fixture fixture) {
        return switch (fixture) {
            case TABLE -> Phase.FIND_TABLE;
            case CHEST -> Phase.FIND_CHEST;
            case DOOR_SUPPORT -> Phase.FIND_DOOR_SUPPORT;
        };
    }

    private static boolean matches(
            final BlockCoordinate position,
            final VerifiedFixtureLocation location
    ) {
        return position.x() == location.x()
                && position.y() == location.y()
                && position.z() == location.z();
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

    private static PerceptionVec3 center(
            final BlockCoordinate position
    ) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y() + 0.5,
                position.z() + 0.5
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

    private static SkillParameterResult<NoParameters> parseNone(
            final List<SkillArgument> arguments
    ) {
        return arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    private enum Fixture {
        TABLE("table"),
        CHEST("chest"),
        DOOR_SUPPORT("door_support");

        private final String failureStem;

        Fixture(final String failureStem) {
            this.failureStem = failureStem;
        }

        private String failureStem() {
            return failureStem;
        }
    }

    private enum Phase {
        IDLE,
        DISCOVER_CHESTS,
        PREPARE_MATERIALS,
        PREPARE_PLANKS,
        PREPARE_WITHDRAWN_PLANKS,
        FIND_TABLE,
        MOVE_TO_FIXTURE,
        AIM_TABLE,
        OPEN_TABLE,
        CONFIRM_TABLE_MENU,
        CRAFT_CHEST,
        CRAFT_DOOR,
        CONFIRM_DOOR_ITEM,
        CONFIRM_CHEST_ITEM,
        CLOSE_TABLE,
        EQUIP_CHEST,
        FIND_SUPPORT,
        AIM_SUPPORT,
        PLACE_CHEST,
        CONFIRM_CHEST,
        FIND_CHEST,
        AIM_CHEST,
        OPEN_CHEST,
        CONFIRM_CHEST_MENU,
        AIM_RECLAIM_CHEST,
        START_RECLAIM_CHEST,
        RECLAIM_CHEST,
        DEPOSIT_SURPLUS,
        WITHDRAW_LOG,
        CONFIRM_WITHDRAW_LOG,
        CLOSE_CHEST,
        FIND_DOOR_SUPPORT,
        EQUIP_DOOR,
        AIM_DOOR_SUPPORT,
        PLACE_DOOR,
        CONFIRM_DOOR,
        FINISH,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this != IDLE
                    && this != COMPLETED
                    && this != FAILED
                    && this != CANCELLED;
        }
    }
}
