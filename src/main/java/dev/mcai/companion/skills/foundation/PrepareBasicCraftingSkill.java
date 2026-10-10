package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntityPlacementEnvelope;
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
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts already-owned wood into the first complete 3x3 crafting setup.
 *
 * <p>The model still decides when this high-level skill is appropriate. The
 * latency-sensitive recipe, placement, aiming, menu-open and result-confirm
 * sequence remains local so a slow provider cannot leave the body repeatedly
 * promising the same action. Every mutation uses ordinary recipe-menu or
 * first-person block-interaction paths.</p>
 */
public final class PrepareBasicCraftingSkill
        implements Skill<NoParameters> {
    public static final String NAME = "prepare_basic_crafting";

    private static final String CRAFTING_TABLE =
            "minecraft:crafting_table";
    private static final String STICK = "minecraft:stick";
    private static final String WOODEN_PICKAXE =
            "minecraft:wooden_pickaxe";
    private static final int MAXIMUM_TICKS = 600;
    private static final int MAXIMUM_RECIPE_WAIT_TICKS = 60;
    private static final int MAXIMUM_AIM_TICKS = 80;
    private static final int MAXIMUM_TABLE_CONFIRM_TICKS = 100;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAXIMUM_SUPPORT_SCANS = 16;
    private static final int MAXIMUM_SUPPORT_REPOSITIONS = 3;
    /*
     * At standing eye height, a 55-degree floor ray lands only about 1.13
     * blocks away and is then correctly rejected by the 1.35-block
     * self-clearance rule below. A shallower look exposes a reachable support
     * outside the body envelope instead of rotating through sixteen
     * geometrically impossible samples.
     */
    private static final float SUPPORT_SCAN_PITCH = 42.0F;
    private static final double MINIMUM_PLACEMENT_DISTANCE = 1.35;
    private static final double MAXIMUM_PLACEMENT_DISTANCE = 4.25;
    private static final double MAXIMUM_TABLE_CENTER_DISTANCE = 3.75;
    private static final float[] SUPPORT_SCAN_YAW_OFFSETS = {
            0.0F,
            45.0F,
            -45.0F,
            90.0F,
            -90.0F,
            135.0F,
            -135.0F,
            180.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long nextScanTick = -1;
    private int supportScans;
    private float supportScanBaseYaw;
    private VisibleBlockFace selectedSupport;
    private VisibleBlockFace selectedTable;
    private BlockCoordinate expectedTablePosition;
    private final Set<BlockCoordinate> rejectedSupports =
            new HashSet<>();
    private int phaseTransitions;
    private int tablePlacementAttempts;
    private int menuOpenAttempts;
    private MoveToSkill supportMovement;
    private MoveToParameters supportMovementParameters;
    private GridPos supportMovementTarget;
    private final Set<GridPos> rejectedSupportStands = new HashSet<>();
    private int supportRepositions;

    public PrepareBasicCraftingSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory
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
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return PrepareBasicCraftingSkill::parseNone;
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
        if (frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_pose"
            ));
        }
        if (ownsPickaxe(frame)) {
            return Optional.empty();
        }
        final int required = requiredPlanks(frame);
        final int potential = plankCount(frame)
                + convertibleWoodCount(frame) * 4;
        return potential >= required
                ? Optional.empty()
                : Optional.of(SkillFailure.of(
                        NAME + ".insufficient_wood"
                ));
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
                        "Companion body changed before basic crafting"
                )
        );
        phase = Phase.PREPARE_INGREDIENTS;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        supportScans = 0;
        supportScanBaseYaw = yaw(frame.lookDirection());
        selectedSupport = null;
        selectedTable = null;
        expectedTablePosition = null;
        rejectedSupports.clear();
        phaseTransitions = 0;
        tablePlacementAttempts = 0;
        menuOpenAttempts = 0;
        supportMovement = null;
        supportMovementParameters = null;
        supportMovementTarget = null;
        rejectedSupportStands.clear();
        supportRepositions = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
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
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        final SupportDiagnostics support =
                supportDiagnostics(frame, rejectedSupports);
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"supportScans\":%d,"
                                + "\"elapsedTicks\":%d,"
                                + "\"phaseElapsedTicks\":%d,"
                                + "\"phaseTransitions\":%d,"
                                + "\"tablePlacementAttempts\":%d,"
                                + "\"menuOpenAttempts\":%d,"
                                + "\"visibleFaces\":%d,"
                                + "\"upFaces\":%d,"
                                + "\"sturdyUpFaces\":%d,"
                                + "\"reachableUpFaces\":%d,"
                                + "\"entityClearUpFaces\":%d,"
                                + "\"rangeClearUpFaces\":%d,"
                                + "\"selfClearUpFaces\":%d,"
                                + "\"rejectedSupports\":%d,"
                                + "\"supportRepositions\":%d,"
                                + "\"supportMovementTarget\":%s,"
                                + "\"onGround\":%s,"
                                + "\"bodyPosition\":%s,"
                                + "\"expectedTable\":%s,"
                                + "\"selectedSupport\":%s,"
                                + "\"selectedTable\":%s}",
                        phase.name(),
                        supportScans,
                        Math.max(0L, context.gameTick() - startedAtTick),
                        Math.max(
                                0L,
                                context.gameTick() - phaseStartedAtTick
                        ),
                        phaseTransitions,
                        tablePlacementAttempts,
                        menuOpenAttempts,
                        support.visibleFaces(),
                        support.upFaces(),
                        support.sturdyUpFaces(),
                        support.reachableUpFaces(),
                        support.entityClearUpFaces(),
                        support.rangeClearUpFaces(),
                        support.selfClearUpFaces(),
                        rejectedSupports.size(),
                        supportRepositions,
                        gridJson(supportMovementTarget),
                        frame == null ? "null" : frame.onGround(),
                        frame == null
                                ? "null"
                                : vectorJson(frame.position()),
                        coordinateJson(expectedTablePosition),
                        selectedSupport == null
                                ? "null"
                                : coordinateJson(
                                        selectedSupport.block()
                                ),
                        selectedTable == null
                                ? "null"
                                : coordinateJson(
                                        selectedTable.block()
                                )
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (supportMovement != null
                && supportMovementParameters != null) {
            supportMovement.cancel(context, supportMovementParameters);
        }
        supportMovement = null;
        supportMovementParameters = null;
        supportMovementTarget = null;
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
        if (frame.inWater()) {
            return fail(NAME + ".unsafe_pose");
        }
        if (!frame.onGround()) {
            core.stop();
            return SkillTickResult.running(false, true);
        }
        if (ownsPickaxe(frame)) {
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
            case PREPARE_INGREDIENTS ->
                    prepareIngredients(context, frame);
            case CRAFT_TABLE -> craftTable(context, frame);
            case CRAFT_STICKS -> craftSticks(context, frame);
            case EQUIP_TABLE -> equipTable(context, frame);
            case FIND_SUPPORT -> findSupport(context, frame);
            case REPOSITION_FOR_SUPPORT ->
                    repositionForSupport(context, frame);
            case AIM_SUPPORT -> aimSupport(context, frame);
            case PLACE_TABLE -> placeTable(context);
            case CONFIRM_TABLE -> confirmTable(context, frame);
            case FIND_TABLE -> findTable(context, frame);
            case AIM_TABLE -> aimTable(context, frame);
            case OPEN_TABLE -> openTable(context);
            case CONFIRM_MENU -> confirmMenu(context);
            case CRAFT_PICKAXE -> craftPickaxe(context);
            case CONFIRM_PICKAXE ->
                    SkillTickResult.running(false, true);
            default -> fail(NAME + ".invalid_state");
        };
    }

    private SkillTickResult prepareIngredients(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (plankCount(frame) >= requiredPlanks(frame)) {
            return transition(
                    context,
                    hasTable(frame)
                            ? Phase.CRAFT_STICKS
                            : Phase.CRAFT_TABLE
            );
        }
        final InventoryOperationResult crafted = craftOnePlank(frame);
        if (crafted.succeeded()) {
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                < MAXIMUM_RECIPE_WAIT_TICKS) {
            return SkillTickResult.running(false, true);
        }
        return fail(NAME + ".plank_recipe_unavailable");
    }

    private SkillTickResult craftTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (hasTable(frame)) {
            return transition(context, Phase.CRAFT_STICKS);
        }
        return craftAndWait(
                context,
                new CraftRecipeParameters(CRAFTING_TABLE, 1),
                NAME + ".table_recipe_unavailable"
        );
    }

    private SkillTickResult craftSticks(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (itemCount(frame, STICK) >= 2) {
            if (inventory.hasThreeByThreeCraftingMenu()) {
                return transition(context, Phase.CRAFT_PICKAXE);
            }
            if (visibleTable(frame).isPresent()) {
                return transition(context, Phase.FIND_TABLE);
            }
            return transition(context, Phase.EQUIP_TABLE);
        }
        return craftAndWait(
                context,
                new CraftRecipeParameters(STICK, 1),
                NAME + ".stick_recipe_unavailable"
        );
    }

    private SkillTickResult equipTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> visible = visibleTable(frame);
        if (visible.isPresent()) {
            selectedTable = visible.orElseThrow();
            return transition(context, Phase.AIM_TABLE);
        }
        if (!hasOwnedItem(frame, CRAFTING_TABLE)) {
            return fail(NAME + ".table_item_missing");
        }
        if (frame.mainHand().itemId().equals(CRAFTING_TABLE)
                && frame.mainHand().count() > 0) {
            supportScans = 0;
            supportScanBaseYaw = yaw(frame.lookDirection());
            nextScanTick = context.gameTick();
            return transition(context, Phase.FIND_SUPPORT);
        }
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
        if (supportScans >= MAXIMUM_SUPPORT_SCANS) {
            if (supportRepositions < MAXIMUM_SUPPORT_REPOSITIONS) {
                final Optional<SkillTickResult> reposition =
                        beginSupportReposition(context, frame);
                if (reposition.isPresent()) {
                    return reposition.orElseThrow();
                }
            }
            return fail(NAME + ".safe_support_not_visible");
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final float yaw = supportScanBaseYaw
                + SUPPORT_SCAN_YAW_OFFSETS[
                        supportScans
                                % SUPPORT_SCAN_YAW_OFFSETS.length
                ];
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        yaw,
                        SUPPORT_SCAN_PITCH
                )).accepted()) {
            return fail(NAME + ".support_scan_rejected");
        }
        supportScans++;
        nextScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private Optional<SkillTickResult> beginSupportReposition(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<GridPos> candidate = selectSupportReposition(
                frame,
                rejectedSupportStands,
                context.hardcore() ? 0.08 : 0.25
        );
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        final GridPos target = candidate.orElseThrow();
        final MoveToParameters parameters = new MoveToParameters(
                frame.dimension(),
                target.x() + 0.5,
                target.y(),
                target.z() + 0.5,
                0.45
        );
        final MoveToSkill movement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> rejected = movement.preconditions(
                context,
                parameters
        );
        if (rejected.isPresent()) {
            rejectedSupportStands.add(target);
            return Optional.empty();
        }
        movement.start(context, parameters);
        supportMovement = movement;
        supportMovementParameters = parameters;
        supportMovementTarget = target;
        rejectedSupportStands.add(target);
        supportRepositions++;
        return Optional.of(transition(
                context,
                Phase.REPOSITION_FOR_SUPPORT
        ));
    }

    private SkillTickResult repositionForSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (supportMovement == null
                || supportMovementParameters == null
                || supportMovementTarget == null) {
            return fail(NAME + ".support_reposition_state_missing");
        }
        final SkillTickResult moved = supportMovement.tick(
                context,
                supportMovementParameters
        );
        if (moved.status() == SkillTickResult.Status.RUNNING) {
            return SkillTickResult.running(
                    moved.madeProgress(),
                    moved.safeCheckpoint()
            );
        }
        supportMovement = null;
        supportMovementParameters = null;
        supportMovementTarget = null;
        if (moved.status() == SkillTickResult.Status.FAILED) {
            if (supportRepositions < MAXIMUM_SUPPORT_REPOSITIONS) {
                return beginSupportReposition(context, frame)
                        .orElseGet(() -> fail(
                                NAME + ".support_reposition_failed"
                        ));
            }
            return fail(NAME + ".support_reposition_failed");
        }
        supportScans = 0;
        nextScanTick = context.gameTick();
        supportScanBaseYaw = yaw(frame.lookDirection());
        selectedSupport = null;
        return transition(context, Phase.FIND_SUPPORT);
    }

    private SkillTickResult aimSupport(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedSupport == null) {
            return transition(context, Phase.FIND_SUPPORT);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.isPresent()
                && sameBlockAndFace(
                        crosshair.orElseThrow(),
                        selectedSupport
                )) {
            selectedSupport = crosshair.orElseThrow();
            return transition(context, Phase.PLACE_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            selectedSupport = null;
            return transition(context, Phase.FIND_SUPPORT);
        }
        return aimAt(frame, selectedSupport.hitPosition());
    }

    private SkillTickResult placeTable(final SkillContext context) {
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
        selectedTable = null;
        return transition(context, Phase.CONFIRM_TABLE);
    }

    private SkillTickResult confirmTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> table =
                visibleExpectedTable(frame)
                    .or(() -> visibleTable(frame));
        if (table.isPresent()) {
            selectedTable = table.orElseThrow();
            return transition(context, Phase.AIM_TABLE);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareBasicCraftingSkill::isCraftingTable);
        if (crosshair.isPresent()) {
            selectedTable = crosshair.orElseThrow();
            return transition(context, Phase.OPEN_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_TABLE_CONFIRM_TICKS) {
            if (hasOwnedItem(frame, CRAFTING_TABLE)
                    && selectedSupport != null
                    && rejectedSupports.size() < 8) {
                rejectedSupports.add(selectedSupport.block());
                selectedSupport = null;
                expectedTablePosition = null;
                supportScans = 0;
                nextScanTick = context.gameTick();
                supportScanBaseYaw = yaw(
                        frame.lookDirection()
                );
                return transition(context, Phase.FIND_SUPPORT);
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

    private SkillTickResult findTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> table = visibleTable(frame);
        if (table.isPresent()) {
            selectedTable = table.orElseThrow();
            return transition(context, Phase.AIM_TABLE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_TABLE_CONFIRM_TICKS) {
            return fail(NAME + ".visible_table_lost");
        }
        return scanForTable(context, frame);
    }

    private SkillTickResult aimTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (selectedTable == null) {
            return transition(context, Phase.FIND_TABLE);
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
            return transition(context, Phase.FIND_TABLE);
        }
        return aimAt(frame, selectedTable.hitPosition());
    }

    private SkillTickResult openTable(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, Phase.CRAFT_PICKAXE);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(PrepareBasicCraftingSkill::isCraftingTable);
        if (crosshair.isEmpty()) {
            return transition(context, Phase.AIM_TABLE);
        }
        final ActionOutcome outcome = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target(crosshair.orElseThrow())
        );
        menuOpenAttempts++;
        if (!outcome.accepted()) {
            return fail(NAME + ".table_open_rejected");
        }
        return transition(context, Phase.CONFIRM_MENU);
    }

    private SkillTickResult confirmMenu(final SkillContext context) {
        if (inventory.hasThreeByThreeCraftingMenu()) {
            return transition(context, Phase.CRAFT_PICKAXE);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= 20L) {
            return fail(NAME + ".table_menu_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult craftPickaxe(final SkillContext context) {
        final CraftRecipeParameters recipe =
                new CraftRecipeParameters(WOODEN_PICKAXE, 1);
        final InventoryOperationResult checked =
                inventory.checkCraft(recipe);
        if (!checked.succeeded()) {
            if (context.gameTick() - phaseStartedAtTick
                    < MAXIMUM_RECIPE_WAIT_TICKS) {
                return SkillTickResult.running(false, true);
            }
            return fail(NAME + ".pickaxe_recipe_unavailable");
        }
        final InventoryOperationResult crafted =
                inventory.craftOnce(recipe);
        if (!crafted.succeeded()) {
            return fail(NAME + ".pickaxe_craft_failed");
        }
        return transition(context, Phase.CONFIRM_PICKAXE);
    }

    private SkillTickResult craftAndWait(
            final SkillContext context,
            final CraftRecipeParameters recipe,
            final String failureCode
    ) {
        final InventoryOperationResult checked =
                inventory.checkCraft(recipe);
        if (checked.succeeded()) {
            final InventoryOperationResult crafted =
                    inventory.craftOnce(recipe);
            if (crafted.succeeded()) {
                phaseStartedAtTick = context.gameTick();
                return SkillTickResult.running(true, true);
            }
        }
        if (context.gameTick() - phaseStartedAtTick
                < MAXIMUM_RECIPE_WAIT_TICKS) {
            return SkillTickResult.running(false, true);
        }
        return fail(failureCode);
    }

    private InventoryOperationResult craftOnePlank(
            final CoreSkillFrame frame
    ) {
        for (InventoryItemSummary item : frame.inventory().stream()
                .sorted(Comparator.comparing(
                        InventoryItemSummary::itemId
                ))
                .toList()) {
            final Optional<String> recipeId =
                    plankRecipeFor(item.itemId());
            if (recipeId.isEmpty()) {
                continue;
            }
            final CraftRecipeParameters recipe =
                    new CraftRecipeParameters(
                            recipeId.orElseThrow(),
                            1
                    );
            if (!inventory.checkCraft(recipe).succeeded()) {
                continue;
            }
            return inventory.craftOnce(recipe);
        }
        return InventoryOperationResult.rejected(
                NAME + ".plank_recipe_unavailable"
        );
    }

    private SkillTickResult scanForTable(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final float yaw = supportScanBaseYaw
                + SUPPORT_SCAN_YAW_OFFSETS[
                        supportScans
                                % SUPPORT_SCAN_YAW_OFFSETS.length
                ];
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, 25.0F)).accepted()) {
            return fail(NAME + ".table_scan_rejected");
        }
        supportScans++;
        nextScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
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
        phaseTransitions++;
        return SkillTickResult.running(true, true);
    }

    private static String vectorJson(final PerceptionVec3 vector) {
        return String.format(
                Locale.ROOT,
                "[%.3f,%.3f,%.3f]",
                vector.x(),
                vector.y(),
                vector.z()
        );
    }

    private static String coordinateJson(
            final BlockCoordinate coordinate
    ) {
        return coordinate == null
                ? "null"
                : String.format(
                        Locale.ROOT,
                        "[%d,%d,%d]",
                        coordinate.x(),
                        coordinate.y(),
                        coordinate.z()
                );
    }

    private static String gridJson(final GridPos position) {
        return position == null
                ? "null"
                : String.format(
                        Locale.ROOT,
                        "[%d,%d,%d]",
                        position.x(),
                        position.y(),
                        position.z()
                );
    }

    private SkillTickResult fail(final String code) {
        supportMovement = null;
        supportMovementParameters = null;
        supportMovementTarget = null;
        core.stop();
        closeCraftingMenuIfOpen();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
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

    private Optional<VisibleBlockFace> selectPlacementSupport(
            final CoreSkillFrame frame
    ) {
        return selectPlacementSupport(frame, rejectedSupports);
    }

    static Optional<VisibleBlockFace> selectPlacementSupport(
            final CoreSkillFrame frame,
            final Set<BlockCoordinate> rejected
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(rejected, "rejected");
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.face().equals("up"))
                .filter(face ->
                        !rejected.contains(face.block()))
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .filter(face ->
                        isReachableTableSupport(frame, face))
                .filter(face ->
                        visiblePlacementVolumeIsClear(frame, face))
                .filter(face ->
                        face.distance() >= MINIMUM_PLACEMENT_DISTANCE
                                && face.distance()
                                    <= MAXIMUM_PLACEMENT_DISTANCE)
                .filter(face -> {
                    final double centerX = face.block().x() + 0.5;
                    final double centerZ = face.block().z() + 0.5;
                    return Math.hypot(
                            centerX - frame.position().x(),
                            centerZ - frame.position().z()
                    ) >= MINIMUM_PLACEMENT_DISTANCE;
                })
                .min(Comparator
                        .comparingInt((VisibleBlockFace face) ->
                                Math.abs(
                                    face.block().y() + 1
                                        - frame.feet().y()
                                ))
                        .thenComparingDouble(
                                VisibleBlockFace::distance
                        ));
    }

    private static boolean visiblePlacementVolumeIsClear(
            final CoreSkillFrame frame,
            final VisibleBlockFace support
    ) {
        final BlockCoordinate block = support.block();
        final int tableY = block.y() + 1;
        return frame.visibleEntities().stream().noneMatch(entity ->
                VisibleEntityPlacementEnvelope.intersectsBlock(
                        entity,
                        block.x(),
                        tableY,
                        block.z()
                )
        );
    }

    /**
     * A table may only be placed on a support whose resulting block remains
     * near the body's standing plane and comfortably inside vanilla reach.
     * This prevents a visible ore/tree-column top from winning the nearest
     * face sort and producing a physically placed but unusable workstation.
     */
    static boolean isReachableTableSupport(
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(face, "face");
        final int tableY = face.block().y() + 1;
        if (Math.abs(tableY - frame.feet().y()) > 1) {
            return false;
        }
        final double deltaX = face.block().x() + 0.5
                - frame.eyePosition().x();
        final double deltaY = tableY + 0.5
                - frame.eyePosition().y();
        final double deltaZ = face.block().z() + 0.5
                - frame.eyePosition().z();
        return deltaX * deltaX + deltaY * deltaY
                + deltaZ * deltaZ
                <= MAXIMUM_TABLE_CENTER_DISTANCE
                    * MAXIMUM_TABLE_CENTER_DISTANCE;
    }

    private Optional<VisibleBlockFace> visibleExpectedTable(
            final CoreSkillFrame frame
    ) {
        if (expectedTablePosition == null) {
            return visibleTable(frame);
        }
        return frame.visibleBlockFaces().stream()
                .filter(PrepareBasicCraftingSkill::isCraftingTable)
                .filter(face -> face.block().equals(
                        expectedTablePosition
                ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static SupportDiagnostics supportDiagnostics(
            final CoreSkillFrame frame,
            final Set<BlockCoordinate> rejected
    ) {
        if (frame == null) {
            return new SupportDiagnostics(0, 0, 0, 0, 0, 0, 0);
        }
        final List<VisibleBlockFace> available = frame.visibleBlockFaces()
                .stream()
                .filter(face -> !rejected.contains(face.block()))
                .toList();
        final List<VisibleBlockFace> up = available.stream()
                .filter(face -> face.face().equals("up"))
                .toList();
        final List<VisibleBlockFace> sturdy = up.stream()
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .toList();
        final List<VisibleBlockFace> reachable = sturdy.stream()
                .filter(face -> isReachableTableSupport(frame, face))
                .toList();
        final List<VisibleBlockFace> entityClear = reachable.stream()
                .filter(face -> visiblePlacementVolumeIsClear(frame, face))
                .toList();
        final List<VisibleBlockFace> rangeClear = entityClear.stream()
                .filter(face ->
                        face.distance() >= MINIMUM_PLACEMENT_DISTANCE
                                && face.distance()
                                    <= MAXIMUM_PLACEMENT_DISTANCE)
                .toList();
        final long selfClear = rangeClear.stream()
                .filter(face -> {
                    final double centerX = face.block().x() + 0.5;
                    final double centerZ = face.block().z() + 0.5;
                    return Math.hypot(
                            centerX - frame.position().x(),
                            centerZ - frame.position().z()
                    ) >= MINIMUM_PLACEMENT_DISTANCE;
                })
                .count();
        return new SupportDiagnostics(
                available.size(),
                up.size(),
                sturdy.size(),
                reachable.size(),
                entityClear.size(),
                rangeClear.size(),
                Math.toIntExact(selfClear)
        );
    }

    static Optional<GridPos> selectSupportReposition(
            final CoreSkillFrame frame,
            final Set<GridPos> rejected,
            final double maximumDanger
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(rejected, "rejected");
        if (!Double.isFinite(maximumDanger)
                || maximumDanger < 0.0
                || maximumDanger > 1.0) {
            throw new IllegalArgumentException(
                    "maximumDanger is outside [0,1]"
            );
        }
        final GridPos current = frame.feet();
        return frame.navigation().observedVoxels().values().stream()
                .map(ObservedVoxel::position)
                .filter(candidate -> !rejected.contains(candidate))
                .filter(candidate ->
                        Math.abs(candidate.y() - current.y()) <= 1)
                .filter(candidate -> {
                    final double distance = current.euclideanDistance(
                            candidate
                    );
                    return distance >= 2.0 && distance <= 5.0;
                })
                .filter(candidate -> observedSafeStand(
                        frame,
                        candidate,
                        maximumDanger
                ))
                .filter(candidate -> frame.visibleEntities().stream()
                        .noneMatch(entity ->
                                VisibleEntityPlacementEnvelope
                                    .intersectsBlock(
                                        entity,
                                        candidate.x(),
                                        candidate.y(),
                                        candidate.z()
                                    )
                                    || VisibleEntityPlacementEnvelope
                                        .intersectsBlock(
                                            entity,
                                            candidate.x(),
                                            candidate.y() + 1,
                                            candidate.z()
                                        )))
                .min(Comparator
                        .comparingDouble(current::euclideanDistance)
                        .thenComparingInt(GridPos::y)
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z));
    }

    private static boolean observedSafeStand(
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
                                support.orElseThrow().effectiveDanger()
                        )
                ) <= maximumDanger;
    }

    private static Optional<VisibleBlockFace> visibleTable(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(PrepareBasicCraftingSkill::isCraftingTable)
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static boolean isCraftingTable(
            final VisibleBlockFace face
    ) {
        return face.blockTypeId().equals(CRAFTING_TABLE);
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
        final BlockFace face = BlockFace.valueOf(
                visible.face().toUpperCase(Locale.ROOT)
        );
        return new BlockInteractionTarget(
                visible.block().x(),
                visible.block().y(),
                visible.block().z(),
                face,
                new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                )
        );
    }

    private static int requiredPlanks(final CoreSkillFrame frame) {
        int required = 3;
        if (!hasTable(frame)) {
            required += 4;
        }
        if (itemCount(frame, STICK) < 2) {
            required += 2;
        }
        return required;
    }

    private static int plankCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().endsWith("_planks"))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static int convertibleWoodCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> plankRecipeFor(
                        item.itemId()
                ).isPresent())
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    /**
     * Returns the vanilla-style plank recipe identifier for one convertible
     * wood item. This is also used by trusted route readiness so the planner
     * and the legal crafting executor cannot disagree about chest wood.
     */
    public static Optional<String> plankRecipeFor(final String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        final int separator = itemId.indexOf(':');
        if (separator <= 0 || separator == itemId.length() - 1) {
            return Optional.empty();
        }
        final String namespace = itemId.substring(0, separator);
        String path = itemId.substring(separator + 1);
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        final String family;
        if (path.endsWith("_log")) {
            family = path.substring(0, path.length() - 4);
        } else if (path.endsWith("_wood")) {
            family = path.substring(0, path.length() - 5);
        } else if (path.endsWith("_stem")) {
            family = path.substring(0, path.length() - 5);
        } else if (path.endsWith("_hyphae")) {
            family = path.substring(0, path.length() - 7);
        } else if (path.equals("bamboo_block")) {
            family = "bamboo";
        } else {
            return Optional.empty();
        }
        return family.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        namespace + ":" + family + "_planks"
                );
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

    private static boolean hasOwnedItem(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return itemCount(frame, itemId) > 0
                || frame.mainHand().itemId().equals(itemId)
                    && frame.mainHand().count() > 0
                || frame.offHand().itemId().equals(itemId)
                    && frame.offHand().count() > 0;
    }

    private static boolean hasTable(final CoreSkillFrame frame) {
        return hasOwnedItem(frame, CRAFTING_TABLE)
                || visibleTable(frame).isPresent();
    }

    private static boolean ownsPickaxe(final CoreSkillFrame frame) {
        return frame.inventory().stream().anyMatch(item ->
                item.itemId().endsWith("_pickaxe")
                        && item.count() > 0
        ) || frame.mainHand().itemId().endsWith("_pickaxe")
                && frame.mainHand().count() > 0
                || frame.offHand().itemId().endsWith("_pickaxe")
                && frame.offHand().count() > 0;
    }

    private static float yaw(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(Math.atan2(
                -direction.x(),
                direction.z()
        ));
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
        PREPARE_INGREDIENTS,
        CRAFT_TABLE,
        CRAFT_STICKS,
        EQUIP_TABLE,
        FIND_SUPPORT,
        REPOSITION_FOR_SUPPORT,
        AIM_SUPPORT,
        PLACE_TABLE,
        CONFIRM_TABLE,
        FIND_TABLE,
        AIM_TABLE,
        OPEN_TABLE,
        CONFIRM_MENU,
        CRAFT_PICKAXE,
        CONFIRM_PICKAXE,
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

    private record SupportDiagnostics(
            int visibleFaces,
            int upFaces,
            int sturdyUpFaces,
            int reachableUpFaces,
            int entityClearUpFaces,
            int rangeClearUpFaces,
            int selfClearUpFaces
    ) {
    }
}
