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
    private static final int MAXIMUM_INVENTORY_CONFIRM_TICKS = 100;
    private static final int MAXIMUM_REMEMBERED_TABLE_AIM_TICKS = 20;
    private static final double RELIABLE_TABLE_INTERACTION_DISTANCE = 3.75;
    private static final double TABLE_APPROACH_RADIUS = 3.0;
    /*
     * Live telemetry (FIX9) showed a full 360-degree scan of this world's
     * surface reporting visibleFaces=14 with stoneFaces=0, and an offline
     * read of the region file confirmed why: the stone layer begins about
     * five blocks below the grass, underneath mushroom blocks, leaf litter,
     * grass and dirt.  No heading can ever expose it to a first-person ray,
     * so completing the search by scanning alone is impossible here while an
     * ordinary player simply digs straight down until stone appears.  Every
     * descent block is still only ever acted on after the companion has
     * genuinely looked at it, and the descent is bounded so a body standing
     * on bedrock or over a shaft stops with a diagnostic instead of digging
     * forever.
     */
    private static final int MAXIMUM_DIG_DOWN_BLOCKS = 24;
    private static final int MAXIMUM_DIG_AIM_TICKS = 40;
    private static final float DIG_DOWN_PITCH = -90.0F;
    private static final double DIG_SEED_CLUSTER_RADIUS = 1.0;
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
    private GatherVisibleBlockClusterParameters gatheringParameters;
    private MoveToSkill tableMovement;
    private MoveToParameters tableMovementParameters;
    private boolean digGather;
    private int digDownBlocks;
    private long digAimStartedAtTick = -1;
    private long digAimObservationRevision = -1L;
    private boolean awaitingDigAim;

    /*
     * Diagnostics only -- surfaced through checkpoint() so a failed
     * search can be told apart from a search that never had candidates.
     * A negative value means "not sampled yet".
     */
    private int diagnosticsVisibleFaces = -1;
    private int diagnosticsStoneFaces = -1;
    private int diagnosticsStoneFacesKept = -1;
    private int diagnosticsTableFaces = -1;

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
        gatheringParameters = null;
        tableMovement = null;
        tableMovementParameters = null;
        digGather = false;
        digDownBlocks = 0;
        digAimStartedAtTick = -1L;
        digAimObservationRevision = -1L;
        awaitingDigAim = false;
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
                                + "\"tableFaces\":%d}",
                        phase.name(),
                        scanTurns,
                        Math.max(0L, context.gameTick() - startedAtTick),
                        phase == Phase.GATHER_STONE,
                        digDownBlocks,
                        diagnosticsVisibleFaces,
                        diagnosticsStoneFaces,
                        diagnosticsStoneFacesKept,
                        diagnosticsTableFaces
                )
        );
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
                && phase != Phase.DIG_DOWN) {
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
            case MOVE_TO_TABLE -> moveToTable(context, frame);
            case DIG_DOWN -> digDown(context, frame);
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
                                4.0,
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
     * Enters the straight-down fallback after a full heading sweep found no
     * naturally exposed stone.
     *
     * <p>Nothing is mined here. This only arms the descent, and the next
     * ticks turn the body's own view onto the block it is standing on so the
     * face is a real first-person observation before any action is bound.</p>
     */
    private SkillTickResult beginDigDown(final SkillContext context) {
        digAimStartedAtTick = context.gameTick();
        digAimObservationRevision = -1L;
        awaitingDigAim = false;
        phase = Phase.DIG_DOWN;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
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
        final BlockCoordinate support = supportBlockOf(frame);
        final Optional<VisibleBlockFace> visibleSupport =
                visibleFaceAt(
                        interaction.visibleBlockFaces(),
                        support
                );
        if (visibleSupport.isPresent()) {
            return beginSupportDig(
                    context,
                    interaction,
                    visibleSupport.orElseThrow()
            );
        }
        if (awaitingDigAim
                && frame.observationRevision()
                    <= digAimObservationRevision) {
            return SkillTickResult.running(false, true);
        }
        if (context.gameTick() - digAimStartedAtTick
                >= MAXIMUM_DIG_AIM_TICKS) {
            return fail(NAME + ".dig_target_not_visible");
        }
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        yaw(frame.lookDirection()),
                        DIG_DOWN_PITCH
                )).accepted()) {
            return fail(NAME + ".dig_aim_rejected");
        }
        digAimObservationRevision = frame.observationRevision();
        awaitingDigAim = true;
        return SkillTickResult.running(true, true);
    }

    /*
     * One observation, one block. The seed carries the type the ray actually
     * reported, so the descent never has to guess what the body is standing
     * on, and keeping the current hand avoids a spurious tool swap for soil.
     */
    private SkillTickResult beginSupportDig(
            final SkillContext context,
            final InteractionSkillFrame interaction,
            final VisibleBlockFace support
    ) {
        gatheringParameters = new GatherVisibleBlockClusterParameters(
                interaction.dimension(),
                new ObservedBlockTarget(
                        interaction.observationRevision(),
                        support.block().x(),
                        support.block().y(),
                        support.block().z(),
                        BlockFace.valueOf(
                                support.face().toUpperCase(Locale.ROOT)
                        )
                ),
                support.blockTypeId(),
                1,
                DIG_SEED_CLUSTER_RADIUS,
                KEEP_CURRENT_HAND
        );
        final Optional<SkillFailure> rejected = gatherer.preconditions(
                context,
                gatheringParameters
        );
        if (rejected.isPresent()) {
            return fail(NAME + ".dig_block_rejected");
        }
        gatherer.start(context, gatheringParameters);
        digGather = true;
        digDownBlocks++;
        phase = Phase.GATHER_STONE;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
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
                 * not enter the cobblestone confirmation window. Re-enter the
                 * descent instead: it stops on its own once the ordinary
                 * stone sweep or the cobblestone count is satisfied.
                 */
                digGather = false;
                return beginDigDown(context);
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
                    : fail(NAME + ".visible_table_not_found");
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
     * The one block a body standing at this position rests on.
     */
    static BlockCoordinate supportBlockAt(
            final double x,
            final double y,
            final double z
    ) {
        return new BlockCoordinate(
                (int) Math.floor(x),
                (int) Math.floor(y) - 1,
                (int) Math.floor(z)
        );
    }

    private static BlockCoordinate supportBlockOf(final CoreSkillFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return supportBlockAt(
                frame.position().x(),
                frame.position().y(),
                frame.position().z()
        );
    }

    /**
     * The nearest retained observation of an exact coordinate, so a descent
     * block can only ever be bound to a face the companion really saw.
     */
    static Optional<VisibleBlockFace> visibleFaceAt(
            final List<VisibleBlockFace> visibleBlockFaces,
            final BlockCoordinate block
    ) {
        Objects.requireNonNull(visibleBlockFaces, "visibleBlockFaces");
        Objects.requireNonNull(block, "block");
        return visibleBlockFaces.stream()
                .filter(face -> face.block().x() == block.x()
                        && face.block().y() == block.y()
                        && face.block().z() == block.z())
                .min(Comparator.comparingDouble(
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

    private static Optional<VisibleBlockFace> visibleTable(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(PrepareStoneToolsSkill::isCraftingTable)
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
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
        GATHER_STONE,
        CONFIRM_COBBLESTONE,
        FIND_TABLE,
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
