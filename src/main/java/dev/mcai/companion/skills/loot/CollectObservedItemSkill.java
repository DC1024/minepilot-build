package dev.mcai.companion.skills.loot;

import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
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
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Walks to one first-person-visible dropped stack and confirms collection
 * against the companion's own inventory. The dropped entity UUID remains
 * local and neither its exact stack count nor NBT is exposed.
 */
public final class CollectObservedItemSkill
        implements Skill<CollectObservedItemParameters> {
    public static final String NAME = "collect_observed_item";

    private static final String ITEM_ENTITY = "minecraft:item";
    private static final int LOST_GRACE_TICKS = 50;
    private static final double RETARGET_DISTANCE_SQUARED = 0.64;
    /*
     * Vanilla ItemEntity.mergeWith() can remove the UUID that the player
     * originally saw while keeping a nearby same-item entity as the stack
     * survivor.  Rebinding is allowed only to a synchronized, first-person
     * visible same-item entity within this small observation-radius; it never
     * scans the level or assumes an unseen drop.
     */
    private static final double MERGED_REPLACEMENT_DISTANCE_SQUARED = 4.0;
    private static final double DIRECT_APPROACH_VERTICAL_LIMIT = 1.5;
    /*
     * Player.tick() searches an AABB inflated by 1.0 horizontally and 0.5
     * vertically before calling ItemEntity.playerTouch(). Waiting inside a
     * conservative one-block column lets the vanilla pickup path settle and
     * avoids asking A* to route to an item entity's non-walkable Y value.
     */
    private static final double PICKUP_WAIT_HORIZONTAL_RADIUS = 1.0;
    private static final double PICKUP_WAIT_VERTICAL_LIMIT = 2.25;
    private static final double HORIZONTAL_EPSILON = 1.0E-9;
    private static final long DIRECT_STEP_MEMORY_REVISIONS = 16;
    private static final int MAXIMUM_ROUTE_EVIDENCE_PROBES = 12;
    private static final int[][] CARDINALS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    /**
     * Compound-skill authorization for one bounded, already-proven risk
     * context.  Ordinary item collection never supplies this authorization;
     * the sheltered Enderman wrapper is the only current caller.
     */
    private final BiPredicate<SkillContext, CoreSkillFrame>
            hardcoreRiskAuthorization;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundEntityId;
    private String itemId;
    private DimensionRef boundDimension;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lostSinceTick = -1;
    private long lastObservationRevision = -1;
    private int initialItemCount;
    private PerceptionVec3 lastKnownPosition;
    private GridPos directStepCell;
    private PerceptionVec3 directStepTarget;
    private long directStepEvidenceRevision = -1;
    private int routeEvidenceProbes;
    private MoveToSkill movement;
    private MoveToParameters movementParameters;

    public CollectObservedItemSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
    ) {
        this(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                (context, frame) -> false
        );
    }

    CollectObservedItemSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final BiPredicate<SkillContext, CoreSkillFrame>
                    hardcoreRiskAuthorization
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
        this.hardcoreRiskAuthorization = Objects.requireNonNull(
                hardcoreRiskAuthorization,
                "hardcoreRiskAuthorization"
        );
    }

    @Override
    public SkillParameterParser<CollectObservedItemParameters>
            parameters() {
        return LootSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final CollectObservedItemParameters parameters
    ) {
        final Resolution resolution =
                resolveInitial(parameters);
        if (resolution.failure().isPresent()) {
            return resolution.failure();
        }
        return safetyFailure(
                context,
                resolution.snapshot().orElseThrow().core()
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final CollectObservedItemParameters parameters
    ) {
        final Resolution resolution =
                resolveInitial(parameters);
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Dropped item changed before collection start"
            );
        }
        final Snapshot snapshot =
                resolution.snapshot().orElseThrow();
        final VisibleEntity target =
                resolution.target().orElseThrow();
        phase = Phase.COLLECTING;
        failure = null;
        boundEntityId = target.entityId();
        itemId = target.visibleProperties().get("itemId");
        boundDimension = snapshot.core().dimension();
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        startedAtTick = context.gameTick();
        lostSinceTick = -1;
        lastObservationRevision =
                snapshot.core().observationRevision();
        initialItemCount = inventoryCount(
                snapshot.core(),
                itemId
        );
        lastKnownPosition = target.position();
        clearDirectStep();
        routeEvidenceProbes = 0;
        movement = null;
        movementParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final CollectObservedItemParameters parameters
    ) {
        if (phase != Phase.COLLECTING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final CollectObservedItemParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"itemId\":\"%s\","
                            + "\"lostSinceTick\":%d,"
                            + "\"routeEvidenceProbes\":%d}",
                        phase.name(),
                        itemId == null ? "" : itemId,
                        lostSinceTick,
                        routeEvidenceProbes
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final CollectObservedItemParameters parameters
    ) {
        cancelMovement(context);
        core.stop();
        phase = Phase.CANCELLED;
        clearBinding();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final CollectObservedItemParameters parameters
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
            final CollectObservedItemParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= parameters.maximumTicks()) {
            return fail(context, NAME + ".timed_out");
        }
        final SnapshotValidation validation =
                currentSnapshot();
        if (validation.failure().isPresent()) {
            return fail(
                    context,
                    validation.failure().orElseThrow()
            );
        }
        final Snapshot snapshot =
                validation.snapshot().orElseThrow();
        final CoreSkillFrame frame = snapshot.core();
        if (!boundDimension.equals(frame.dimension())) {
            return fail(context, NAME + ".dimension_changed");
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(context, NAME + ".stale_observation");
        }
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow());
        }
        if (inventoryCount(frame, itemId) > initialItemCount) {
            cancelMovement(context);
            core.stop();
            phase = Phase.COMPLETED;
            clearBinding();
            return SkillTickResult.completed();
        }
        final Optional<VisibleEntity> target =
                frame.visibleEntities().stream()
                    .filter(entity ->
                            entity.entityId().equals(boundEntityId)
                    )
                    .filter(entity ->
                            ITEM_ENTITY.equals(entity.entityTypeId())
                    )
                    .filter(entity ->
                            itemId.equals(
                                entity.visibleProperties()
                                    .get("itemId")
                            )
                    )
                    .findFirst();
        if (target.isEmpty()) {
            final Optional<VisibleEntity> mergedReplacement =
                    mergedReplacement(snapshot);
            if (mergedReplacement.isPresent()) {
                /*
                 * The original observed entity may have merged into this
                 * survivor between semantic frames. Keep the ordinary
                 * collection skill alive and bind only to the survivor that
                 * both core and interaction frames currently show.
                 */
                boundEntityId = mergedReplacement.orElseThrow()
                        .entityId();
                lastKnownPosition = mergedReplacement.orElseThrow()
                        .position();
                lostSinceTick = -1;
                cancelMovement(context);
                clearDirectStep();
                routeEvidenceProbes = 0;
                return SkillTickResult.running(true, true);
            }
            return awaitReacquisition(
                    context,
                    frame,
                    fresh
            );
        }
        lostSinceTick = -1;
        final PerceptionVec3 currentTarget =
                target.orElseThrow().position();
        if (withinPickupWaitRange(
                frame.position(),
                currentTarget
        )) {
            cancelMovement(context);
            clearDirectStep();
            routeEvidenceProbes = 0;
            lastKnownPosition = currentTarget;
            core.stop();
            /*
             * Pickup is a vanilla collision/tick transaction. Inventory
             * growth on a later owned-body frame is the only completion
             * signal; merely reaching this column is never reported as
             * success.
             */
            return SkillTickResult.running(fresh, false);
        }
        /*
         * A visible stack has priority over an older route attempt.  The
         * direct approach queues the next legal player input frame, so the
         * stale MoveTo child must be quiesced before (and not after) that
         * input is queued.  Cancelling it after directObservedApproach()
         * calls CoreSkillSafety.quiesce(), which clears the freshly queued
         * forward input and leaves the player turning in place until the
         * item's 50-tick lost-target grace expires.
         */
        if (movement != null) {
            cancelMovement(context);
        }
        final Optional<SkillTickResult> directApproach =
                directObservedApproach(
                        context,
                        frame,
                        currentTarget
                );
        if (directApproach.isPresent()) {
            lastKnownPosition = currentTarget;
            return directApproach.orElseThrow();
        }
        if (movement == null
                || currentTarget.subtract(lastKnownPosition)
                    .lengthSquared()
                        > RETARGET_DISTANCE_SQUARED) {
            cancelMovement(context);
            lastKnownPosition = currentTarget;
            movementParameters = new MoveToParameters(
                    boundDimension,
                    currentTarget.x(),
                    currentTarget.y(),
                    currentTarget.z(),
                    0.5
            );
            movement = new MoveToSkill(
                    expectedPlayerId,
                    core,
                    coreFrames,
                    (moveContext, moveFrame, moveParameters) ->
                            allowsBoundedHardcoreRisk(
                                    moveContext,
                                    moveFrame
                            )
                                    && horizontalDistanceSquared(
                                            moveFrame.position(),
                                            moveParameters.target()
                                    ) <= 20.25
            );
            final Optional<SkillFailure> precondition =
                    movement.preconditions(
                        context,
                        movementParameters
                    );
            if (precondition.isPresent()) {
                movement = null;
                movementParameters = null;
                return acquireRouteEvidence(
                        context,
                        frame,
                        currentTarget,
                        fresh
                );
            }
            movement.start(context, movementParameters);
        }
        final SkillTickResult result =
                movement.tick(context, movementParameters);
        if (result.status() == SkillTickResult.Status.FAILED) {
            movement = null;
            movementParameters = null;
            return acquireRouteEvidence(
                    context,
                    frame,
                    currentTarget,
                    fresh
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            movement = null;
            movementParameters = null;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    /**
     * Keeps a nearby observed stack in view while taking one bounded player
     * input frame toward it. The general A* mover remains the fallback when
     * the immediate destination lacks fresh clearance/support evidence.
     */
    private Optional<SkillTickResult> directObservedApproach(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 desired = new PerceptionVec3(
                target.x() - frame.position().x(),
                0.0,
                target.z() - frame.position().z()
        );
        if (desired.lengthSquared() <= HORIZONTAL_EPSILON
                || Math.abs(target.y() - frame.position().y())
                    > DIRECT_APPROACH_VERTICAL_LIMIT) {
            return Optional.empty();
        }
        final Optional<DirectStep> safeStep =
                rememberedOrFreshCardinalStep(frame, desired);
        if (safeStep.isEmpty()) {
            return nearbyVisibleAdvance(context, frame, target);
        }

        final PerceptionVec3 movementTarget =
                safeStep.orElseThrow().target();
        if (!core.look(lookAt(
                frame.eyePosition(),
                new PerceptionVec3(
                        movementTarget.x(),
                        frame.eyePosition().y(),
                        movementTarget.z()
                )
        )).accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".look_rejected"
            ));
        }
        /*
         * The queued look and movement are consumed in the same vanilla
         * input frame, so the new look direction is the forward basis.
         * Projecting against the previous semantic look makes the body
         * strafe away from the item after a turn.
         */
        final MovementIntent movement =
                new MovementIntent(1.0, 0.0, false, false);
        if (!core.move(movement).accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".move_rejected"
            ));
        }
        routeEvidenceProbes = 0;
        return Optional.of(SkillTickResult.running(true, true));
    }

    /**
     * A visible stack can be only a few blocks away while the semantic mapper
     * is between cardinal support observations.  A real player advances one
     * bounded input frame in that situation.  This fallback remains fair:
     * the stack is first-person visible, the current body/support cells are
     * freshly observed, and a hostile/proximity signal is accepted only by a
     * compound parent that has proved the shelter invariant.
     */
    private Optional<SkillTickResult> nearbyVisibleAdvance(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final double horizontalDistanceSquared =
                horizontalDistanceSquared(frame.position(), target);
        if (horizontalDistanceSquared > 16.0
                || Math.abs(target.y() - frame.position().y()) > 0.9
                || !frame.onGround()
                || frame.inWater()
                || !allowsBoundedHardcoreRisk(context, frame)
                || !nearbyAdvanceHasNoUnmanagedThreat(context, frame)) {
            return Optional.empty();
        }
        /*
         * The companion is already standing on a vanilla collision surface,
         * but a first-person ray fan can leave the exact centre-below voxel
         * unknown (for example when the camera is looking at a dropped item).
         * A single, low-speed input frame toward a same-level, recently
         * observed stack is the ordinary player action in that situation.
         * Keep the permission bounded: reject a known liquid support and do
         * not use this fallback while the body is in a hazardous pose. The
         * next semantic frame still revalidates collision and survival state.
         */
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(frame.feet().below());
        if (support.isPresent()
                && support.orElseThrow().kind().isLiquid()) {
            return Optional.empty();
        }
        final LookIntent look = lookAt(
                frame.eyePosition(),
                target.add(new PerceptionVec3(0.0, 0.15, 0.0))
        );
        if (!core.look(look).accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".look_rejected"
            ));
        }
        if (!core.move(new MovementIntent(1.0, 0.0, false, false))
                .accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".move_rejected"
            ));
        }
        routeEvidenceProbes = 0;
        return Optional.of(SkillTickResult.running(true, true));
    }

    private boolean allowsBoundedHardcoreRisk(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!context.hardcore()
                || Math.max(context.riskScore(), frame.danger()) <= 0.10) {
            return true;
        }
        try {
            return hardcoreRiskAuthorization.test(context, frame);
        } catch (RuntimeException invalidAuthorization) {
            return false;
        }
    }

    private boolean nearbyAdvanceHasNoUnmanagedThreat(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final boolean hostileVisible = frame.visibleEntities().stream()
                .anyMatch(entity -> entity.hostile() || entity.projectile());
        if (hostileVisible && !allowsBoundedHardcoreRisk(context, frame)) {
            return false;
        }
        for (final dev.mcai.companion.perception.DangerSignal signal
                : frame.dangerSignals()) {
            if (signal.kind()
                    != dev.mcai.companion.perception.DangerKind
                        .HOSTILE_PROXIMITY
                    || !allowsBoundedHardcoreRisk(context, frame)) {
                return false;
            }
        }
        return true;
    }

    private static double horizontalDistanceSquared(
            final PerceptionVec3 first,
            final PerceptionVec3 second
    ) {
        final double deltaX = second.x() - first.x();
        final double deltaZ = second.z() - first.z();
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private Optional<DirectStep> rememberedOrFreshCardinalStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 desired
    ) {
        final Optional<DirectStep> fresh =
                safestFreshCardinalStep(frame, desired);
        if (fresh.isPresent()) {
            final DirectStep step = fresh.orElseThrow();
            directStepCell = step.cell();
            directStepTarget = step.target();
            directStepEvidenceRevision =
                    frame.navigation().revision();
            return fresh;
        }
        if (directStepReusable(frame)) {
            return Optional.of(new DirectStep(
                    directStepCell,
                    directStepTarget
            ));
        }
        clearDirectStep();
        return Optional.empty();
    }

    private Optional<VisibleEntity> mergedReplacement(
            final Snapshot snapshot
    ) {
        if (lastKnownPosition == null
                || boundEntityId == null
                || itemId == null) {
            return Optional.empty();
        }
        return snapshot.core().visibleEntities().stream()
                .filter(candidate ->
                        !boundEntityId.equals(candidate.entityId())
                )
                .filter(candidate ->
                        ITEM_ENTITY.equals(candidate.entityTypeId())
                )
                .filter(candidate -> itemId.equals(
                        candidate.visibleProperties().get("itemId")
                ))
                .filter(candidate -> snapshot.interaction()
                        .visibleEntities().stream()
                        .anyMatch(interactionCandidate ->
                                candidate.entityId().equals(
                                        interactionCandidate.entityId()
                                )
                                    && itemId.equals(
                                        interactionCandidate
                                            .visibleProperties()
                                                .get("itemId")
                                    )
                        ))
                .filter(candidate -> withinMergedReplacementRadius(
                        lastKnownPosition,
                        candidate.position()
                ))
                .min(java.util.Comparator.comparingDouble(
                        candidate -> candidate.position()
                            .subtract(lastKnownPosition)
                            .lengthSquared()
                ));
    }

    static boolean withinMergedReplacementRadius(
            final PerceptionVec3 original,
            final PerceptionVec3 candidate
    ) {
        return original != null
                && candidate != null
                && candidate.subtract(original).lengthSquared()
                    <= MERGED_REPLACEMENT_DISTANCE_SQUARED;
    }

    private static Optional<DirectStep> safestFreshCardinalStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 desired
    ) {
        final PerceptionVec3 direction = desired.normalized();
        final GridPos feet = frame.feet();
        DirectStep best = null;
        double bestScore = 0.0;
        for (final int[] cardinal : CARDINALS) {
            final GridPos destination = feet.offset(
                    cardinal[0],
                    0,
                    cardinal[1]
            );
            final Optional<ObservedVoxel> body =
                    frame.navigation().voxelAt(destination);
            final Optional<ObservedVoxel> head =
                    frame.navigation().voxelAt(destination.above());
            final Optional<ObservedVoxel> support =
                    frame.navigation().voxelAt(destination.below());
            if (body.isEmpty()
                    || head.isEmpty()
                    || support.isEmpty()
                    || !NavigationEvidence.hasFreshTraversalClearance(
                        body.orElseThrow(),
                        frame.navigation().revision()
                    )
                    || !NavigationEvidence.hasFreshTraversalClearance(
                        head.orElseThrow(),
                        frame.navigation().revision()
                    )
                    || !NavigationEvidence.isFreshStandingSupport(
                        support.orElseThrow(),
                        frame.navigation().revision()
                    )
                    || Math.max(
                        body.orElseThrow().effectiveDanger(),
                        head.orElseThrow().effectiveDanger()
                    ) > 0.35) {
                continue;
            }
            final double score = direction.dot(
                    new PerceptionVec3(
                            cardinal[0],
                            0.0,
                            cardinal[1]
                    )
            );
            if (score <= bestScore) {
                continue;
            }
            bestScore = score;
            best = new DirectStep(
                    destination,
                    new PerceptionVec3(
                            destination.x() + 0.5,
                            frame.position().y(),
                            destination.z() + 0.5
                    )
            );
        }
        return Optional.ofNullable(best);
    }

    private boolean directStepReusable(
            final CoreSkillFrame frame
    ) {
        if (directStepCell == null
                || directStepTarget == null
                || directStepEvidenceRevision < 0
                || frame.feet().equals(directStepCell)
                || frame.navigation().revision()
                    < directStepEvidenceRevision
                || frame.navigation().revision()
                    - directStepEvidenceRevision
                        > DIRECT_STEP_MEMORY_REVISIONS) {
            return false;
        }
        final Optional<ObservedVoxel> body =
                frame.navigation().voxelAt(directStepCell);
        final Optional<ObservedVoxel> head =
                frame.navigation().voxelAt(
                        directStepCell.above()
                );
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(
                        directStepCell.below()
                );
        return body.isPresent()
                && head.isPresent()
                && support.isPresent()
                && recentTraversalEvidence(
                    body.orElseThrow(),
                    frame.navigation().revision()
                )
                && recentTraversalEvidence(
                    head.orElseThrow(),
                    frame.navigation().revision()
                )
                && recentStandingSupport(
                    support.orElseThrow(),
                    frame.navigation().revision()
                )
                && Math.max(
                    body.orElseThrow().effectiveDanger(),
                    head.orElseThrow().effectiveDanger()
                ) <= 0.35;
    }

    private static boolean recentTraversalEvidence(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return revision >= voxel.observationRevision()
                && revision - voxel.observationRevision()
                    <= DIRECT_STEP_MEMORY_REVISIONS
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    private static boolean recentStandingSupport(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return revision >= voxel.observationRevision()
                && revision - voxel.observationRevision()
                    <= DIRECT_STEP_MEMORY_REVISIONS
                && voxel.kind().supportsWeight()
                && voxel.topSupportAffordance()
                    == TopSupportAffordance.STURDY_FULL_TOP
                && (voxel.occupancyEvidence()
                    == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                    == OccupancyEvidence.BODY_CONTACT);
    }

    private SkillTickResult awaitReacquisition(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (lostSinceTick < 0) {
            lostSinceTick = context.gameTick();
        }
        if (context.gameTick() - lostSinceTick
                >= LOST_GRACE_TICKS) {
            return fail(
                    context,
                    NAME + ".item_lost_without_pickup"
            );
        }

        /*
         * A dropped stack can leave the narrow first-person view while the
         * body turns, while the stack is still settling, or while MoveTo
         * looks down to validate the floor. Cancelling on the first absent
         * semantic frame creates a livelock: reacquisition restarts movement,
         * movement looks away, and the next refresh cancels it again.
         *
         * Continue only to the last position the companion genuinely saw,
         * for the same bounded lost-target grace period. This exposes no
         * hidden position and inventory ownership remains the sole success
         * condition.
         */
        /*
         * Give the remembered, bounded last position the same input-frame
         * priority as a currently visible stack.  A route child can be left
         * over from the preceding visible frame; if it is allowed to remain
         * active, its quiesce/replan path can clear the direct approach on
         * the same tick and recreate the observed turning-without-walking
         * loop.
         */
        if (movement != null) {
            cancelMovement(context);
        }
        final Optional<SkillTickResult> directApproach =
                directObservedApproach(
                        context,
                        frame,
                        lastKnownPosition
                );
        if (directApproach.isPresent()) {
            return directApproach.orElseThrow();
        }
        if (movement == null && lastKnownPosition != null) {
            startLostTargetMovement(context, frame, lastKnownPosition);
        }
        if (movement != null && movementParameters != null) {
            final SkillTickResult result =
                    movement.tick(context, movementParameters);
            if (result.status() == SkillTickResult.Status.FAILED) {
                movement = null;
                movementParameters = null;
                return fail(
                        context,
                        NAME + ".safe_route_unavailable"
                );
            }
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                return SkillTickResult.running(
                        result.madeProgress(),
                        result.safeCheckpoint()
                );
            }
            movement = null;
            movementParameters = null;
        }

        return acquireRouteEvidence(
                context,
                frame,
                lastKnownPosition,
                fresh
        );
    }

    /**
     * Once a stack leaves the narrow view, keep walking to its last observed
     * floor column before spending the grace window on camera probes.  The
     * target position is bounded first-person memory; the child still plans
     * only through freshly observed navigation evidence and never treats the
     * remembered entity as a hidden world query.
     */
    private void startLostTargetMovement(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        if (target == null
                || horizontalDistanceSquared(frame.position(), target)
                    > 20.25) {
            return;
        }
        movementParameters = new MoveToParameters(
                boundDimension,
                target.x(),
                frame.position().y(),
                target.z(),
                0.5
        );
        movement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                (moveContext, moveFrame, moveParameters) ->
                        allowsBoundedHardcoreRisk(
                                moveContext,
                                moveFrame
                        )
                                && horizontalDistanceSquared(
                                        moveFrame.position(),
                                        moveParameters.target()
                                ) <= 20.25
        );
        final Optional<SkillFailure> blocked = movement.preconditions(
                context,
                movementParameters
        );
        if (blocked.isPresent()) {
            movement = null;
            movementParameters = null;
            return;
        }
        movement.start(context, movementParameters);
    }

    private SkillTickResult acquireRouteEvidence(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 target,
            final boolean fresh
    ) {
        if (withinPickupWaitRange(frame.position(), target)) {
            routeEvidenceProbes = 0;
            core.stop();
            return SkillTickResult.running(fresh, false);
        }
        if (routeEvidenceProbes
                >= MAXIMUM_ROUTE_EVIDENCE_PROBES) {
            return fail(
                    context,
                    NAME + ".safe_route_unavailable"
            );
        }
        final PerceptionVec3 horizontal = new PerceptionVec3(
                target.x() - frame.position().x(),
                0.0,
                target.z() - frame.position().z()
        );
        core.stop();
        if (horizontal.lengthSquared() <= HORIZONTAL_EPSILON) {
            final LookIntent look = lookAt(
                    frame.eyePosition(),
                    target.add(new PerceptionVec3(0.0, 0.2, 0.0))
            );
            if (!core.look(look).accepted()) {
                return fail(context, NAME + ".look_rejected");
            }
            routeEvidenceProbes++;
            return SkillTickResult.running(true, false);
        }
        /*
         * A visible entity proves the destination, but a forward eye ray does
         * not necessarily prove the next floor/body/head cells. Briefly look
         * at the next support cell; the normal fair semantic sampler then
         * records the traversed air and sturdy surface for the next tick.
         */
        final PerceptionVec3 direction = horizontal.normalized();
        final PerceptionVec3 floorProbe = new PerceptionVec3(
                frame.position().x() + direction.x() * 0.85,
                frame.position().y() - 0.05,
                frame.position().z() + direction.z() * 0.85
        );
        final LookIntent look = lookAt(
                frame.eyePosition(),
                floorProbe
        );
        if (!core.look(look).accepted()) {
            return fail(context, NAME + ".look_rejected");
        }
        routeEvidenceProbes++;
        return SkillTickResult.running(true, false);
    }

    static boolean withinPickupWaitRange(
            final PerceptionVec3 playerPosition,
            final PerceptionVec3 itemPosition
    ) {
        final double deltaX =
                itemPosition.x() - playerPosition.x();
        final double deltaZ =
                itemPosition.z() - playerPosition.z();
        return deltaX * deltaX + deltaZ * deltaZ
                    <= PICKUP_WAIT_HORIZONTAL_RADIUS
                        * PICKUP_WAIT_HORIZONTAL_RADIUS
                && Math.abs(
                    itemPosition.y() - playerPosition.y()
                ) <= PICKUP_WAIT_VERTICAL_LIMIT;
    }

    private Resolution resolveInitial(
            final CollectObservedItemParameters parameters
    ) {
        final Optional<InteractionSkillFrame> historicalInteraction =
                interactionFrames.atObservation(
                        parameters.sampleSequence()
                );
        if (historicalInteraction.isEmpty()) {
            return Resolution.failed(
                    NAME + ".stale_observation_id"
            );
        }
        final InteractionSkillFrame originallyObservedFrame =
                historicalInteraction.orElseThrow();
        if (!expectedPlayerId.equals(
                originallyObservedFrame.playerId()
        )) {
            return Resolution.failed(NAME + ".body_mismatch");
        }
        final int index = parameters.observationIndex();
        if (index < 0
                || index >= originallyObservedFrame
                    .visibleEntities()
                    .size()) {
            return Resolution.failed(
                    NAME + ".invalid_observation_id"
            );
        }
        final VisibleEntity originallyObserved =
                originallyObservedFrame.visibleEntities().get(index);
        final String originallyVisibleItem =
                originallyObserved.visibleProperties().get("itemId");
        if (!ITEM_ENTITY.equals(
                    originallyObserved.entityTypeId()
                )
                || originallyVisibleItem == null
                || originallyVisibleItem.isBlank()) {
            return Resolution.failed(
                    NAME + ".visible_dropped_item_required"
            );
        }

        final SnapshotValidation validation =
                currentSnapshot();
        if (validation.failure().isPresent()) {
            return Resolution.failed(
                    validation.failure().orElseThrow()
            );
        }
        final Snapshot snapshot =
                validation.snapshot().orElseThrow();
        final Optional<VisibleEntity> currentCoreTarget =
                snapshot.core().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                originallyObserved.entityId()
                        ))
                        .findFirst();
        final Optional<VisibleEntity> currentInteractionTarget =
                snapshot.interaction().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                originallyObserved.entityId()
                        ))
                        .findFirst();
        if (currentCoreTarget.isEmpty()
                || currentInteractionTarget.isEmpty()) {
            return Resolution.failed(
                    NAME + ".target_not_currently_visible"
            );
        }
        final VisibleEntity coreTarget =
                currentCoreTarget.orElseThrow();
        final VisibleEntity interactionTarget =
                currentInteractionTarget.orElseThrow();
        if (!coreTarget.entityId().equals(
                interactionTarget.entityId()
        )) {
            return Resolution.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        final String visibleItem =
                coreTarget.visibleProperties().get("itemId");
        if (!ITEM_ENTITY.equals(coreTarget.entityTypeId())
                || !originallyObserved.entityTypeId().equals(
                    coreTarget.entityTypeId()
                )
                || !originallyVisibleItem.equals(visibleItem)
                || !visibleItem.equals(
                    interactionTarget.visibleProperties()
                            .get("itemId")
                )) {
            return Resolution.failed(
                    NAME + ".visible_dropped_item_changed"
            );
        }
        return Resolution.resolved(snapshot, coreTarget);
    }

    private SnapshotValidation currentSnapshot() {
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.current();
        final Optional<InteractionSkillFrame> maybeInteraction =
                interactionFrames.current();
        if (maybeCore.isEmpty() || maybeInteraction.isEmpty()) {
            return SnapshotValidation.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final CoreSkillFrame frame = maybeCore.orElseThrow();
        final InteractionSkillFrame interaction =
                maybeInteraction.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())
                || !expectedPlayerId.equals(
                    interaction.playerId()
                )) {
            return SnapshotValidation.failed(
                    NAME + ".body_mismatch"
            );
        }
        if (!frame.dimension().equals(interaction.dimension())
                || frame.observationRevision()
                    != interaction.observationRevision()) {
            return SnapshotValidation.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        final OptionalLong generation =
                interactions.sessionGeneration();
        if (generation.isEmpty()
                || generation.orElseThrow()
                    != interaction.sessionGeneration()
                || boundSessionGeneration >= 0
                    && generation.orElseThrow()
                        != boundSessionGeneration) {
            return SnapshotValidation.failed(
                    NAME + ".session_mismatch"
            );
        }
        return SnapshotValidation.available(
                new Snapshot(frame, interaction)
        );
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double dangerLimit = context.hardcore()
                ? 0.10
                : 0.35;
        if (Math.max(context.riskScore(), frame.danger())
                    > dangerLimit
                && !allowsBoundedHardcoreRisk(context, frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double health = frame.health() / frame.maxHealth();
        if (health < (context.hardcore() ? 0.80 : 0.45)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        return new LookIntent(
                (float) Math.toDegrees(
                    Math.atan2(-delta.x(), delta.z())
                ),
                (float) Math.toDegrees(
                    Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                    )
                )
        );
    }

    private void cancelMovement(final SkillContext context) {
        if (movement == null || movementParameters == null) {
            return;
        }
        try {
            movement.cancel(context, movementParameters);
        } catch (RuntimeException ignored) {
            core.stop();
        }
        movement = null;
        movementParameters = null;
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        return fail(context, SkillFailure.of(code));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final SkillFailure reason
    ) {
        cancelMovement(context);
        core.stop();
        failure = reason;
        phase = Phase.FAILED;
        clearBinding();
        return SkillTickResult.failed(reason);
    }

    private void clearBinding() {
        boundEntityId = null;
        itemId = null;
        boundDimension = null;
        boundSessionGeneration = -1;
        routeEvidenceProbes = 0;
        clearDirectStep();
    }

    private void clearDirectStep() {
        directStepCell = null;
        directStepTarget = null;
        directStepEvidenceRevision = -1;
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String expectedItemId
    ) {
        return frame.inventory().stream()
                .filter(item ->
                        item.itemId().equals(expectedItemId)
                )
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private enum Phase {
        IDLE,
        COLLECTING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
    }

    private record DirectStep(
            GridPos cell,
            PerceptionVec3 target
    ) {
    }

    private record SnapshotValidation(
            Optional<Snapshot> snapshot,
            Optional<SkillFailure> failure
    ) {
        private static SnapshotValidation available(
                final Snapshot snapshot
        ) {
            return new SnapshotValidation(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        private static SnapshotValidation failed(
                final String code
        ) {
            return new SnapshotValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    private record Resolution(
            Optional<Snapshot> snapshot,
            Optional<VisibleEntity> target,
            Optional<SkillFailure> failure
    ) {
        private static Resolution resolved(
                final Snapshot snapshot,
                final VisibleEntity target
        ) {
            return new Resolution(
                    Optional.of(snapshot),
                    Optional.of(target),
                    Optional.empty()
            );
        }

        private static Resolution failed(
                final String code
        ) {
            return failed(SkillFailure.of(code));
        }

        private static Resolution failed(
                final SkillFailure failure
        ) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }
}
