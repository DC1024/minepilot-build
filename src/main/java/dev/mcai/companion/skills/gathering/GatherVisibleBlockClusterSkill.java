package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
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
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.loot.CollectObservedItemParameters;
import dev.mcai.companion.skills.loot.CollectObservedItemSkill;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Gathers a bounded connected cluster using only successive fair semantic
 * observations and ordinary player inputs.
 *
 * <p>The seed is bound to an exact observation. After that, a coordinate may
 * be added only when a new first-person ray reports the requested block type,
 * it is within the bounded seed radius, and it is face-adjacent to the already
 * discovered component. There is deliberately no level, chunk, registry
 * search, loot lookup, or hidden-block accessor in this class.</p>
 */
public final class GatherVisibleBlockClusterSkill
        implements Skill<GatherVisibleBlockClusterParameters> {
    private static final String NAME = "gather_visible_block_cluster";
    private static final String ITEM_ENTITY_ID = "minecraft:item";
    private static final double LOOK_EPSILON = 1.0E-12;
    private static final double AIM_ALIGNMENT_DEGREES = 2.5;
    private static final int MAXIMUM_AIM_TICKS = 80;
    /*
     * Item pickup is collision based. Stopping at ordinary interaction reach
     * leaves a dropped stack visibly nearby but outside the player's body.
     */
    private static final double PICKUP_DISTANCE = 0.25;
    private static final double PICKUP_MICRO_APPROACH_DISTANCE = 1.25;
    private static final double PICKUP_ORIGIN_RADIUS = 3.5;
    private static final double STUCK_PROGRESS_DISTANCE_SQUARED = 0.01;
    private static final int MAXIMUM_APPROACH_SUPPORT_PROBES = 8;
    private static final float[] SCAN_PITCHES = {
            -35.0F,
            0.0F,
            20.0F,
            40.0F
    };
    private static final int[][] CARDINALS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final ResourceInventorySource inventory;
    private final GatheringSkillPolicy policy;

    private final Set<BlockKey> discovered = new LinkedHashSet<>();
    private final Set<BlockKey> completed = new LinkedHashSet<>();
    private final Set<BlockKey> unavailable = new LinkedHashSet<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private DimensionRef boundDimension;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private BlockKey seed;
    private BlockKey target;
    private PerceptionVec3 targetAimPoint;
    private BlockKey lastMinedOrigin;
    private Optional<String> requiredPickupItemId = Optional.empty();
    private int directDropCountAtStart;
    private int directDropCountBeforeMining;
    private int collectionRequiredOwnedCount;
    private boolean finalCollectionSweep;
    private long miningStartedAtTick = -1;
    private long aimStartedAtTick = -1;
    private long collectionEndsAtTick = -1;
    private CollectObservedItemSkill dropCollector;
    private CollectObservedItemParameters dropCollectorParameters;
    private long nextCollectionScanTick = -1;
    private long nextScanTick = -1;
    private long lastScanObservationRevision = -1;
    private float scanBaseYaw;
    private int scanTurns;
    private int blocksMined;
    private int equipAttempts;
    private long equippedAtRevision = -1;
    private PerceptionVec3 motionWindowPosition;
    private long motionWindowStartedTick = -1;
    private int stuckRecoveries;
    private int approachSupportProbes;
    private long lastApproachSupportProbeRevision = -1;
    private DropCollectionDebt uncollectedDropDebt;

    public GatherVisibleBlockClusterSkill(
            UUID expectedPlayerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            ResourceInventorySource inventory,
            GatheringSkillPolicy policy
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
    public SkillParameterParser<GatherVisibleBlockClusterParameters>
            parameters() {
        return GatheringSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        SnapshotResult current = currentSnapshot(parameters.dimension(), -1);
        if (current.failure().isPresent()) {
            return current.failure();
        }
        Snapshot snapshot = current.snapshot().orElseThrow();
        Optional<SkillFailure> safety = safetyFailure(context, snapshot.core());
        if (safety.isPresent()) {
            return safety;
        }
        Optional<VisibleBlockFace> visible = initialSeed(
                snapshot,
                parameters
        );
        if (visible.isEmpty()) {
            return Optional.of(failure("seed_not_visible"));
        }
        if (snapshot.inventory().emptyMainInventorySlots() == 0) {
            return Optional.of(failure("inventory_full"));
        }
        if (!parameters.keepsCurrentHand()
                && !ownsItem(
                        snapshot.interaction(),
                        parameters.toolItemId()
                )) {
            return Optional.of(failure("tool_unavailable"));
        }
        if (!parameters.keepsCurrentHand()
                && parameters.toolItemId().equals(
                        snapshot.interaction().mainHand().itemId()
                )) {
            Optional<SkillFailure> durability = durabilityFailure(
                    snapshot.interaction().mainHand()
            );
            if (durability.isPresent()) {
                return durability;
            }
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        SnapshotResult current = currentSnapshot(parameters.dimension(), -1);
        if (current.failure().isPresent()) {
            throw new IllegalStateException(
                    "Gathering body binding changed before start"
            );
        }
        Snapshot snapshot = current.snapshot().orElseThrow();
        if (initialSeed(snapshot, parameters).isEmpty()) {
            throw new IllegalStateException(
                    "Gathering seed changed before start"
            );
        }

        phase = Phase.READY;
        failure = null;
        uncollectedDropDebt = null;
        boundDimension = parameters.dimension();
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        startedAtTick = context.gameTick();
        lastObservationRevision =
                snapshot.interaction().observationRevision();
        seed = new BlockKey(
                parameters.seed().x(),
                parameters.seed().y(),
                parameters.seed().z()
        );
        target = null;
        targetAimPoint = null;
        lastMinedOrigin = null;
        requiredPickupItemId = requiredPickupItemId(
                parameters.blockId()
        );
        directDropCountAtStart = requiredPickupItemId
                .map(itemId -> ownedInventoryCount(
                        snapshot,
                        itemId
                ))
                .orElse(0);
        directDropCountBeforeMining = 0;
        collectionRequiredOwnedCount = directDropCountAtStart;
        finalCollectionSweep = false;
        miningStartedAtTick = -1;
        aimStartedAtTick = -1;
        collectionEndsAtTick = -1;
        dropCollector = null;
        dropCollectorParameters = null;
        nextCollectionScanTick = -1;
        nextScanTick = context.gameTick();
        lastScanObservationRevision = -1;
        scanBaseYaw = yawOf(snapshot.core().lookDirection());
        scanTurns = 0;
        blocksMined = 0;
        equipAttempts = 0;
        equippedAtRevision = -1;
        motionWindowPosition = null;
        motionWindowStartedTick = -1;
        stuckRecoveries = 0;
        approachSupportProbes = 0;
        lastApproachSupportProbeRevision = -1;
        discovered.clear();
        completed.clear();
        unavailable.clear();
        discovered.add(seed);
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
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
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        StringBuilder payload = new StringBuilder(512);
        payload.append("{\"phase\":\"")
                .append(phase.name())
                .append("\",\"dimension\":\"")
                .append(parameters.dimension().id())
                .append("\",\"blockId\":\"")
                .append(parameters.blockId())
                .append("\",\"seed\":[")
                .append(seed.x()).append(',')
                .append(seed.y()).append(',')
                .append(seed.z())
                .append("],\"completed\":[");
        boolean first = true;
        for (BlockKey block : completed) {
            if (!first) {
                payload.append(',');
            }
            first = false;
            payload.append('[')
                    .append(block.x()).append(',')
                    .append(block.y()).append(',')
                    .append(block.z()).append(']');
        }
        payload.append("],\"mined\":")
                .append(blocksMined)
                .append(",\"max\":")
                .append(parameters.maxBlocks())
                .append(",\"session\":")
                .append(boundSessionGeneration)
                .append(",\"observation\":")
                .append(lastObservationRevision)
                .append(",\"target\":")
                .append(target == null
                        ? "null"
                        : "[%d,%d,%d]".formatted(
                                target.x(),
                                target.y(),
                                target.z()
                        ))
                .append(",\"scanTurns\":")
                .append(scanTurns)
                .append(",\"approachSupportProbes\":")
                .append(approachSupportProbes)
                .append(",\"lastApproachSupportProbeRevision\":")
                .append(lastApproachSupportProbeRevision)
                .append(",\"unavailable\":")
                .append(unavailable.size())
                .append('}');
        return new SkillCheckpoint(1, payload.toString());
    }

    @Override
    public void cancel(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearBinding();
    }

    @Override
    public SkillResult result(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
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
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        SnapshotResult current = currentSnapshot(
                parameters.dimension(),
                boundSessionGeneration
        );
        if (current.failure().isPresent()) {
            return fail(current.failure().orElseThrow());
        }
        Snapshot snapshot = current.snapshot().orElseThrow();
        if (snapshot.interaction().observationRevision()
                < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        lastObservationRevision = Math.max(
                lastObservationRevision,
                snapshot.interaction().observationRevision()
        );

        Optional<SkillFailure> safety = safetyFailure(
                context,
                snapshot.core()
        );
        if (safety.isPresent()) {
            return fail(safety.orElseThrow());
        }
        if (!parameters.keepsCurrentHand()
                && parameters.toolItemId().equals(
                        snapshot.interaction().mainHand().itemId()
                )) {
            Optional<SkillFailure> durability = durabilityFailure(
                    snapshot.interaction().mainHand()
            );
            if (durability.isPresent()) {
                return fail(durability.orElseThrow());
            }
        }

        return switch (phase) {
            case READY -> ready(context, parameters, snapshot);
            case EQUIPPING -> waitForEquipment(
                    context,
                    parameters,
                    snapshot
            );
            case AIMING -> aimAndBeginMining(
                    context,
                    parameters,
                    snapshot
            );
            case APPROACHING -> approach(
                    context,
                    parameters,
                    snapshot
            );
            case MINING -> continueMining(
                    context,
                    parameters,
                    snapshot
            );
            case COLLECTING -> collectDrops(
                    context,
                    parameters,
                    snapshot
            );
            case SCANNING -> scan(
                    context,
                    parameters,
                    snapshot
            );
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    private SkillTickResult ready(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        if (!parameters.keepsCurrentHand()
                && !parameters.toolItemId().equals(
                        snapshot.interaction().mainHand().itemId()
                )) {
            if (!ownsItem(
                    snapshot.interaction(),
                    parameters.toolItemId()
            )) {
                return fail(NAME + ".tool_unavailable");
            }
            return equipTool(context, parameters, snapshot);
        }
        if (snapshot.inventory().emptyMainInventorySlots() == 0) {
            return fail(NAME + ".inventory_full");
        }
        if (blocksMined >= parameters.maxBlocks()) {
            return complete();
        }

        Optional<VisibleBlockFace> next = selectCandidate(
                snapshot,
                parameters
        );
        if (next.isEmpty()) {
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        VisibleBlockFace face = next.orElseThrow();
        target = key(face);
        targetAimPoint = face.hitPosition();
        approachSupportProbes = 0;
        lastApproachSupportProbeRevision = -1;
        directDropCountBeforeMining = requiredPickupItemId
                .map(itemId -> ownedInventoryCount(
                        snapshot,
                        itemId
                ))
                .orElse(0);
        discovered.add(target);
        beginAiming(context, snapshot.core());
        return running(context, true, true);
    }

    private SkillTickResult equipTool(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        ActionOutcome equipped = interactionActuator.equipMainHand(
                parameters.toolItemId()
        );
        if (!equipped.accepted()) {
            return fail(
                    NAME + ".equip_" + outcomeCode(equipped)
            );
        }
        equipAttempts++;
        equippedAtRevision =
                snapshot.interaction().observationRevision();
        phase = Phase.EQUIPPING;
        return running(context, true, true);
    }

    private SkillTickResult waitForEquipment(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        if (parameters.toolItemId().equals(
                snapshot.interaction().mainHand().itemId()
        )) {
            Optional<SkillFailure> durability = durabilityFailure(
                    snapshot.interaction().mainHand()
            );
            if (durability.isPresent()) {
                return fail(durability.orElseThrow());
            }
            phase = Phase.READY;
            return running(context, true, true);
        }
        if (snapshot.interaction().observationRevision()
                <= equippedAtRevision) {
            return running(context, false, false);
        }
        if (equipAttempts >= 2
                || !ownsItem(
                        snapshot.interaction(),
                        parameters.toolItemId()
                )) {
            return fail(NAME + ".tool_equip_not_observed");
        }
        return equipTool(context, parameters, snapshot);
    }

    private SkillTickResult aimAndBeginMining(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        final Optional<VisibleBlockFace> crosshair =
                currentCrosshairTarget(parameters);
        if (crosshair.isPresent()) {
            return beginMining(
                    context,
                    parameters,
                    snapshot,
                    crosshair.orElseThrow()
            );
        }
        if (aimStartedAtTick < 0) {
            aimStartedAtTick = context.gameTick();
        }
        if (context.gameTick() - aimStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            if (target != null) {
                unavailable.add(target);
            }
            target = null;
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        final Optional<VisibleBlockFace> current = currentTargetFace(
                snapshot.interaction(),
                parameters
        );
        final PerceptionVec3 aimPoint = targetAimPoint != null
                ? targetAimPoint
                : current.map(VisibleBlockFace::hitPosition)
                    .orElse(null);
        if (aimPoint == null) {
            target = null;
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        final double distance = aimPoint
                .subtract(snapshot.core().eyePosition())
                .length();
        if (distance > policy.miningApproachDistance()) {
            phase = Phase.APPROACHING;
            aimStartedAtTick = -1;
            return approach(context, parameters, snapshot);
        }

        ActionOutcome stopped = coreActuator.stop();
        if (!stopped.accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        AimResult aim = aimAt(snapshot.core(), aimPoint);
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return running(context, true, false);
        }

        if (current.isEmpty()) {
            /*
             * The target left the lower-frequency semantic fan while the
             * head was turning. Keep aiming at the recently ray-proven point,
             * but require the tick-local crosshair to re-identify the exact
             * block before any mining mutation is attempted.
             */
            return running(context, true, false);
        }
        return beginMining(
                context,
                parameters,
                snapshot,
                current.orElseThrow()
        );
    }

    private SkillTickResult beginMining(
            final SkillContext context,
            final GatherVisibleBlockClusterParameters parameters,
            final Snapshot snapshot,
            final VisibleBlockFace face
    ) {
        final ActionOutcome mining = interactionActuator.beginMining(
                interactionTarget(face)
        );
        if (mining == ActionOutcome.COMPLETED) {
            return blockCompleted(context, parameters);
        }
        if (mining == ActionOutcome.TARGET_OUT_OF_REACH) {
            interactionActuator.abortMining();
            phase = Phase.APPROACHING;
            aimStartedAtTick = -1;
            return running(context, true, true);
        }
        if (mining == ActionOutcome.TARGET_OCCLUDED) {
            interactionActuator.abortMining();
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        if (!mining.accepted()) {
            return fail(
                    NAME + ".mining_" + outcomeCode(mining)
            );
        }
        miningStartedAtTick = context.gameTick();
        aimStartedAtTick = -1;
        phase = Phase.MINING;
        return running(context, true, true);
    }

    private SkillTickResult continueMining(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        if (context.gameTick() - miningStartedAtTick
                >= policy.maximumBlockMiningTicks()) {
            interactionActuator.abortMining();
            return fail(NAME + ".mining_timed_out");
        }
        ActionOutcome outcome = interactionActuator.continueMining();
        if (outcome == ActionOutcome.COMPLETED) {
            return blockCompleted(context, parameters);
        }
        if (outcome.accepted()) {
            return running(context, true, false);
        }
        interactionActuator.abortMining();
        if (outcome == ActionOutcome.TARGET_OUT_OF_REACH) {
            phase = Phase.APPROACHING;
            return running(context, true, true);
        }
        if (outcome == ActionOutcome.TARGET_OCCLUDED) {
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        if (outcome == ActionOutcome.TARGET_CHANGED
                || outcome == ActionOutcome.TARGET_UNLOADED
                || outcome == ActionOutcome.WORLD_DENIED) {
            if (target != null) {
                unavailable.add(target);
            }
            target = null;
            targetAimPoint = null;
            phase = Phase.READY;
            return running(context, true, true);
        }
        return fail(
                NAME + ".mining_" + outcomeCode(outcome)
        );
    }

    private SkillTickResult blockCompleted(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters
    ) {
        if (target == null) {
            return fail(NAME + ".target_binding_lost");
        }
        completed.add(target);
        lastMinedOrigin = target;
        target = null;
        targetAimPoint = null;
        aimStartedAtTick = -1;
        blocksMined++;
        collectionRequiredOwnedCount = Math.max(
                directDropCountAtStart + blocksMined,
                directDropCountBeforeMining + 1
        );
        finalCollectionSweep =
                blocksMined >= parameters.maxBlocks();
        miningStartedAtTick = -1;
        interactionActuator.abortMining();
        coreActuator.stop();
        collectionEndsAtTick =
                context.gameTick() + policy.collectionTicks();
        dropCollector = null;
        dropCollectorParameters = null;
        nextCollectionScanTick = context.gameTick();
        phase = policy.collectionTicks() == 0
                ? Phase.READY
                : Phase.COLLECTING;
        resetScan(context);
        if (blocksMined >= parameters.maxBlocks()
                && policy.collectionTicks() == 0) {
            return complete();
        }
        return running(context, true, true);
    }

    private SkillTickResult collectDrops(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        final Optional<String> requiredItem =
                requiredPickupItemId;
        final boolean requiresDirectPickup =
                requiredItem.isPresent();
        final int requiredDirectDropCount =
                collectionRequiredOwnedCount;
        if (requiresDirectPickup
                && ownedInventoryCount(
                    snapshot,
                    requiredItem.orElseThrow()
                ) >= requiredDirectDropCount) {
            if (Boolean.getBoolean("mcai.liveModelTest")) {
                MinecraftAiCompanion.LOGGER.info(
                        "Gatherer confirmed cumulative pickup for {} "
                            + "from {}: "
                            + "owned={}, required={}, mined={}, "
                            + "beforeCurrentBlock={}",
                        requiredItem.orElseThrow(),
                        parameters.blockId(),
                        ownedInventoryCount(
                                snapshot,
                                requiredItem.orElseThrow()
                        ),
                        requiredDirectDropCount,
                        blocksMined,
                        directDropCountBeforeMining
                );
            }
            return finishCollection(context, parameters);
        }
        if (context.gameTick() >= collectionEndsAtTick) {
            coreActuator.stop();
            if (requiresDirectPickup) {
                MinecraftAiCompanion.LOGGER.debug(
                        "Gatherer timed out collecting {} after mining {}: "
                            + "player={}, feet={}, origin={}, "
                            + "visibleMatchingDrop={}, owned={}, required={}",
                        parameters.blockId(),
                        blocksMined,
                        snapshot.core().position(),
                        snapshot.core().feet(),
                        lastMinedOrigin,
                        visibleNearbyDrop(
                                snapshot.core(),
                                requiredItem
                        ).map(VisibleEntity::position),
                        ownedInventoryCount(
                                snapshot,
                                requiredItem.orElseThrow()
                        ),
                        requiredDirectDropCount
                );
                if (finalCollectionSweep
                        || blocksMined >= parameters.maxBlocks()) {
                    if (Boolean.getBoolean("mcai.liveModelTest")) {
                        MinecraftAiCompanion.LOGGER.info(
                                "Gatherer drop audit: block={}, expected={}, "
                                    + "mined={}, owned={}, player={}, "
                                    + "mainHand={}, offHand={}, "
                                    + "inventory={}, visibleDrops={}",
                                parameters.blockId(),
                                requiredItem.orElse(""),
                                blocksMined,
                                ownedInventoryCount(
                                        snapshot,
                                        requiredItem.orElseThrow()
                                ),
                                snapshot.core().position(),
                                snapshot.interaction().mainHand(),
                                snapshot.interaction().offHand(),
                                snapshot.interaction().inventory(),
                                snapshot.interaction().visibleEntities()
                                    .stream()
                                    .filter(entity -> ITEM_ENTITY_ID.equals(
                                            entity.entityTypeId()
                                    ))
                                    .map(entity -> entity.position()
                                            + ":" + entity.visibleProperties())
                                    .toList()
                        );
                    }
                    uncollectedDropDebt = new DropCollectionDebt(
                            new GridPos(
                                    lastMinedOrigin.x(),
                                    lastMinedOrigin.y(),
                                    lastMinedOrigin.z()
                            ),
                            requiredItem.orElseThrow(),
                            requiredDirectDropCount,
                            ownedInventoryCount(
                                    snapshot,
                                    requiredItem.orElseThrow()
                            )
                    );
                    return fail(NAME + ".drop_not_collected");
                }
                /*
                 * A higher log can drop onto a still-solid lower trunk where
                 * the player's collision box cannot reach it. A player keeps
                 * chopping the connected trunk and collects once the support
                 * is gone. Preserve that legal behaviour: remember the
                 * cumulative pickup debt, resume bounded first-person cluster
                 * discovery, and require every outstanding log during the
                 * final sweep.
                 */
                cancelCollectionMovement(context);
                phase = Phase.READY;
                collectionEndsAtTick = -1;
                return running(context, true, true);
            }
            return finishCollection(context, parameters);
        }

        Optional<VisibleEntity> drop = visibleNearbyDrop(
            snapshot.core(),
            requiredItem
        );
        if (drop.isEmpty()) {
            return searchDropOrigin(context, snapshot);
        }
        VisibleEntity item = drop.orElseThrow();
        final Optional<SkillTickResult> delegated =
                collectWithProductionDropSkill(
                        context,
                        snapshot,
                        item
                );
        if (delegated.isPresent()) {
            return delegated.orElseThrow();
        }
        final PerceptionVec3 itemOffset = item.position()
                .subtract(snapshot.core().position());
        /*
         * ItemEntity.position() is vertically above the player's feet even
         * when both collision boxes already overlap. Using the full 3-D
         * distance here made the gatherer walk away from a drop directly
         * inside its horizontal footprint because the vertical component
         * alone exceeded the pickup threshold.
         */
        final double horizontalDistanceSquared =
                itemOffset.x() * itemOffset.x()
                + itemOffset.z() * itemOffset.z();
        if (horizontalDistanceSquared
                <= PICKUP_DISTANCE * PICKUP_DISTANCE) {
            cancelCollectionMovement(context);
            coreActuator.stop();
            return running(context, true, false);
        }
        final PerceptionVec3 walkingTarget =
                new PerceptionVec3(
                        item.position().x(),
                        snapshot.core().position().y(),
                        item.position().z()
                );
        return moveToCollectionTarget(
                context,
                snapshot,
                walkingTarget
        );
    }

    private Optional<SkillTickResult> collectWithProductionDropSkill(
            final SkillContext context,
            final Snapshot snapshot,
            final VisibleEntity item
    ) {
        if (dropCollector == null
                || dropCollectorParameters == null) {
            final List<VisibleEntity> visible =
                    snapshot.interaction().visibleEntities();
            int observationIndex = -1;
            for (int index = 0; index < visible.size(); index++) {
                if (visible.get(index).entityId().equals(
                        item.entityId()
                )) {
                    observationIndex = index;
                    break;
                }
            }
            if (observationIndex < 0) {
                return Optional.empty();
            }
            final long remaining = Math.max(
                    20L,
                    collectionEndsAtTick - context.gameTick()
            );
            dropCollectorParameters =
                    new CollectObservedItemParameters(
                            snapshot.interaction()
                                .observationRevision(),
                            "visible-" + observationIndex,
                            (int) Math.min(600L, remaining)
                    );
            dropCollector = new CollectObservedItemSkill(
                    expectedPlayerId,
                    coreActuator,
                    coreFrames,
                    interactionActuator,
                    interactionFrames
            );
            final Optional<SkillFailure> precondition =
                    dropCollector.preconditions(
                            context,
                            dropCollectorParameters
                    );
            if (precondition.isPresent()) {
                dropCollector = null;
                dropCollectorParameters = null;
                return Optional.empty();
            }
            dropCollector.start(
                    context,
                    dropCollectorParameters
            );
        }
        final SkillTickResult result = dropCollector.tick(
                context,
                dropCollectorParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            dropCollector = null;
            dropCollectorParameters = null;
            return Optional.empty();
        }
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            dropCollector = null;
            dropCollectorParameters = null;
            return Optional.of(running(context, true, true));
        }
        return Optional.of(running(
                context,
                result.madeProgress(),
                result.safeCheckpoint()
        ));
    }

    private SkillTickResult finishCollection(
            final SkillContext context,
            final GatherVisibleBlockClusterParameters parameters
    ) {
        cancelCollectionMovement(context);
        coreActuator.stop();
        if (finalCollectionSweep) {
            return complete();
        }
        if (blocksMined >= parameters.maxBlocks()) {
            return complete();
        }
        phase = Phase.READY;
        return running(context, true, true);
    }

    private SkillTickResult searchDropOrigin(
            final SkillContext context,
            final Snapshot snapshot
    ) {
        if (lastMinedOrigin == null) {
            return fail(NAME + ".target_binding_lost");
        }
        final PerceptionVec3 position = snapshot.core().position();
        final PerceptionVec3 pickupPoint = new PerceptionVec3(
            lastMinedOrigin.x() + 0.5,
            position.y(),
            lastMinedOrigin.z() + 0.5
        );
        final PerceptionVec3 horizontal = pickupPoint.subtract(position);
        if (horizontal.lengthSquared() > PICKUP_DISTANCE
                * PICKUP_DISTANCE) {
            return moveToCollectionTarget(
                context,
                snapshot,
                pickupPoint
            );
        }
        cancelCollectionMovement(context);
        coreActuator.stop();
        if (context.gameTick() < nextCollectionScanTick) {
            return running(context, false, false);
        }
        final float yaw = wrapDegrees(
            yawOf(snapshot.core().lookDirection()) + 45.0F
        );
        final ActionOutcome look = coreActuator.look(
            new LookIntent(yaw, 45.0F)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        nextCollectionScanTick = context.gameTick()
            + policy.scanIntervalTicks();
        return running(context, true, false);
    }

    private SkillTickResult moveToCollectionTarget(
            final SkillContext context,
            final Snapshot snapshot,
            final PerceptionVec3 destination
    ) {
        final double deltaX = destination.x()
                - snapshot.core().position().x();
        final double deltaZ = destination.z()
                - snapshot.core().position().z();
        final double horizontalDistanceSquared =
                deltaX * deltaX + deltaZ * deltaZ;
        if (horizontalDistanceSquared
                <= PICKUP_DISTANCE * PICKUP_DISTANCE) {
            cancelCollectionMovement(context);
            coreActuator.stop();
            return running(context, true, false);
        }
        /*
         * MoveTo deliberately has a player-scale arrival radius. Item pickup
         * needs a smaller collision overlap, so the last block is executed as
         * a normal forward input even when the route planner has already
         * declared arrival or the player and item straddle a cell boundary.
         */
        if (horizontalDistanceSquared
                <= PICKUP_MICRO_APPROACH_DISTANCE
                    * PICKUP_MICRO_APPROACH_DISTANCE
                && Math.abs(
                    destination.y()
                        - snapshot.core().position().y()
                ) <= 1.25) {
            cancelCollectionMovement(context);
            final PerceptionVec3 horizontalTarget =
                    new PerceptionVec3(
                            destination.x(),
                            snapshot.core().eyePosition().y(),
                            destination.z()
                    );
            final AimResult aim = aimAt(
                    snapshot.core(),
                    horizontalTarget
            );
            if (!aim.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            if (aim.errorDegrees()
                    > policy.movementAlignmentDegrees()) {
                coreActuator.stop();
                return running(context, true, false);
            }
            final ActionOutcome movement = coreActuator.move(
                    new MovementIntent(1.0, 0.0, false, false)
            );
            if (!movement.accepted()) {
                return fail(NAME + ".move_rejected");
            }
            return running(context, true, false);
        }
        cancelCollectionMovement(context);
        return moveToward(
            context,
            snapshot,
            destination,
            Phase.COLLECTING
        );
    }

    private void cancelCollectionMovement(
            final SkillContext context
    ) {
        if (dropCollector != null
                && dropCollectorParameters != null) {
            dropCollector.cancel(
                    context,
                    dropCollectorParameters
            );
        }
        dropCollector = null;
        dropCollectorParameters = null;
    }

    private SkillTickResult approach(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        Optional<VisibleBlockFace> current = currentTargetFace(
                snapshot.interaction(),
                parameters
        );
        final PerceptionVec3 approachPoint = targetAimPoint != null
                ? targetAimPoint
                : current.map(VisibleBlockFace::hitPosition)
                    .orElse(null);
        if (approachPoint == null) {
            coreActuator.stop();
            target = null;
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }
        final double distance = approachPoint
                .subtract(snapshot.core().eyePosition())
                .length();
        if (distance <= policy.miningApproachDistance()) {
            coreActuator.stop();
            beginAiming(context, snapshot.core());
            return running(context, true, true);
        }
        return moveToward(
                context,
                snapshot,
                approachPoint,
                Phase.APPROACHING
        );
    }

    private SkillTickResult moveToward(
            SkillContext context,
            Snapshot snapshot,
            PerceptionVec3 destination,
            Phase movementPhase
    ) {
        Optional<PerceptionVec3> step = safeStep(
                snapshot.core(),
                destination.subtract(snapshot.core().position()),
                maximumDanger(context)
        );
        if (step.isEmpty()) {
            coreActuator.stop();
            if (movementPhase == Phase.COLLECTING) {
                /*
                 * Sparse first-person voxel memory may not yet contain the
                 * adjacent body/head/support triple. Do not deadlock and do
                 * not guess. Look down at the next intended step so the next
                 * fair semantic sample can prove a sturdy top face.
                 */
                final PerceptionVec3 horizontal =
                        new PerceptionVec3(
                                destination.x()
                                    - snapshot.core().position().x(),
                                0.0,
                                destination.z()
                                    - snapshot.core().position().z()
                        );
                if (horizontal.lengthSquared() > LOOK_EPSILON) {
                    final PerceptionVec3 direction =
                            horizontal.normalized();
                    final PerceptionVec3 floorProbe =
                            new PerceptionVec3(
                                    snapshot.core().position().x()
                                        + direction.x() * 0.85,
                                    snapshot.core().position().y()
                                        - 0.05,
                                    snapshot.core().position().z()
                                        + direction.z() * 0.85
                            );
                    final ActionOutcome look = coreActuator.look(
                            lookAt(
                                    snapshot.core().eyePosition(),
                                    floorProbe
                            )
                    );
                    if (!look.accepted()) {
                        return fail(NAME + ".look_rejected");
                    }
                    return running(context, true, false);
                }
                return running(context, false, true);
            }
            if (movementPhase == Phase.APPROACHING
                    && target != null) {
                /*
                 * The body ticks at 20 Hz while semantic sight is deliberately
                 * sampled at only 2-5 Hz. Counting every game tick here used
                 * to exhaust all support probes before even one fresh
                 * first-person observation could report the floor. Count only
                 * distinct observation revisions and keep the visible target
                 * bound while the camera result is pending.
                 */
                final long observationRevision = snapshot.interaction()
                        .observationRevision();
                if (observationRevision
                        <= lastApproachSupportProbeRevision) {
                    return running(context, false, false);
                }
                if (approachSupportProbes
                        >= MAXIMUM_APPROACH_SUPPORT_PROBES) {
                    unavailable.add(target);
                    target = null;
                    approachSupportProbes = 0;
                    lastApproachSupportProbeRevision = -1;
                    beginScan(context, snapshot.core());
                    return running(context, true, true);
                }
                /*
                 * Seeing a distant block proves its surface, but a horizontal
                 * ray does not necessarily prove the next floor cell. A real
                 * player briefly looks down before walking. Preserve the
                 * observed target, gather that fair support evidence, and
                 * bound the retries so an unknown ledge cannot deadlock the
                 * whole parent skill.
                 */
                final Optional<PerceptionVec3> floorProbe =
                        supportProbeTarget(
                                snapshot.core(),
                                destination.subtract(
                                        snapshot.core().position()
                                ),
                                maximumDanger(context)
                        );
                if (floorProbe.isPresent()) {
                    final ActionOutcome look = coreActuator.look(
                            lookAt(
                                    snapshot.core().eyePosition(),
                                    floorProbe.orElseThrow()
                            )
                    );
                    if (!look.accepted()) {
                        return fail(NAME + ".look_rejected");
                    }
                    lastApproachSupportProbeRevision =
                            observationRevision;
                    approachSupportProbes++;
                    return running(context, true, false);
                }
            }
            beginScan(context, snapshot.core());
            return running(context, true, true);
        }

        PerceptionVec3 stepTarget = step.orElseThrow();
        final ActionOutcome look = coreActuator.look(
                lookAt(snapshot.core().eyePosition(), stepTarget)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        /*
         * Look and movement are consumed in the same vanilla input frame.
         * The queued look therefore defines the forward basis; projecting
         * against the previous semantic look makes the body strafe or walk
         * backwards after a turn.
         */
        ActionOutcome movement = coreActuator.move(
                new MovementIntent(1.0, 0.0, false, false)
        );
        if (!movement.accepted()) {
            return fail(NAME + ".move_rejected");
        }
        approachSupportProbes = 0;
        lastApproachSupportProbeRevision = -1;
        phase = movementPhase;
        return movementProgress(
                context,
                snapshot.core(),
                movementPhase
        );
    }

    private SkillTickResult movementProgress(
            SkillContext context,
            CoreSkillFrame frame,
            Phase movementPhase
    ) {
        if (motionWindowPosition == null
                || motionWindowStartedTick < 0) {
            resetMotionWindow(context, frame);
            return running(context, true, false);
        }
        if (frame.position()
                .subtract(motionWindowPosition)
                .lengthSquared()
                >= STUCK_PROGRESS_DISTANCE_SQUARED) {
            stuckRecoveries = 0;
            resetMotionWindow(context, frame);
            return running(context, true, false);
        }
        if (context.gameTick() - motionWindowStartedTick
                < policy.stuckWindowTicks()) {
            return running(context, false, false);
        }

        coreActuator.stop();
        /*
         * A model may point at the top or a side branch of a tree even when
         * lower blocks from the same connected component are visible. If
         * ordinary movement made no measurable progress for a full window,
         * do not keep reselecting that identical obstructed block. Mark only
         * this currently observed coordinate unavailable and rescan; lower
         * connected faces remain eligible. Item collection keeps its bounded
         * retry behavior because it has no alternate block target.
         */
        if (movementPhase == Phase.APPROACHING
                && target != null) {
            unavailable.add(target);
            target = null;
            stuckRecoveries = 0;
            resetMotionWindow(context, frame);
            beginScan(context, frame);
            return running(context, true, true);
        }
        stuckRecoveries++;
        resetMotionWindow(context, frame);
        if (stuckRecoveries > policy.maximumStuckRecoveries()) {
            return fail(NAME + ".stuck");
        }
        beginScan(context, frame);
        return running(context, true, true);
    }

    private SkillTickResult scan(
            SkillContext context,
            GatherVisibleBlockClusterParameters parameters,
            Snapshot snapshot
    ) {
        Optional<VisibleBlockFace> next = selectCandidate(
                snapshot,
                parameters
        );
        if (next.isPresent()) {
            final VisibleBlockFace selected = next.orElseThrow();
            target = key(selected);
            targetAimPoint = selected.hitPosition();
            approachSupportProbes = 0;
            lastApproachSupportProbeRevision = -1;
            directDropCountBeforeMining = requiredPickupItemId
                    .map(itemId -> ownedInventoryCount(
                            snapshot,
                            itemId
                    ))
                    .orElse(0);
            discovered.add(target);
            beginAiming(context, snapshot.core());
            return running(context, true, true);
        }

        if (!coreActuator.stop().accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        if (scanTurns >= policy.maximumScanTurns()) {
            if (blocksMined == 0) {
                return fail(NAME + ".cluster_not_rediscovered");
            }
            if (requiredPickupItemId.isEmpty()
                    || ownedInventoryCount(
                            snapshot,
                            requiredPickupItemId.orElseThrow()
                    ) >= directDropCountAtStart + blocksMined) {
                return complete();
            }
            finalCollectionSweep = true;
            collectionRequiredOwnedCount =
                    directDropCountAtStart + blocksMined;
            collectionEndsAtTick =
                    context.gameTick() + policy.collectionTicks();
            dropCollector = null;
            dropCollectorParameters = null;
            nextCollectionScanTick = context.gameTick();
            phase = Phase.COLLECTING;
            return running(context, true, true);
        }
        if (context.gameTick() < nextScanTick
                || snapshot.interaction().observationRevision()
                <= lastScanObservationRevision) {
            return running(context, false, false);
        }

        int yawIndex = scanTurns / SCAN_PITCHES.length;
        int pitchIndex = scanTurns % SCAN_PITCHES.length;
        float pitch = SCAN_PITCHES[pitchIndex];
        /*
         * Eight 45-degree headings with four elevations cover a full sphere
         * in 32 of the default 36 bounded scan turns. The previous
         * 0/-35/+35 pattern skipped the 15-25 degree downward band where a
         * one-block-high log or dropped item normally appears a few blocks
         * away.
         */
        float yaw = wrapDegrees(scanBaseYaw + yawIndex * 45.0F);
        ActionOutcome look = coreActuator.look(
                new LookIntent(yaw, pitch)
        );
        if (!look.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        scanTurns++;
        nextScanTick =
                context.gameTick() + policy.scanIntervalTicks();
        lastScanObservationRevision =
                snapshot.interaction().observationRevision();
        return running(context, true, true);
    }

    private Optional<VisibleBlockFace> selectCandidate(
            Snapshot snapshot,
            GatherVisibleBlockClusterParameters parameters
    ) {
        discoverVisibleComponent(
                snapshot.interaction(),
                parameters
        );
        Map<BlockKey, VisibleBlockFace> unique = new LinkedHashMap<>();
        for (VisibleBlockFace face
                : snapshot.interaction().visibleBlockFaces()) {
            BlockKey candidate = key(face);
            if (!parameters.blockId().equals(face.blockTypeId())
                    || completed.contains(candidate)
                    || unavailable.contains(candidate)
                    || !insideClusterRadius(candidate, parameters)
                    || !connectedToDiscovered(candidate)
                    || isCurrentSupport(
                            snapshot.core().feet(),
                            face
                    )) {
                continue;
            }
            unique.merge(
                    candidate,
                    face,
                    (left, right) ->
                            left.distance() <= right.distance()
                                    ? left
                                    : right
            );
        }
        return unique.values().stream()
                .min(Comparator
                        /*
                         * Current support blocks were rejected above. Favor
                         * player-height blocks over a model-selected canopy
                         * seed. Distance breaks ties only after the
                         * physically useful vertical entry point.
                         */
                        .comparingInt((VisibleBlockFace face) ->
                                targetHeightDistance(
                                        snapshot.core().feet().y(),
                                        face.block().y()
                                ))
                        .thenComparingDouble(face ->
                                face.hitPosition()
                                        .subtract(
                                                snapshot.core()
                                                        .eyePosition()
                                        )
                                        .lengthSquared())
                        .thenComparingInt(face -> face.block().y())
                        .thenComparingInt(face -> face.block().x())
                        .thenComparingInt(face -> face.block().z()));
    }

    /**
     * Expands the known component to a fixed point using only block faces in
     * the current fair observation.
     *
     * <p>A model is allowed to identify any visible member of a tree as the
     * public seed. If that happens to be a canopy log, considering only the
     * seed on the first tick makes the body mine high first and chase a drop
     * that can rest on the lower trunk. Discovering the already-visible
     * connected faces first lets the normal candidate ordering choose a
     * player-height entry. Hidden blocks remain unknown until a later ray
     * exposes them.</p>
     */
    private void discoverVisibleComponent(
            final InteractionSkillFrame frame,
            final GatherVisibleBlockClusterParameters parameters
    ) {
        boolean expanded;
        do {
            expanded = false;
            for (VisibleBlockFace face : frame.visibleBlockFaces()) {
                final BlockKey candidate = key(face);
                if (discovered.contains(candidate)
                        || completed.contains(candidate)
                        || unavailable.contains(candidate)
                        || !parameters.blockId().equals(
                                face.blockTypeId()
                        )
                        || !insideClusterRadius(
                                candidate,
                                parameters
                        )
                        || !connectedToDiscovered(candidate)) {
                    continue;
                }
                discovered.add(candidate);
                expanded = true;
            }
        } while (expanded);
    }

    static int targetHeightDistance(
            final int feetY,
            final int blockY
    ) {
        return Math.abs(blockY - feetY);
    }

    static boolean isCurrentSupport(
            final GridPos feet,
            final VisibleBlockFace face
    ) {
        return face.block().x() == feet.x()
                && face.block().y() == feet.y() - 1
                && face.block().z() == feet.z();
    }

    private Optional<VisibleBlockFace> currentTargetFace(
            InteractionSkillFrame frame,
            GatherVisibleBlockClusterParameters parameters
    ) {
        if (target == null) {
            return Optional.empty();
        }
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        key(face).equals(target)
                                && parameters.blockId().equals(
                                        face.blockTypeId()
                                ))
                .min(Comparator.comparingDouble(VisibleBlockFace::distance));
    }

    private Optional<VisibleBlockFace> currentCrosshairTarget(
            final GatherVisibleBlockClusterParameters parameters
    ) {
        if (target == null) {
            return Optional.empty();
        }
        return interactionFrames.currentCrosshairBlock()
                .filter(face ->
                        key(face).equals(target)
                            && parameters.blockId().equals(
                                    face.blockTypeId()
                            ));
    }

    private Optional<VisibleEntity> visibleNearbyDrop(
            final CoreSkillFrame frame,
            final Optional<String> requiredItemId
    ) {
        if (lastMinedOrigin == null) {
            return Optional.empty();
        }
        return frame.visibleEntities().stream()
                .filter(entity ->
                        ITEM_ENTITY_ID.equals(entity.entityTypeId())
                                && requiredItemId.map(itemId ->
                                    itemId.equals(
                                        entity.visibleProperties()
                                            .get("itemId")
                                    )
                                ).orElse(true)
                                && nearCompletedOrigin(
                                        entity.position()
                                ))
                .min(Comparator.comparingDouble(entity ->
                        entity.position()
                                .subtract(frame.position())
                                .lengthSquared()));
    }

    private boolean nearCompletedOrigin(
            final PerceptionVec3 position
    ) {
        if (completed.stream().anyMatch(origin ->
                position.subtract(origin.center()).length()
                        <= PICKUP_ORIGIN_RADIUS)) {
            return true;
        }
        return lastMinedOrigin != null
                && position.subtract(lastMinedOrigin.center()).length()
                    <= PICKUP_ORIGIN_RADIUS;
    }

    private static int inventoryCount(
            final InteractionSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
            .filter(item -> item.itemId().equals(itemId))
            .mapToInt(InventoryItemSummary::count)
            .sum();
    }

    /**
     * Inventory is player-visible state, so the 20 TPS core body refresh may
     * safely confirm pickup before the next 2-5 Hz semantic publication. The
     * maximum preserves compatibility with a same-session semantic frame
     * that was published after the live core snapshot in a test adapter.
     */
    private static int ownedInventoryCount(
            final Snapshot snapshot,
            final String itemId
    ) {
        final int live = snapshot.core().inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
        return Math.max(
                live,
                inventoryCount(snapshot.interaction(), itemId)
        );
    }

    /**
     * Returns the causal pickup debt retained after a terminal collection
     * miss.
     *
     * <p>The origin is the exact block this same skill just mined through the
     * vanilla player path. It is not a level/entity query and does not reveal
     * a hidden dropped-item position. A composing survival skill can use the
     * receipt to walk back, look for a normal first-person-visible item, and
     * confirm inventory growth instead of incorrectly searching for another
     * ore vein.</p>
     */
    public Optional<DropCollectionDebt> uncollectedDropDebt() {
        return Optional.ofNullable(uncollectedDropDebt);
    }

    public record DropCollectionDebt(
            GridPos origin,
            String itemId,
            int requiredOwnedCount,
            int observedOwnedCount
    ) {
        public DropCollectionDebt {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(itemId, "itemId");
            if (itemId.isBlank()
                    || requiredOwnedCount < 1
                    || observedOwnedCount < 0
                    || observedOwnedCount >= requiredOwnedCount) {
                throw new IllegalArgumentException(
                        "Invalid mined-drop collection debt"
                );
            }
        }
    }

    /**
     * Minimum ordinary drop that proves the mined resource actually reached
     * this player's inventory. Requiring one item per mined block is
     * deliberately conservative and remains valid with Silk Touch absent,
     * Fortune present, or multi-item ore drops.
     */
    static Optional<String> requiredPickupItemId(
            final String blockId
    ) {
        if (blockId.endsWith("_log")
                || blockId.endsWith("_wood")
                || blockId.endsWith("_stem")
                || blockId.endsWith("_hyphae")) {
            return Optional.of(blockId);
        }
        return switch (blockId) {
            case "minecraft:stone" ->
                    Optional.of("minecraft:cobblestone");
            case "minecraft:coal_ore",
                    "minecraft:deepslate_coal_ore" ->
                    Optional.of("minecraft:coal");
            case "minecraft:iron_ore",
                    "minecraft:deepslate_iron_ore" ->
                    Optional.of("minecraft:raw_iron");
            case "minecraft:copper_ore",
                    "minecraft:deepslate_copper_ore" ->
                    Optional.of("minecraft:raw_copper");
            case "minecraft:gold_ore",
                    "minecraft:deepslate_gold_ore" ->
                    Optional.of("minecraft:raw_gold");
            case "minecraft:diamond_ore",
                    "minecraft:deepslate_diamond_ore" ->
                    Optional.of("minecraft:diamond");
            case "minecraft:emerald_ore",
                    "minecraft:deepslate_emerald_ore" ->
                    Optional.of("minecraft:emerald");
            case "minecraft:lapis_ore",
                    "minecraft:deepslate_lapis_ore" ->
                    Optional.of("minecraft:lapis_lazuli");
            case "minecraft:redstone_ore",
                    "minecraft:deepslate_redstone_ore" ->
                    Optional.of("minecraft:redstone");
            default -> Optional.empty();
        };
    }

    private SnapshotResult currentSnapshot(
            DimensionRef expectedDimension,
            long expectedSession
    ) {
        Optional<CoreSkillFrame> core = coreFrames.current();
        Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        Optional<ResourceInventoryState> inventoryState =
                inventory.current();
        if (core.isEmpty()
                || interaction.isEmpty()
                || inventoryState.isEmpty()) {
            return SnapshotResult.failed(
                    NAME + ".observation_unavailable"
            );
        }
        CoreSkillFrame coreFrame = core.orElseThrow();
        InteractionSkillFrame interactionFrame =
                interaction.orElseThrow();
        ResourceInventoryState ownedInventory =
                inventoryState.orElseThrow();
        if (!expectedPlayerId.equals(coreFrame.playerId())
                || !expectedPlayerId.equals(
                        interactionFrame.playerId()
                )) {
            return SnapshotResult.failed(NAME + ".player_mismatch");
        }
        if (!expectedDimension.equals(coreFrame.dimension())
                || !expectedDimension.equals(
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
        OptionalLong actuatorSession =
                interactionActuator.sessionGeneration();
        if (actuatorSession.isEmpty()) {
            return SnapshotResult.failed(NAME + ".player_unavailable");
        }
        long session = actuatorSession.orElseThrow();
        if (interactionFrame.sessionGeneration() != session
                || ownedInventory.sessionGeneration() != session
                || expectedSession >= 0
                && expectedSession != session) {
            return SnapshotResult.failed(NAME + ".session_mismatch");
        }
        return SnapshotResult.valid(new Snapshot(
                coreFrame,
                interactionFrame,
                ownedInventory
        ));
    }

    private Optional<SkillFailure> safetyFailure(
            SkillContext context,
            CoreSkillFrame frame
    ) {
        double maximumDanger = maximumDanger(context);
        if (context.riskScore() > maximumDanger
                || frame.danger() > maximumDanger) {
            return Optional.of(failure("danger_detected"));
        }
        double health = frame.health() / frame.maxHealth();
        double minimum = context.hardcore()
                ? policy.minimumHardcoreHealthFraction()
                : policy.minimumNormalHealthFraction();
        return health < minimum
                ? Optional.of(failure("health_reserve_low"))
                : Optional.empty();
    }

    private Optional<SkillFailure> durabilityFailure(
            HeldItemSummary item
    ) {
        if (item.maxDamage() <= 0) {
            return Optional.empty();
        }
        int remaining = item.maxDamage() - item.damage();
        return remaining <= policy.durabilityReserve()
                ? Optional.of(failure("tool_durability_reserve"))
                : Optional.empty();
    }

    private double maximumDanger(SkillContext context) {
        return context.hardcore()
                ? policy.maximumHardcoreDanger()
                : policy.maximumNormalDanger();
    }

    private Optional<VisibleBlockFace> initialSeed(
            Snapshot current,
            GatherVisibleBlockClusterParameters parameters
    ) {
        /*
         * Resolve the public handle against the exact retained sample the
         * model received, then rebind only if that same coordinate and block
         * type is visible in the latest fair first-person sample. Requiring
         * the latest sample sequence itself to equal the model's sequence
         * makes every network-backed decision impossible once semantic
         * sampling advances during provider latency.
         */
        final Optional<InteractionSkillFrame> historical =
                interactionFrames.atObservation(
                        parameters.seed().sampleSequence()
                );
        if (historical.isEmpty()) {
            return Optional.empty();
        }
        final InteractionSkillFrame observed = historical.orElseThrow();
        if (!expectedPlayerId.equals(observed.playerId())
                || !parameters.dimension().equals(
                        observed.dimension()
                )
                || observed.sessionGeneration()
                    != current.interaction().sessionGeneration()
                || exactSeedFace(observed, parameters).isEmpty()) {
            return Optional.empty();
        }
        final Optional<VisibleBlockFace> currentlyVisible =
                current.interaction().visibleBlockFaces().stream()
                .filter(face ->
                        face.block().x() == parameters.seed().x()
                                && face.block().y()
                                        == parameters.seed().y()
                                && face.block().z()
                                        == parameters.seed().z()
                                && face.blockTypeId().equals(
                                        parameters.blockId()
                ))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        /*
         * A completed fair survey commonly ends with the camera facing a
         * different heading than the selected tree. Preserve the exact
         * retained ray as authority to begin, then let READY/SCANNING turn
         * the body and require a fresh visible match before any mining
         * action. If the block changed or disappeared, bounded reacquisition
         * fails without mutating the world.
         */
        return currentlyVisible.or(() ->
                exactSeedFace(observed, parameters));
    }

    private static Optional<VisibleBlockFace> exactSeedFace(
            final InteractionSkillFrame frame,
            final GatherVisibleBlockClusterParameters parameters
    ) {
        if (frame.observationRevision()
                != parameters.seed().sampleSequence()) {
            return Optional.empty();
        }
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        face.block().x() == parameters.seed().x()
                                && face.block().y()
                                        == parameters.seed().y()
                                && face.block().z()
                                        == parameters.seed().z()
                                && face.face().equals(
                                        parameters.seed()
                                                .face()
                                                .name()
                                                .toLowerCase(Locale.ROOT)
                                )
                                && face.blockTypeId().equals(
                                        parameters.blockId()
                                ))
                .findFirst();
    }

    private boolean connectedToDiscovered(BlockKey candidate) {
        if (candidate.equals(seed)) {
            return true;
        }
        return discovered.stream().anyMatch(
                known -> known.manhattanDistance(candidate) == 1
        );
    }

    private boolean insideClusterRadius(
            BlockKey candidate,
            GatherVisibleBlockClusterParameters parameters
    ) {
        return candidate.distanceSquared(seed)
                <= parameters.clusterRadius()
                        * parameters.clusterRadius()
                        + 1.0E-9;
    }

    private static boolean ownsItem(
            InteractionSkillFrame frame,
            String itemId
    ) {
        if (itemId.equals(frame.mainHand().itemId())
                || itemId.equals(frame.offHand().itemId())) {
            return true;
        }
        return frame.inventory().stream()
                .map(InventoryItemSummary::itemId)
                .anyMatch(itemId::equals);
    }

    private Optional<PerceptionVec3> safeStep(
            CoreSkillFrame frame,
            PerceptionVec3 desiredDirection,
            double maximumDanger
    ) {
        PerceptionVec3 horizontal = new PerceptionVec3(
                desiredDirection.x(),
                0.0,
                desiredDirection.z()
        );
        if (horizontal.lengthSquared() <= LOOK_EPSILON) {
            return Optional.empty();
        }
        PerceptionVec3 desired = horizontal.normalized();
        GridPos feet = frame.feet();
        return java.util.Arrays.stream(CARDINALS)
                .map(direction -> stepCandidate(
                        frame,
                        feet,
                        direction,
                        desired,
                        maximumDanger
                ))
                .flatMap(Optional::stream)
                .filter(candidate -> candidate.score() > 0.0)
                .max(Comparator
                        .comparingDouble(StepCandidate::score)
                        .thenComparing(
                                StepCandidate::danger,
                                Comparator.reverseOrder()
                ))
                .map(StepCandidate::target);
    }

    /**
     * Selects the safest still-plausible adjacent floor cell to inspect.
     *
     * <p>The destination is a block-center point just inside the support
     * block. The ordinary first-person ray can therefore report the exact
     * sturdy top face consumed by {@link #safeStep(CoreSkillFrame,
     * PerceptionVec3, double)} on a later semantic revision. This method never
     * treats an unknown cell as walkable.</p>
     */
    private static Optional<PerceptionVec3> supportProbeTarget(
            final CoreSkillFrame frame,
            final PerceptionVec3 desiredDirection,
            final double maximumDanger
    ) {
        final PerceptionVec3 horizontal = new PerceptionVec3(
                desiredDirection.x(),
                0.0,
                desiredDirection.z()
        );
        if (horizontal.lengthSquared() <= LOOK_EPSILON) {
            return Optional.empty();
        }
        final PerceptionVec3 desired = horizontal.normalized();
        final GridPos feet = frame.feet();
        return java.util.Arrays.stream(CARDINALS)
                .map(direction -> supportProbeCandidate(
                        frame,
                        feet,
                        direction,
                        desired,
                        maximumDanger
                ))
                .flatMap(Optional::stream)
                .filter(candidate -> candidate.score() > 0.0)
                .max(Comparator
                        .comparingDouble(StepCandidate::score)
                        .thenComparing(
                                StepCandidate::danger,
                                Comparator.reverseOrder()
                        ))
                .map(StepCandidate::target);
    }

    private static Optional<StepCandidate> supportProbeCandidate(
            final CoreSkillFrame frame,
            final GridPos feet,
            final int[] direction,
            final PerceptionVec3 desired,
            final double maximumDanger
    ) {
        final GridPos destination = feet.offset(
                direction[0],
                0,
                direction[1]
        );
        final Optional<ObservedVoxel> body =
                frame.navigation().voxelAt(destination);
        final Optional<ObservedVoxel> head =
                frame.navigation().voxelAt(destination.above());
        if (body.isPresent()
                && !body.orElseThrow().kind().isPassable()
                || head.isPresent()
                && !head.orElseThrow().kind().isPassable()) {
            return Optional.empty();
        }
        final double danger = Math.max(
                body.map(ObservedVoxel::effectiveDanger).orElse(0.0),
                head.map(ObservedVoxel::effectiveDanger).orElse(0.0)
        );
        if (danger > maximumDanger) {
            return Optional.empty();
        }
        final PerceptionVec3 directionVector = new PerceptionVec3(
                direction[0],
                0.0,
                direction[1]
        );
        return Optional.of(new StepCandidate(
                new PerceptionVec3(
                        destination.x() + 0.5,
                        feet.y() - 0.05,
                        destination.z() + 0.5
                ),
                desired.dot(directionVector),
                danger
        ));
    }

    private static Optional<StepCandidate> stepCandidate(
            CoreSkillFrame frame,
            GridPos feet,
            int[] direction,
            PerceptionVec3 desired,
            double maximumDanger
    ) {
        GridPos destination = feet.offset(
                direction[0],
                0,
                direction[1]
        );
        Optional<ObservedVoxel> body = frame.navigation()
                .voxelAt(destination);
        Optional<ObservedVoxel> head = frame.navigation()
                .voxelAt(destination.above());
        Optional<ObservedVoxel> support = frame.navigation()
                .voxelAt(destination.below());
        if (body.isPresent()
                && !body.orElseThrow().kind().isPassable()) {
            return Optional.empty();
        }
        if (head.isPresent()
                && !head.orElseThrow().kind().isPassable()) {
            return Optional.empty();
        }
        final boolean liquidBody = body.isPresent()
                && body.orElseThrow().kind().isLiquid();
        final boolean observedSupport = support.isPresent()
                && support.orElseThrow().kind().supportsWeight();
        final boolean visiblySturdySupport =
                hasVisibleSturdyTop(
                        frame,
                        destination.below()
                );
        if (!liquidBody
                && !observedSupport
                && !visiblySturdySupport) {
            return Optional.empty();
        }
        /*
         * A fair top-face ray proves both the support surface and a clear ray
         * through the one-block approach corridor. This permits a cautious
         * forward step when the sparse voxel cache has not materialized all
         * three cells yet; an explicitly observed solid body/head still wins
         * and rejects movement above.
         */
        double danger = Math.max(
                body.map(ObservedVoxel::effectiveDanger).orElse(0.0),
                head.map(ObservedVoxel::effectiveDanger).orElse(0.0)
        );
        if (danger > maximumDanger) {
            return Optional.empty();
        }
        PerceptionVec3 directionVector = new PerceptionVec3(
                direction[0],
                0.0,
                direction[1]
        );
        return Optional.of(new StepCandidate(
                new PerceptionVec3(
                        destination.x() + 0.5,
                        frame.eyePosition().y(),
                        destination.z() + 0.5
                ),
                desired.dot(directionVector),
                danger
        ));
    }

    private static boolean hasVisibleSturdyTop(
            final CoreSkillFrame frame,
            final GridPos support
    ) {
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && "up".equals(face.face())
                    && face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private AimResult aimAt(
            CoreSkillFrame frame,
            PerceptionVec3 targetPosition
    ) {
        PerceptionVec3 delta = targetPosition.subtract(
                frame.eyePosition()
        );
        if (delta.lengthSquared() <= LOOK_EPSILON) {
            return new AimResult(true, 0.0);
        }
        ActionOutcome outcome = coreActuator.look(
                lookAt(frame.eyePosition(), targetPosition)
        );
        double dot = frame.lookDirection()
                .normalized()
                .dot(delta.normalized());
        double error = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
        return new AimResult(outcome.accepted(), error);
    }

    private static LookIntent lookAt(
            PerceptionVec3 eye,
            PerceptionVec3 target
    ) {
        PerceptionVec3 delta = target.subtract(eye);
        float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private void beginScan(
            SkillContext context,
            CoreSkillFrame frame
    ) {
        phase = Phase.SCANNING;
        target = null;
        targetAimPoint = null;
        aimStartedAtTick = -1;
        scanBaseYaw = yawOf(frame.lookDirection());
        resetScan(context);
        resetMotionWindow(context, frame);
    }

    private void beginAiming(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        phase = Phase.AIMING;
        aimStartedAtTick = context.gameTick();
        resetMotionWindow(context, frame);
    }

    private void resetScan(SkillContext context) {
        scanTurns = 0;
        nextScanTick = context.gameTick();
        lastScanObservationRevision = -1;
    }

    private void resetMotionWindow(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        motionWindowPosition = frame.position();
        /*
         * SkillContext uses the server tick clock. CoreSkillFrame.gameTime is
         * the dimension clock and can be offset (especially in GameTest or
         * after /time changes). Mixing them falsely declares movement stuck.
         */
        motionWindowStartedTick = context.gameTick();
    }

    private SkillTickResult running(
            SkillContext context,
            boolean madeProgress,
            boolean boundary
    ) {
        boolean safe = boundary
                || Math.floorMod(
                        context.gameTick() - startedAtTick,
                        policy.safeCheckpointIntervalTicks()
                ) == 0;
        return SkillTickResult.running(madeProgress, safe);
    }

    private SkillTickResult complete() {
        quiesce();
        phase = Phase.COMPLETED;
        clearBinding();
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        clearBinding();
        return SkillTickResult.failed(reason);
    }

    private void quiesce() {
        interactionActuator.abortMining();
        coreActuator.stop();
    }

    private void clearBinding() {
        boundDimension = null;
        boundSessionGeneration = -1;
        target = null;
        targetAimPoint = null;
        aimStartedAtTick = -1;
        requiredPickupItemId = Optional.empty();
        miningStartedAtTick = -1;
        collectionEndsAtTick = -1;
        dropCollector = null;
        dropCollectorParameters = null;
        nextCollectionScanTick = -1;
        motionWindowPosition = null;
        motionWindowStartedTick = -1;
        approachSupportProbes = 0;
        lastApproachSupportProbeRevision = -1;
    }

    private static BlockInteractionTarget interactionTarget(
            VisibleBlockFace face
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

    private static BlockKey key(VisibleBlockFace face) {
        return new BlockKey(
                face.block().x(),
                face.block().y(),
                face.block().z()
        );
    }

    private static float yawOf(PerceptionVec3 direction) {
        return wrapDegrees((float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        ));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped == 0.0F ? 0.0F : wrapped;
    }

    private static String outcomeCode(ActionOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    private static SkillFailure failure(String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        READY,
        EQUIPPING,
        AIMING,
        APPROACHING,
        MINING,
        COLLECTING,
        SCANNING,
        COMPLETED,
        FAILED,
        CANCELLED;

        boolean active() {
            return this != IDLE
                    && this != COMPLETED
                    && this != FAILED
                    && this != CANCELLED;
        }
    }

    private record BlockKey(int x, int y, int z) {
        int manhattanDistance(BlockKey other) {
            return Math.abs(x - other.x)
                    + Math.abs(y - other.y)
                    + Math.abs(z - other.z);
        }

        double distanceSquared(BlockKey other) {
            double dx = x - (double) other.x;
            double dy = y - (double) other.y;
            double dz = z - (double) other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        PerceptionVec3 center() {
            return new PerceptionVec3(
                    x + 0.5,
                    y + 0.5,
                    z + 0.5
            );
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
        SnapshotResult {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(failure, "failure");
            if (snapshot.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Snapshot result requires exactly one outcome"
                );
            }
        }

        static SnapshotResult valid(Snapshot snapshot) {
            return new SnapshotResult(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        static SnapshotResult failed(String code) {
            return new SnapshotResult(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    private record AimResult(boolean accepted, double errorDegrees) {
    }

    private record StepCandidate(
            PerceptionVec3 target,
            double score,
            double danger
    ) {
    }
}
