package dev.mcai.companion.skills.mining;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.gathering.ResourceInventoryState;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Excavates a bounded two-block-high tunnel using only fair observations and
 * ordinary player actions.
 *
 * <p>This class intentionally has no Minecraft level, chunk, block-state, or
 * registry dependency. Missing observation evidence remains unknown. Every
 * block break is bound to a currently visible ray hit; every step waits for
 * two observed-clear cells and an observed supporting top face. Lighting is
 * established with an owned torch through the same normal use-on-block path.
 * Hidden ores are never queried: a requested target stops the skill only
 * after a first-person semantic ray actually exposes it.</p>
 */
public final class ExcavateSafeTunnelSkill
        implements Skill<ExcavateSafeTunnelParameters> {
    public static final String NAME = "excavate_safe_tunnel";

    private static final String TORCH_ITEM_ID = "minecraft:torch";
    private static final double AIM_ALIGNMENT_DEGREES = 3.0;
    private static final double MOVE_ALIGNMENT_DEGREES = 10.0;
    private static final double ARRIVAL_HORIZONTAL_DISTANCE = 0.58;
    private static final double STEP_ORIGIN_CENTER_DISTANCE = 0.14;
    private static final int STEP_ORIGIN_STABLE_TICKS = 3;
    private static final double STEP_ORIGIN_MOVE_SPEED = 0.32;
    private static final double MAXIMUM_REUSABLE_TORCH_DISTANCE = 6.0;
    private static final int MAXIMUM_TOTAL_TICKS = 30_000;
    private static final int BASE_TOTAL_TICKS = 400;
    private static final int TOTAL_TICKS_PER_STEP = 1_300;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final ResourceInventorySource inventory;
    private final MiningSkillPolicy policy;
    private final Map<FaceKey, FaceEvidence> observedFaces =
            new HashMap<>();
    private final Set<GridPos> verifiedClearCells = new HashSet<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long boundGoalRevision = -1;
    private long boundWorldRevision = -1;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private GridPos origin;
    private GridPos destination;
    private GridPos activeBlock;
    private GridPos exposedTarget;
    private int completedSteps;
    private int stepsSinceTorch;
    private int phaseTicks;
    private int equipAttempts;
    private long actionObservationRevision = -1;
    private int torchCountBeforePlacement = -1;
    private GridPos torchPlacementBlock;
    private GridPos hazardRetreatDestination;
    private SkillFailure pendingHazardFailure;
    private GridPos committedStepSupport;
    private GridPos settledStepOrigin;
    private GridPos settlingStepOrigin;
    private int stepOriginStableTicks;
    private boolean currentPoseStable;

    public ExcavateSafeTunnelSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource inventory,
            final MiningSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(coreFrames, "coreFrames");
        this.interactionActuator = Objects.requireNonNull(
                interactionActuator,
                "interactionActuator"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<ExcavateSafeTunnelParameters> parameters() {
        return MiningSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final SnapshotResult result = currentSnapshot(
                parameters.dimension(),
                -1
        );
        if (result.failure().isPresent()) {
            return result.failure();
        }
        final Snapshot snapshot = result.snapshot().orElseThrow();
        if (snapshot.interaction().observationRevision()
                != parameters.sampleSequence()) {
            return Optional.of(failure("sample_not_current"));
        }
        final Optional<SkillFailure> unsafe = safetyFailure(
                context,
                snapshot.core()
        );
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!snapshot.core().onGround()
                || snapshot.core().inWater()) {
            return Optional.of(failure("stable_dry_ground_required"));
        }
        if (snapshot.inventory().emptyMainInventorySlots() == 0) {
            return Optional.of(failure("inventory_full"));
        }
        if (!ownsItem(snapshot.interaction(), parameters.pickaxeItemId())) {
            return Optional.of(failure("pickaxe_unavailable"));
        }
        if (!ownsItem(snapshot.interaction(), TORCH_ITEM_ID)) {
            return Optional.of(failure("torch_unavailable"));
        }
        final Optional<SkillFailure> durability =
                heldPickaxeDurabilityFailure(
                        snapshot.interaction(),
                        parameters,
                        0
                );
        if (durability.isPresent()) {
            return durability;
        }
        final GridPos feet = snapshot.core().feet();
        if (currentFace(
                snapshot.interaction(),
                feet.below(),
                BlockFace.UP
        ).isEmpty()) {
            return Optional.of(failure(
                    "initial_torch_surface_not_visible"
            ));
        }
        final Optional<SkillFailure> support =
                visibleSupportFailure(
                        snapshot,
                        feet.below(),
                        context
                );
        return support;
    }

    @Override
    public void start(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Snapshot snapshot = currentSnapshot(
                parameters.dimension(),
                -1
        ).snapshot().orElseThrow(() ->
                new IllegalStateException(
                        "Mining body binding changed before start"
                ));
        if (snapshot.interaction().observationRevision()
                != parameters.sampleSequence()) {
            throw new IllegalStateException(
                    "Mining observation changed before start"
            );
        }
        phase = Phase.PREPARE_TORCH;
        failure = null;
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        boundGoalRevision = context.goalRevision();
        boundWorldRevision = context.worldRevision();
        startedAtTick = context.gameTick();
        lastObservationRevision =
                snapshot.interaction().observationRevision() - 1;
        origin = snapshot.core().feet();
        destination = null;
        activeBlock = null;
        exposedTarget = null;
        completedSteps = 0;
        stepsSinceTorch = parameters.torchInterval();
        phaseTicks = 0;
        equipAttempts = 0;
        actionObservationRevision = -1;
        torchCountBeforePlacement = -1;
        torchPlacementBlock = null;
        hazardRetreatDestination = null;
        pendingHazardFailure = null;
        committedStepSupport = null;
        settledStepOrigin = null;
        settlingStepOrigin = null;
        stepOriginStableTicks = 0;
        currentPoseStable = snapshot.core().onGround()
                && !snapshot.core().inWater();
        observedFaces.clear();
        verifiedClearCells.clear();
        verifiedClearCells.add(origin);
        verifiedClearCells.add(origin.above());
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        final GridPos checkpointOrigin =
                origin == null ? new GridPos(0, 0, 0) : origin;
        final String target = exposedTarget == null
                ? "null"
                : String.format(
                        Locale.ROOT,
                        "[%d,%d,%d]",
                        exposedTarget.x(),
                        exposedTarget.y(),
                        exposedTarget.z()
                );
        if (!phase.checkpointBoundary()) {
            throw new IllegalStateException(
                    "Mining checkpoint requested away from a canonical boundary"
            );
        }
        return new SkillCheckpoint(
                2,
                String.format(
                        Locale.ROOT,
                        "{\"purpose\":\"audit_and_high_level_replan\","
                            + "\"resumable\":false,"
                            + "\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"origin\":[%d,%d,%d],"
                            + "\"direction\":\"%s\",\"mode\":\"%s\","
                            + "\"completedSteps\":%d,"
                            + "\"stepsSinceTorch\":%d,"
                            + "\"maximumSteps\":%d,"
                            + "\"session\":%d,\"goalRevision\":%d,"
                            + "\"worldRevision\":%d,\"observation\":%d,"
                            + "\"exposedTarget\":%s}",
                        phase.name(),
                        parameters.dimension().id(),
                        checkpointOrigin.x(),
                        checkpointOrigin.y(),
                        checkpointOrigin.z(),
                        parameters.direction().name(),
                        parameters.mode().name(),
                        completedSteps,
                        stepsSinceTorch,
                        parameters.maximumSteps(),
                        boundSessionGeneration,
                        boundGoalRevision,
                        boundWorldRevision,
                        lastObservationRevision,
                        target
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearTransientBinding();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
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

    private SkillTickResult tickSafely(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters
    ) {
        if (context.goalRevision() != boundGoalRevision
                || context.worldRevision() != boundWorldRevision) {
            return fail(NAME + ".revision_changed");
        }
        final long tickBudget = Math.min(
                MAXIMUM_TOTAL_TICKS,
                BASE_TOTAL_TICKS
                    + (long) parameters.maximumSteps()
                    * TOTAL_TICKS_PER_STEP
        );
        if (context.gameTick() - startedAtTick > tickBudget) {
            return fail(NAME + ".timed_out");
        }
        final SnapshotResult result = currentSnapshot(
                parameters.dimension(),
                boundSessionGeneration
        );
        if (result.failure().isPresent()) {
            return fail(result.failure().orElseThrow());
        }
        final Snapshot snapshot = result.snapshot().orElseThrow();
        currentPoseStable = snapshot.core().onGround()
                && !snapshot.core().inWater();
        if (snapshot.interaction().observationRevision()
                < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final boolean fresh = snapshot.interaction()
                .observationRevision() > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                snapshot.interaction().observationRevision()
        );
        if (fresh) {
            ingestFaces(snapshot.interaction(), parameters);
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, snapshot.core());
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (phase == Phase.RETREATING_HAZARD) {
            return retreatFromHazard(
                    context,
                    parameters,
                    snapshot
            );
        }
        final Optional<SkillFailure> environmental =
                immediateEnvironmentFailure(snapshot);
        if (environmental.isPresent()) {
            return beginHazardRetreat(
                    context,
                    parameters,
                    snapshot,
                    environmental.orElseThrow()
            );
        }
        if (phase == Phase.SETTLING_TARGET) {
            return settleBeforeTargetCompletion(context, snapshot);
        }
        final Optional<GridPos> target =
                visibleTarget(snapshot.interaction(), parameters);
        if (target.isPresent()) {
            exposedTarget = target.orElseThrow();
            quiesce();
            if (currentPoseStable) {
                return complete();
            }
            transition(Phase.SETTLING_TARGET);
            return running(context, true, false);
        }
        if (snapshot.inventory().emptyMainInventorySlots() == 0) {
            return fail(NAME + ".inventory_full");
        }

        return switch (phase) {
            case PREPARE_TORCH ->
                    prepareTorch(context, parameters, snapshot);
            case EQUIPPING_TORCH ->
                    waitForTorchEquipment(
                            context,
                            parameters,
                            snapshot,
                            fresh
                    );
            case PLACING_TORCH ->
                    placeTorch(context, parameters, snapshot);
            case VERIFYING_TORCH ->
                    verifyTorch(
                            context,
                            parameters,
                            snapshot,
                            fresh
                    );
            case EQUIPPING_PICKAXE ->
                    waitForPickaxeEquipment(
                            context,
                            parameters,
                            snapshot,
                            fresh
                    );
            case PREPARE_STEP ->
                    prepareStep(context, parameters, snapshot);
            case SEEKING_BLOCK_FACE ->
                    seekBlockFace(context, parameters, snapshot);
            case AIMING_TO_MINE ->
                    aimAndMine(context, parameters, snapshot);
            case MINING ->
                    continueMining(context, parameters, snapshot);
            case VERIFYING_CLEARANCE ->
                    verifyClearance(
                            context,
                            parameters,
                            snapshot,
                            fresh
                    );
            case VERIFYING_SUPPORT ->
                    verifySupport(context, parameters, snapshot);
            case MOVING ->
                    moveIntoStep(context, parameters, snapshot);
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    private SkillTickResult prepareTorch(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (stepsSinceTorch < parameters.torchInterval()) {
            return ensurePickaxe(
                    context,
                    parameters,
                    snapshot
            );
        }
        /*
         * A parent compound may start another bounded leg at the same
         * excavation station. Requiring a second torch in the player's
         * occupied feet cell can neither improve lighting nor be observed as
         * a new placement. A currently visible nearby owned-world torch is
         * fair evidence that this station is already lit; count the new leg
         * from that light instead of attempting a duplicate placement.
         */
        if (snapshot.interaction().visibleBlockFaces().stream()
                .anyMatch(face ->
                        TORCH_ITEM_ID.equals(face.blockTypeId())
                            && face.distance()
                                <= MAXIMUM_REUSABLE_TORCH_DISTANCE
                )) {
            stepsSinceTorch = 0;
            return ensurePickaxe(
                    context,
                    parameters,
                    snapshot
            );
        }
        if (!ownsItem(snapshot.interaction(), TORCH_ITEM_ID)) {
            return fail(NAME + ".torch_unavailable");
        }
        final GridPos support = snapshot.core().feet().below();
        /*
         * Reaching a lighting interval normally leaves the camera facing
         * along the tunnel. The floor is still ordinary solid support, but
         * its top face may not be in the first semantic frame after the
         * movement stops. Treat that as a bounded observation transition,
         * not as proof that the floor disappeared.
         */
        final Optional<SkillFailure> supportFailure =
                observedSupportFailure(snapshot, support, context);
        if (supportFailure.isPresent()) {
            return fail(supportFailure.orElseThrow());
        }
        if (currentFace(
                snapshot.interaction(),
                support,
                BlockFace.UP
        ).isEmpty() || !supportObserved(snapshot, support)) {
            final ActionOutcome look = lookAt(
                    snapshot.core(),
                    supportTop(support)
            );
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            return waitOrFail(
                    context,
                    NAME + ".torch_surface_not_visible",
                    policy.maximumAimTicks()
            );
        }
        if (!TORCH_ITEM_ID.equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            final ActionOutcome equipped =
                    interactionActuator.equipMainHand(TORCH_ITEM_ID);
            if (!equipped.accepted()) {
                return fail(
                        NAME + ".torch_equip_"
                            + outcomeCode(equipped)
                );
            }
            equipAttempts = 1;
            actionObservationRevision =
                    snapshot.interaction().observationRevision();
            transition(Phase.EQUIPPING_TORCH);
            return running(context, true, true);
        }
        transition(Phase.PLACING_TORCH);
        return running(context, true, true);
    }

    private SkillTickResult waitForTorchEquipment(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        if (TORCH_ITEM_ID.equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            transition(Phase.PLACING_TORCH);
            return running(context, true, true);
        }
        if (!fresh
                || snapshot.interaction().observationRevision()
                        <= actionObservationRevision) {
            return waitOrFail(
                    context,
                    NAME + ".torch_equip_not_observed",
                    policy.maximumAimTicks()
            );
        }
        if (!ownsItem(snapshot.interaction(), TORCH_ITEM_ID)
                || equipAttempts >= 2) {
            return fail(NAME + ".torch_equip_not_observed");
        }
        final ActionOutcome equipped =
                interactionActuator.equipMainHand(TORCH_ITEM_ID);
        if (!equipped.accepted()) {
            return fail(
                    NAME + ".torch_equip_" + outcomeCode(equipped)
            );
        }
        equipAttempts++;
        actionObservationRevision =
                snapshot.interaction().observationRevision();
        return running(context, true, true);
    }

    private SkillTickResult placeTorch(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (!TORCH_ITEM_ID.equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            return fail(NAME + ".torch_not_held");
        }
        final GridPos support = snapshot.core().feet().below();
        final Optional<VisibleBlockFace> face = currentFace(
                snapshot.interaction(),
                support,
                BlockFace.UP
        );
        if (face.isEmpty()) {
            final ActionOutcome look = lookAt(
                    snapshot.core(),
                    supportTop(support)
            );
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            return waitOrFail(
                    context,
                    NAME + ".torch_surface_not_visible",
                    policy.maximumAimTicks()
            );
        }
        final VisibleBlockFace visible = face.orElseThrow();
        final Optional<SkillFailure> surfaceFailure =
                unsafeBlockFailure(visible.blockTypeId());
        if (surfaceFailure.isPresent()) {
            return fail(surfaceFailure.orElseThrow());
        }
        final AimResult aim = aimAt(
                snapshot.core(),
                visible.hitPosition()
        );
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return waitOrFail(
                    context,
                    NAME + ".torch_aim_timed_out",
                    policy.maximumAimTicks()
            );
        }
        /*
         * The semantic fan may be a few ticks older than the body's vanilla
         * crosshair.  It is allowed to guide the turn, but it must never be
         * replayed as an interaction target: the server-owned actuator
         * correctly rejects that stale hit as TARGET_OCCLUDED.  Wait for the
         * live centre ray to bind the same observed support face before
         * issuing the ordinary torch use packet.
         */
        final Optional<VisibleBlockFace> selected =
                interactionFrames.currentCrosshairBlock()
                        .filter(current -> key(current).equals(key(visible)))
                        .filter(current -> "up".equals(current.face()));
        if (selected.isEmpty()) {
            return waitOrFail(
                    context,
                    NAME + ".torch_aim_timed_out",
                    policy.maximumAimTicks()
            );
        }
        final int count = itemCount(
                snapshot.interaction(),
                TORCH_ITEM_ID
        );
        if (count <= 0) {
            return fail(NAME + ".torch_unavailable");
        }
        final ActionOutcome placed = interactionActuator.useOnBlock(
                ActionHand.MAIN_HAND,
                interactionTarget(selected.orElseThrow())
        );
        if (!placed.accepted()) {
            return fail(
                    NAME + ".torch_place_" + outcomeCode(placed)
            );
        }
        torchCountBeforePlacement = count;
        torchPlacementBlock = support.above();
        actionObservationRevision =
                snapshot.interaction().observationRevision();
        transition(Phase.VERIFYING_TORCH);
        return running(context, true, false);
    }

    private SkillTickResult verifyTorch(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        if (fresh
                && snapshot.interaction().observationRevision()
                        > actionObservationRevision
                && itemCount(
                        snapshot.interaction(),
                        TORCH_ITEM_ID
                ) < torchCountBeforePlacement
                && torchPlacementBlock != null
                && currentFace(
                        snapshot.interaction(),
                        torchPlacementBlock
                ).filter(face ->
                        TORCH_ITEM_ID.equals(face.blockTypeId())
                ).isPresent()) {
            stepsSinceTorch = 0;
            torchPlacementBlock = null;
            return ensurePickaxe(
                    context,
                    parameters,
                    snapshot
            );
        }
        if (torchPlacementBlock != null) {
            final ActionOutcome look = lookAt(
                    snapshot.core(),
                    center(torchPlacementBlock)
            );
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
        }
        return waitOrFail(
                context,
                NAME + ".torch_placement_not_observed",
                policy.maximumTorchVerificationTicks()
        );
    }

    private SkillTickResult ensurePickaxe(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (parameters.pickaxeItemId().equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            final Optional<SkillFailure> durability =
                    heldPickaxeDurabilityFailure(
                            snapshot.interaction(),
                            parameters,
                            completedSteps
                    );
            if (durability.isPresent()) {
                return fail(durability.orElseThrow());
            }
            transition(Phase.PREPARE_STEP);
            return running(context, true, true);
        }
        if (!ownsItem(
                snapshot.interaction(),
                parameters.pickaxeItemId()
        )) {
            return fail(NAME + ".pickaxe_unavailable");
        }
        final ActionOutcome equipped =
                interactionActuator.equipMainHand(
                        parameters.pickaxeItemId()
                );
        if (!equipped.accepted()) {
            return fail(
                    NAME + ".pickaxe_equip_" + outcomeCode(equipped)
            );
        }
        equipAttempts = 1;
        actionObservationRevision =
                snapshot.interaction().observationRevision();
        transition(Phase.EQUIPPING_PICKAXE);
        return running(context, true, true);
    }

    private SkillTickResult waitForPickaxeEquipment(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        if (parameters.pickaxeItemId().equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            final Optional<SkillFailure> durability =
                    heldPickaxeDurabilityFailure(
                            snapshot.interaction(),
                            parameters,
                            completedSteps
                    );
            if (durability.isPresent()) {
                return fail(durability.orElseThrow());
            }
            transition(Phase.PREPARE_STEP);
            return running(context, true, true);
        }
        if (!fresh
                || snapshot.interaction().observationRevision()
                        <= actionObservationRevision) {
            return waitOrFail(
                    context,
                    NAME + ".pickaxe_equip_not_observed",
                    policy.maximumAimTicks()
            );
        }
        if (!ownsItem(
                snapshot.interaction(),
                parameters.pickaxeItemId()
        ) || equipAttempts >= 2) {
            return fail(NAME + ".pickaxe_equip_not_observed");
        }
        final ActionOutcome equipped =
                interactionActuator.equipMainHand(
                        parameters.pickaxeItemId()
                );
        if (!equipped.accepted()) {
            return fail(
                    NAME + ".pickaxe_equip_" + outcomeCode(equipped)
            );
        }
        equipAttempts++;
        actionObservationRevision =
                snapshot.interaction().observationRevision();
        return running(context, true, true);
    }

    private SkillTickResult prepareStep(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        /*
         * Stopping input does not erase ordinary player inertia. During the
         * initial torch/equipment sequence the body can legally coast across
         * one block boundary, so bind the unstarted corridor to the settled
         * feet immediately before deriving its first step. Without this,
         * the stale first destination can become the body's current support;
         * the lower actuator correctly refuses to mine that block.
         */
        if (completedSteps == 0
                && !origin.equals(snapshot.core().feet())) {
            if (manhattan(origin, snapshot.core().feet()) > 2) {
                return fail(NAME + ".origin_shifted_too_far");
            }
            origin = snapshot.core().feet();
            destination = null;
            activeBlock = null;
            committedStepSupport = null;
            settledStepOrigin = null;
            settlingStepOrigin = null;
            stepOriginStableTicks = 0;
            verifiedClearCells.clear();
            verifiedClearCells.add(origin);
            verifiedClearCells.add(origin.above());
            return running(context, true, true);
        }
        final Optional<SkillTickResult> settling =
                settleBeforeDescendingStep(
                        context,
                        parameters,
                        snapshot
                );
        if (settling.isPresent()) {
            return settling.orElseThrow();
        }
        if (completedSteps >= parameters.maximumSteps()) {
            return complete();
        }
        final Optional<SkillFailure> durability =
                heldPickaxeDurabilityFailure(
                        snapshot.interaction(),
                        parameters,
                        completedSteps
                );
        if (durability.isPresent()) {
            return fail(durability.orElseThrow());
        }
        destination = stepDestination(parameters, completedSteps + 1);
        final GridPos transitionHead =
                transitionHead(parameters);
        if (transitionHead != null) {
            final CellState transitionHeadState = cellState(
                    snapshot,
                    transitionHead,
                    -1
            );
            if (transitionHeadState.failure().isPresent()) {
                return fail(
                        transitionHeadState.failure().orElseThrow()
                );
            }
            if (!transitionHeadState.clear()) {
                return selectMiningTarget(
                        context,
                        snapshot,
                        transitionHead
                );
            }
            verifiedClearCells.add(transitionHead);
        }
        final GridPos upper = destination.above();
        final CellState upperState = cellState(
                snapshot,
                upper,
                -1
        );
        if (upperState.failure().isPresent()) {
            return fail(upperState.failure().orElseThrow());
        }
        if (!upperState.clear()) {
            return selectMiningTarget(
                    context,
                    snapshot,
                    upper
            );
        }
        verifiedClearCells.add(upper);
        final CellState lowerState = cellState(
                snapshot,
                destination,
                -1
        );
        if (lowerState.failure().isPresent()) {
            return fail(lowerState.failure().orElseThrow());
        }
        if (!lowerState.clear()) {
            return selectMiningTarget(
                    context,
                    snapshot,
                    destination
            );
        }
        verifiedClearCells.add(destination);
        transition(Phase.VERIFYING_SUPPORT);
        return running(context, true, true);
    }

    private SkillTickResult selectMiningTarget(
            final SkillContext context,
            final Snapshot snapshot,
            final GridPos block
    ) {
        activeBlock = block;
        final Optional<VisibleBlockFace> face =
                currentActionFace(snapshot.interaction(), block);
        if (face.isPresent()) {
            transition(Phase.AIMING_TO_MINE);
            return running(context, true, true);
        }
        final ActionOutcome look = lookAt(
                snapshot.core(),
                center(block)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        transition(Phase.SEEKING_BLOCK_FACE);
        return running(context, true, true);
    }

    private SkillTickResult seekBlockFace(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        final CellState state = cellState(snapshot, activeBlock, -1);
        if (state.failure().isPresent()) {
            return fail(state.failure().orElseThrow());
        }
        if (state.clear()) {
            activeBlock = null;
            transition(Phase.PREPARE_STEP);
            return running(context, true, true);
        }
        if (currentActionFace(
                snapshot.interaction(),
                activeBlock
        ).isPresent()) {
            transition(Phase.AIMING_TO_MINE);
            return running(context, true, true);
        }
        final ActionOutcome look = lookAt(
                snapshot.core(),
                center(activeBlock)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        return waitOrFail(
                context,
                NAME + ".block_face_not_visible",
                policy.maximumAimTicks()
        );
    }

    private SkillTickResult aimAndMine(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (!parameters.pickaxeItemId().equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            return fail(NAME + ".pickaxe_not_held");
        }
        final Optional<VisibleBlockFace> aimSurface =
                currentActionFace(snapshot.interaction(), activeBlock);
        if (aimSurface.isEmpty()) {
            transition(Phase.SEEKING_BLOCK_FACE);
            return running(context, true, true);
        }
        final ActionOutcome stopped = coreActuator.stop();
        if (!stopped.accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        final AimResult aim = aimAt(
                snapshot.core(),
                aimSurface.orElseThrow().hitPosition()
        );
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        /*
         * The semantic frame is sampled at 4 Hz and may still describe the
         * old view while the 20 TPS input driver is turning. It may guide the
         * turn, but only the body's live vanilla centre crosshair can
         * authorize an ordinary break packet.
         */
        final Optional<VisibleBlockFace> selected =
                currentCrosshairFace(activeBlock);
        if (selected.isEmpty()) {
            return waitOrFail(
                    context,
                    NAME + ".mining_aim_timed_out",
                    policy.maximumAimTicks()
            );
        }
        final VisibleBlockFace face = selected.orElseThrow();
        if (parameters.isTarget(face.blockTypeId())) {
            exposedTarget = activeBlock;
            return complete();
        }
        final Optional<SkillFailure> blockFailure =
                unsafeBlockFailure(face.blockTypeId());
        if (blockFailure.isPresent()) {
            return fail(blockFailure.orElseThrow());
        }
        final ActionOutcome mining =
                interactionActuator.beginMining(
                        interactionTarget(face)
                );
        if (!mining.accepted()) {
            return fail(
                    NAME + ".mining_" + outcomeCode(mining)
            );
        }
        actionObservationRevision =
                snapshot.interaction().observationRevision();
        if (mining == ActionOutcome.COMPLETED) {
            transition(Phase.VERIFYING_CLEARANCE);
        } else {
            transition(Phase.MINING);
        }
        return running(context, true, false);
    }

    private SkillTickResult continueMining(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        final Optional<SkillFailure> durability =
                heldPickaxeDurabilityFailure(
                        snapshot.interaction(),
                        parameters,
                        completedSteps
                );
        if (durability.isPresent()) {
            return fail(durability.orElseThrow());
        }
        if (phaseTicks >= policy.maximumMiningTicks()) {
            return fail(NAME + ".mining_timed_out");
        }
        final ActionOutcome mining =
                interactionActuator.continueMining();
        if (mining == ActionOutcome.COMPLETED) {
            actionObservationRevision =
                    snapshot.interaction().observationRevision();
            transition(Phase.VERIFYING_CLEARANCE);
            return running(context, true, false);
        }
        if (!mining.accepted()) {
            return fail(
                    NAME + ".mining_" + outcomeCode(mining)
            );
        }
        phaseTicks++;
        return running(context, true, false);
    }

    private SkillTickResult verifyClearance(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        final CellState state = postMiningCellState(
                snapshot,
                activeBlock,
                actionObservationRevision
        );
        if (state.failure().isPresent()) {
            return fail(state.failure().orElseThrow());
        }
        if (state.clear()) {
            interactionActuator.abortMining();
            verifiedClearCells.add(activeBlock);
            activeBlock = null;
            transition(Phase.PREPARE_STEP);
            return running(context, true, true);
        }
        if (fresh
                && currentFace(
                        snapshot.interaction(),
                        activeBlock
                ).isPresent()) {
            return fail(NAME + ".clearance_refilled");
        }
        return waitOrFail(
                context,
                NAME + ".clearance_not_observed",
                policy.maximumClearanceWaitTicks()
        );
    }

    private SkillTickResult verifySupport(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        final CellState lower = cellState(snapshot, destination, -1);
        final CellState upper = cellState(
                snapshot,
                destination.above(),
                -1
        );
        final CellState transitionHead =
                transitionHeadState(snapshot, parameters);
        if (lower.failure().isPresent()) {
            return fail(lower.failure().orElseThrow());
        }
        if (upper.failure().isPresent()) {
            return fail(upper.failure().orElseThrow());
        }
        if (transitionHead.failure().isPresent()) {
            return fail(transitionHead.failure().orElseThrow());
        }
        if (!lower.clear()
                || !upper.clear()
                || !transitionHead.clear()) {
            return fail(NAME + ".clearance_changed");
        }
        final GridPos support = destination.below();
        final Optional<SkillFailure> supportFailure =
                observedSupportFailure(snapshot, support, context);
        if (supportFailure.isEmpty()
                && supportObserved(snapshot, support)) {
            committedStepSupport = support;
            transition(Phase.MOVING);
            return running(context, true, true);
        }
        if (supportFailure.isPresent()) {
            return fail(supportFailure.orElseThrow());
        }
        final ActionOutcome look = lookAt(
                snapshot.core(),
                supportTop(support)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        return waitOrFail(
                context,
                NAME + ".support_not_observed",
                policy.maximumAimTicks()
        );
    }

    private SkillTickResult moveIntoStep(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        final CellState lower = cellState(snapshot, destination, -1);
        final CellState upper = cellState(
                snapshot,
                destination.above(),
                -1
        );
        final CellState transitionHead =
                transitionHeadState(snapshot, parameters);
        if (lower.failure().isPresent()) {
            return fail(lower.failure().orElseThrow());
        }
        if (upper.failure().isPresent()) {
            return fail(upper.failure().orElseThrow());
        }
        if (transitionHead.failure().isPresent()) {
            return fail(transitionHead.failure().orElseThrow());
        }
        if (!lower.clear()
                || !upper.clear()
                || !transitionHead.clear()) {
            return fail(NAME + ".clearance_changed");
        }
        final Optional<SkillFailure> supportFailure =
                observedSupportFailure(
                        snapshot,
                        destination.below(),
                        context
                );
        if (supportFailure.isPresent()) {
            return fail(supportFailure.orElseThrow());
        }
        if (!supportObserved(snapshot, destination.below())
                && !destination.below().equals(
                        committedStepSupport
                )) {
            return fail(NAME + ".support_became_unknown");
        }
        if (arrived(snapshot.core(), destination)) {
            coreActuator.stop();
            completedSteps++;
            stepsSinceTorch++;
            destination = null;
            activeBlock = null;
            committedStepSupport = null;
            settledStepOrigin = null;
            settlingStepOrigin = null;
            stepOriginStableTicks = 0;
            if (completedSteps >= parameters.maximumSteps()) {
                return complete();
            }
            transition(
                    stepsSinceTorch >= parameters.torchInterval()
                            ? Phase.PREPARE_TORCH
                            : Phase.PREPARE_STEP
            );
            return running(context, true, true);
        }
        if (phaseTicks >= policy.maximumMoveTicks()) {
            return fail(NAME + ".movement_timed_out");
        }
        if (parameters.mode() != TunnelMode.ASCENDING
                && snapshot.core().position().y()
                    < destination.y() - 0.55) {
            return fail(NAME + ".unexpected_fall");
        }
        final PerceptionVec3 target = new PerceptionVec3(
                destination.x() + 0.5,
                destination.y(),
                destination.z() + 0.5
        );
        final AimResult aim = aimAt(
                snapshot.core(),
                new PerceptionVec3(
                        target.x(),
                        snapshot.core().eyePosition().y(),
                        target.z()
                )
        );
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > MOVE_ALIGNMENT_DEGREES) {
            coreActuator.stop();
            phaseTicks++;
            return running(context, true, false);
        }
        if (parameters.mode() == TunnelMode.ASCENDING
                && destination.y()
                    > snapshot.core().position().y() + 0.35
                && snapshot.core().onGround()) {
            final ActionOutcome jumped = coreActuator.jump();
            if (!jumped.accepted()) {
                return fail(
                        NAME + ".jump_" + outcomeCode(jumped)
                );
            }
        }
        final ActionOutcome moved = coreActuator.move(
                new MovementIntent(0.65, 0.0, false, false)
        );
        if (!moved.accepted()) {
            return fail(NAME + ".move_" + outcomeCode(moved));
        }
        phaseTicks++;
        return running(context, true, false);
    }

    private CellState transitionHeadState(
            final Snapshot snapshot,
            final ExcavateSafeTunnelParameters parameters
    ) {
        final GridPos transitionHead =
                transitionHead(parameters);
        return transitionHead == null
                ? CellState.clearCell()
                : cellState(snapshot, transitionHead, -1);
    }

    private GridPos transitionHead(
            final ExcavateSafeTunnelParameters parameters
    ) {
        return switch (parameters.mode()) {
            case HORIZONTAL -> null;
            case DESCENDING -> destination.above().above();
            case ASCENDING -> {
                /*
                 * Before climbing, clear the ceiling over the current
                 * foothold. Otherwise that ceiling both blocks the jump arc
                 * and occludes the next step's upper block from the body's
                 * first-person ray. For later steps the current foothold is
                 * the previously completed destination.
                 */
                final GridPos foothold = completedSteps == 0
                        ? origin
                        : stepDestination(
                                parameters,
                                completedSteps
                        );
                yield foothold.above().above();
            }
        };
    }

    /**
     * A descending tunnel removes the block one cell forward and one cell
     * down. A player can still be nominally inside the previous block while
     * their 0.6-wide collision box already overlaps that next lower block.
     * The action boundary then correctly treats it as current support.
     *
     * <p>Do not weaken that safety boundary. Instead, use normal movement to
     * settle near the centre of the last verified corridor cell and hold
     * there for several ticks before selecting a downward mining target.</p>
     */
    private Optional<SkillTickResult> settleBeforeDescendingStep(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (parameters.mode() != TunnelMode.DESCENDING) {
            return Optional.empty();
        }
        final GridPos anchor = completedSteps == 0
                ? origin
                : stepDestination(parameters, completedSteps);
        if (!snapshot.core().feet().equals(anchor)) {
            return Optional.of(fail(
                    NAME + ".step_origin_changed_before_excavation"
            ));
        }
        final double dx = snapshot.core().position().x()
                - (anchor.x() + 0.5);
        final double dz = snapshot.core().position().z()
                - (anchor.z() + 0.5);
        final double distance = Math.hypot(dx, dz);
        if (anchor.equals(settledStepOrigin)
                && distance <= STEP_ORIGIN_CENTER_DISTANCE) {
            return Optional.empty();
        }
        if (!anchor.equals(settlingStepOrigin)) {
            settlingStepOrigin = anchor;
            stepOriginStableTicks = 0;
        }
        if (phaseTicks >= policy.maximumMoveTicks()) {
            return Optional.of(fail(
                    NAME + ".step_origin_settle_timed_out"
            ));
        }
        if (distance <= STEP_ORIGIN_CENTER_DISTANCE) {
            final ActionOutcome stopped = coreActuator.stop();
            if (!stopped.accepted()) {
                return Optional.of(fail(
                        NAME + ".step_origin_stop_"
                            + outcomeCode(stopped)
                ));
            }
            stepOriginStableTicks++;
            phaseTicks++;
            if (stepOriginStableTicks < STEP_ORIGIN_STABLE_TICKS) {
                return Optional.of(running(
                        context,
                        true,
                        false
                ));
            }
            settledStepOrigin = anchor;
            settlingStepOrigin = null;
            stepOriginStableTicks = 0;
            return Optional.empty();
        }

        settledStepOrigin = null;
        stepOriginStableTicks = 0;
        final PerceptionVec3 target = new PerceptionVec3(
                anchor.x() + 0.5,
                snapshot.core().eyePosition().y(),
                anchor.z() + 0.5
        );
        final AimResult aim = aimAt(snapshot.core(), target);
        if (!aim.accepted()) {
            return Optional.of(fail(NAME + ".look_rejected"));
        }
        final ActionOutcome movement;
        if (aim.errorDegrees() > MOVE_ALIGNMENT_DEGREES) {
            movement = coreActuator.stop();
        } else {
            movement = coreActuator.move(new MovementIntent(
                    STEP_ORIGIN_MOVE_SPEED,
                    0.0,
                    false,
                    false
            ));
        }
        if (!movement.accepted()) {
            return Optional.of(fail(
                    NAME + ".step_origin_move_"
                        + outcomeCode(movement)
            ));
        }
        phaseTicks++;
        return Optional.of(running(context, true, false));
    }

    private SkillTickResult beginHazardRetreat(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot,
            final SkillFailure reason
    ) {
        quiesce();
        pendingHazardFailure = reason;
        final GridPos lastCompleted = completedSteps == 0
                ? origin
                : stepDestination(parameters, completedSteps);
        final boolean atLastCompleted =
                arrived(snapshot.core(), lastCompleted);
        if (!atLastCompleted) {
            hazardRetreatDestination = lastCompleted;
        } else if (completedSteps > 0) {
            hazardRetreatDestination = completedSteps == 1
                    ? origin
                    : stepDestination(
                            parameters,
                            completedSteps - 1
                    );
        } else {
            /*
             * There is no previously completed corridor behind the origin.
             * Terminal failure deliberately hands authority to the shared
             * survival controller instead of inventing an unobserved retreat.
             */
            return fail(reason);
        }
        transition(Phase.RETREATING_HAZARD);
        return running(context, true, false);
    }

    private SkillTickResult retreatFromHazard(
            final SkillContext context,
            final ExcavateSafeTunnelParameters parameters,
            final Snapshot snapshot
    ) {
        if (hazardRetreatDestination == null
                || pendingHazardFailure == null) {
            return fail(NAME + ".hazard_retreat_invalid_state");
        }
        if (arrived(snapshot.core(), hazardRetreatDestination)) {
            final SkillFailure terminal = pendingHazardFailure;
            hazardRetreatDestination = null;
            pendingHazardFailure = null;
            return fail(terminal);
        }
        if (phaseTicks >= policy.maximumMoveTicks()) {
            return fail(NAME + ".hazard_retreat_timed_out");
        }

        final CellState lower = cellState(
                snapshot,
                hazardRetreatDestination,
                -1
        );
        final CellState upper = cellState(
                snapshot,
                hazardRetreatDestination.above(),
                -1
        );
        if (lower.failure().isPresent()) {
            return fail(lower.failure().orElseThrow());
        }
        if (upper.failure().isPresent()) {
            return fail(upper.failure().orElseThrow());
        }
        if (!lower.clear() || !upper.clear()) {
            final ActionOutcome look = lookAt(
                    snapshot.core(),
                    center(hazardRetreatDestination.above())
            );
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            return waitOrFail(
                    context,
                    NAME + ".hazard_retreat_clearance_unavailable",
                    policy.maximumMoveTicks()
            );
        }

        final GridPos support = hazardRetreatDestination.below();
        final Optional<SkillFailure> supportFailure =
                observedSupportFailure(snapshot, support, context);
        if (supportFailure.isPresent()) {
            return fail(supportFailure.orElseThrow());
        }
        if (!supportObserved(snapshot, support)) {
            final ActionOutcome look = lookAt(
                    snapshot.core(),
                    supportTop(support)
            );
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            return waitOrFail(
                    context,
                    NAME + ".hazard_retreat_support_unavailable",
                    policy.maximumMoveTicks()
            );
        }

        final PerceptionVec3 target = new PerceptionVec3(
                hazardRetreatDestination.x() + 0.5,
                snapshot.core().eyePosition().y(),
                hazardRetreatDestination.z() + 0.5
        );
        final AimResult aim = aimAt(snapshot.core(), target);
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > MOVE_ALIGNMENT_DEGREES) {
            coreActuator.stop();
            phaseTicks++;
            return running(context, true, false);
        }
        if (hazardRetreatDestination.y()
                > snapshot.core().position().y() + 0.35
                && snapshot.core().onGround()) {
            final ActionOutcome jumped = coreActuator.jump();
            if (!jumped.accepted()) {
                return fail(
                        NAME + ".hazard_retreat_jump_"
                            + outcomeCode(jumped)
                );
            }
        }
        final ActionOutcome moved = coreActuator.move(
                new MovementIntent(0.45, 0.0, false, false)
        );
        if (!moved.accepted()) {
            return fail(
                    NAME + ".hazard_retreat_move_"
                        + outcomeCode(moved)
            );
        }
        phaseTicks++;
        return running(context, true, false);
    }

    private SkillTickResult settleBeforeTargetCompletion(
            final SkillContext context,
            final Snapshot snapshot
    ) {
        quiesce();
        if (currentPoseStable) {
            return complete();
        }
        return waitOrFail(
                context,
                NAME + ".target_completion_not_stable",
                policy.maximumMoveTicks()
        );
    }

    private CellState cellState(
            final Snapshot snapshot,
            final GridPos cell,
            final long newerThanRevision
    ) {
        final Optional<VisibleBlockFace> visible =
                currentFace(snapshot.interaction(), cell);
        if (visible.isPresent()) {
            final VisibleBlockFace face = visible.orElseThrow();
            if (TORCH_ITEM_ID.equals(face.blockTypeId())
                    && verifiedClearCells.contains(cell)) {
                return CellState.clearCell();
            }
            final Optional<SkillFailure> blockFailure =
                    unsafeBlockFailure(face.blockTypeId());
            if (blockFailure.isPresent()) {
                return CellState.failed(blockFailure.orElseThrow());
            }
            return CellState.blocked();
        }
        final Optional<ObservedVoxel> observed =
                snapshot.core().navigation().voxelAt(cell);
        if (observed.isEmpty()
                || !recent(
                        snapshot.interaction().observationRevision(),
                        observed.orElseThrow().observationRevision()
                )
                || observed.orElseThrow().observationRevision()
                        <= newerThanRevision) {
            /*
             * The semantic fan can momentarily stop publishing a cell while
             * the body turns from its support inspection into the actual
             * move. Exact cells accepted here were previously bound to a
             * successful ordinary break and then independently re-observed
             * as air by postMiningCellState. A current visible surface or a
             * current non-air voxel still wins above/below. Vanilla
             * collision remains authoritative during the bounded move.
             */
            return verifiedClearCells.contains(cell)
                    ? CellState.clearCell()
                    : CellState.unknown();
        }
        final ObservedVoxel voxel = observed.orElseThrow();
        if (voxel.kind() == VoxelKind.AIR) {
            final boolean current =
                    voxel.observationRevision()
                        == snapshot.interaction()
                            .observationRevision();
            return (verifiedClearCells.contains(cell)
                    || current
                        && NavigationEvidence
                            .hasTraversalClearance(voxel))
                    ? CellState.clearCell()
                    : CellState.unknown();
        }
        if (voxel.kind().isLiquid()) {
            return CellState.failed(failure("fluid_exposed"));
        }
        return CellState.blocked();
    }

    /**
     * A fresh clear ray alone never proves player-volume clearance.  This
     * narrower verifier is allowed only for the exact block whose currently
     * visible face was bound to an accepted ordinary mining operation.  The
     * new semantic revision must show that surface gone before the cell enters
     * the bounded internally verified corridor.
     */
    private CellState postMiningCellState(
            final Snapshot snapshot,
            final GridPos cell,
            final long newerThanRevision
    ) {
        final Optional<VisibleBlockFace> visible =
                currentFace(snapshot.interaction(), cell);
        if (visible.isPresent()) {
            final Optional<SkillFailure> blockFailure =
                    unsafeBlockFailure(
                            visible.orElseThrow().blockTypeId()
                    );
            return blockFailure
                    .map(CellState::failed)
                    .orElseGet(CellState::blocked);
        }
        final Optional<ObservedVoxel> observed =
                snapshot.core().navigation().voxelAt(cell);
        if (observed.isEmpty()) {
            return CellState.unknown();
        }
        final ObservedVoxel voxel = observed.orElseThrow();
        if (voxel.observationRevision() <= newerThanRevision
                || voxel.observationRevision()
                    != snapshot.interaction()
                        .observationRevision()) {
            return CellState.unknown();
        }
        if (voxel.kind().isLiquid()) {
            return CellState.failed(failure("fluid_exposed"));
        }
        if (voxel.kind() != VoxelKind.AIR
                || voxel.occupancyEvidence()
                    == OccupancyEvidence.UNKNOWN
                || voxel.occupancyEvidence()
                    == OccupancyEvidence.SURFACE_HIT
                || voxel.occupancyEvidence()
                    == OccupancyEvidence.BODY_CONTACT) {
            return CellState.blocked();
        }
        return CellState.clearCell();
    }

    private boolean supportObserved(
            final Snapshot snapshot,
            final GridPos support
    ) {
        final Optional<ObservedVoxel> voxel =
                snapshot.core().navigation().voxelAt(support);
        final Optional<FaceEvidence> face = rememberedFace(
                snapshot.interaction().observationRevision(),
                support,
                BlockFace.UP
        );
        return voxel.isPresent()
                && movementEvidenceRecent(
                        snapshot.interaction().observationRevision(),
                        voxel.orElseThrow().observationRevision()
                )
                && voxel.orElseThrow().kind().supportsWeight()
                && voxel.orElseThrow().topSupportAffordance()
                    .safelySupportsStanding()
                && face.filter(evidence ->
                        evidence.topSupportAffordance()
                            .safelySupportsStanding()
                ).isPresent();
    }

    private Optional<SkillFailure> visibleSupportFailure(
            final Snapshot snapshot,
            final GridPos support,
            final SkillContext context
    ) {
        final Optional<VisibleBlockFace> face = currentFace(
                snapshot.interaction(),
                support,
                BlockFace.UP
        );
        if (face.isEmpty()) {
            return Optional.of(failure("support_not_observed"));
        }
        final Optional<SkillFailure> unsafe =
                unsafeBlockFailure(face.orElseThrow().blockTypeId());
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!face.orElseThrow()
                .topSupportAffordance()
                .safelySupportsStanding()) {
            return Optional.of(failure("unsafe_support"));
        }
        final Optional<ObservedVoxel> observed =
                snapshot.core().navigation().voxelAt(support);
        if (observed.isEmpty()
                || !recent(
                        snapshot.interaction().observationRevision(),
                        observed.orElseThrow().observationRevision()
                )) {
            return Optional.of(failure("support_not_observed"));
        }
        final ObservedVoxel voxel = observed.orElseThrow();
        if (!voxel.kind().supportsWeight()
                || voxel.kind().isLiquid()
                || !voxel.topSupportAffordance()
                    .safelySupportsStanding()
                || voxel.effectiveDanger() > maximumDanger(context)) {
            return Optional.of(failure("unsafe_support"));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> observedSupportFailure(
            final Snapshot snapshot,
            final GridPos support,
            final SkillContext context
    ) {
        final Optional<ObservedVoxel> observed =
                snapshot.core().navigation().voxelAt(support);
        if (observed.isPresent()
                && recent(
                        snapshot.interaction().observationRevision(),
                        observed.orElseThrow().observationRevision()
                )) {
            final ObservedVoxel voxel = observed.orElseThrow();
            if (!voxel.kind().supportsWeight()
                    || voxel.kind().isLiquid()
                    || voxel.topSupportAffordance()
                        == TopSupportAffordance
                            .NON_STURDY_OR_PARTIAL) {
                return Optional.of(failure("unsafe_support"));
            }
            if (voxel.effectiveDanger() > maximumDanger(context)) {
                return Optional.of(failure("unsafe_support"));
            }
        }
        final Optional<FaceEvidence> face = rememberedFace(
                snapshot.interaction().observationRevision(),
                support,
                BlockFace.UP
        );
        if (face.isPresent()) {
            final FaceEvidence evidence = face.orElseThrow();
            final Optional<SkillFailure> unsafe =
                    unsafeBlockFailure(evidence.blockTypeId());
            if (unsafe.isPresent()) {
                return unsafe;
            }
            if (!evidence.topSupportAffordance()
                    .safelySupportsStanding()) {
                return Optional.of(failure("unsafe_support"));
            }
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> immediateEnvironmentFailure(
            final Snapshot snapshot
    ) {
        final GridPos feet = snapshot.core().feet();
        for (VisibleEntity entity
                : snapshot.interaction().visibleEntities()) {
            if (!"minecraft:falling_block".equals(
                    entity.entityTypeId()
            )) {
                continue;
            }
            final GridPos block = floor(entity.position());
            if (entity.distance() <= 4.0
                    && (manhattan(block, feet) <= 3
                        || destination != null
                            && manhattan(block, destination) <= 3)) {
                return Optional.of(failure(
                        "falling_block_entity_exposed"
                ));
            }
        }
        for (VisibleBlockFace face
                : snapshot.interaction().visibleBlockFaces()) {
            final GridPos block = key(face);
            if (manhattan(block, feet) > 3
                    && (destination == null
                            || manhattan(block, destination) > 3)) {
                continue;
            }
            if (isFluid(face.blockTypeId())) {
                return Optional.of(failure("fluid_exposed"));
            }
            if ((block.equals(activeBlock)
                    || destination != null
                        && (block.equals(destination)
                            || block.equals(destination.above())))
                    && isUnstable(face.blockTypeId())) {
                return Optional.of(failure(
                        "unstable_block_exposed"
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = maximumDanger(context);
        if (context.riskScore() > maximumDanger
                || frame.danger() > maximumDanger) {
            return Optional.of(failure("danger_detected"));
        }
        final double healthFraction =
                frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? policy.hardcoreMinimumHealthFraction()
                : policy.normalMinimumHealthFraction();
        if (healthFraction < minimumHealth) {
            return Optional.of(failure("health_reserve_low"));
        }
        final int minimumFood = context.hardcore()
                ? policy.hardcoreMinimumFood()
                : policy.normalMinimumFood();
        if (frame.foodLevel() < minimumFood) {
            return Optional.of(failure("food_reserve_low"));
        }
        if (frame.inWater()) {
            return Optional.of(failure("unexpected_fluid_contact"));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> unsafeBlockFailure(
            final String blockId
    ) {
        if (isFluid(blockId)) {
            return Optional.of(failure("fluid_exposed"));
        }
        if (isUnstable(blockId)) {
            return Optional.of(failure("unstable_block_exposed"));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> heldPickaxeDurabilityFailure(
            final InteractionSkillFrame frame,
            final ExcavateSafeTunnelParameters parameters,
            final int stepsAlreadyCompleted
    ) {
        final HeldItemSummary held = frame.mainHand();
        if (!parameters.pickaxeItemId().equals(held.itemId())) {
            return Optional.empty();
        }
        if (held.maxDamage() <= 0) {
            return Optional.empty();
        }
        final int remaining =
                held.maxDamage() - held.damage();
        final int required = policy.durabilityReserve()
                + 2 * (
                    parameters.maximumSteps()
                        - stepsAlreadyCompleted
                );
        return remaining <= required
                ? Optional.of(failure(
                        "pickaxe_durability_reserve"
                ))
                : Optional.empty();
    }

    private SnapshotResult currentSnapshot(
            final DimensionRef dimension,
            final long expectedSession
    ) {
        final Optional<CoreSkillFrame> core = coreFrames.current();
        final Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        final Optional<ResourceInventoryState> ownedInventory =
                inventory.current();
        if (core.isEmpty()
                || interaction.isEmpty()
                || ownedInventory.isEmpty()) {
            return SnapshotResult.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final CoreSkillFrame coreFrame = core.orElseThrow();
        final InteractionSkillFrame interactionFrame =
                interaction.orElseThrow();
        final ResourceInventoryState inventoryState =
                ownedInventory.orElseThrow();
        if (!expectedPlayerId.equals(coreFrame.playerId())
                || !expectedPlayerId.equals(
                        interactionFrame.playerId()
                )) {
            return SnapshotResult.failed(NAME + ".player_mismatch");
        }
        if (!dimension.equals(coreFrame.dimension())
                || !dimension.equals(
                        interactionFrame.dimension()
                )) {
            return SnapshotResult.failed(NAME + ".dimension_mismatch");
        }
        if (coreFrame.observationRevision()
                != interactionFrame.observationRevision()) {
            return SnapshotResult.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        if (interactionFrame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return SnapshotResult.failed(NAME + ".stale_observation");
        }
        final OptionalLong session =
                interactionActuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                        != interactionFrame.sessionGeneration()
                || session.orElseThrow()
                        != inventoryState.sessionGeneration()
                || expectedSession >= 0
                    && session.orElseThrow() != expectedSession) {
            return SnapshotResult.failed(NAME + ".session_mismatch");
        }
        return SnapshotResult.valid(new Snapshot(
                coreFrame,
                interactionFrame,
                inventoryState
        ));
    }

    private void ingestFaces(
            final InteractionSkillFrame frame,
            final ExcavateSafeTunnelParameters parameters
    ) {
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            final GridPos block = key(face);
            if (origin != null
                    && manhattan(block, origin)
                        > observationManhattanBound(parameters)) {
                continue;
            }
            final BlockFace side;
            try {
                side = BlockFace.valueOf(
                        face.face().toUpperCase(Locale.ROOT)
                );
            } catch (IllegalArgumentException exception) {
                continue;
            }
            observedFaces.put(
                    new FaceKey(block, side),
                    new FaceEvidence(
                            frame.observationRevision(),
                            face.blockTypeId(),
                            face.topSupportAffordance()
                    )
            );
        }
        observedFaces.entrySet().removeIf(entry ->
                !recent(
                        frame.observationRevision(),
                        entry.getValue().observationRevision()
                )
        );
    }

    private Optional<GridPos> visibleTarget(
            final InteractionSkillFrame frame,
            final ExcavateSafeTunnelParameters parameters
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> parameters.isTarget(
                        face.blockTypeId()
                ))
                .map(ExcavateSafeTunnelSkill::key)
                .filter(block -> origin == null
                        || manhattan(block, origin)
                            <= observationManhattanBound(parameters))
                .findFirst();
    }

    private static int observationManhattanBound(
            final ExcavateSafeTunnelParameters parameters
    ) {
        /*
         * A descending step advances one block horizontally and one block
         * vertically. Its support is another block down, so Manhattan
         * distance grows at roughly twice the step count. Using the
         * horizontal-only envelope silently discarded valid late-leg floor
         * faces even though they were present in the fair first-person
         * frame.
         */
        final int distancePerStep =
                parameters.mode() == TunnelMode.HORIZONTAL ? 1 : 2;
        return parameters.maximumSteps() * distancePerStep + 8;
    }

    private Optional<VisibleBlockFace> currentFace(
            final InteractionSkillFrame frame,
            final GridPos block
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> key(face).equals(block))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    /**
     * Uses the body's tick-local centre crosshair after an active turn, with
     * the bounded semantic frame as the fallback. The 4 Hz scene sampler can
     * legitimately miss a one-block stair face between two samples; forcing
     * mining to wait only on that fan made a correctly aligned headless
     * player time out despite vanilla's own crosshair selecting the block.
     * This path remains a single finite first-person OUTLINE ray and never
     * scans or reads a hidden cell.
     */
    private Optional<VisibleBlockFace> currentActionFace(
            final InteractionSkillFrame frame,
            final GridPos block
    ) {
        final Optional<VisibleBlockFace> crosshair =
                currentCrosshairFace(block);
        return crosshair.isPresent()
                ? crosshair
                : currentFace(frame, block);
    }

    private Optional<VisibleBlockFace> currentCrosshairFace(
            final GridPos block
    ) {
        return interactionFrames.currentCrosshairBlock()
                .filter(face -> key(face).equals(block));
    }

    private Optional<VisibleBlockFace> currentFace(
            final InteractionSkillFrame frame,
            final GridPos block,
            final BlockFace side
    ) {
        final String expected =
                side.name().toLowerCase(Locale.ROOT);
        return frame.visibleBlockFaces().stream()
                .filter(face -> key(face).equals(block)
                        && expected.equals(face.face()))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<FaceEvidence> rememberedFace(
            final long currentRevision,
            final GridPos block,
            final BlockFace side
    ) {
        final FaceEvidence evidence = observedFaces.get(
                new FaceKey(block, side)
        );
        return evidence != null
                && recent(
                        currentRevision,
                        evidence.observationRevision()
                )
                ? Optional.of(evidence)
                : Optional.empty();
    }

    private GridPos stepDestination(
            final ExcavateSafeTunnelParameters parameters,
            final int oneBasedStep
    ) {
        final int vertical = switch (parameters.mode()) {
            case HORIZONTAL -> 0;
            case DESCENDING -> -oneBasedStep;
            case ASCENDING -> oneBasedStep;
        };
        return origin.offset(
                parameters.direction().stepX() * oneBasedStep,
                vertical,
                parameters.direction().stepZ() * oneBasedStep
        );
    }

    private SkillTickResult waitOrFail(
            final SkillContext context,
            final String code,
            final int maximumTicks
    ) {
        phaseTicks++;
        if (phaseTicks > maximumTicks) {
            return fail(code);
        }
        return running(context, false, false);
    }

    private void transition(final Phase next) {
        phase = Objects.requireNonNull(next, "next");
        phaseTicks = 0;
        equipAttempts = next == Phase.EQUIPPING_TORCH
                || next == Phase.EQUIPPING_PICKAXE
                ? equipAttempts
                : 0;
    }

    private SkillTickResult running(
            final SkillContext context,
            final boolean madeProgress,
            final boolean safeBoundary
    ) {
        final boolean checkpoint = safeBoundary
                && currentPoseStable
                && phase.checkpointBoundary();
        if (checkpoint) {
            quiesce();
        }
        return SkillTickResult.running(madeProgress, checkpoint);
    }

    private SkillTickResult complete() {
        quiesce();
        phase = Phase.COMPLETED;
        clearTransientBinding();
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        clearTransientBinding();
        return SkillTickResult.failed(reason);
    }

    private void quiesce() {
        interactionActuator.abortMining();
        coreActuator.stop();
    }

    private void clearTransientBinding() {
        destination = null;
        activeBlock = null;
        actionObservationRevision = -1;
        torchCountBeforePlacement = -1;
        torchPlacementBlock = null;
        hazardRetreatDestination = null;
        pendingHazardFailure = null;
        committedStepSupport = null;
        settledStepOrigin = null;
        settlingStepOrigin = null;
        stepOriginStableTicks = 0;
    }

    private AimResult aimAt(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta =
                target.subtract(frame.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return new AimResult(true, 0.0);
        }
        final PerceptionVec3 direction = delta.normalized();
        final double dot = Math.max(
                -1.0,
                Math.min(
                        1.0,
                        frame.lookDirection().dot(direction)
                )
        );
        final double error = Math.toDegrees(Math.acos(dot));
        final float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float pitch = (float) Math.toDegrees(
                Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                )
        );
        return new AimResult(
                coreActuator.look(new LookIntent(yaw, pitch))
                        .accepted(),
                error
        );
    }

    private ActionOutcome lookAt(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta =
                target.subtract(frame.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return ActionOutcome.COMPLETED;
        }
        final float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float pitch = (float) Math.toDegrees(
                Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                )
        );
        return coreActuator.look(new LookIntent(yaw, pitch));
    }

    private boolean arrived(
            final CoreSkillFrame frame,
            final GridPos target
    ) {
        final double dx = frame.position().x()
                - (target.x() + 0.5);
        final double dz = frame.position().z()
                - (target.z() + 0.5);
        return frame.onGround()
                && !frame.inWater()
                && Math.hypot(dx, dz)
                    <= ARRIVAL_HORIZONTAL_DISTANCE
                && Math.abs(
                        frame.position().y() - target.y()
                ) <= 0.30;
    }

    private boolean recent(
            final long currentRevision,
            final long evidenceRevision
    ) {
        return evidenceRevision <= currentRevision
                && currentRevision - evidenceRevision
                    <= policy.maximumEvidenceRevisionLag();
    }

    private boolean movementEvidenceRecent(
            final long currentRevision,
            final long evidenceRevision
    ) {
        return evidenceRevision <= currentRevision
                && currentRevision - evidenceRevision <= 1;
    }

    private double maximumDanger(final SkillContext context) {
        return context.hardcore()
                ? policy.hardcoreMaximumDanger()
                : policy.normalMaximumDanger();
    }

    private static boolean ownsItem(
            final InteractionSkillFrame frame,
            final String itemId
    ) {
        return itemCount(frame, itemId) > 0;
    }

    private static int itemCount(
            final InteractionSkillFrame frame,
            final String itemId
    ) {
        final int aggregate = frame.inventory().stream()
                .filter(item -> itemId.equals(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
        if (aggregate > 0) {
            return aggregate;
        }
        int fallback = 0;
        if (itemId.equals(frame.mainHand().itemId())) {
            fallback += frame.mainHand().count();
        }
        if (itemId.equals(frame.offHand().itemId())) {
            fallback += frame.offHand().count();
        }
        return fallback;
    }

    private static boolean isFluid(final String blockId) {
        return blockId.equals("minecraft:water")
                || blockId.equals("minecraft:lava")
                || blockId.endsWith(":water")
                || blockId.endsWith(":lava");
    }

    private static boolean isUnstable(final String blockId) {
        return blockId.equals("minecraft:sand")
                || blockId.equals("minecraft:red_sand")
                || blockId.equals("minecraft:gravel")
                || blockId.equals("minecraft:suspicious_sand")
                || blockId.equals("minecraft:suspicious_gravel")
                || blockId.endsWith("_concrete_powder");
    }

    private static GridPos key(final VisibleBlockFace face) {
        return new GridPos(
                face.block().x(),
                face.block().y(),
                face.block().z()
        );
    }

    private static GridPos floor(final PerceptionVec3 position) {
        return new GridPos(
                (int) Math.floor(position.x()),
                (int) Math.floor(position.y()),
                (int) Math.floor(position.z())
        );
    }

    private static int manhattan(
            final GridPos first,
            final GridPos second
    ) {
        return Math.abs(first.x() - second.x())
                + Math.abs(first.y() - second.y())
                + Math.abs(first.z() - second.z());
    }

    private static PerceptionVec3 center(final GridPos block) {
        return new PerceptionVec3(
                block.x() + 0.5,
                block.y() + 0.5,
                block.z() + 0.5
        );
    }

    private static PerceptionVec3 supportTop(
            final GridPos support
    ) {
        return new PerceptionVec3(
                support.x() + 0.5,
                support.y() + 1.0,
                support.z() + 0.5
        );
    }

    private static BlockInteractionTarget interactionTarget(
            final VisibleBlockFace face
    ) {
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(
                        face.face().toUpperCase(Locale.ROOT)
                ),
                new ActionVec3(
                        face.hitPosition().x(),
                        face.hitPosition().y(),
                        face.hitPosition().z()
                )
        );
    }

    private static String outcomeCode(final ActionOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    private static SkillFailure failure(final String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        PREPARE_TORCH,
        EQUIPPING_TORCH,
        PLACING_TORCH,
        VERIFYING_TORCH,
        EQUIPPING_PICKAXE,
        PREPARE_STEP,
        SEEKING_BLOCK_FACE,
        AIMING_TO_MINE,
        MINING,
        VERIFYING_CLEARANCE,
        VERIFYING_SUPPORT,
        MOVING,
        RETREATING_HAZARD,
        SETTLING_TARGET,
        COMPLETED,
        FAILED,
        CANCELLED;

        boolean active() {
            return this != IDLE
                    && this != COMPLETED
                    && this != FAILED
                    && this != CANCELLED;
        }

        boolean checkpointBoundary() {
            return this == PREPARE_TORCH
                    || this == PREPARE_STEP
                    || this == COMPLETED;
        }
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction,
            ResourceInventoryState inventory
    ) {
    }

    private record SnapshotResult(
            Optional<Snapshot> snapshot,
            Optional<SkillFailure> failure
    ) {
        static SnapshotResult valid(final Snapshot snapshot) {
            return new SnapshotResult(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        static SnapshotResult failed(final String code) {
            return new SnapshotResult(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    private record FaceKey(GridPos block, BlockFace side) {
    }

    private record FaceEvidence(
            long observationRevision,
            String blockTypeId,
            TopSupportAffordance topSupportAffordance
    ) {
    }

    private record AimResult(boolean accepted, double errorDegrees) {
    }

    private record CellState(
            boolean clear,
            Optional<SkillFailure> failure
    ) {
        static CellState clearCell() {
            return new CellState(true, Optional.empty());
        }

        static CellState blocked() {
            return new CellState(false, Optional.empty());
        }

        static CellState unknown() {
            return blocked();
        }

        static CellState failed(final SkillFailure failure) {
            return new CellState(
                    false,
                    Optional.of(failure)
            );
        }
    }
}
