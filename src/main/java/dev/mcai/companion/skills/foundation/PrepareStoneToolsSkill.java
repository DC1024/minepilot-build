package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.BlockCoordinate;
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
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.gathering.GatherVisibleBlockClusterParameters;
import dev.mcai.companion.skills.gathering.GatherVisibleBlockClusterSkill;
import dev.mcai.companion.skills.gathering.GatheringSkillPolicy;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Performs the first stone-tool upgrade as one bounded local transaction.
 *
 * <p>The language model decides to start this skill once. The local
 * controller then searches only with the body's rotating first-person view,
 * binds a freshly visible natural-stone face, delegates legal mining and
 * physical pickup to the production cluster gatherer, re-finds the visible
 * crafting table, and crafts through the ordinary vanilla menu. It never
 * scans a chunk, queries a hidden block, generates an item, or teleports.</p>
 */
public final class PrepareStoneToolsSkill implements Skill<NoParameters> {
    public static final String NAME = "prepare_stone_tools";

    private static final String STONE = "minecraft:stone";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String CRAFTING_TABLE =
            "minecraft:crafting_table";
    private static final String STICK = "minecraft:stick";
    private static final String STONE_PICKAXE =
            "minecraft:stone_pickaxe";
    private static final int REQUIRED_COBBLESTONE = 3;
    private static final int MAXIMUM_TICKS = 2_400;
    private static final int MAXIMUM_SCAN_TURNS = 24;
    private static final int SCAN_INTERVAL_TICKS = 2;
    private static final int MAXIMUM_AIM_TICKS = 80;
    private static final int MAXIMUM_MENU_WAIT_TICKS = 40;
    private static final int MAXIMUM_RECIPE_WAIT_TICKS = 80;
    private static final int MAXIMUM_SUPPORT_SCANS = 24;
    private static final int MAXIMUM_SUPPORT_AIM_TICKS = 80;
    private static final int MAXIMUM_TABLE_PLACEMENT_ATTEMPTS = 3;
    private static final int MAXIMUM_PLACED_CONFIRM_TICKS = 40;
    private static final int MAXIMUM_INVENTORY_CONFIRM_TICKS = 100;
    private static final int MAXIMUM_REMEMBERED_TABLE_AIM_TICKS = 20;
    private static final double RELIABLE_TABLE_INTERACTION_DISTANCE = 3.75;
    private static final double TABLE_APPROACH_RADIUS = 3.0;
    /*
     * Live telemetry (FIX9/FIX10) showed a full 360-degree scan of this
     * world's surface reporting visibleFaces=24 with stoneFaces=0, and an
     * offline read of the region file confirmed why: the stone layer begins
     * about five blocks below the grass, underneath leaf litter, grass and
     * dirt.  No heading can expose it to a first-person ray, so completing
     * the search by scanning can never succeed here.
     *
     * The obvious fallback -- mine the block underfoot -- is refused by
     * PlayerSupportBlockGuard, which returns WORLD_DENIED for the collision
     * surface currently carrying the body.  That guard is a fairness
     * property, not an obstacle to route around: its own contract says a
     * skill that needs the block must first walk onto another observed
     * support.  So the descent is a staircase, which is also what a careful
     * player digs: mine down the neighbouring column, step into the shaft,
     * and repeat.  Every block is still only ever acted on after the body
     * has genuinely looked at it, and the descent is bounded so a body on
     * bedrock or beside a shaft stops with a diagnostic.
     *
     * One step is exactly one block lower, and the neighbouring column is
     * mined from its own top downwards until its topmost remaining block
     * sits one below the body's feet.  Mining a fixed number of blocks
     * instead does not work on level ground: the next column always starts
     * at its original height, so a fixed two-block shaft stops descending
     * after the first step.  The number of blocks per shaft therefore grows
     * as the body sinks (one, then two, then three, ...), which is exactly
     * how deep a straight stair is dug by hand.
     */
    /*
     * Offline replay of this world's column profile (surface at y=68, stone
     * from y=63) settles in eleven mined blocks over four steps; a stone
     * layer three blocks deeper needs twenty-six. The bound is set above
     * that so the descent fails on an unexpected column rather than on a
     * slightly deeper overburden, while still refusing to dig through a
     * whole mountain.
     */
    static final int MAXIMUM_DIG_DOWN_BLOCKS = 48;
    static final int MAXIMUM_STAIR_DROP = 3;
    private static final int MAXIMUM_DIG_AIM_TICKS = 40;
    private static final int MAXIMUM_STAIR_ENTRY_TICKS = 160;
    static final double STAIR_REACH = 6.0;
    private static final double STAIR_ARRIVAL_RADIUS = 0.35;
    private static final double DIG_SEED_CLUSTER_RADIUS = 1.0;
    static final double STONE_BURST_RADIUS = 4.0;
    private static final String KEEP_CURRENT_HAND = "minecraft:air";
    /*
     * Fair perception already clips every block ray to 24 blocks. The
     * cluster gatherer can legally walk toward a retained visible seed before
     * mining it, so imposing the old five-block interaction radius here made
     * the companion report that no stone existed when a player could plainly
     * see an exposed outcrop several blocks away. This remains perception
     * bounded: hidden blocks and blocks outside the first-person ray budget
     * never enter the candidate list.
     */
    static final double MAXIMUM_VISIBLE_STONE_SEED_DISTANCE = 24.0;
    private static final Set<String> STONE_OR_BETTER_PICKAXES = Set.of(
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );
    private static final List<String> STONE_MINING_TOOLS = List.of(
            "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;
    private final GatherVisibleBlockClusterSkill gatherer;
    private final LongFunction<Optional<VerifiedFixtureLocation>>
            knownCraftingTable;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long nextScanTick = -1;
    private float scanBaseYaw;
    private int scanTurns;
    private long scanObservationRevision = -1L;
    private boolean awaitingScanObservation;
    private VisibleBlockFace selectedTable;
    private VisibleBlockFace selectedSupport;
    private BlockCoordinate expectedTablePosition;
    private int tablePlacementAttempts;
    private float supportScanBaseYaw;
    private int supportScans;
    private long nextSupportScanTick = -1;
    private long supportObservationRevision = -1L;
    private boolean awaitingSupportObservation;
    private GatherVisibleBlockClusterParameters gatheringParameters;
    private MoveToSkill tableMovement;
    private MoveToParameters tableMovementParameters;
    private boolean digGather;
    private int digDownBlocks;
    private boolean stairDirectionChosen;
    private int stairStepX;
    private int stairStepZ;
    private int stairColumnMined;
    private MoveToSkill stairMovement;
    private MoveToParameters stairMovementParameters;

    /*
     * Diagnostics only -- surfaced through checkpoint() so a failed
     * search can be told apart from a search that never had candidates.
     * A negative value means "not sampled yet".
     */
    private int diagnosticsVisibleFaces = -1;
    private int diagnosticsStoneFaces = -1;
    private int diagnosticsStoneFacesKept = -1;
    private int diagnosticsTableFaces = -1;
    /*
     * Stair-column descent diagnostics. When the neighbouring step column
     * offers nothing mineable the skill fails with {@code stair_column_empty},
     * but that bare code cannot tell whether the column was genuinely open,
     * occluded by perception sparseness, killed by the eye-height filter, or
     * simply beyond the stair reach. These three counters snapshot the last
     * per-tick observation of the chosen column so the terminal failure code
     * can disambiguate the real cause.
     */
    private int diagnosticsStairColumnFaces = -1;
    private int diagnosticsStairColumnEyes = -1;
    private int diagnosticsStairColumnReach = -1;

    public PrepareStoneToolsSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final ResourceInventorySource resourceInventory
    ) {
        this(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                inventory,
                resourceInventory,
                ignored -> Optional.empty()
        );
    }

