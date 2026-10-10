package dev.mcai.companion.skills.core;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Follows one currently observable, non-hostile entity without privileged
 * entity tracking. The target must repeatedly pass the ordinary semantic
 * distance/FOV/occlusion filter. Brief loss of sight causes a bounded visual
 * search, never hidden-position pursuit.
 */
public final class FollowEntitySkill implements Skill<FollowEntityParameters> {
    private static final double RETARGET_DISTANCE = 2.0;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final long MAX_BINDING_SAMPLE_LAG = 512L;
    /**
     * Turning to scan is perception work, not physical follow progress. A
     * blocked local route must therefore hand control back to the planner
     * instead of rotating indefinitely in place.
     */
    private static final int MAX_PHYSICAL_STALL_TICKS = 80;
    private static final double MINIMUM_PHYSICAL_PROGRESS = 0.08;
    private static final int ROUTE_SCAN_TICKS = 48;
    private static final int MAX_ROUTE_SCAN_ATTEMPTS = 2;
    private static final int TRACKED_REACQUIRE_TICKS = 12;
    /*
     * A visible teammate on the same, safe floor does not need a full A*
     * reconstruction before the first legal input frame.  Requiring one made
     * ordinary "come here" requests wait for repeated scan/plan cycles and
     * look like a stationary, rotating companion.  The direct lane is only a
     * short, first-person-visible, level-ground input; obstacles, height
     * changes, water, danger, or loss of visibility still fall back to the
     * conservative route planner.
     */
    private static final double DIRECT_FOLLOW_DISTANCE = 12.0;
    private static final double DIRECT_FOLLOW_VERTICAL_LIMIT = 0.9;
    private static final double DIRECT_FOLLOW_SPRINT_DISTANCE = 5.0;
    private static final double DIRECT_FOLLOW_MAXIMUM_DANGER = 0.20;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final LocalAStarPlanner planner;
    private final CoreSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundEntityId;
    private MoveToSkill movement;
    private MoveToParameters movementTarget;
    private long lastObservationRevision = -1;
    private long lostSinceTick = -1;
    private long nextScanTick;
    private String lastMovementFailure = "";
    private int movementFailureCount;
    private PerceptionVec3 lastPhysicalPosition;
    private long lastPhysicalProgressTick = -1;
    private long firstMovementInputTick = -1;
    private long routeScanStartedTick = -1;
    private long nextRouteScanTick = -1;
    private int routeScanTurns;
    private int routeScanAttempts;
    private long trackedReacquireStartedTick = -1;

