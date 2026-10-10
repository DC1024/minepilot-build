package dev.mcai.companion.skills.core;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.LocalPlannerOptions;
import dev.mcai.companion.navigation.LocalRoute;
import dev.mcai.companion.navigation.LocalRouteStatus;
import dev.mcai.companion.navigation.LocalStep;
import dev.mcai.companion.navigation.MovementPrimitive;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.NavigationRiskProfile;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conservative local move skill. It plans only through voxels already derived
 * from fair first-person observations and executes one revalidated step at a
 * time.
 */
public final class MoveToSkill implements Skill<MoveToParameters> {
    private static final double DANGER_EPSILON = 1.0E-9;
    private static final int MAXIMUM_DOOR_ADVANCE_TICKS = 20;
    private static final int STUCK_WINDOW_TICKS = 20;
    private static final int MAXIMUM_STUCK_RECOVERIES = 3;
    private static final int
            MAXIMUM_CONSECUTIVE_TIME_BUDGET_EXHAUSTIONS = 8;
    private static final double STUCK_PROGRESS_DISTANCE_SQUARED = 0.01;
    private static final int TURN_ALIGNMENT_STALL_TICKS = 20;
    private static final int TURN_ALIGNMENT_MAXIMUM_TICKS = 80;
    private static final double TURN_ALIGNMENT_PROGRESS_DEGREES = 0.5;
    private static final long MAXIMUM_ROUTE_MEMORY_AGE_REVISIONS = 16;
    /**
     * A body-contact support is stronger than a visual ray: it was physically
     * stood on by this player.  Keep a slightly longer, still bounded memory
     * for retracing a recently walked corridor (not for arbitrary terrain).
     */
    private static final long MAXIMUM_BODY_CONTACT_SUPPORT_MEMORY_REVISIONS =
            128;
    private static final double PRECISION_ALIGNMENT_DEGREES = 4.0;
    private static final double PRECISION_MINIMUM_FORWARD = 0.12;
    private static final double PRECISION_MAXIMUM_FORWARD = 0.35;
    private static final double PRECISION_DISTANCE_GAIN = 0.45;
    private static final double PRECISION_ARRIVAL_RADIUS = 0.25;
    private static final float[] NAVIGATION_SCAN_YAW_OFFSETS = {
            0.0F,
            -20.0F,
            20.0F,
            -40.0F,
            40.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final LocalAStarPlanner planner;
    private final CoreSkillPolicy policy;
    private final HardcoreRiskAuthorization hardcoreRiskAuthorization;
    private final boolean avoidAgriculturalJumps;
    private final boolean sneakDuringMovement;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private LocalRoute route;
    private LocalNavSnapshot plannedSnapshot;
    private LocalNavSnapshot planningMemorySource;
    private LocalNavSnapshot planningMemorySnapshot;
    private GridPos planningMemoryStart;
    private int stepIndex;
    private long lastPlannedRevision = -1;
    private long nextScanGameTick;
    private int scanTurns;
    private boolean jumpIssued;
    private boolean doorInteractionIssued;
    private int doorAdvanceTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private long lastObservationRevision = -1;
    private final Set<GridPos> transientBlocked = new HashSet<>();
    private final Set<GridPos> visitedFrontiers = new HashSet<>();
    private boolean frontierRoute;
    private long avoidanceRevision = -1;
    private PerceptionVec3 motionWindowPosition;
    private long motionWindowStartedTick = -1;
    private int stuckRecoveries;
    private int consecutiveTimeBudgetExhaustions;
    private long turnAlignmentStartedTick = -1;
    private long turnAlignmentProgressTick = -1;
    private double bestTurnAlignmentError =
            Double.POSITIVE_INFINITY;

    public MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                HardcoreRiskAuthorization.none(),
                false,
                false
        );
    }

    /**
     * Creates a flat agricultural movement child. Optional jump input is
     * disabled so a correction between harvest atoms cannot trample farmland.
     */
    public MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            boolean avoidAgriculturalJumps
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                HardcoreRiskAuthorization.none(),
                avoidAgriculturalJumps,
                false
        );
    }

    /**
     * Creates a movement child that keeps the vanilla sneak key held while
     * walking.  This is used by atomic farmland transactions: ordinary
     * walking over a re-planted farmland cell can turn it into dirt, while
     * sneaking is the same player-visible input that prevents trampling.
     * Swimming deliberately releases sneak in the swim-specific path below.
     */
    public MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            boolean avoidAgriculturalJumps,
            boolean sneakDuringMovement
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                HardcoreRiskAuthorization.none(),
                avoidAgriculturalJumps,
                sneakDuringMovement
        );
    }

    /**
     * Creates a movement child whose compound parent may authorize only one
     * narrowly proven aggregate Hardcore risk. Per-step route danger remains
     * fail-closed.
     */
    public MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            HardcoreRiskAuthorization hardcoreRiskAuthorization
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                hardcoreRiskAuthorization,
                false,
                false
        );
    }

    public MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LocalAStarPlanner planner,
            CoreSkillPolicy policy
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                planner,
                policy,
                HardcoreRiskAuthorization.none(),
                false,
                false
        );
    }

    MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LocalAStarPlanner planner,
            CoreSkillPolicy policy,
            HardcoreRiskAuthorization hardcoreRiskAuthorization
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                planner,
                policy,
                hardcoreRiskAuthorization,
                false,
                false
        );
    }

    MoveToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LocalAStarPlanner planner,
            CoreSkillPolicy policy,
            HardcoreRiskAuthorization hardcoreRiskAuthorization,
            boolean avoidAgriculturalJumps,
            boolean sneakDuringMovement
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.hardcoreRiskAuthorization = Objects.requireNonNull(
                hardcoreRiskAuthorization,
                "hardcoreRiskAuthorization"
        );
        this.avoidAgriculturalJumps = avoidAgriculturalJumps;
        this.sneakDuringMovement = sneakDuringMovement;
    }

    @Override
    public SkillParameterParser<MoveToParameters> parameters() {
        return CoreSkillParameters::parseMoveTo;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            MoveToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        CoreSkillFrame frame = validation.frame().orElseThrow();
        if (hardcoreRiskRejected(context, parameters, frame)) {
            return Optional.of(SkillFailure.of("move_to.hardcore_danger"));
        }
        return Optional.empty();
    }

    @Override
    public void start(SkillContext context, MoveToParameters parameters) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        // Deliberately state-only: no movement, jump, interaction, or other
        // irreversible segment may begin inside Skill.start().
        phase = Phase.READY;
        failure = null;
        route = null;
        plannedSnapshot = null;
        planningMemorySource = null;
        planningMemorySnapshot = null;
        planningMemoryStart = null;
        stepIndex = 0;
        lastPlannedRevision = -1;
        nextScanGameTick = context.gameTick();
        scanTurns = 0;
        jumpIssued = false;
        doorInteractionIssued = false;
        doorAdvanceTicks = 0;
        lastDistance = Double.POSITIVE_INFINITY;
        lastObservationRevision = -1;
        transientBlocked.clear();
        visitedFrontiers.clear();
        frontierRoute = false;
        avoidanceRevision = -1;
        motionWindowPosition = null;
        motionWindowStartedTick = -1;
        stuckRecoveries = 0;
        consecutiveTimeBudgetExhaustions = 0;
        resetTurnAlignment();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            MoveToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        /*
         * Compound parents can observe a child once more at the same server
         * tick as the arrival checkpoint.  Completion is an idempotent safe
         * boundary; reporting it again lets the parent consume the arrival
         * without turning a perfectly valid move into invalid_state.
         */
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (phase != Phase.READY
                && phase != Phase.SCANNING
                && phase != Phase.FOLLOWING) {
            MinecraftAiCompanion.LOGGER.warn(
                    "MoveTo tick reached an inactive phase phase={} "
                            + "target={} gridGoal={} gameTick={} "+
                            "lastObservationRevision={} lastPlannedRevision={}",
                    phase,
                    parameters.target(),
                    parameters.gridGoal(),
                    context.gameTick(),
                    lastObservationRevision,
                    lastPlannedRevision
            );
            return SkillTickResult.failed("move_to.invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return failCurrent("move_to.internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            MoveToParameters parameters
    ) {
        long routeRevision = route == null ? -1 : route.snapshotRevision();
        final Optional<CoreSkillFrame> current;
        try {
            current = frames.current()
                    .filter(frame ->
                            expectedPlayerId.equals(frame.playerId())
                    );
        } catch (RuntimeException unavailable) {
            return new SkillCheckpoint(
                    1,
                    String.format(
                            Locale.ROOT,
                            "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.6f,\"y\":%.6f,\"z\":%.6f,"
                                + "\"arrivalRadius\":%.3f,\"step\":%d,"
                                + "\"routeRevision\":%d,"
                                + "\"frameAvailable\":false,"
                                + "\"stuckRecoveries\":%d,"
                                + "\"planningTimeBudgetExhaustions\":%d}",
                            phase.name(),
                            parameters.dimension().id(),
                            parameters.x(),
                            parameters.y(),
                            parameters.z(),
                            parameters.arrivalRadius(),
                            stepIndex,
                            routeRevision,
                            stuckRecoveries,
                            consecutiveTimeBudgetExhaustions
                    )
            );
        }
        final double distance = current
                .map(frame ->
                        frame.position()
                            .subtract(parameters.target())
                            .length()
                )
                .orElse(-1.0);
        final long motionAge = current
                .filter(ignored -> motionWindowStartedTick >= 0L)
                .map(frame ->
                        Math.max(
                                0L,
                                frame.gameTime()
                                    - motionWindowStartedTick
                        )
                )
                .orElse(-1L);
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.6f,\"y\":%.6f,\"z\":%.6f,"
                                + "\"arrivalRadius\":%.3f,\"step\":%d,"
                                + "\"routeRevision\":%d,"
                                + "\"frameAvailable\":%s,"
                                + "\"distance\":%.6f,"
                                + "\"motionWindowAge\":%d,"
                                + "\"stuckRecoveries\":%d,"
                                + "\"planningTimeBudgetExhaustions\":%d,"
                                + "\"transientBlocked\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        parameters.arrivalRadius(),
                        stepIndex,
                        routeRevision,
                        current.isPresent(),
                        distance,
                        motionAge,
                        stuckRecoveries,
                        consecutiveTimeBudgetExhaustions,
                        transientBlocked.size()
                )
        );
    }

    @Override
    public void cancel(SkillContext context, MoveToParameters parameters) {
        frames.current().ifPresent(frame -> CoreSkillSafety.quiesce(actuator, frame));
        phase = Phase.CANCELLED;
        clearRoute();
    }

    @Override
    public SkillResult result(
            SkillContext context,
            MoveToParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(SkillFailure.of("move_to.invalid_state"));
        };
    }

    private SkillTickResult tickSafely(
            SkillContext context,
            MoveToParameters parameters
    ) {
        FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(
                    validation.frame(),
                    validation.failure().orElseThrow().code()
            );
        }
        CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.navigation().revision() < lastPlannedRevision
                || frame.observationRevision() < lastObservationRevision) {
            return fail(Optional.of(frame), "move_to.stale_observation");
        }
        if (hardcoreRiskRejected(context, parameters, frame)) {
            return fail(Optional.of(frame), "move_to.hardcore_danger");
        }
        if (frame.navigation().revision() != avoidanceRevision) {
            avoidanceRevision = frame.navigation().revision();
        }

        double distance = frame.position().subtract(parameters.target()).length();
        boolean observedProgress =
                frame.observationRevision() > lastObservationRevision
                        && distance + 0.05 < lastDistance;
        lastObservationRevision = frame.observationRevision();
        lastDistance = distance;
        if (distance <= parameters.arrivalRadius()) {
            if (!CoreSkillSafety.quiesce(actuator, frame)) {
                return fail(Optional.of(frame), "move_to.actuator_rejected");
            }
            phase = Phase.COMPLETED;
            clearRoute();
            return SkillTickResult.completed();
        }

        final Optional<GridPos> waterExit = observedWaterExit(frame);
        if (frame.inWater() && waterExit.isPresent()) {
            return swimTowardLanding(
                    frame,
                    waterExit.orElseThrow(),
                    observedProgress
            );
        }

        if (route != null) {
            SkillTickResult routeTick = followRoute(
                    context,
                    parameters,
                    frame,
                    observedProgress
            );
            if (routeTick != null) {
                return routeTick;
            }
        }

        if (phase == Phase.FOLLOWING) {
            /*
             * An empty route can mean that the body already occupies the
             * target grid cell and is performing exact point docking. Keep
             * renewing that ordinary low-speed input on every server tick.
             * Falling through to the unchanged-navigation scan branch after
             * one frame produced a move-stop-turn loop: the player advanced
             * only once per semantic revision and eventually exhausted scan
             * turns while less than half a block from the goal.
             */
            if (frame.feet().equals(parameters.gridGoal())) {
                return moveWithinObservedCell(
                        context,
                        parameters,
                        frame,
                        observedProgress
                );
            }
            /*
             * Inertia or a collision may carry the player out of the target
             * cell. Do not extend same-cell authority across that boundary;
             * resume normal observed-route planning.
             */
            clearRoute();
            phase = Phase.READY;
            lastPlannedRevision = -1L;
        }

        if (frame.navigation().revision() == lastPlannedRevision) {
            return scan(
                    context,
                    parameters,
                    frame,
                    observedProgress
            );
        }
        return planAndStart(context, parameters, frame, observedProgress);
    }

    private SkillTickResult planAndStart(
            SkillContext context,
            MoveToParameters parameters,
            CoreSkillFrame frame,
            boolean observedProgress
    ) {
        LocalNavSnapshot snapshot = frame.navigation();
        GridPos start = frame.feet();
        visitedFrontiers.add(start);
        /*
         * A first-person ray fan cannot normally prove feet clearance, head
         * clearance and a sturdy top for every corridor cell in one semantic
         * frame. Fuse only recent, safety-grade visual evidence before
         * planning. Unknown cells remain unknown, a newer conflicting sample
         * has already replaced the old voxel in PerceptionNavMapper, and the
         * live dependency checks below still stop on change or expiry.
         */
        LocalNavSnapshot planningSnapshot = withTransientAvoidance(
                cachedPlanningMemory(snapshot, start)
        );
        LocalPlannerOptions options = new LocalPlannerOptions(
                context.hardcore()
                        ? NavigationRiskProfile.HARDCORE
                        : NavigationRiskProfile.NORMAL,
                policy.planningBudget(),
                context.hardcore() ? 1 : 3,
                true,
                false
        );
        LocalRoute planned = planner.planWithinRadius(
                planningSnapshot,
                start,
                parameters.target(),
                parameters.arrivalRadius(),
                options,
                frame.onGround()
        );
        boolean plannedFrontier = false;
        lastPlannedRevision = snapshot.revision();
        if (!planned.found()) {
            if (planned.status()
                    == LocalRouteStatus.TIME_BUDGET_EXCEEDED) {
                return deferAfterPlanningTimeBudget(
                        frame,
                        observedProgress
                );
            }
            if (planned.status()
                    == LocalRouteStatus.NODE_BUDGET_EXCEEDED) {
                return fail(
                        Optional.of(frame),
                        "move_to.planning_budget_exceeded"
                );
            }
            /*
             * The final arrival region may be outside the currently proven
             * first-person corridor. Advance one fail-closed observed step,
             * then replan from the newly revealed view just as a human does.
             */
            planned = planner.planTowardObserved(
                    planningSnapshot,
                    start,
                    parameters.target(),
                    options,
                    frame.onGround()
            );
            if (!planned.found()) {
                if (planned.status()
                        == LocalRouteStatus.TIME_BUDGET_EXCEEDED) {
                    return deferAfterPlanningTimeBudget(
                            frame,
                            observedProgress
                    );
                }
                if (planned.status()
                        == LocalRouteStatus.NODE_BUDGET_EXCEEDED) {
                    return fail(
                            Optional.of(frame),
                            "move_to.planning_budget_exceeded"
                    );
                }
                consecutiveTimeBudgetExhaustions = 0;
                clearRoute();
                phase = Phase.SCANNING;
                return scan(
                        context,
                        parameters,
                        frame,
                        observedProgress
                );
            }
            plannedFrontier = true;
            if (visitedFrontiers.contains(planned.reached())) {
                /*
                 * A one-step observed-frontier plan may move sideways to
                 * begin a detour around a visible obstruction. It must never
                 * revisit a frontier already reached by this MoveTo
                 * invocation: production traces showed that doing so walks a
                 * closed ring around an unobserved goal forever while the
                 * displacement watchdog incorrectly sees continuous
                 * progress. Stop and gather a new first-person view instead.
                 */
                if (!CoreSkillSafety.quiesce(actuator, frame)) {
                    return fail(
                            Optional.of(frame),
                            "move_to.actuator_rejected"
                    );
                }
                clearRoute();
                phase = Phase.SCANNING;
                return scan(
                        context,
                        parameters,
                        frame,
                        observedProgress
                );
            }
        }
        consecutiveTimeBudgetExhaustions = 0;
        if (planned.steps().isEmpty()) {
            phase = Phase.FOLLOWING;
            return moveWithinObservedCell(
                    context,
                    parameters,
                    frame,
                    observedProgress
            );
        }
        if (context.hardcore() && planned.steps().stream().anyMatch(
                step -> isHardcoreUnsafe(step, snapshot)
        )) {
            return fail(Optional.of(frame), "move_to.hardcore_unsafe_route");
        }
        route = planned;
        frontierRoute = plannedFrontier;
        // Dependency checks compare against the actual fair snapshot. The
        // transient avoidance overlay only removes a repeatedly failed local
        // step from this planning attempt.
        plannedSnapshot = snapshot;
        stepIndex = 0;
        phase = Phase.FOLLOWING;
        resetStepState();
        SkillTickResult first = followRoute(
                context,
                parameters,
                frame,
                true
        );
        return first == null
                ? SkillTickResult.running(true, true)
                : first;
    }

    private SkillTickResult deferAfterPlanningTimeBudget(
            final CoreSkillFrame frame,
            final boolean observedProgress
    ) {
        consecutiveTimeBudgetExhaustions++;
        if (consecutiveTimeBudgetExhaustions
                > MAXIMUM_CONSECUTIVE_TIME_BUDGET_EXHAUSTIONS) {
            return fail(
                    Optional.of(frame),
                    "move_to.planning_time_budget_exceeded"
            );
        }
        /*
         * First-use JIT compilation or an operating-system scheduling pause
         * can consume the strict per-tick planning budget. Keep the 2 ms
         * ceiling and clear input, then retry on a later tick; a bounded
         * sequence still terminates instead of hiding a genuinely
         * under-budget route.
         */
        if (!CoreSkillSafety.quiesce(actuator, frame)) {
            return fail(
                    Optional.of(frame),
                    "move_to.actuator_rejected"
            );
        }
        clearRoute();
        phase = Phase.READY;
        lastPlannedRevision = -1L;
        return SkillTickResult.running(observedProgress, true);
    }

    /**
     * Returns null only when this route was invalidated and a fresh plan may
     * be attempted by the caller on the next tick.
     */
    private SkillTickResult followRoute(
            SkillContext context,
            MoveToParameters parameters,
            CoreSkillFrame frame,
            boolean observedProgress
    ) {
        if (route == null || plannedSnapshot == null) {
            return null;
        }
        if (stepIndex >= route.steps().size()) {
            clearRoute();
            phase = Phase.READY;
            return SkillTickResult.running(true, true);
        }
        LocalStep step = route.steps().get(stepIndex);
        GridPos feet = frame.feet();
        if (feet.equals(step.to())) {
            actuator.stop();
            stepIndex++;
            resetStepState();
            if (stepIndex >= route.steps().size()) {
                final boolean completedFrontier = frontierRoute;
                if (completedFrontier) {
                    visitedFrontiers.add(feet);
                }
                clearRoute();
                if (!completedFrontier
                        && frame.position()
                        .subtract(parameters.target())
                        .length() > parameters.arrivalRadius()) {
                    /*
                     * Crossing the final grid boundary is not precise
                     * arrival. Continue toward the exact point immediately
                     * with the low-speed docking controller; otherwise
                     * vanilla inertia can carry the body through the cell
                     * and cause an adjacent-cell replan orbit.
                     */
                    phase = Phase.FOLLOWING;
                    return moveWithinObservedCell(
                            context,
                            parameters,
                            frame,
                            observedProgress
                    );
                }
                phase = Phase.READY;
                if (completedFrontier) {
                    /*
                     * A frontier endpoint is only an observation station, not
                     * authority to walk directly at a remote exact point.
                     * Wait for a newer fair map; with the same revision the
                     * normal scan branch aims at the target corridor.
                     */
                    lastPlannedRevision =
                            frame.navigation().revision();
                }
            }
            return SkillTickResult.running(true, true);
        }
        if (!feet.equals(step.from())) {
            actuator.stop();
            clearRoute();
            phase = Phase.READY;
            lastPlannedRevision = -1;
            return SkillTickResult.running(true, true);
        }
        if (dependenciesChanged(
                step.observedDependencies(),
                plannedSnapshot,
                frame.navigation()
        )) {
            actuator.stop();
            clearRoute();
            phase = Phase.READY;
            lastPlannedRevision = -1;
            return SkillTickResult.running(true, true);
        }
        if (!isStructurallySafe(step, frame.navigation())) {
            actuator.stop();
            clearRoute();
            phase = Phase.READY;
            lastPlannedRevision = -1;
            return SkillTickResult.running(true, true);
        }
        if (context.hardcore()
                && isHardcoreUnsafe(step, frame.navigation())) {
            return fail(Optional.of(frame), "move_to.hardcore_unsafe_step");
        }

        PerceptionVec3 stepTarget = new PerceptionVec3(
                step.to().x() + 0.5,
                frame.eyePosition().y(),
                step.to().z() + 0.5
        );
        boolean finalStep = stepIndex == route.steps().size() - 1
                && precisionDocking(parameters)
                && !frontierRoute;
        return switch (step.primitive()) {
            case WALK -> aimAndMove(
                    frame,
                    stepTarget,
                    false,
                    false,
                    observedProgress,
                    finalStep
            );
            case SPRINT -> aimAndMove(
                    frame,
                    stepTarget,
                    true,
                    false,
                    observedProgress,
                    finalStep
            );
            case JUMP -> aimAndMove(
                    frame,
                    stepTarget,
                    false,
                    step.to().y() > step.from().y(),
                    observedProgress,
                    false,
                    step.to()
            );
            case SWIM -> swimRouteStep(
                    frame,
                    stepTarget,
                    step.to().y() > step.from().y(),
                    observedProgress,
                    step.to()
            );
            case OPEN_DOOR -> executeDoor(frame, step, observedProgress);
            case CLIMB -> fail(Optional.of(frame), "move_to.unsupported_climb");
            case BRIDGE, PILLAR -> fail(
                    Optional.of(frame),
                    "move_to.unsupported_build"
            );
        };
    }

    private SkillTickResult aimAndMove(
            CoreSkillFrame frame,
            PerceptionVec3 target,
            boolean sprint,
            boolean shouldJump,
            boolean observedProgress,
            boolean precision
    ) {
        return aimAndMove(
                frame,
                target,
                sprint,
                shouldJump,
                observedProgress,
                precision,
                null,
                precision || sneakDuringMovement
        );
    }

    private SkillTickResult aimAndMove(
            CoreSkillFrame frame,
            PerceptionVec3 target,
            boolean sprint,
            boolean shouldJump,
            boolean observedProgress,
            boolean precision,
            GridPos intendedLanding
    ) {
        return aimAndMove(
                frame,
                target,
                sprint,
                shouldJump,
                observedProgress,
                precision,
                intendedLanding,
                precision || sneakDuringMovement
        );
    }

    private SkillTickResult aimAndMove(
            CoreSkillFrame frame,
            PerceptionVec3 target,
            boolean sprint,
            boolean shouldJump,
            boolean observedProgress,
            boolean precision,
            GridPos intendedLanding,
            boolean sneak
    ) {
        PerceptionVec3 delta = target.subtract(frame.eyePosition());
        double horizontalDistance = Math.hypot(delta.x(), delta.z());
        if (horizontalDistance <= 1.0E-6) {
            actuator.stop();
            return SkillTickResult.running(observedProgress, true);
        }
        LookIntent look = CoreSkillGeometry.lookAt(frame.eyePosition(), target);
        ActionOutcome lookOutcome = actuator.look(look);
        double error = CoreSkillGeometry.horizontalAngularErrorDegrees(
                frame.lookDirection(),
                delta
        );
        if (!lookOutcome.accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        double permittedAlignment = precision
                ? Math.min(
                        policy.movementAlignmentDegrees(),
                        PRECISION_ALIGNMENT_DEGREES
                )
                : policy.movementAlignmentDegrees();
        if (error > permittedAlignment) {
            if (!actuator.stop().accepted()) {
                return fail(Optional.of(frame), "move_to.actuator_rejected");
            }
            return turnAlignmentProgress(
                    frame,
                    observedProgress,
                    error
            );
        }
        resetTurnAlignment();

        double forward = precision
                ? Math.max(
                        PRECISION_MINIMUM_FORWARD,
                        Math.min(
                                PRECISION_MAXIMUM_FORWARD,
                                horizontalDistance
                                        * PRECISION_DISTANCE_GAIN
                        )
                )
                : 1.0;
        ActionOutcome movement = actuator.move(
                new MovementIntent(
                        forward,
                        0.0,
                        sprint && !precision,
                        sneak
                )
        );
        if (!movement.accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        boolean issuedJump = false;
        if (shouldJump
                && !jumpIssued
                && !agriculturalJumpForbidden(
                        frame,
                        target,
                        intendedLanding
                )) {
            ActionOutcome jump = actuator.jump();
            if (!jump.accepted()) {
                return fail(Optional.of(frame), "move_to.jump_rejected");
            }
            jumpIssued = true;
            issuedJump = true;
        }
        return movementProgress(
                frame,
                observedProgress || issuedJump
        );
    }

    private SkillTickResult swimRouteStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 target,
            final boolean ascend,
            final boolean observedProgress,
            final GridPos intendedLanding
    ) {
        /* Swimming upward is a held input, not a one-shot ground jump. */
        if (ascend) {
            jumpIssued = false;
        }
        return aimAndMove(
                frame,
                target,
                false,
                ascend,
                observedProgress,
                false,
                intendedLanding,
                false
        );
    }

    private SkillTickResult swimTowardLanding(
            final CoreSkillFrame frame,
            final GridPos landingFeet,
            final boolean observedProgress
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                landingFeet.x() + 0.5,
                frame.eyePosition().y(),
                landingFeet.z() + 0.5
        );
        final LookIntent look = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                target
        );
        final ActionOutcome looked = actuator.look(look);
        final double error = CoreSkillGeometry
                .horizontalAngularErrorDegrees(
                        frame.lookDirection(),
                        target.subtract(frame.eyePosition())
                );
        if (!looked.accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        if (error > policy.movementAlignmentDegrees()) {
            if (!actuator.stop().accepted()) {
                return fail(
                        Optional.of(frame),
                        "move_to.actuator_rejected"
                );
            }
            return turnAlignmentProgress(frame, observedProgress, error);
        }
        resetTurnAlignment();
        final ActionOutcome moved = actuator.move(
                new MovementIntent(0.60, 0.0, false, false)
        );
        final ActionOutcome jumped = agriculturalJumpForbidden(
                frame,
                target,
                landingFeet
        ) ? ActionOutcome.DISPATCHED : actuator.jump();
        if (!moved.accepted() || !jumped.accepted()) {
            return fail(Optional.of(frame), "move_to.swim_rejected");
        }
        return movementProgress(frame, true);
    }

    /**
     * Ordinary movement must not use a jump as a shortcut across a visible
     * farm.  Vanilla turns farmland into dirt when an entity lands on it; the
     * crop/soil face is already part of the companion's first-person sample,
     * so suppressing only this optional jump is a fair local safety decision.
     */
    private static boolean observedAgriculturalSurface(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream().anyMatch(face -> {
            final String block = face.blockTypeId();
            return "minecraft:farmland".equals(block)
                    || "minecraft:wheat".equals(block)
                    || "minecraft:carrots".equals(block)
                    || "minecraft:potatoes".equals(block)
                    || "minecraft:beetroots".equals(block)
                    || "minecraft:nether_wart".equals(block);
        });
    }

    /**
     * Farm-field movement may not jump across a crop row.  The one exception
     * is an observed water escape whose landing support is fresh, solid and
     * visibly not farmland; without that exception a field at an irrigation
     * edge can strand the player in water forever.  No level lookup is used.
     */
    private boolean agriculturalJumpForbidden(
            final CoreSkillFrame frame,
            final PerceptionVec3 target,
            final GridPos intendedLanding
    ) {
        if (!avoidAgriculturalJumps) {
            return observedAgriculturalSurface(frame);
        }
        if (!frame.inWater()) {
            return true;
        }
        final GridPos destination = new GridPos(
                intendedLanding == null
                        ? (int) Math.floor(target.x())
                        : intendedLanding.x(),
                intendedLanding == null
                        ? (int) Math.ceil(frame.position().y() - 1.0E-6)
                        : intendedLanding.y(),
                intendedLanding == null
                        ? (int) Math.floor(target.z())
                        : intendedLanding.z()
        );
        final long revision = frame.navigation().revision();
        final Optional<ObservedVoxel> support = frame.navigation().voxelAt(
                destination.below()
        );
        if (support.isEmpty()
                || !NavigationEvidence.isFreshStandingSupport(
                        support.orElseThrow(),
                        revision
                )
                || support.orElseThrow().effectiveDanger() > 0.20) {
            return true;
        }
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                (face.block().x() == destination.x()
                        && face.block().y() == destination.y()
                        && face.block().z() == destination.z()
                        && isAgriculturalBlock(face.blockTypeId()))
                || (face.block().x() == destination.x()
                        && face.block().y() == destination.y() - 1
                        && face.block().z() == destination.z()
                        && isAgriculturalBlock(face.blockTypeId()))
        );
    }

    private static boolean isAgriculturalBlock(final String blockTypeId) {
        return "minecraft:farmland".equals(blockTypeId)
                || "minecraft:wheat".equals(blockTypeId)
                || "minecraft:carrots".equals(blockTypeId)
                || "minecraft:potatoes".equals(blockTypeId)
                || "minecraft:beetroots".equals(blockTypeId)
                || "minecraft:nether_wart".equals(blockTypeId);
    }

    private static Optional<GridPos> observedWaterExit(
            final CoreSkillFrame frame
    ) {
        final LocalNavSnapshot navigation = frame.navigation();
        final long revision = navigation.revision();
        final GridPos body = frame.feet();
        return navigation.observedVoxels().values().stream()
                .filter(support ->
                        NavigationEvidence.isFreshStandingSupport(
                                support,
                                revision
                        ))
                .map(ObservedVoxel::position)
                .filter(support -> {
                    final GridPos feet = support.above();
                    final Optional<ObservedVoxel> feetVoxel =
                            navigation.voxelAt(feet);
                    final Optional<ObservedVoxel> headVoxel =
                            navigation.voxelAt(feet.above());
                    return feetVoxel.isPresent()
                            && headVoxel.isPresent()
                            && NavigationEvidence
                                    .hasFreshTraversalClearance(
                                            feetVoxel.orElseThrow(),
                                            revision
                                    )
                            && NavigationEvidence
                                    .hasFreshTraversalClearance(
                                            headVoxel.orElseThrow(),
                                            revision
                                    )
                            && Math.abs(feet.y() - body.y()) <= 2
                            && Math.hypot(
                                    feet.x() - body.x(),
                                    feet.z() - body.z()
                            ) <= 3.0;
                })
                .map(GridPos::above)
                .min(Comparator
                        .comparingDouble(body::euclideanDistance)
                        .thenComparing(GridPos::compareTo));
    }

    private SkillTickResult executeDoor(
            CoreSkillFrame frame,
            LocalStep step,
            boolean observedProgress
    ) {
        PerceptionVec3 advanceTarget = new PerceptionVec3(
                step.to().x() + 0.5,
                frame.eyePosition().y(),
                step.to().z() + 0.5
        );
        if (doorInteractionIssued) {
            doorAdvanceTicks++;
            if (doorAdvanceTicks > MAXIMUM_DOOR_ADVANCE_TICKS) {
                actuator.stop();
                clearRoute();
                phase = Phase.SCANNING;
                lastPlannedRevision = frame.navigation().revision();
                return SkillTickResult.running(true, true);
            }
            return aimAndMove(
                    frame,
                    advanceTarget,
                    false,
                    false,
                    observedProgress,
                    false
            );
        }

        Optional<BlockInteractionTarget> target = visibleDoorTarget(frame, step.to());
        if (target.isEmpty()) {
            actuator.stop();
            clearRoute();
            phase = Phase.SCANNING;
            lastPlannedRevision = frame.navigation().revision();
            return SkillTickResult.running(true, true);
        }
        BlockInteractionTarget door = target.orElseThrow();
        PerceptionVec3 hit = new PerceptionVec3(
                door.hitPoint().x(),
                door.hitPoint().y(),
                door.hitPoint().z()
        );
        PerceptionVec3 delta = hit.subtract(frame.eyePosition());
        ActionOutcome look = actuator.look(
                CoreSkillGeometry.lookAt(frame.eyePosition(), hit)
        );
        if (!look.accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        if (CoreSkillGeometry.angularErrorDegrees(
                frame.lookDirection(),
                delta
        ) > policy.interactionAlignmentDegrees()) {
            actuator.stop();
            return SkillTickResult.running(observedProgress, true);
        }
        actuator.stop();
        ActionOutcome interaction = actuator.useMainHandOn(door);
        if (!interaction.accepted()) {
            clearRoute();
            phase = Phase.SCANNING;
            lastPlannedRevision = frame.navigation().revision();
            return SkillTickResult.running(true, true);
        }
        doorInteractionIssued = true;
        doorAdvanceTicks = 0;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult moveWithinObservedCell(
            SkillContext context,
            MoveToParameters parameters,
            CoreSkillFrame frame,
            boolean observedProgress
    ) {
        GridPos feet = frame.feet();
        if (!frame.navigation().isObserved(feet)
                || !frame.navigation().isObserved(feet.above())) {
            phase = Phase.SCANNING;
            return scan(
                    context,
                    parameters,
                    frame,
                    observedProgress
            );
        }
        if (Math.abs(parameters.y() - frame.position().y()) > 0.75) {
            return fail(Optional.of(frame), "move_to.unsupported_micro_vertical");
        }
        PerceptionVec3 target = new PerceptionVec3(
                parameters.x(),
                frame.eyePosition().y(),
                parameters.z()
        );
        return aimAndMove(
                frame,
                target,
                false,
                false,
                observedProgress,
                true
        );
    }

    private static boolean precisionDocking(
            final MoveToParameters parameters
    ) {
        return parameters.arrivalRadius()
                <= PRECISION_ARRIVAL_RADIUS + 1.0E-9;
    }

    private boolean hardcoreRiskRejected(
            final SkillContext context,
            final MoveToParameters parameters,
            final CoreSkillFrame frame
    ) {
        if (!context.hardcore()
                || context.riskScore()
                    <= policy.hardcoreMaximumDanger()
                && frame.danger()
                    <= policy.hardcoreMaximumDanger()) {
            return false;
        }
        try {
            return !hardcoreRiskAuthorization.allows(
                    context,
                    frame,
                    parameters
            );
        } catch (RuntimeException invalidAuthorization) {
            return true;
        }
    }

    private SkillTickResult scan(
            SkillContext context,
            MoveToParameters parameters,
            CoreSkillFrame frame,
            boolean observedProgress
    ) {
        if (!actuator.stop().accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        phase = Phase.SCANNING;
        if (context.gameTick() < nextScanGameTick) {
            return SkillTickResult.running(observedProgress, true);
        }
        if (scanTurns >= policy.maximumScanTurns()) {
            return fail(Optional.of(frame), "move_to.route_unknown");
        }
        ActionOutcome look = actuator.look(
                CoreSkillGeometry.navigationScanTarget(
                        frame,
                        parameters.target(),
                        NAVIGATION_SCAN_YAW_OFFSETS[
                                scanTurns
                                    % NAVIGATION_SCAN_YAW_OFFSETS.length
                        ]
                )
        );
        if (!look.accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        scanTurns++;
        nextScanGameTick = context.gameTick() + policy.scanTurnIntervalTicks();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult failCurrent(String code) {
        Optional<CoreSkillFrame> frame;
        try {
            frame = frames.current();
        } catch (RuntimeException ignored) {
            frame = Optional.empty();
        }
        return fail(frame, code);
    }

    private SkillTickResult fail(
            Optional<CoreSkillFrame> frame,
            String code
    ) {
        frame.ifPresent(value -> CoreSkillSafety.quiesce(actuator, value));
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        clearRoute();
        return SkillTickResult.failed(failure);
    }

    private SkillTickResult movementProgress(
            CoreSkillFrame frame,
            boolean observedProgress
    ) {
        if (motionWindowPosition == null
                || motionWindowStartedTick < 0) {
            resetMotionWindow(frame);
            return SkillTickResult.running(observedProgress, true);
        }
        if (frame.position().subtract(motionWindowPosition).lengthSquared()
                >= STUCK_PROGRESS_DISTANCE_SQUARED) {
            transientBlocked.clear();
            stuckRecoveries = 0;
            resetMotionWindow(frame);
            return SkillTickResult.running(true, true);
        }
        if (frame.gameTime() - motionWindowStartedTick
                < STUCK_WINDOW_TICKS) {
            return SkillTickResult.running(observedProgress, true);
        }

        GridPos blocked = null;
        if (route != null && stepIndex < route.steps().size()) {
            blocked = route.steps().get(stepIndex).to();
        }
        if (blocked != null && !blocked.equals(frame.feet())) {
            transientBlocked.add(blocked);
        }
        if (!actuator.stop().accepted()) {
            return fail(Optional.of(frame), "move_to.actuator_rejected");
        }
        stuckRecoveries++;
        if (stuckRecoveries > MAXIMUM_STUCK_RECOVERIES) {
            return fail(Optional.of(frame), "move_to.stuck");
        }
        clearRoute();
        phase = Phase.READY;
        lastPlannedRevision = -1;
        resetMotionWindow(frame);
        return SkillTickResult.running(true, true);
    }

    private void resetMotionWindow(CoreSkillFrame frame) {
        motionWindowPosition = frame.position();
        motionWindowStartedTick = frame.gameTime();
    }

    private SkillTickResult turnAlignmentProgress(
            final CoreSkillFrame frame,
            final boolean observedProgress,
            final double error
    ) {
        if (turnAlignmentStartedTick < 0) {
            turnAlignmentStartedTick = frame.gameTime();
            turnAlignmentProgressTick = frame.gameTime();
            bestTurnAlignmentError = error;
            return SkillTickResult.running(
                    observedProgress,
                    true
            );
        }
        if (error + TURN_ALIGNMENT_PROGRESS_DEGREES
                < bestTurnAlignmentError) {
            bestTurnAlignmentError = error;
            turnAlignmentProgressTick = frame.gameTime();
        }
        final boolean stalled =
                frame.gameTime() - turnAlignmentProgressTick
                        >= TURN_ALIGNMENT_STALL_TICKS;
        final boolean expired =
                frame.gameTime() - turnAlignmentStartedTick
                        >= TURN_ALIGNMENT_MAXIMUM_TICKS;
        if (!stalled && !expired) {
            return SkillTickResult.running(
                    observedProgress,
                    true
            );
        }

        stuckRecoveries++;
        if (stuckRecoveries > MAXIMUM_STUCK_RECOVERIES) {
            return fail(
                    Optional.of(frame),
                    "move_to.turn_stuck"
            );
        }
        clearRoute();
        phase = Phase.READY;
        lastPlannedRevision = -1;
        return SkillTickResult.running(true, true);
    }

    private void resetTurnAlignment() {
        turnAlignmentStartedTick = -1;
        turnAlignmentProgressTick = -1;
        bestTurnAlignmentError = Double.POSITIVE_INFINITY;
    }

    private LocalNavSnapshot withTransientAvoidance(
            LocalNavSnapshot snapshot
    ) {
        if (transientBlocked.isEmpty()) {
            return snapshot;
        }
        List<ObservedVoxel> voxels = new ArrayList<>(
                snapshot.observedVoxels().size()
        );
        for (ObservedVoxel voxel : snapshot.observedVoxels().values()) {
            if (transientBlocked.contains(voxel.position())) {
                voxels.add(new ObservedVoxel(
                        voxel.position(),
                        VoxelKind.SOLID,
                        voxel.danger(),
                        voxel.observationRevision()
                ));
            } else {
                voxels.add(voxel);
            }
        }
        return new LocalNavSnapshot(
                snapshot.dimension(),
                snapshot.revision(),
                voxels
        );
    }

    private static LocalNavSnapshot withPlanningMemory(
            final LocalNavSnapshot snapshot,
            final GridPos start
    ) {
        final List<ObservedVoxel> fused = new ArrayList<>(
                snapshot.observedVoxels().size()
        );
        boolean changed = false;
        for (ObservedVoxel voxel : snapshot.observedVoxels().values()) {
            final long age = snapshot.revision()
                    - voxel.observationRevision();
            final boolean boundedBodyContactMemory = age > 0
                    && age <= MAXIMUM_BODY_CONTACT_SUPPORT_MEMORY_REVISIONS
                    && voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT
                    && voxel.kind().supportsWeight();
            if (age > 0
                    && (age <= MAXIMUM_ROUTE_MEMORY_AGE_REVISIONS
                        && safetyGradePlanningMemory(voxel, start)
                        || boundedBodyContactMemory)) {
                fused.add(new ObservedVoxel(
                        voxel.position(),
                        voxel.kind(),
                        voxel.danger(),
                        snapshot.revision(),
                        voxel.occupancyEvidence(),
                        voxel.topSupportAffordance()
                ));
                changed = true;
            } else {
                fused.add(voxel);
            }
        }
        return changed
                ? new LocalNavSnapshot(
                    snapshot.dimension(),
                    snapshot.revision(),
                    fused
                )
                : snapshot;
    }

    /**
     * A navigation snapshot is immutable. Reusing its bounded evidence
     * fusion for planning retries avoids rebuilding and re-indexing thousands
     * of unchanged observed voxels on every 20 TPS tick. The cache is scoped
     * to the exact source object and current feet cell; a new observation or
     * physical cell immediately rebuilds it.
     */
    private LocalNavSnapshot cachedPlanningMemory(
            final LocalNavSnapshot snapshot,
            final GridPos start
    ) {
        if (planningMemorySource == snapshot
                && start.equals(planningMemoryStart)
                && planningMemorySnapshot != null) {
            return planningMemorySnapshot;
        }
        final LocalNavSnapshot fused = withPlanningMemory(snapshot, start);
        planningMemorySource = snapshot;
        planningMemoryStart = start;
        planningMemorySnapshot = fused;
        return fused;
    }

    private static boolean safetyGradePlanningMemory(
            final ObservedVoxel voxel,
            final GridPos start
    ) {
        if (voxel.kind() == VoxelKind.CLOSED_DOOR) {
            /*
             * Doors can change without altering their block identity. They
             * must be observed in the current frame immediately before use.
             */
            return false;
        }
        if (NavigationEvidence.hasTraversalClearance(voxel)) {
            return true;
        }
        if (!voxel.kind().supportsWeight()) {
            return false;
        }
        if (voxel.position().equals(start.below())
                && voxel.occupancyEvidence()
                    == OccupancyEvidence.BODY_CONTACT) {
            return true;
        }
        return voxel.occupancyEvidence()
                    == OccupancyEvidence.SURFACE_HIT
                && voxel.topSupportAffordance()
                    == TopSupportAffordance.STURDY_FULL_TOP;
    }

    private FrameValidation validateFrame(MoveToParameters parameters) {
        Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed("move_to.observation_unavailable");
        }
        CoreSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(frame, "move_to.player_mismatch");
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(frame, "move_to.dimension_mismatch");
        }
        return FrameValidation.valid(frame);
    }

    private static boolean dependenciesChanged(
            Set<GridPos> dependencies,
            LocalNavSnapshot planned,
            LocalNavSnapshot current
    ) {
        if (!planned.dimension().equals(current.dimension())
                || current.revision() < planned.revision()) {
            return true;
        }
        for (GridPos dependency : dependencies) {
            Optional<ObservedVoxel> before = planned.voxelAt(dependency);
            Optional<ObservedVoxel> now = current.voxelAt(dependency);
            if (before.isEmpty()
                    || now.isEmpty()) {
                return true;
            }
            ObservedVoxel previous = before.orElseThrow();
            ObservedVoxel latest = now.orElseThrow();
            if (!evidenceWithinRouteMemory(latest, current)
                    || latest.observationRevision()
                            < previous.observationRevision()) {
                return true;
            }
            /*
             * Absence from the latest view is not a world change. Preserve
             * bounded first-person memory until the dependency is seen again
             * or expires. A newer observation must remain semantically and
             * evidentially identical to the planned route.
             */
            if (latest.observationRevision()
                    == previous.observationRevision()) {
                continue;
            }
            if (previous.kind() != latest.kind()
                    || previous.occupancyEvidence()
                            != latest.occupancyEvidence()
                    || previous.topSupportAffordance()
                            != latest.topSupportAffordance()
                    || Math.abs(
                            previous.effectiveDanger()
                                    - latest.effectiveDanger()
                    ) > DANGER_EPSILON) {
                return true;
            }
        }
        return false;
    }

    /**
     * Revalidates the newest available first-person evidence on every tick.
     * Initial planning still requires a complete current sample. During
     * execution, a dependency can use short-lived visual memory; any newly
     * seen change, evidence downgrade, danger change, or expiry stops before
     * another movement action.
     */
    private static boolean isStructurallySafe(
            LocalStep step,
            LocalNavSnapshot snapshot
    ) {
        Optional<ObservedVoxel> destination =
                snapshot.voxelAt(step.to());
        if (destination.isEmpty()) {
            return false;
        }
        ObservedVoxel body = destination.orElseThrow();
        if (step.primitive() == MovementPrimitive.OPEN_DOOR) {
            return NavigationEvidence.isFreshClosedDoor(
                        body,
                        snapshot.revision()
                    )
                    && hasRememberedTraversalClearance(
                        snapshot,
                        step.to().above()
                    )
                    && hasRememberedSupport(
                        snapshot,
                        step.to().below()
                    );
        }
        if (step.primitive() == MovementPrimitive.BRIDGE
                || step.primitive() == MovementPrimitive.PILLAR) {
            return false;
        }
        if (!hasRememberedTraversalClearance(body, snapshot)) {
            return false;
        }
        if (step.primitive() == MovementPrimitive.CLIMB) {
            return step.to().y() <= step.from().y()
                    || hasRememberedTraversalClearance(
                        snapshot,
                        step.to().above()
                    );
        }
        if (!hasRememberedTraversalClearance(
                snapshot,
                step.to().above()
        )) {
            return false;
        }
        if (step.primitive() == MovementPrimitive.SWIM
                && body.kind().isLiquid()) {
            return true;
        }
        return hasRememberedSupport(snapshot, step.to().below());
    }

    private static boolean hasRememberedTraversalClearance(
            LocalNavSnapshot snapshot,
            GridPos position
    ) {
        return snapshot.voxelAt(position)
                .map(voxel -> hasRememberedTraversalClearance(
                        voxel,
                        snapshot
                ))
                .orElse(false);
    }

    private static boolean hasRememberedTraversalClearance(
            ObservedVoxel voxel,
            LocalNavSnapshot snapshot
    ) {
        return evidenceWithinRouteMemory(voxel, snapshot)
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    private static boolean hasRememberedSupport(
            LocalNavSnapshot snapshot,
            GridPos position
    ) {
        return snapshot.voxelAt(position)
                .map(voxel ->
                        evidenceWithinRouteMemory(voxel, snapshot)
                        && voxel.kind().supportsWeight()
                        && voxel.topSupportAffordance()
                                == TopSupportAffordance.STURDY_FULL_TOP
                        && (voxel.occupancyEvidence()
                                == OccupancyEvidence.SURFACE_HIT
                            || voxel.occupancyEvidence()
                                == OccupancyEvidence.BODY_CONTACT)
                )
                .orElse(false);
    }

    private static boolean evidenceWithinRouteMemory(
            ObservedVoxel voxel,
            LocalNavSnapshot snapshot
    ) {
        long age = snapshot.revision()
                - voxel.observationRevision();
        return age >= 0
                && age <= MAXIMUM_ROUTE_MEMORY_AGE_REVISIONS;
    }

    private boolean isHardcoreUnsafe(
            LocalStep step,
            LocalNavSnapshot snapshot
    ) {
        if (step.primitive() == MovementPrimitive.BRIDGE
                || step.primitive() == MovementPrimitive.PILLAR
                || step.danger() > policy.hardcoreMaximumDanger()) {
            return true;
        }
        for (GridPos dependency : step.observedDependencies()) {
            Optional<ObservedVoxel> voxel = snapshot.voxelAt(dependency);
            if (voxel.isEmpty()
                    || voxel.orElseThrow().effectiveDanger()
                    > policy.hardcoreMaximumDanger()) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BlockInteractionTarget> visibleDoorTarget(
            CoreSkillFrame frame,
            GridPos position
    ) {
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            if (visible.block().x() != position.x()
                    || visible.block().y() != position.y()
                    || visible.block().z() != position.z()
                    || !visible.blockTypeId().endsWith("_door")) {
                continue;
            }
            try {
                BlockFace face = BlockFace.valueOf(
                        visible.face().toUpperCase(Locale.ROOT)
                );
                return Optional.of(new BlockInteractionTarget(
                        position.x(),
                        position.y(),
                        position.z(),
                        face,
                        new ActionVec3(
                                visible.hitPosition().x(),
                                visible.hitPosition().y(),
                                visible.hitPosition().z()
                        )
                ));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void clearRoute() {
        route = null;
        plannedSnapshot = null;
        frontierRoute = false;
        stepIndex = 0;
        resetStepState();
    }

    private void resetStepState() {
        jumpIssued = false;
        doorInteractionIssued = false;
        doorAdvanceTicks = 0;
        resetTurnAlignment();
    }

    private enum Phase {
        IDLE,
        READY,
        SCANNING,
        FOLLOWING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Explicit compound-skill proof for one otherwise rejected aggregate
     * risk sample. This never authorizes a route step: the planner's observed
     * voxel danger checks remain mandatory.
     */
    @FunctionalInterface
    public interface HardcoreRiskAuthorization {
        boolean allows(
                SkillContext context,
                CoreSkillFrame frame,
                MoveToParameters parameters
        );

        static HardcoreRiskAuthorization none() {
            return (context, frame, parameters) -> false;
        }
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(CoreSkillFrame frame) {
            return new FrameValidation(Optional.of(frame), Optional.empty());
        }

        private static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }

        private static FrameValidation failed(CoreSkillFrame frame, String code) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