    public PrepareStoneToolsSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final ResourceInventorySource resourceInventory,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownCraftingTable
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
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return PrepareStoneToolsSkill::parseNone;
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
        if (!safeEnvironment(frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_pose"
            ));
        }
        if (ownsStoneOrBetterPickaxe(frame)) {
            return Optional.empty();
        }
        if (ownedCount(frame, COBBLESTONE) < REQUIRED_COBBLESTONE
                && selectMiningTool(frame).isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".pickaxe_required"
            ));
        }
        if (ownedCount(frame, STICK) < 2
                && plankCount(frame) < 2) {
            return Optional.of(SkillFailure.of(
                    NAME + ".handle_material_missing"
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
                        "Companion body changed before stone preparation"
                )
        );
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        selectedTable = null;
        selectedSupport = null;
        expectedTablePosition = null;
        tablePlacementAttempts = 0;
        supportScans = 0;
        gatheringParameters = null;
        tableMovement = null;
        tableMovementParameters = null;
        digGather = false;
        digDownBlocks = 0;
        stairDirectionChosen = false;
        stairStepX = 0;
        stairStepZ = 0;
        stairColumnMined = 0;
        stairMovement = null;
        stairMovementParameters = null;
        beginScan(
                context,
                frame,
                ownedCount(frame, STICK) < 2
                        ? Phase.PREPARE_STICKS
                        : ownedCount(frame, COBBLESTONE)
                            >= REQUIRED_COBBLESTONE
                                ? Phase.FIND_TABLE
                                : Phase.FIND_STONE
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
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"scanTurns\":%d,"
                                + "\"elapsedTicks\":%d,"
                                + "\"gathering\":%s,"
                                + "\"digBlocks\":%d,"
                                + "\"visibleFaces\":%d,"
                                + "\"stoneFaces\":%d,"
                                + "\"stoneFacesKept\":%d,"
                                + "\"tableFaces\":%d,"
                                + "\"digGather\":%s,"
                                + "\"stairColumnMined\":%d,"
                                + "\"stairStepX\":%d,"
                                + "\"stairStepZ\":%d,"
                                + "\"stairColumnFaces\":%d,"
                                + "\"stairColumnEyes\":%d,"
                                + "\"stairColumnReach\":%d,"
                                + "\"gather\":%s}",
                        phase.name(),
                        scanTurns,
                        Math.max(0L, context.gameTick() - startedAtTick),
                        phase == Phase.GATHER_STONE,
                        digDownBlocks,
                        diagnosticsVisibleFaces,
                        diagnosticsStoneFaces,
                        diagnosticsStoneFacesKept,
                        diagnosticsTableFaces,
                        digGather,
                        stairColumnMined,
                        stairStepX,
                        stairStepZ,
                        diagnosticsStairColumnFaces,
                        diagnosticsStairColumnEyes,
                        diagnosticsStairColumnReach,
                        gatherCheckpointPayload(context)
                )
        );
    }

    /**
     * Inlines the cluster gatherer's own checkpoint while it is driving a
     * stone burst. The gatherer already publishes its internal phase
     * (APPROACHING / AIMING / MINING / COLLECTING / SCANNING) plus the seed,
     * the current target, the number of blocks mined and the approach probe
     * counters, but the parent row used to reduce all of that to a single
     * {@code "gathering":true} boolean, which made a stall inside the child
     * completely invisible. Returning the child payload verbatim keeps the
     * persisted {@code task_checkpoint} row self-contained.
     */
    private String gatherCheckpointPayload(final SkillContext context) {
        if (phase != Phase.GATHER_STONE || gatheringParameters == null) {
            return "null";
        }
        try {
            return gatherer.checkpoint(context, gatheringParameters)
                    .payload();
        } catch (RuntimeException exception) {
            return "null";
        }
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (phase == Phase.GATHER_STONE
                && gatheringParameters != null) {
            gatherer.cancel(context, gatheringParameters);
        }
        cancelTableMovement(context);
        cancelStairMovement(context);
        core.stop();
        closeCraftingMenuIfOpen();
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
            return fail(NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(NAME + ".body_unavailable");
        }
        if (!safeEnvironment(frame)) {
            return fail(NAME + ".unsafe_pose");
        }
        if (!frame.onGround()
                && phase != Phase.GATHER_STONE
                && phase != Phase.MOVE_TO_TABLE
                && phase != Phase.DIG_DOWN
                && phase != Phase.ENTER_STAIR) {
            core.stop();
            return SkillTickResult.running(false, true);
        }
        if (ownsStoneOrBetterPickaxe(frame)) {
            if (inventory.hasThreeByThreeCraftingMenu()) {
                final InventoryOperationResult closed =
                        inventory.closeThreeByThreeCraftingMenu();
                if (!closed.succeeded()) {
                    return fail(NAME + ".table_menu_close_failed");
                }
                return SkillTickResult.running(true, true);
            }
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }

        return switch (phase) {
            case PREPARE_STICKS -> prepareSticks(context, frame);
            case FIND_STONE -> findStone(context, frame);
            case GATHER_STONE -> gatherStone(context);
            case CONFIRM_COBBLESTONE ->
                    confirmCobblestone(context, frame);
            case FIND_TABLE -> findTable(context, frame);
            case FIND_SUPPORT -> findSupport(context, frame);
            case AIM_SUPPORT -> aimSupport(context, frame);
            case PLACE_TABLE -> placeTable(context, frame);
            case CONFIRM_PLACED -> confirmPlaced(context, frame);
            case MOVE_TO_TABLE -> moveToTable(context, frame);
            case DIG_DOWN -> digDown(context, frame);
            case ENTER_STAIR -> enterStair(context, frame);
            case AIM_TABLE -> aimTable(context, frame);
            case OPEN_TABLE -> openTable(context);
            case CONFIRM_MENU -> confirmMenu(context);
            case CRAFT_STONE_PICKAXE ->
                    craftStonePickaxe(context);
            case CONFIRM_STONE_PICKAXE ->
                    confirmStonePickaxe(context, frame);
            default -> fail(NAME + ".invalid_state");
        };
    }

    private SkillTickResult prepareSticks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownedCount(frame, STICK) >= 2) {
            return beginNextFoundationPhase(context, frame);
        }
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(STICK, 1);
        if (inventory.checkCraft(recipe).succeeded()
                && inventory.craftOnce(recipe).succeeded()) {
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                < MAXIMUM_RECIPE_WAIT_TICKS) {
            return SkillTickResult.running(false, true);
        }
        return fail(NAME + ".stick_recipe_unavailable");
    }

    private SkillTickResult beginNextFoundationPhase(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownedCount(frame, COBBLESTONE) >= REQUIRED_COBBLESTONE) {
            beginScan(context, frame, Phase.FIND_TABLE);
        } else {
            beginScan(context, frame, Phase.FIND_STONE);
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult findStone(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<InteractionSkillFrame> maybeInteraction =
                ownedInteractionFrame(frame);
        if (maybeInteraction.isPresent()) {
            final InteractionSkillFrame interaction =
                    maybeInteraction.orElseThrow();
            final BlockCoordinate feet = feetOf(frame);
            recordStoneSearchDiagnostics(
                    feet,
                    interaction.visibleBlockFaces()
            );
            final Optional<VisibleBlockFace> stone =
                    selectNaturalStone(
                            feet,
                            interaction.visibleBlockFaces()
                    );
            if (stone.isPresent()) {
                final VisibleBlockFace visible = stone.orElseThrow();
                final String tool = selectMiningTool(frame)
                        .orElse(null);
                if (tool == null) {
                    return fail(NAME + ".pickaxe_required");
                }
                gatheringParameters =
                        new GatherVisibleBlockClusterParameters(
                                interaction.dimension(),
                                new ObservedBlockTarget(
                                        interaction
                                            .observationRevision(),
                                        visible.block().x(),
                                        visible.block().y(),
                                        visible.block().z(),
                                        BlockFace.valueOf(
                                                visible.face()
                                                    .toUpperCase(
                                                        Locale.ROOT
                                                    )
                                        )
                                ),
                                STONE,
                                REQUIRED_COBBLESTONE,
                                STONE_BURST_RADIUS,
                                tool
                        );
                final Optional<SkillFailure> rejected =
                        gatherer.preconditions(
                                context,
                                gatheringParameters
                        );
                if (rejected.isEmpty()) {
                    gatherer.start(context, gatheringParameters);
                    phase = Phase.GATHER_STONE;
                    phaseStartedAtTick = context.gameTick();
                    return SkillTickResult.running(true, true);
                }
            }
        }
        return scan(context, frame, Phase.FIND_STONE);
    }

    /**
     * Arms the staircase after a full heading sweep found no naturally
     * exposed stone.
     *
     * <p>Nothing is mined here. The descent direction is fixed once so the
     * staircase keeps heading the same way, and the next ticks turn the
     * body's own view onto the neighbouring column so the face is a real
     * first-person observation before any action is bound.</p>
     */
    private SkillTickResult beginDigDown(final SkillContext context) {
        stairDirectionChosen = false;
        stairStepX = 0;
        stairStepZ = 0;
        stairColumnMined = 0;
        return transition(context, Phase.DIG_DOWN);
    }

    private SkillTickResult digDown(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownedCount(frame, COBBLESTONE) >= REQUIRED_COBBLESTONE) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (digDownBlocks >= MAXIMUM_DIG_DOWN_BLOCKS) {
            return fail(NAME + ".stone_not_reached");
        }
        final Optional<InteractionSkillFrame> maybeInteraction =
                ownedInteractionFrame(frame);
        if (maybeInteraction.isEmpty()) {
            return SkillTickResult.running(false, true);
        }
        final InteractionSkillFrame interaction =
                maybeInteraction.orElseThrow();
        final BlockCoordinate feet = feetOf(frame);
        if (!stairDirectionChosen) {
            chooseStairDirection(frame);
        }
        recordStoneSearchDiagnostics(
                feet,
                interaction.visibleBlockFaces()
        );
        /*
         * The descent exists to expose stone, not to reach bedrock. The
         * moment any genuinely observed stone face is on offer it is mined
         * with the ordinary stone wiring, exactly as FIND_STONE would, so
         * the staircase never walks past the layer it was digging towards.
         * This fires as soon as the blocks above the stone have been removed
         * from the step column, which happens several blocks before the
         * body's own feet reach that depth.
         */
        final Optional<String> tool = selectMiningTool(frame);
        final Optional<VisibleBlockFace> stone = selectNaturalStone(
                feet,
                interaction.visibleBlockFaces()
        );
        if (stone.isPresent() && tool.isPresent()) {
            return beginDescentGather(
                    context,
                    interaction,
                    stone.orElseThrow(),
                    REQUIRED_COBBLESTONE,
                    STONE_BURST_RADIUS,
                    tool.orElseThrow()
            );
        }
        /*
         * The shaft is open once the neighbouring column's topmost remaining
         * block sits one below the body: stepping into it drops exactly one
         * block, which is the shallowest step a stair can take and the only
         * one that keeps the next column reachable from the new floor.
         */
        final int stepTop = feet.y() - 1;
        recordStairColumnDiagnostics(
                feet,
                stairStepX,
                stairStepZ,
                frame.eyePosition().y(),
                interaction.visibleBlockFaces()
        );
        final Optional<VisibleBlockFace> next = selectStairBlock(
                feet,
                stairStepX,
                stairStepZ,
                frame.eyePosition().y(),
                interaction.visibleBlockFaces()
        );
        if (next.isPresent()) {
            final VisibleBlockFace face = next.orElseThrow();
            if (face.block().y() >= stepTop) {
                return beginStairDig(
                        context,
                        frame,
                        interaction,
                        face
                );
            }
            /*
             * The column is already open below the step. A gap far deeper
             * than one step is a ledge, not a stair: walking into it would
             * be falling, and a Hardcore body does not fall into a hole on
             * purpose. Stop with a diagnostic instead.
             */
            if (stepTop - face.block().y() > MAXIMUM_STAIR_DROP) {
                return fail(NAME + ".stair_step_too_deep");
            }
            return beginStairEntry(context, frame);
        }
        /*
         * Nothing left to see in the neighbouring column: it is open at
         * least as deep as the step. A column that never offered a single
         * block is not walked into blind, because that is a ledge, not a
         * staircase.
         */
        if (stairColumnMined > 0) {
            return beginStairEntry(context, frame);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_DIG_AIM_TICKS) {
            return fail(stairColumnEmptyCode(
                    diagnosticsStairColumnFaces,
                    diagnosticsStairColumnEyes,
                    diagnosticsStairColumnReach
            ));
        }
        /*
         * Steeply down at the middle of the next column's step block. The
         * block rays span 100 by 70 degrees around the look direction, and
         * the near ground one block ahead sits about 58 degrees below the
         * eye, so a level aim would frame the horizon and leave the step
         * column itself outside the vertical budget entirely. From here the
         * fan still covers that column's top face and the side wall under
         * it, which is everything the descent ever needs to mine.
         */
        return aimAt(
                frame,
                new PerceptionVec3(
                        feet.x() + stairStepX + 0.5,
                        feet.y() - 0.5,
                        feet.z() + stairStepZ + 0.5
                )
        );
    }

    private void chooseStairDirection(final CoreSkillFrame frame) {
        final double radians = Math.toRadians(yaw(frame.lookDirection()));
        final double forwardX = -Math.sin(radians);
        final double forwardZ = Math.cos(radians);
        if (Math.abs(forwardX) >= Math.abs(forwardZ)) {
            stairStepX = forwardX >= 0.0 ? 1 : -1;
            stairStepZ = 0;
        } else {
            stairStepX = 0;
            stairStepZ = forwardZ >= 0.0 ? 1 : -1;
        }
        stairDirectionChosen = true;
    }

    /*
     * Binds one genuinely observed face and hands it to the production
     * cluster gatherer. The seed carries the type the ray actually reported,
     * so nothing here ever guesses what it is digging, and the current hand
     * is only kept when the caller has no better tool to offer.
     *
     * Everything mined during the descent is routed through the descent
     * branch of gatherStone(), including stone, because the cobblestone
     * count is re-read at the top of digDown: that keeps one integration
     * point instead of two competing completion paths.
     */
    private SkillTickResult beginDescentGather(
            final SkillContext context,
            final InteractionSkillFrame interaction,
            final VisibleBlockFace face,
            final int maxBlocks,
            final double clusterRadius,
            final String toolItemId
    ) {
        gatheringParameters = new GatherVisibleBlockClusterParameters(
                interaction.dimension(),
                new ObservedBlockTarget(
                        interaction.observationRevision(),
                        face.block().x(),
                        face.block().y(),
                        face.block().z(),
                        BlockFace.valueOf(
                                face.face().toUpperCase(Locale.ROOT)
                        )
                ),
                face.blockTypeId(),
                maxBlocks,
                clusterRadius,
                toolItemId
        );
        final Optional<SkillFailure> rejected = gatherer.preconditions(
                context,
                gatheringParameters
        );
        if (rejected.isPresent()) {
            /*
             * Diagnostic propagation (P19): the cluster gatherer swallows the
             * real cause into one of its own failure codes, so surface it here
             * instead of collapsing everything into a bare "stair_block_rejected".
             * The terminal code becomes e.g.
             * "prepare_stone_tools.stair_block_rejected.danger_detected" while
             * still containing the "stair_block_rejected" token for monitors.
             */
            return fail(stairBlockRejectedCode(rejected.get()));
        }
        gatherer.start(context, gatheringParameters);
        digGather = true;
        phase = Phase.GATHER_STONE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    /*
     * One step, one block. The best available mining tool is equipped even
     * for the soil and leaf litter above the stone, because the staircase is
     * expected to reach the stone layer: a bare hand on stone yields no
     * cobblestone and would make the whole descent pointless. Equipping one
     * tool for every step is also cheaper than swapping back and forth
     * between a shovel and a pickaxe.
     */
    private SkillTickResult beginStairDig(
            final SkillContext context,
            final CoreSkillFrame frame,
            final InteractionSkillFrame interaction,
            final VisibleBlockFace next
    ) {
        final SkillTickResult result = beginDescentGather(
                context,
                interaction,
                next,
                1,
                DIG_SEED_CLUSTER_RADIUS,
                selectMiningTool(frame).orElse(KEEP_CURRENT_HAND)
        );
        if (phase == Phase.GATHER_STONE) {
            digDownBlocks++;
            stairColumnMined++;
        }
        return result;
    }

    private void planStairEntry(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelStairMovement(context);
        final BlockCoordinate feet = feetOf(frame);
        stairMovementParameters = new MoveToParameters(
                frame.dimension(),
                feet.x() + stairStepX + 0.5,
                frame.position().y(),
                feet.z() + stairStepZ + 0.5,
                STAIR_ARRIVAL_RADIUS
        );
        stairMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected =
                stairMovement.preconditions(
                        context,
                        stairMovementParameters
                );
        if (rejected.isPresent()) {
            stairMovement = null;
            stairMovementParameters = null;
            fail(NAME + ".stair_entry_rejected");
            return;
        }
        stairMovement.start(context, stairMovementParameters);
        stairColumnMined = 0;
        phase = Phase.ENTER_STAIR;
        phaseStartedAtTick = context.gameTick();
    }

    private SkillTickResult enterStair(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (stairMovement == null
                || stairMovementParameters == null) {
            return fail(NAME + ".stair_binding_missing");
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_STAIR_ENTRY_TICKS) {
            cancelStairMovement(context);
            return fail(NAME + ".stair_entry_stalled");
        }
        final SkillTickResult movement = stairMovement.tick(
                context,
                stairMovementParameters
        );
        if (movement.status() == SkillTickResult.Status.FAILED) {
            stairMovement = null;
            stairMovementParameters = null;
            return fail(NAME + ".stair_entry_failed");
        }
        if (movement.status() == SkillTickResult.Status.COMPLETED) {
            stairMovement = null;
            stairMovementParameters = null;
            return transition(context, Phase.DIG_DOWN);
        }
        return SkillTickResult.running(
                movement.madeProgress(),
                movement.safeCheckpoint()
        );
    }

    private SkillTickResult beginStairEntry(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        planStairEntry(context, frame);
        if (phase != Phase.ENTER_STAIR) {
            return SkillTickResult.failed(
                    Objects.requireNonNull(failure)
            );
        }
        return SkillTickResult.running(true, true);
    }

    private void cancelStairMovement(final SkillContext context) {
        if (stairMovement != null && stairMovementParameters != null) {
            stairMovement.cancel(context, stairMovementParameters);
        }
        stairMovement = null;
        stairMovementParameters = null;
    }

    private SkillTickResult gatherStone(final SkillContext context) {
        if (gatheringParameters == null) {
            return fail(NAME + ".gather_binding_missing");
        }
        final SkillTickResult child = gatherer.tick(
                context,
                gatheringParameters
        );
        if (child.status() == SkillTickResult.Status.FAILED) {
            return fail(NAME + ".stone_gather_failed");
        }
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            if (digGather) {
                /*
                 * A descent block yields soil, not cobblestone, so it must
                 * not enter the cobblestone confirmation window. Resume the
                 * same staircase instead: it stops on its own once the
                 * ordinary stone sweep or the cobblestone count is met.
                 */
                digGather = false;
                return transition(context, Phase.DIG_DOWN);
            }
            phase = Phase.CONFIRM_COBBLESTONE;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                child.madeProgress(),
                child.safeCheckpoint()
        );
    }

    private SkillTickResult confirmCobblestone(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownedCount(frame, COBBLESTONE) >= REQUIRED_COBBLESTONE) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_INVENTORY_CONFIRM_TICKS) {
            return fail(NAME + ".cobblestone_not_collected");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult findTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, Phase.CRAFT_STONE_PICKAXE);
        }
        /*
         * A remembered-table aim is confirmed by the high-frequency
         * crosshair ray before the broader semantic face sample necessarily
         * refreshes.  Treat that present ray hit as the required visual
         * re-verification instead of continuing to scan past a table that is
         * already under the companion's crosshair.
         */
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(
                                PrepareStoneToolsSkill::isCraftingTable
                        );
        if (crosshair.isPresent()) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        diagnosticsVisibleFaces = frame.visibleBlockFaces().size();
        diagnosticsTableFaces = (int) frame.visibleBlockFaces().stream()
                .filter(PrepareStoneToolsSkill::isCraftingTable)
                .count();
        final Optional<VisibleBlockFace> table =
                visibleTable(frame);
        if (table.isPresent()) {
            final VisibleBlockFace visible = table.orElseThrow();
            if (visible.distance()
                    > RELIABLE_TABLE_INTERACTION_DISTANCE) {
                return beginTableApproach(
                        context,
                        frame,
                        visible.block().x(),
                        visible.block().y(),
                        visible.block().z()
                );
            }
            selectedTable = visible;
            return transition(context, Phase.AIM_TABLE);
        }
        /*
         * This exact location was learned only when this companion
         * previously opened the table through a vanilla interaction. Aim at
         * that bounded spatial memory first, but still require the current
         * first-person sampler to see and identify the table before opening
         * it. A destroyed or moved table therefore cannot be used from stale
         * memory.
         */
        final Optional<PerceptionVec3> remembered =
                rememberedTableTarget(context, frame);
        if (remembered.isPresent()
                && remembered.orElseThrow()
                    .subtract(frame.eyePosition())
                    .length()
                    > RELIABLE_TABLE_INTERACTION_DISTANCE) {
            final PerceptionVec3 target = remembered.orElseThrow();
            return beginTableApproach(
                    context,
                    frame,
                    (int) Math.floor(target.x()),
                    (int) Math.floor(target.y()),
                    (int) Math.floor(target.z())
            );
        }
        if (remembered.isPresent()
                && context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_REMEMBERED_TABLE_AIM_TICKS) {
            return aimAt(frame, remembered.orElseThrow());
        }
        return scan(context, frame, Phase.FIND_TABLE);
    }

    private SkillTickResult beginTableApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final int tableX,
            final int tableY,
            final int tableZ
    ) {
        cancelTableMovement(context);
        tableMovementParameters = new MoveToParameters(
                frame.dimension(),
                tableX + 0.5,
                frame.position().y(),
                tableZ + 0.5,
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
            tableMovement = null;
            tableMovementParameters = null;
            return fail(NAME + ".table_approach_rejected");
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
            return fail(NAME + ".table_approach_binding_missing");
        }
        final SkillTickResult movement = tableMovement.tick(
                context,
                tableMovementParameters
        );
        if (movement.status() == SkillTickResult.Status.FAILED) {
            tableMovement = null;
            tableMovementParameters = null;
            return fail(NAME + ".table_approach_failed");
        }
        if (movement.status() == SkillTickResult.Status.COMPLETED) {
            tableMovement = null;
            tableMovementParameters = null;
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                movement.madeProgress(),
                movement.safeCheckpoint()
        );
    }

    private Optional<PerceptionVec3> rememberedTableTarget(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VerifiedFixtureLocation> remembered;
        try {
            remembered = Objects.requireNonNull(
                    knownCraftingTable.apply(context.goalRevision()),
                    "known crafting table result"
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

    private SkillTickResult aimTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedTable == null) {
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && isCraftingTable(crosshair.orElseThrow())) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            selectedTable = null;
            beginScan(context, frame, Phase.FIND_TABLE);
            return SkillTickResult.running(true, true);
        }
        return aimAt(frame, selectedTable.hitPosition());
    }

    private SkillTickResult openTable(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, Phase.CRAFT_STONE_PICKAXE);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareStoneToolsSkill::isCraftingTable);
        if (crosshair.isEmpty()) {
            return transition(context, Phase.FIND_TABLE);
        }
        final ActionOutcome outcome = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        if (!outcome.accepted()) {
            return fail(NAME + ".table_open_rejected");
        }
        return transition(context, Phase.CONFIRM_MENU);
    }

    private SkillTickResult confirmMenu(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, Phase.CRAFT_STONE_PICKAXE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_MENU_WAIT_TICKS) {
            return fail(NAME + ".table_menu_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult craftStonePickaxe(
            final SkillContext context
    ) {
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(STONE_PICKAXE, 1);
        if (!inventory.checkCraft(recipe).succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_RECIPE_WAIT_TICKS) {
                return SkillTickResult.running(false, true);
            }
            return fail(NAME + ".stone_pickaxe_recipe_unavailable");
        }
        if (!inventory.craftOnce(recipe).succeeded()) {
            return fail(NAME + ".stone_pickaxe_craft_failed");
        }
        return transition(context, Phase.CONFIRM_STONE_PICKAXE);
    }

    private SkillTickResult confirmStonePickaxe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (ownsStoneOrBetterPickaxe(frame)) {
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_INVENTORY_CONFIRM_TICKS) {
            return fail(NAME + ".stone_pickaxe_not_observed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult beginFindSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        phase = Phase.FIND_SUPPORT;
        phaseStartedAtTick = context.gameTick();
        nextSupportScanTick = context.gameTick();
        supportScans = 0;
        supportObservationRevision = frame.observationRevision();
        awaitingSupportObservation = false;
        supportScanBaseYaw = yaw(frame.lookDirection());
        selectedSupport = null;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult findSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> support =
                PrepareBasicCraftingSkill.selectPlacementSupport(
                        frame,
                        Set.of()
                );
        if (support.isPresent()) {
            selectedSupport = support.orElseThrow();
            return transition(context, Phase.AIM_SUPPORT);
        }
        if (supportScans >= MAXIMUM_SUPPORT_SCANS) {
            return fail(NAME + ".no_table_support_visible");
        }
        if (awaitingSupportObservation
                && frame.observationRevision()
                    <= supportObservationRevision) {
            return SkillTickResult.running(false, true);
        }
        if (awaitingSupportObservation) {
            awaitingSupportObservation = false;
        }
        if (context.gameTick() < nextSupportScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = supportScans / 3;
        final int pitchIndex = supportScans % 3;
        final float pitch = switch (pitchIndex) {
            case 1 -> -25.0F;
            case 2 -> 25.0F;
            default -> 0.0F;
        };
        final float yaw = wrapDegrees(
                supportScanBaseYaw + yawIndex * 45.0F
        );
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, pitch)).accepted()) {
            return fail(NAME + ".support_scan_rejected");
        }
        supportScans++;
        supportObservationRevision = frame.observationRevision();
        awaitingSupportObservation = true;
        nextSupportScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult aimSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedSupport == null) {
            return beginFindSupport(context, frame);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            return transition(context, Phase.PLACE_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_SUPPORT_AIM_TICKS) {
            selectedSupport = null;
            return beginFindSupport(context, frame);
        }
        return aimAt(frame, selectedSupport.hitPosition());
    }

    private SkillTickResult placeTable(
            final SkillContext context,
            final CoreSkillFrame frame
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
        if (!frame.mainHand().itemId().equals(CRAFTING_TABLE)
                || frame.mainHand().count() <= 0) {
            final InventoryOperationResult equipped = inventory.equip(
                    new EquipItemParameters(
                            CRAFTING_TABLE,
                            EquipmentTarget.MAINHAND
                    )
            );
            if (!equipped.succeeded()) {
                return fail(NAME + ".table_equip_failed");
            }
            return SkillTickResult.running(true, true);
        }
        final VisibleBlockFace actual = crosshair.orElseThrow();
        final ActionOutcome outcome = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(actual)
        );
        tablePlacementAttempts++;
        if (!outcome.accepted()) {
            return fail(NAME + ".table_place_rejected");
        }
        expectedTablePosition = new BlockCoordinate(
                actual.block().x(),
                actual.block().y() + 1,
                actual.block().z()
        );
        selectedSupport = null;
        selectedTable = null;
        return transition(context, Phase.CONFIRM_PLACED);
    }

    private SkillTickResult confirmPlaced(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> table = visibleTable(frame);
        if (table.isPresent()) {
            selectedTable = table.orElseThrow();
            return transition(context, Phase.FIND_TABLE);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareStoneToolsSkill::isCraftingTable);
        if (crosshair.isPresent()) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_PLACED_CONFIRM_TICKS) {
            if (hasOwnedItem(frame, CRAFTING_TABLE)
                    && tablePlacementAttempts
                        < MAXIMUM_TABLE_PLACEMENT_ATTEMPTS) {
                return beginFindSupport(context, frame);
            }
            return fail(NAME + ".table_placement_unconfirmed");
        }
        if (expectedTablePosition != null) {
            return aimAt(
                    frame,
                    new PerceptionVec3(
                            expectedTablePosition.x() + 0.5,
                            expectedTablePosition.y() + 0.5,
                            expectedTablePosition.z() + 0.5
                    )
            );
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult placeOwnedTableOrFail(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (hasOwnedItem(frame, CRAFTING_TABLE)) {
            return beginFindSupport(context, frame);
        }
        return fail(NAME + ".visible_table_not_found");
    }

    private static boolean sameBlockAndFace(
            final VisibleBlockFace first,
            final VisibleBlockFace second
    ) {
        return first.block().equals(second.block())
                && first.face().equals(second.face());
    }

    private SkillTickResult scan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final Phase scanningPhase
    ) {
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            /*
             * A completed heading sweep is the whole of what re-aiming can
             * prove. Refusing to act past it is only honest when a target
             * was found; otherwise the companion does what a player does and
             * digs down instead of reporting that stone does not exist.
             */
            return scanningPhase == Phase.FIND_STONE
                    ? beginDigDown(context)
                    : placeOwnedTableOrFail(context, frame);
        }
        if (awaitingScanObservation
                && frame.observationRevision()
                    <= scanObservationRevision) {
            return SkillTickResult.running(false, true);
        }
        if (awaitingScanObservation) {
            awaitingScanObservation = false;
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = scanTurns / 3;
        final int pitchIndex = scanTurns % 3;
        final float pitch = switch (pitchIndex) {
            case 1 -> -25.0F;
            case 2 -> 25.0F;
            default -> 0.0F;
        };
        final float yaw = wrapDegrees(scanBaseYaw + yawIndex * 45.0F);
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, pitch)).accepted()) {
            return fail(NAME + ".scan_rejected");
        }
        scanTurns++;
        scanObservationRevision = frame.observationRevision();
        awaitingScanObservation = true;
        nextScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
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
        scanObservationRevision = frame.observationRevision();
        awaitingScanObservation = false;
        scanBaseYaw = yaw(frame.lookDirection());
        selectedTable = null;
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

    private SkillTickResult fail(final String code) {
        core.stop();
        closeCraftingMenuIfOpen();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private void cancelTableMovement(final SkillContext context) {
        if (tableMovement != null
                && tableMovementParameters != null) {
            tableMovement.cancel(
                    context,
                    tableMovementParameters
            );
        }
        tableMovement = null;
        tableMovementParameters = null;
    }

    private void closeCraftingMenuIfOpen() {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            inventory.closeThreeByThreeCraftingMenu();
        }
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private Optional<InteractionSkillFrame> ownedInteractionFrame(
            final CoreSkillFrame frame
    ) {
        return interactionFrames.current().filter(interaction ->
                expectedPlayerId.equals(interaction.playerId())
                        && frame.dimension().equals(
                                interaction.dimension()
                        )
                        && frame.observationRevision()
                            == interaction.observationRevision()
        );
    }

    /**
     * Picks the nearest naturally visible stone face worth mining.
     *
     * <p>Only the single block directly underfoot is excluded, because
     * mining it would drop the companion. Everything else is fair game --
     * including stone below the feet, which is exactly what the downward
     * scan tilts exist to find. Excluding all stone below the feet (as this
     * selector previously did) contradicted the scan itself and made a body
     * standing on open grass report {@code visible_stone_not_found} until
     * the scan budget ran out.</p>
     */
    static Optional<VisibleBlockFace> selectNaturalStone(
            final BlockCoordinate feet,
            final List<VisibleBlockFace> visibleBlockFaces
    ) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(visibleBlockFaces, "visibleBlockFaces");
        final int supportY = feet.y() - 1;
        return visibleBlockFaces.stream()
                .filter(face -> face.blockTypeId().equals(STONE))
                .filter(face -> !isOwnSupportBlock(
                        face.block(),
                        feet,
                        supportY
                ))
                .filter(face -> face.distance()
                        <= MAXIMUM_VISIBLE_STONE_SEED_DISTANCE)
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static boolean isOwnSupportBlock(
            final BlockCoordinate block,
            final BlockCoordinate feet,
            final int supportY
    ) {
        return block.x() == feet.x()
                && block.y() == supportY
                && block.z() == feet.z();
    }

    static BlockCoordinate feetOf(final CoreSkillFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return new BlockCoordinate(
                (int) Math.floor(frame.position().x()),
                (int) Math.floor(frame.position().y()),
                (int) Math.floor(frame.position().z())
        );
    }

    /**
     * The next descent block: the <em>highest</em> observed face in the
     * neighbouring column that the body can still see.
     *
     * <p>Height first, distance only as a tie-break. A straight stair is
     * dug from the top of the next column downwards, so the block that
     * matters is always the topmost one still standing there; taking the
     * nearest face instead would happily reach sideways into the middle of
     * the column and leave the surface above it floating, which is
     * un-walkable and therefore not a step at all.</p>
     *
     * <p>The body's own column is deliberately excluded. The block underfoot
     * is the surface carrying the companion, and
     * {@code PlayerSupportBlockGuard} refuses it at the action boundary; only
     * a different observed support may be removed, which is what turns the
     * descent into a staircase.</p>
     *
     * <p>The cut-off is the eye itself rather than the block containing the
     * eye, because a body one block down a stair looks at the next column's
     * surface almost level: that surface is in the same block layer as the
     * eye's own position, and rejecting it would stall the descent with
     * nothing left to mine.</p>
     */
    static Optional<VisibleBlockFace> selectStairBlock(
            final BlockCoordinate feet,
            final int stepX,
            final int stepZ,
            final double eyeY,
            final List<VisibleBlockFace> visibleBlockFaces
    ) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(visibleBlockFaces, "visibleBlockFaces");
        if (stepX == 0 && stepZ == 0) {
            return Optional.empty();
        }
        final int columnX = feet.x() + stepX;
        final int columnZ = feet.z() + stepZ;
        return visibleBlockFaces.stream()
                .filter(face -> face.block().x() == columnX
                        && face.block().z() == columnZ)
                .filter(face -> face.block().y() < eyeY)
                .filter(face -> face.distance() <= STAIR_REACH)
                .min(Comparator.comparingInt(
                                (VisibleBlockFace face) ->
                                        -face.block().y()
                        )
                        .thenComparingDouble(
                                VisibleBlockFace::distance
                        ));
    }

    private void recordStoneSearchDiagnostics(
            final BlockCoordinate feet,
            final List<VisibleBlockFace> visibleBlockFaces
    ) {
        diagnosticsVisibleFaces = visibleBlockFaces.size();
        final List<VisibleBlockFace> stones = visibleBlockFaces.stream()
                .filter(face -> face.blockTypeId().equals(STONE))
                .toList();
        diagnosticsStoneFaces = stones.size();
        diagnosticsStoneFacesKept = (int) stones.stream()
                .filter(face -> !isOwnSupportBlock(
                        face.block(),
                        feet,
                        feet.y() - 1
                ))
                .filter(face -> face.distance()
                        <= MAXIMUM_VISIBLE_STONE_SEED_DISTANCE)
                .count();
    }

    /**
     * Snapshots how many faces the chosen step column offered on this tick,
     * and how far the descent's own filters stripped them down. The three
     * counters mirror the predicate chain inside
     * {@link #selectStairBlock}: column membership, then the eye-height
     * cutoff, then the stair reach cutoff. When the column ends up empty they
     * reveal which filter was responsible.
     */
    private void recordStairColumnDiagnostics(
            final BlockCoordinate feet,
            final int stepX,
            final int stepZ,
            final double eyeY,
            final List<VisibleBlockFace> visibleBlockFaces
    ) {
        if (stepX == 0 && stepZ == 0) {
            diagnosticsStairColumnFaces = 0;
            diagnosticsStairColumnEyes = 0;
            diagnosticsStairColumnReach = 0;
            return;
        }
        final int columnX = feet.x() + stepX;
        final int columnZ = feet.z() + stepZ;
        final List<VisibleBlockFace> inColumn = visibleBlockFaces.stream()
                .filter(face -> face.block().x() == columnX
                        && face.block().z() == columnZ)
                .toList();
        diagnosticsStairColumnFaces = inColumn.size();
        final List<VisibleBlockFace> belowEye = inColumn.stream()
                .filter(face -> face.block().y() < eyeY)
                .toList();
        diagnosticsStairColumnEyes = belowEye.size();
        diagnosticsStairColumnReach = (int) belowEye.stream()
                .filter(face -> face.distance() <= STAIR_REACH)
                .count();
    }

    private static Optional<VisibleBlockFace> visibleTable(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(PrepareStoneToolsSkill::isCraftingTable)
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    /**
     * P19 diagnostic helper: turns the cluster gatherer's swallowed rejection
     * reason into a still-monitorable terminal code. The bare
     * {@code stair_block_rejected} token is preserved so existing monitors keep
     * matching, but the real sub-cause (e.g. {@code danger_detected}) is now
     * visible in the failure code itself.
     */
    static String stairBlockRejectedCode(final SkillFailure cause) {
        return NAME + ".stair_block_rejected." + cause.code();
    }

    /**
     * P20 diagnostic helper: disambiguates an empty step column by appending
     * the last observation's filter counters to the still-monitorable
     * {@code stair_column_empty} token. The three numbers are
     * {@code c}andidate faces in the column, faces that survived the
     * {@code e}ye-height cutoff, and faces that survived the {@code r}each
     * cutoff:
     * <ul>
     *   <li>{@code c0} — nothing observed in that column at all
     *       (genuinely open, occluded, or perception sparse);</li>
     *   <li>{@code c>0 e0} — blocks existed but every one sat at/above the
     *       eye, so the height filter starved the descent;</li>
     *   <li>{@code e>0 r0} — within eye height but beyond {@code STAIR_REACH};</li>
     *   <li>{@code e>0 r>0} — survived both filters yet still empty, which
     *       points at a selection bug rather than geometry.</li>
     * </ul>
     */
    static String stairColumnEmptyCode(
            final int candidates,
            final int eyes,
            final int reach
    ) {
        return NAME
                + ".stair_column_empty.c"
                + candidates
                + ".e"
                + eyes
                + ".r"
                + reach;
    }

    private static boolean isCraftingTable(
            final VisibleBlockFace face
    ) {
        return face.blockTypeId().equals(CRAFTING_TABLE);
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

    private static Optional<String> selectMiningTool(
            final CoreSkillFrame frame
    ) {
        return STONE_MINING_TOOLS.stream().filter(itemId ->
                hasOwnedItem(frame, itemId)
        ).findFirst();
    }

    private static boolean ownsStoneOrBetterPickaxe(
            final CoreSkillFrame frame
    ) {
        return STONE_OR_BETTER_PICKAXES.stream().anyMatch(itemId ->
                hasOwnedItem(frame, itemId)
        );
    }

    private static boolean hasOwnedItem(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return ownedCount(frame, itemId) > 0
                || frame.mainHand().itemId().equals(itemId)
                    && frame.mainHand().count() > 0
                || frame.offHand().itemId().equals(itemId)
                    && frame.offHand().count() > 0;
    }

    private static int ownedCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static int plankCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().endsWith("_planks"))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static boolean safeEnvironment(final CoreSkillFrame frame) {
        return !frame.inWater()
                && frame.danger() <= 0.20;
    }

    private static float yaw(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(Math.atan2(
                -direction.x(),
                direction.z()
        ));
    }

    private static float wrapDegrees(final float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped == 0.0F ? 0.0F : wrapped;
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

    private enum Phase {
        IDLE,
        PREPARE_STICKS,
        FIND_STONE,
        DIG_DOWN,
        ENTER_STAIR,
        GATHER_STONE,
        CONFIRM_COBBLESTONE,
        FIND_TABLE,
        FIND_SUPPORT,
        AIM_SUPPORT,
        PLACE_TABLE,
        CONFIRM_PLACED,
        MOVE_TO_TABLE,
        AIM_TABLE,
        OPEN_TABLE,
        CONFIRM_MENU,
        CRAFT_STONE_PICKAXE,
        CONFIRM_STONE_PICKAXE,
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