    public FollowEntitySkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LocalAStarPlanner planner,
            CoreSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<FollowEntityParameters> parameters() {
        return CoreSkillParameters::parseFollowEntity;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    "follow_entity.observation_unavailable"
            ));
        }
        CoreSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return Optional.of(SkillFailure.of(
                    "follow_entity.player_mismatch"
            ));
        }
        Optional<VisibleEntity> target =
                resolveAuthoredTarget(frame, parameters);
        if (target.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    parameters.sampleSequence()
                                    == frame.observationRevision()
                            ? "follow_entity.invalid_observation_id"
                            : "follow_entity.stale_observation_id"
            ));
        }
        if (!safeFollowTarget(target.orElseThrow())) {
            return Optional.of(SkillFailure.of(
                    "follow_entity.unsafe_target"
            ));
        }
        /*
         * Do not require the target to remain in the current camera frame
         * while a model response is in flight.  The authored sample is still
         * fair evidence: it is bounded by MAX_BINDING_SAMPLE_LAG, retained
         * by the first-person frame source, and restricted to the same
         * dimension.  If the target has left the current view, start in the
         * bounded SEARCHING phase and let the ordinary lostGraceTicks window
         * reacquire it.  This closes the real field race where a player turns
         * a corner during model latency and a valid follow command is
         * rejected before it can issue one movement input.
         */
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.FOLLOWING;
        failure = null;
        CoreSkillFrame frame = frames.current().orElseThrow(
                () -> new IllegalStateException(
                    "Follow observation disappeared before start"
                )
        );
        final VisibleEntity authoredTarget = resolveAuthoredTarget(
                frame,
                parameters
        )
                .filter(FollowEntitySkill::safeFollowTarget)
                .orElseThrow(() -> new IllegalStateException(
                        "Follow observation changed before start"
                ));
        boundEntityId = authoredTarget.entityId();
        phase = currentlyVisibleBoundTarget(frame, authoredTarget).isPresent()
                ? Phase.FOLLOWING
                : Phase.SEARCHING;
        movement = null;
        movementTarget = null;
        lastObservationRevision = -1;
        lostSinceTick = -1;
        nextScanTick = context.gameTick();
        lastMovementFailure = "";
        movementFailureCount = 0;
        lastPhysicalPosition = frame.position();
        /*
         * Route observation is not motion.  Do not start a physical-stall
         * timer until this skill has actually sent a movement input frame.
         */
        lastPhysicalProgressTick = -1;
        firstMovementInputTick = -1;
        routeScanStartedTick = -1;
        nextRouteScanTick = -1;
        routeScanTurns = 0;
        routeScanAttempts = 0;
        trackedReacquireStartedTick = -1;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.FOLLOWING
                && phase != Phase.SEARCHING
                && phase != Phase.ROUTE_SEARCHING) {
            return SkillTickResult.failed("follow_entity.invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail("follow_entity.internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"observationId\":\"%s\","
                                + "\"sampleSequence\":%d,"
                                + "\"followDistance\":%.3f,"
                        + "\"lostSinceTick\":%d,"
                        + "\"movementFailures\":%d,"
                                + "\"lastMovementFailure\":\"%s\","
                                + "\"physicalStallAge\":%d}",
                        phase.name(),
                        parameters.observationId(),
                        parameters.sampleSequence(),
                        parameters.followDistance(),
                        lostSinceTick,
                        movementFailureCount,
                        lastMovementFailure,
                        lastPhysicalProgressTick < 0
                                ? -1
                                : Math.max(
                                    0L,
                                    context.gameTick()
                                        - lastPhysicalProgressTick
                                )
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        cancelMovement(context);
        frames.current().ifPresent(
                frame -> CoreSkillSafety.quiesce(actuator, frame)
        );
        phase = Phase.CANCELLED;
        boundEntityId = null;
        lastPhysicalPosition = null;
        lastPhysicalProgressTick = -1;
        firstMovementInputTick = -1;
        clearRouteSearch();
        trackedReacquireStartedTick = -1;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        return switch (phase) {
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(
                    SkillFailure.of("follow_entity.invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(
            SkillContext context,
            FollowEntityParameters parameters
    ) {
        Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return fail("follow_entity.observation_unavailable");
        }
        CoreSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return fail("follow_entity.player_mismatch");
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail("follow_entity.stale_observation");
        }

        final Optional<VisibleEntity> visible = visibleTarget(frame);
        final Optional<CoreSkillFrameSource.TrackablePlayer> tracked =
                frames.trackablePlayer(boundEntityId)
                        .filter(player -> player.dimension().equals(
                                frame.dimension()
                        ));
        if (visible.isEmpty() && tracked.isEmpty()) {
            return search(context, parameters, frame);
        }
        final boolean reacquired = phase == Phase.SEARCHING;
        final boolean routeSearching = phase == Phase.ROUTE_SEARCHING;
        if (reacquired) {
            /* Do not carry pre-loss stall age into a newly reacquired route. */
            lastPhysicalPosition = frame.position();
            lastPhysicalProgressTick = -1;
            firstMovementInputTick = -1;
        }
        if (visible.isPresent()
                && !safeFollowTarget(visible.orElseThrow())) {
            return fail("follow_entity.unsafe_target");
        }

        lostSinceTick = -1;
        if (lastPhysicalPosition == null) {
            lastPhysicalPosition = frame.position();
        } else if (frame.position().subtract(lastPhysicalPosition).length()
                >= MINIMUM_PHYSICAL_PROGRESS) {
            lastPhysicalPosition = frame.position();
            lastPhysicalProgressTick = context.gameTick();
        }
        final PerceptionVec3 targetPosition = visible
                .map(VisibleEntity::position)
                .orElseGet(() -> tracked.orElseThrow().position());
        boolean freshTarget = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = frame.observationRevision();

        double distance = frame.position()
                .subtract(targetPosition)
                .length();
        if (distance <= parameters.followDistance()) {
            phase = Phase.FOLLOWING;
            clearRouteSearch();
            cancelMovement(context);
            if (!actuator.stop().accepted()) {
                return fail("follow_entity.actuator_rejected");
            }
            if (!aimAt(frame, targetPosition)) {
                return fail("follow_entity.actuator_rejected");
            }
            // Maintaining the requested moving-distance invariant is useful
            // progress for a deliberately long-running follow skill.
            return SkillTickResult.running(true, true);
        }

        if (routeSearching) {
            return continueRouteSearch(
                    context,
                    frame,
                    targetPosition
            );
        }
        phase = Phase.FOLLOWING;

        if (visible.isPresent()) {
            trackedReacquireStartedTick = -1;
        } else if (tracked.isPresent()) {
            final Optional<SkillTickResult> reacquiring =
                    aimToReacquireTrackedPlayer(
                            context,
                            frame,
                            targetPosition
                    );
            if (reacquiring.isPresent()) {
                return reacquiring.orElseThrow();
            }
        }

        if (firstMovementInputTick >= 0
                && lastPhysicalProgressTick >= 0
                && context.gameTick() - lastPhysicalProgressTick
                    >= MAX_PHYSICAL_STALL_TICKS) {
            cancelMovement(context);
            actuator.stop();
            return beginRouteSearch(context, frame, targetPosition);
        }

        if (context.hardcore()
                && frame.danger() > policy.hardcoreMaximumDanger()) {
            cancelMovement(context);
            actuator.stop();
            return SkillTickResult.running(true, true);
        }

        /*
         * Do this before constructing a MoveTo child.  MoveTo is intentionally
         * fail-closed while its fair voxel map is incomplete; that is correct
         * for unknown terrain, but it must not turn a plainly visible player
         * a few blocks away into a scan-before-every-step loop.
         */
        Optional<SkillTickResult> direct = directVisibleFollow(
                context,
                frame,
                targetPosition,
                distance,
                visible.isPresent()
        );
        if (direct.isPresent()) {
            return direct.orElseThrow();
        }

        boolean targetMoved = movementTarget == null
                || movementTarget.target()
                .subtract(targetPosition)
                .length() >= RETARGET_DISTANCE;
        if (movement == null || (freshTarget && targetMoved)) {
            cancelMovement(context);
            movementTarget = new MoveToParameters(
                    frame.dimension(),
                    targetPosition.x(),
                    targetPosition.y(),
                    targetPosition.z(),
                    parameters.followDistance()
            );
            movement = new MoveToSkill(
                    expectedPlayerId,
                    actuator,
                    frames,
                    planner,
                    policy
            );
            Optional<SkillFailure> rejected = movement.preconditions(
                    context,
                    movementTarget
            );
            if (rejected.isPresent()) {
                movement = null;
                movementTarget = null;
                actuator.stop();
                return SkillTickResult.running(freshTarget, true);
            }
            movement.start(context, movementTarget);
        }

        SkillTickResult result = movement.tick(context, movementTarget);
        if (result.status() == SkillTickResult.Status.RUNNING
                && result.madeProgress()) {
            /*
             * MoveTo reports an accepted local action or a route transition.
             * Starting the timer here prevents pre-route observation from
             * being mistaken for failed walking while preserving a bounded
             * physical-stall escape once movement has begun.
             */
            markMovementInput(context, frame);
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            movement = null;
            movementTarget = null;
            lastMovementFailure = "";
            movementFailureCount = 0;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            lastMovementFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse("move_to.invalid_tick_result");
            movementFailureCount++;
            if (Boolean.getBoolean("mcai.liveModelTest")
                    && (movementFailureCount == 1
                        || movementFailureCount % 20 == 0)) {
                MinecraftAiCompanion.LOGGER.info(
                        "Live follow local movement attempt failed: "
                            + "code={}, attempts={}, observation={}, "
                            + "navigation={}, feet={}",
                        lastMovementFailure,
                        movementFailureCount,
                        frame.observationRevision(),
                        frame.navigation().revision(),
                        frame.feet()
                );
            }
            movement = null;
            movementTarget = null;
            actuator.stop();
            // A target can remain visible beyond the currently mapped local
            // corridor. Let subsequent head scans grow fair perception rather
            // than converting that into privileged direct pursuit.
            return SkillTickResult.running(false, true);
        }
        return result;
    }

    /**
     * Issues one normal player input frame toward a nearby teammate that the
     * companion can currently see.  This does not inspect world state beyond
     * the already-published fair frame and does not cross unknown terrain.
     */
    private Optional<SkillTickResult> directVisibleFollow(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPosition,
            final double distance,
            final boolean targetVisible
    ) {
        final double horizontalDistance = Math.hypot(
                targetPosition.x() - frame.position().x(),
                targetPosition.z() - frame.position().z()
        );
        if (!targetVisible
                || horizontalDistance <= parametersSafeDistanceEpsilon()
                || horizontalDistance > DIRECT_FOLLOW_DISTANCE
                || Math.abs(targetPosition.y() - frame.position().y())
                    > DIRECT_FOLLOW_VERTICAL_LIMIT
                || !frame.onGround()
                || frame.inWater()
                || frame.danger() > DIRECT_FOLLOW_MAXIMUM_DANGER) {
            return Optional.empty();
        }
        final Optional<ObservedVoxel> currentSupport = frame.navigation()
                .voxelAt(frame.feet().below());
        if (currentSupport.isPresent()
                && currentSupport.orElseThrow().kind().isLiquid()) {
            return Optional.empty();
        }
        /* A direct input lane replaces, rather than momentarily interrupts,
         * an existing route child. The old implementation cancelled the
         * child before it knew whether direct movement was usable, causing a
         * new A* plan on every tick whenever this method returned empty. */
        cancelMovement(context);
        if (!aimAt(frame, targetPosition)) {
            return Optional.of(fail("follow_entity.actuator_rejected"));
        }
        final boolean sprint = distance > DIRECT_FOLLOW_SPRINT_DISTANCE
                && frame.foodLevel() > 6;
        if (!actuator.move(new MovementIntent(1.0, 0.0, sprint, false))
                .accepted()) {
            return Optional.of(fail("follow_entity.actuator_rejected"));
        }
        markMovementInput(context, frame);
        return Optional.of(SkillTickResult.running(true, true));
    }

    private void markMovementInput(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (firstMovementInputTick < 0) {
            firstMovementInputTick = context.gameTick();
        }
        if (lastPhysicalProgressTick < 0) {
            lastPhysicalPosition = frame.position();
            lastPhysicalProgressTick = context.gameTick();
        }
    }

    /**
     * A non-sneaking teammate may publish an authorized live coordinate even
     * between semantic camera samples. Before falling into a conservative
     * route reconstruction, turn once toward that nearby coordinate and wait
     * briefly for the normal first-person sampler to confirm line of sight.
     * This prevents a teammate crossing the follow-distance threshold between
     * samples from leaving the body permanently looking at an old heading.
     */
    private Optional<SkillTickResult> aimToReacquireTrackedPlayer(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPosition
    ) {
        final double horizontalDistance = Math.hypot(
                targetPosition.x() - frame.position().x(),
                targetPosition.z() - frame.position().z()
        );
        if (horizontalDistance > DIRECT_FOLLOW_DISTANCE
                || Math.abs(targetPosition.y() - frame.position().y())
                    > DIRECT_FOLLOW_VERTICAL_LIMIT
                || !frame.onGround()
                || frame.inWater()
                || frame.danger() > DIRECT_FOLLOW_MAXIMUM_DANGER) {
            trackedReacquireStartedTick = -1;
            return Optional.empty();
        }
        if (trackedReacquireStartedTick < 0) {
            trackedReacquireStartedTick = context.gameTick();
        }
        if (context.gameTick() - trackedReacquireStartedTick
                >= TRACKED_REACQUIRE_TICKS) {
            trackedReacquireStartedTick = -1;
            return Optional.empty();
        }
        cancelMovement(context);
        if (!actuator.stop().accepted()
                || !aimAt(frame, targetPosition)) {
            return Optional.of(fail("follow_entity.actuator_rejected"));
        }
        return Optional.of(SkillTickResult.running(true, true));
    }

    private SkillTickResult beginRouteSearch(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPosition
    ) {
        if (routeScanAttempts >= MAX_ROUTE_SCAN_ATTEMPTS) {
            return fail("follow_entity.no_walkable_route");
        }
        routeScanAttempts++;
        routeScanStartedTick = context.gameTick();
        nextRouteScanTick = context.gameTick();
        routeScanTurns = 0;
        phase = Phase.ROUTE_SEARCHING;
        lastPhysicalPosition = frame.position();
        lastPhysicalProgressTick = -1;
        firstMovementInputTick = -1;
        return continueRouteSearch(context, frame, targetPosition);
    }

    /**
     * Grows only the ordinary first-person navigation map after measured
     * physical stalling. The body remains stopped while its camera samples
     * the floor and side corridors around the tracked teammate direction.
     */
    private SkillTickResult continueRouteSearch(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPosition
    ) {
        cancelMovement(context);
        if (!actuator.stop().accepted()) {
            return fail("follow_entity.actuator_rejected");
        }
        if (context.gameTick() - routeScanStartedTick
                >= ROUTE_SCAN_TICKS) {
            phase = Phase.FOLLOWING;
            routeScanStartedTick = -1;
            nextRouteScanTick = -1;
            routeScanTurns = 0;
            lastPhysicalPosition = frame.position();
            lastPhysicalProgressTick = -1;
            firstMovementInputTick = -1;
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() < nextRouteScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int directionIndex = routeScanTurns % 12;
        final float yawOffset = (float) (
                directionIndex * 30.0 - 180.0
        );
        if (!actuator.look(CoreSkillGeometry.navigationScanTarget(
                frame,
                targetPosition,
                yawOffset
        )).accepted()) {
            return fail("follow_entity.actuator_rejected");
        }
        routeScanTurns++;
        nextRouteScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private void clearRouteSearch() {
        routeScanStartedTick = -1;
        nextRouteScanTick = -1;
        routeScanTurns = 0;
        routeScanAttempts = 0;
    }

    private static double parametersSafeDistanceEpsilon() {
        return 1.0E-6;
    }

    private SkillTickResult search(
            SkillContext context,
            FollowEntityParameters parameters,
            CoreSkillFrame frame
    ) {
        cancelMovement(context);
        if (!actuator.stop().accepted()) {
            return fail("follow_entity.actuator_rejected");
        }
        if (lostSinceTick < 0) {
            lostSinceTick = context.gameTick();
        }
        if (context.gameTick() - lostSinceTick
                >= parameters.lostGraceTicks()) {
            return fail("follow_entity.target_lost");
        }
        phase = Phase.SEARCHING;
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        ActionOutcome scanned = actuator.look(
                CoreSkillGeometry.scanTarget(frame, policy.scanYawDegrees())
        );
        if (!scanned.accepted()) {
            return fail("follow_entity.actuator_rejected");
        }
        nextScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private void cancelMovement(SkillContext context) {
        if (movement != null && movementTarget != null) {
            movement.cancel(context, movementTarget);
        }
        movement = null;
        movementTarget = null;
    }

    private boolean aimAt(
            CoreSkillFrame frame,
            PerceptionVec3 targetPosition
    ) {
        PerceptionVec3 targetCenter = new PerceptionVec3(
                targetPosition.x(),
                targetPosition.y() + 1.0,
                targetPosition.z()
        );
        if (targetCenter.subtract(frame.eyePosition()).lengthSquared()
                > 1.0E-12) {
            return actuator.look(CoreSkillGeometry.lookAt(
                    frame.eyePosition(),
                    targetCenter
            )).accepted();
        }
        return true;
    }

    private SkillTickResult fail(String code) {
        frames.current().ifPresent(
                frame -> CoreSkillSafety.quiesce(actuator, frame)
        );
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        boundEntityId = null;
        movement = null;
        movementTarget = null;
        lastPhysicalPosition = null;
        lastPhysicalProgressTick = -1;
        firstMovementInputTick = -1;
        clearRouteSearch();
        trackedReacquireStartedTick = -1;
        return SkillTickResult.failed(failure);
    }

    private Optional<VisibleEntity> visibleTarget(
            CoreSkillFrame frame
    ) {
        if (boundEntityId == null) {
            return Optional.empty();
        }
        return frame.visibleEntities().stream()
                .filter(entity -> entity.entityId().equals(boundEntityId))
                .findFirst();
    }

    /**
     * Resolves the exact fair sample the model named while allowing the
     * perception loop to continue publishing during network latency. The
     * model still never receives an entity UUID: the server privately maps
     * the old public observation index to a UUID, then requires that same
     * entity to be visible in the current frame before granting movement.
     */
    private Optional<VisibleEntity> resolveAuthoredTarget(
            final CoreSkillFrame current,
            final FollowEntityParameters parameters
    ) {
        if (current.observationRevision()
                    < parameters.sampleSequence()
                || current.observationRevision()
                    - parameters.sampleSequence()
                    > MAX_BINDING_SAMPLE_LAG) {
            return Optional.empty();
        }
        return frames.visibleEntityAtObservation(
                        parameters.sampleSequence(),
                        parameters.observationIndex()
                )
                .filter(binding -> binding.dimension().equals(
                        current.dimension()
                ))
                .map(CoreSkillFrameSource.VisibleEntityBinding::entity);
    }

    private static Optional<VisibleEntity>
            currentlyVisibleBoundTarget(
                    final CoreSkillFrame current,
                    final VisibleEntity authoredTarget
            ) {
        return current.visibleEntities().stream()
                .filter(entity -> entity.entityId().equals(
                        authoredTarget.entityId()
                ))
                .filter(FollowEntitySkill::safeFollowTarget)
                .findFirst();
    }

    private static boolean safeFollowTarget(VisibleEntity target) {
        return !target.hostile() && !target.projectile();
    }

    private enum Phase {
        IDLE,
        FOLLOWING,
        SEARCHING,
        ROUTE_SEARCHING,
        CANCELLED,
        FAILED
    }
}
