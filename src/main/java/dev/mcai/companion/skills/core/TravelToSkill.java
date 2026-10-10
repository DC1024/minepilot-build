package dev.mcai.companion.skills.core;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Fair, rolling same-dimension travel for remote coordinates and shared
 * waypoints.
 *
 * <p>The skill repeatedly turns the player's own eyes, waits for a newer
 * semantic sample, chooses a short route whose complete dependency set was
 * observed, and delegates that route to {@link MoveToSkill}. It never reads a
 * level, chunk, path-navigation service, structure lookup, or hidden block.</p>
 */
public final class TravelToSkill implements Skill<TravelToParameters> {
    private static final long MAXIMUM_ROUTE_MEMORY_AGE_REVISIONS = 16;
    /** Bounded memory for retracing a recently stood-on support corridor. */
    private static final long MAXIMUM_BODY_CONTACT_SUPPORT_MEMORY_REVISIONS =
            128;
    private static final double PHYSICAL_PROGRESS_EPSILON = 0.10;
    private static final double MAP_PROGRESS_EPSILON = 0.25;
    private static final double POSITION_PROGRESS_DISTANCE_SQUARED = 0.01;
    private static final int MAXIMUM_REJECTED_FRONTIERS = 256;
    private static final int MAXIMUM_CONSECUTIVE_TIME_BUDGET_EXHAUSTIONS = 8;
    private static final float[] FRONTIER_SCAN_YAW_OFFSETS = {
        0.0F,
        -40.0F,
        40.0F,
        -60.0F,
        60.0F
    };
    private static final ScanOffset[] SCAN_PATTERN = {
        new ScanOffset(0.0F, 25.0F),
        new ScanOffset(-1.0F, 25.0F),
        new ScanOffset(1.0F, 25.0F),
        new ScanOffset(-2.0F, 40.0F),
        new ScanOffset(2.0F, 40.0F),
        new ScanOffset(-3.0F, 25.0F),
        new ScanOffset(3.0F, 25.0F),
        new ScanOffset(-4.0F, 40.0F),
        new ScanOffset(4.0F, 40.0F),
        new ScanOffset(-5.0F, 25.0F),
        new ScanOffset(5.0F, 25.0F),
        new ScanOffset(6.0F, 40.0F),
        new ScanOffset(0.0F, 10.0F),
        new ScanOffset(-1.0F, 10.0F),
        new ScanOffset(1.0F, 10.0F),
        new ScanOffset(-2.0F, 10.0F),
        new ScanOffset(2.0F, 10.0F),
        new ScanOffset(-3.0F, 10.0F),
        new ScanOffset(3.0F, 10.0F),
        new ScanOffset(-4.0F, 10.0F),
        new ScanOffset(4.0F, 10.0F),
        new ScanOffset(-5.0F, 10.0F),
        new ScanOffset(5.0F, 10.0F),
        new ScanOffset(6.0F, 10.0F)
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final LocalAStarPlanner localPlanner;
    private final CoreSkillPolicy corePolicy;
    private final TravelSkillPolicy travelPolicy;
    private final RollingTravelPlanner rollingPlanner;
    private final LongSupplier sessionGeneration;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastProgressTick = -1;
    private long lastFrameGameTime = -1;
    private long lastObservationRevision = -1;
    private long lastNavigationRevision = -1;
    private long requiredObservationRevision = -1;
    private long nextScanTick = -1;
    private double bestPhysicalDistance = Double.POSITIVE_INFINITY;
    private double bestKnownMapDistance = Double.POSITIVE_INFINITY;
    private PerceptionVec3 lastPhysicalPosition;
    private PerceptionVec3 courseOrigin;
    private PerceptionVec3 segmentAnchor;
    private long segmentAnchorTick = -1;
    private double segmentStartDistance = Double.POSITIVE_INFINITY;
    private int completedSegments;
    private int scansWithoutGrowth;
    private int scanPatternIndex;
    private int consecutiveTimeBudgetExhaustions;
    private int rejectedCourseSide;
    private double rejectedCourseSideReleaseDistance =
            Double.NEGATIVE_INFINITY;
    private boolean recoveringCourse;
    private boolean lastPlanDangerBlocked;
    private boolean escapingWater;
    private long waterEscapeLastProgressTick = -1;
    private PerceptionVec3 waterEscapeLastPosition;
    private MoveToSkill segment;
    private MoveToParameters segmentParameters;
    private GridPos segmentEndpoint;
    private GridPos acceptedArrival;
    private final Set<GridPos> rejectedFrontiers = new LinkedHashSet<>();

    public TravelToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LongSupplier sessionGeneration
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                sessionGeneration,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                TravelSkillPolicy.defaults()
        );
    }

    public TravelToSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LongSupplier sessionGeneration,
            LocalAStarPlanner planner,
            CoreSkillPolicy corePolicy,
            TravelSkillPolicy travelPolicy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.localPlanner = Objects.requireNonNull(planner, "planner");
        this.corePolicy = Objects.requireNonNull(corePolicy, "corePolicy");
        this.travelPolicy = Objects.requireNonNull(
                travelPolicy,
                "travelPolicy"
        );
        this.rollingPlanner = new RollingTravelPlanner(
                localPlanner,
                corePolicy,
                travelPolicy
        );
    }

    @Override
    public SkillParameterParser<TravelToParameters> parameters() {
        return TravelSkills::parseTravelTo;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            TravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        long generation;
        try {
            generation = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return Optional.of(SkillFailure.of(
                    "travel_to.session_unavailable"
            ));
        }
        if (generation < 0) {
            return Optional.of(SkillFailure.of(
                    "travel_to.session_unavailable"
            ));
        }
        FrameValidation validation = validateFrame(parameters, generation);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        CoreSkillFrame frame = validation.frame().orElseThrow();
        if (unsafe(context, frame)) {
            return Optional.of(SkillFailure.of(
                    context.hardcore()
                            ? "travel_to.hardcore_danger"
                            : "travel_to.current_danger"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            TravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        // State only: the first look or movement is issued by tick().
        boundSessionGeneration = sessionGeneration.getAsLong();
        if (boundSessionGeneration < 0) {
            throw new IllegalStateException(
                    "Travel session generation is unavailable"
            );
        }
        phase = Phase.PLANNING;
        failure = null;
        startedAtTick = context.gameTick();
        lastProgressTick = context.gameTick();
        lastFrameGameTime = -1;
        lastObservationRevision = -1;
        lastNavigationRevision = -1;
        requiredObservationRevision = -1;
        nextScanTick = context.gameTick();
        bestPhysicalDistance = Double.POSITIVE_INFINITY;
        bestKnownMapDistance = Double.POSITIVE_INFINITY;
        lastPhysicalPosition = null;
        courseOrigin = null;
        segmentAnchor = null;
        segmentAnchorTick = -1;
        segmentStartDistance = Double.POSITIVE_INFINITY;
        completedSegments = 0;
        scansWithoutGrowth = 0;
        scanPatternIndex = 0;
        consecutiveTimeBudgetExhaustions = 0;
        rejectedCourseSide = 0;
        rejectedCourseSideReleaseDistance = Double.NEGATIVE_INFINITY;
        recoveringCourse = false;
        lastPlanDangerBlocked = false;
        escapingWater = false;
        waterEscapeLastProgressTick = -1;
        waterEscapeLastPosition = null;
        segment = null;
        segmentParameters = null;
        segmentEndpoint = null;
        acceptedArrival = null;
        rejectedFrontiers.clear();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            TravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.PLANNING
                && phase != Phase.SCANNING
                && phase != Phase.MOVING) {
            return SkillTickResult.failed("travel_to.invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(currentFrame(), context, parameters,
                    "travel_to.internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            TravelToParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.6f,\"y\":%.6f,\"z\":%.6f,"
                                + "\"arrivalRadius\":%.3f,"
                                + "\"sessionGeneration\":%d,"
                                + "\"segments\":%d,"
                                + "\"observationRevision\":%d,"
                                + "\"bestDistance\":%.6f,"
                                + "\"segmentStartDistance\":%.6f,"
                                + "\"segmentEndpoint\":\"%s\","
                                + "\"rejectedFrontiers\":%d,"
                                + "\"rejectedSample\":\"%s\","
                                + "\"child\":\"%s\"}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        parameters.arrivalRadius(),
                        boundSessionGeneration,
                        completedSegments,
                        lastObservationRevision,
                        bestPhysicalDistance,
                        segmentStartDistance,
                        segmentEndpoint == null
                                ? ""
                                : segmentEndpoint,
                        rejectedFrontiers.size(),
                        rejectedFrontierSample(),
                        segmentCheckpoint(context)
                )
        );
    }

    private String segmentCheckpoint(final SkillContext context) {
        if (segment == null || segmentParameters == null) {
            return "";
        }
        return segment.checkpoint(context, segmentParameters)
                .payload()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String rejectedFrontierSample() {
        return rejectedFrontiers.stream()
                .limit(12)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","))
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @Override
    public void cancel(
            SkillContext context,
        TravelToParameters parameters
    ) {
        Optional<CoreSkillFrame> frame = currentFrame();
        if (!cancelSegment(context)) {
            frame.ifPresent(value ->
                    CoreSkillSafety.quiesce(actuator, value));
        }
        phase = Phase.CANCELLED;
        clearSegment();
    }

    @Override
    public SkillResult result(
            SkillContext context,
            TravelToParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(
                    SkillFailure.of("travel_to.invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(
            SkillContext context,
            TravelToParameters parameters
    ) {
        FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(
                    validation.frame(),
                    context,
                    parameters,
                    validation.failure().orElseThrow().code()
            );
        }
        CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision() < lastObservationRevision
                || frame.navigation().revision() < lastNavigationRevision) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.stale_observation");
        }
        if (lastFrameGameTime >= 0
                && frame.gameTime() < lastFrameGameTime) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.session_clock_regressed");
        }
        if (unsafe(context, frame)) {
            return fail(
                    Optional.of(frame),
                    context,
                    parameters,
                    context.hardcore()
                            ? "travel_to.hardcore_danger"
                            : "travel_to.current_danger"
            );
        }
        if (context.gameTick() - startedAtTick
                > travelPolicy.maximumTotalTicks()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.timeout");
        }
        if (completedSegments >= travelPolicy.maximumSegments()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.segment_budget_exceeded");
        }

        final boolean freshObservation =
                frame.observationRevision() > lastObservationRevision;
        final boolean physicalProgress = updatePhysicalProgress(
                context,
                frame,
                parameters
        );
        final boolean mapProgress = freshObservation
                && updateMapProgress(context, frame, parameters);
        lastFrameGameTime = frame.gameTime();
        lastObservationRevision = frame.observationRevision();
        lastNavigationRevision = frame.navigation().revision();

        final Optional<GridPos> observedWaterExit =
                observedWaterExit(frame);
        if (frame.inWater() && observedWaterExit.isPresent()) {
            return escapeWater(
                    context,
                    parameters,
                    frame,
                    observedWaterExit.orElseThrow()
            );
        }
        if (!frame.inWater() && escapingWater) {
            escapingWater = false;
            waterEscapeLastProgressTick = -1;
            waterEscapeLastPosition = null;
            clearSegment();
            requiredObservationRevision = frame.observationRevision() + 1;
            phase = Phase.SCANNING;
            if (!CoreSkillSafety.quiesce(actuator, frame)) {
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.actuator_rejected");
            }
            return SkillTickResult.running(true, true);
        }

        if (context.gameTick() - lastProgressTick
                > travelPolicy.maximumNoProgressTicks()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.no_progress");
        }
        if (arrived(frame, parameters, context.hardcore())) {
            if (!CoreSkillSafety.quiesce(actuator, frame)) {
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.actuator_rejected");
            }
            phase = Phase.COMPLETED;
            clearSegment();
            return SkillTickResult.completed();
        }

        if (segment != null) {
            return tickSegment(
                    context,
                    parameters,
                    frame,
                    physicalProgress || mapProgress
            );
        }
        if (requiredObservationRevision >= 0
                && frame.observationRevision()
                < requiredObservationRevision) {
            return scan(
                    context,
                    parameters,
                    frame,
                    physicalProgress || mapProgress
            );
        }
        return planNextSegment(
                context,
                parameters,
                frame,
                physicalProgress || mapProgress || freshObservation
        );
    }

    private SkillTickResult escapeWater(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            GridPos landingFeet
    ) {
        if (!escapingWater) {
            cancelSegment(context);
            clearSegment();
            escapingWater = true;
            waterEscapeLastProgressTick = context.gameTick();
            waterEscapeLastPosition = frame.position();
        } else if (waterEscapeLastPosition == null
                || frame.position()
                        .subtract(waterEscapeLastPosition)
                        .lengthSquared()
                        >= POSITION_PROGRESS_DISTANCE_SQUARED) {
            waterEscapeLastProgressTick = context.gameTick();
            waterEscapeLastPosition = frame.position();
        }
        if (context.gameTick() - waterEscapeLastProgressTick
                > travelPolicy.maximumStationarySegmentTicks()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.water_escape_stuck");
        }

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
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.actuator_rejected");
        }
        if (error > corePolicy.movementAlignmentDegrees()) {
            if (!actuator.stop().accepted()) {
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.actuator_rejected");
            }
            return SkillTickResult.running(false, false);
        }
        final ActionOutcome moved = actuator.move(
                new MovementIntent(0.60, 0.0, false, false)
        );
        final ActionOutcome jumped = actuator.jump();
        if (!moved.accepted() || !jumped.accepted()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.water_escape_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private static Optional<GridPos> observedWaterExit(
            CoreSkillFrame frame
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

    private SkillTickResult planNextSegment(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            boolean madeProgress
    ) {
        if (courseOrigin == null) {
            courseOrigin = frame.position();
        }
        final double courseDeviation =
                RollingTravelPlanner.courseDeviation(
                        courseOrigin,
                        parameters,
                        frame.feet()
                );
        if (recoveringCourse
                && courseDeviation
                    <= rollingPlanner.courseRecoveryExitDeviation()) {
            recoveringCourse = false;
        }
        RollingTravelPlanner.SegmentSelection selection =
                rollingPlanner.select(
                        withPlanningMemory(
                                frame.navigation(),
                                frame.feet()
                        ),
                        frame.feet(),
                        parameters,
                        context.hardcore(),
                        Set.copyOf(rejectedFrontiers),
                        courseOrigin,
                        recoveringCourse,
                        rejectedCourseSide,
                        frame.onGround()
                );
        switch (selection.status()) {
            case PLANNING_TIME_BUDGET_EXCEEDED:
                return deferAfterPlanningTimeBudget(
                        context,
                        parameters,
                        frame,
                        madeProgress
                );
            case PLANNING_NODE_BUDGET_EXCEEDED:
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.planning_node_budget_exceeded");
            case CANDIDATE_UNREACHABLE:
                consecutiveTimeBudgetExhaustions = 0;
                rememberRejectedFrontier(
                        selection.endpoint().orElseThrow()
                );
                if (!CoreSkillSafety.quiesce(actuator, frame)) {
                    return fail(Optional.of(frame), context, parameters,
                            "travel_to.actuator_rejected");
                }
                phase = Phase.PLANNING;
                return SkillTickResult.running(true, true);
            case DIMENSION_MISMATCH:
                consecutiveTimeBudgetExhaustions = 0;
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.dimension_mismatch");
            case DANGER_BLOCKED:
                consecutiveTimeBudgetExhaustions = 0;
                lastPlanDangerBlocked = true;
                return awaitMoreObservation(
                        context,
                        parameters,
                        frame,
                        madeProgress
                );
            case NEEDS_OBSERVATION:
                consecutiveTimeBudgetExhaustions = 0;
                lastPlanDangerBlocked = false;
                return awaitMoreObservation(
                        context,
                        parameters,
                        frame,
                        madeProgress
                );
            case FOUND:
                consecutiveTimeBudgetExhaustions = 0;
                return startSegment(
                        context,
                        parameters,
                        frame,
                        selection,
                        madeProgress
                );
        }
        return fail(Optional.of(frame), context, parameters,
                "travel_to.internal_failure");
    }


    private SkillTickResult deferAfterPlanningTimeBudget(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            boolean madeProgress
    ) {
        consecutiveTimeBudgetExhaustions++;
        if (consecutiveTimeBudgetExhaustions
                > MAXIMUM_CONSECUTIVE_TIME_BUDGET_EXHAUSTIONS) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.planning_time_budget_exceeded");
        }
        /*
         * A wall-clock budget can be consumed by first-use JIT compilation
         * or an operating-system scheduling pause. Keep the per-tick 2 ms
         * ceiling, but do not turn one noisy tick into a permanent journey
         * failure. Holding position also prevents stale movement input while
         * the same fully observed local segment is retried next tick.
         */
        if (!CoreSkillSafety.quiesce(actuator, frame)) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.actuator_rejected");
        }
        phase = Phase.PLANNING;
        return SkillTickResult.running(madeProgress, true);
    }

    private SkillTickResult startSegment(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            RollingTravelPlanner.SegmentSelection selection,
            boolean madeProgress
    ) {
        GridPos endpoint = selection.endpoint().orElseThrow();
        MoveToParameters localTarget = new MoveToParameters(
                parameters.dimension(),
                endpoint.x() + 0.5,
                endpoint.y(),
                endpoint.z() + 0.5,
                0.55
        );
        MoveToSkill local = new MoveToSkill(
                expectedPlayerId,
                actuator,
                frames,
                localPlanner,
                corePolicy
        );
        Optional<SkillFailure> precondition = local.preconditions(
                context,
                localTarget
        );
        if (precondition.isPresent()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.segment_precondition");
        }
        local.start(context, localTarget);
        segment = local;
        segmentParameters = localTarget;
        segmentEndpoint = endpoint;
        if (selection.courseRecovery()) {
            if (!recoveringCourse && courseOrigin != null) {
                final double signedDeviation =
                        RollingTravelPlanner.signedCourseDeviation(
                                courseOrigin,
                                parameters,
                                frame.feet()
                        );
                rejectedCourseSide = signedDeviation > 0.0
                        ? 1
                        : signedDeviation < 0.0
                            ? -1
                            : rejectedCourseSide;
                rejectedCourseSideReleaseDistance =
                        bestPhysicalDistance
                            - travelPolicy.maximumSegmentDistance() * 2.0;
            }
            recoveringCourse = true;
        }
        if (selection.arrival()) {
            acceptedArrival = endpoint;
        }
        segmentAnchor = frame.position();
        segmentAnchorTick = context.gameTick();
        segmentStartDistance = frame.position()
                .subtract(parameters.target())
                .length();
        requiredObservationRevision = -1;
        phase = Phase.MOVING;
        return SkillTickResult.running(madeProgress, true);
    }

    private SkillTickResult tickSegment(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            boolean madeProgress
    ) {
        if (segmentAnchor == null) {
            segmentAnchor = frame.position();
            segmentAnchorTick = context.gameTick();
        } else if (frame.position().subtract(segmentAnchor).lengthSquared()
                >= POSITION_PROGRESS_DISTANCE_SQUARED) {
            segmentAnchor = frame.position();
            segmentAnchorTick = context.gameTick();
        } else if (context.gameTick() - segmentAnchorTick
                > travelPolicy.maximumStationarySegmentTicks()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.stuck");
        }

        final SkillTickResult result;
        if (segmentEndpoint != null
                && frame.feet().equals(segmentEndpoint)) {
            /*
             * A rolling segment endpoint is a planner-verified standing
             * cell, not a precise interaction coordinate. Once the body's
             * feet physically occupy that exact cell, forcing it to dock
             * within 0.55 blocks of the center can leave normal movement
             * stopped at a cell corner forever. Preserve precise arrival at
             * the public journey target, but consume this internal segment
             * and replan from the actually reached safe cell.
             */
            segment.cancel(
                    context,
                    Objects.requireNonNull(segmentParameters)
            );
            result = SkillTickResult.completed();
        } else {
            result = segment.tick(
                    context,
                    Objects.requireNonNull(segmentParameters)
            );
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            String code = result.failure().orElseThrow().code();
            if (code.contains("hardcore")
                    || code.contains("danger")
                    || code.contains("stuck")
                    || code.contains("stale")
                    || code.contains("player_mismatch")
                    || code.contains("dimension_mismatch")) {
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.segment_" + suffix(code));
            }
            rememberRejectedFrontier(segmentEndpoint);
            clearSegment();
            requiredObservationRevision =
                    frame.observationRevision() + 1;
            phase = Phase.SCANNING;
            return scan(
                    context,
                    parameters,
                    frame,
                    true
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            completedSegments++;
            scansWithoutGrowth = 0;
            final double segmentDistance = frame.position()
                    .subtract(parameters.target())
                    .length();
            /*
             * A successfully reached endpoint is not a rejected frontier.
             * Keep rejection memory for candidates whose route actually
             * failed, but allow a later course-recovery pass to walk back
             * through a previously visited safe cell. Permanently rejecting
             * completed cells can seal the only legal branch at a junction
             * and turn a fair detour into route_unknown.
             */
            clearSegment();
            if (arrived(frame, parameters, context.hardcore())) {
                if (!CoreSkillSafety.quiesce(actuator, frame)) {
                    return fail(Optional.of(frame), context, parameters,
                            "travel_to.actuator_rejected");
                }
                phase = Phase.COMPLETED;
                return SkillTickResult.completed();
            }
            requiredObservationRevision =
                    frame.observationRevision() + 1;
            phase = Phase.SCANNING;
            if (!CoreSkillSafety.quiesce(actuator, frame)) {
                return fail(Optional.of(frame), context, parameters,
                        "travel_to.actuator_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        phase = Phase.MOVING;
        return SkillTickResult.running(
                madeProgress || result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult awaitMoreObservation(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            boolean madeProgress
    ) {
        scansWithoutGrowth++;
        if (scansWithoutGrowth
                > travelPolicy.maximumScansWithoutGrowth()) {
            logTerminalObservationEvidence(frame, parameters);
            return fail(
                    Optional.of(frame),
                    context,
                    parameters,
                    lastPlanDangerBlocked
                            ? "travel_to.danger_blocked"
                            : "travel_to.route_unknown"
            );
        }
        requiredObservationRevision = frame.observationRevision() + 1;
        phase = Phase.SCANNING;
        return scan(
                context,
                parameters,
                frame,
                madeProgress
        );
    }

    private SkillTickResult scan(
            SkillContext context,
            TravelToParameters parameters,
            CoreSkillFrame frame,
            boolean madeProgress
    ) {
        ActionOutcome stopped = actuator.stop();
        if (!stopped.accepted()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.actuator_rejected");
        }
        phase = Phase.SCANNING;
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(madeProgress, true);
        }

        final int scanNumber = scanPatternIndex++;
        final LookIntent scan;
        if (scanNumber < FRONTIER_SCAN_YAW_OFFSETS.length) {
            /*
             * Refresh the one-block floor frontier first. A remote target's
             * full bearing can point above the next support surface, leaving
             * a flat world apparently unwalkable. This is still a finite
             * first-person ray and does not reveal the remote route.
             */
            scan = CoreSkillGeometry.navigationScanTarget(
                    frame,
                    parameters.target(),
                    FRONTIER_SCAN_YAW_OFFSETS[scanNumber]
            );
        } else {
            LookIntent direct = CoreSkillGeometry.lookAt(
                    frame.eyePosition(),
                    scanTarget(frame, parameters)
            );
            ScanOffset offset = SCAN_PATTERN[
                    scanNumber % SCAN_PATTERN.length
            ];
            scan = new LookIntent(
                    direct.yawDegrees()
                            + offset.yawMultiplier()
                            * travelPolicy.scanSpreadDegrees(),
                    Math.max(
                            -35.0F,
                            Math.min(
                                    55.0F,
                                    direct.pitchDegrees()
                                            + offset.pitchOffsetDegrees()
                            )
                    )
            );
        }
        ActionOutcome looked = actuator.look(scan);
        if (!looked.accepted()) {
            return fail(Optional.of(frame), context, parameters,
                    "travel_to.actuator_rejected");
        }
        nextScanTick =
                context.gameTick() + travelPolicy.scanIntervalTicks();
        return SkillTickResult.running(true, true);
    }

    private void logTerminalObservationEvidence(
            final CoreSkillFrame frame,
            final TravelToParameters parameters
    ) {
        final LocalNavSnapshot navigation = frame.navigation();
        final long revision = navigation.revision();
        final GridPos feet = frame.feet();
        int currentClearance = 0;
        int currentStandingSupports = 0;
        int currentSafeFeet = 0;
        int targetAdvancingSafeFeet = 0;
        final StringBuilder advancingDetails = new StringBuilder();
        final double currentDistance = frame.position()
                .subtract(parameters.target())
                .length();
        for (ObservedVoxel voxel :
                navigation.observedVoxels().values()) {
            if (voxel.observationRevision() == revision
                    && NavigationEvidence
                        .hasFreshTraversalClearance(
                                voxel,
                                revision
                        )) {
                currentClearance++;
            }
            if (voxel.observationRevision() == revision
                    && NavigationEvidence
                        .isFreshStandingSupport(
                                voxel,
                                revision
                        )) {
                currentStandingSupports++;
            }
            final GridPos candidate = voxel.position();
            final Optional<ObservedVoxel> head =
                    navigation.voxelAt(candidate.above());
            final Optional<ObservedVoxel> support =
                    navigation.voxelAt(candidate.below());
            final boolean safeFeet =
                    NavigationEvidence
                        .hasFreshTraversalClearance(
                                voxel,
                                revision
                        )
                    && head.filter(value ->
                            NavigationEvidence
                                .hasFreshTraversalClearance(
                                        value,
                                        revision
                                )
                    ).isPresent()
                    && support.filter(value ->
                            NavigationEvidence
                                .isFreshStandingSupport(
                                        value,
                                        revision
                                )
                    ).isPresent();
            if (!safeFeet) {
                continue;
            }
            currentSafeFeet++;
            final PerceptionVec3 center = new PerceptionVec3(
                    candidate.x() + 0.5,
                    candidate.y(),
                    candidate.z() + 0.5
            );
            if (center.subtract(parameters.target()).length()
                    + 0.20 < currentDistance) {
                targetAdvancingSafeFeet++;
                if (advancingDetails.length() > 0) {
                    advancingDetails.append(',');
                }
                advancingDetails
                        .append(candidate)
                        .append("/rejected=")
                        .append(rejectedFrontiers.contains(candidate));
            }
        }
        MinecraftAiCompanion.LOGGER.warn(
                "Travel route observation exhausted: position={}, "
                    + "feet={}, target={}, navRevision={}, "
                    + "observedVoxels={}, visibleFaces={}, "
                    + "currentClearance={}, currentStandingSupports={}, "
                    + "currentSafeFeet={}, advancingSafeFeet={}, "
                    + "advancingDetails={}, rejectedFrontiers={}, "
                    + "feetVoxel={}, headVoxel={}, supportVoxel={}, "
                    + "look={}, faces={}",
                frame.position(),
                feet,
                parameters,
                revision,
                navigation.observedVoxels().size(),
                frame.visibleBlockFaces().size(),
                currentClearance,
                currentStandingSupports,
                currentSafeFeet,
                targetAdvancingSafeFeet,
                advancingDetails,
                rejectedFrontiers.size(),
                navigation.voxelAt(feet).orElse(null),
                navigation.voxelAt(feet.above()).orElse(null),
                navigation.voxelAt(feet.below()).orElse(null),
                frame.lookDirection(),
                frame.visibleBlockFaces()
        );
    }

    private boolean updatePhysicalProgress(
            SkillContext context,
            CoreSkillFrame frame,
            TravelToParameters parameters
    ) {
        double distance = frame.position()
                .subtract(parameters.target())
                .length();
        boolean targetProgress =
                distance + PHYSICAL_PROGRESS_EPSILON
                        < bestPhysicalDistance;
        boolean positionChanged = lastPhysicalPosition != null
                && frame.position()
                .subtract(lastPhysicalPosition)
                .lengthSquared() >= POSITION_PROGRESS_DISTANCE_SQUARED;
        if (targetProgress) {
            bestPhysicalDistance = distance;
            if (!recoveringCourse
                    && rejectedCourseSide != 0
                    && distance
                        <= rejectedCourseSideReleaseDistance) {
                rejectedCourseSide = 0;
                rejectedCourseSideReleaseDistance =
                        Double.NEGATIVE_INFINITY;
            }
        }
        lastPhysicalPosition = frame.position();
        if (targetProgress || positionChanged) {
            /*
             * Physical displacement alone is not journey progress. A
             * detour can move every tick while its distance to the waypoint
             * gets worse; counting that as progress was the reason a live
             * body could wander for the entire exploration timeout. The
             * travel watchdog is reset only by target-closing motion (or the
             * separately measured map progress below).
             */
            if (targetProgress) {
                lastProgressTick = context.gameTick();
            }
            if (positionChanged && targetProgress) {
                /*
                 * Once the body has advanced toward the target, restart at
                 * the direct, downward sample. Completed frontiers remain
                 * bounded and reject-aware so a small target improvement
                 * cannot reopen the same two-cell loop.
                 */
                scanPatternIndex = 0;
            }
            return true;
        }
        return false;
    }

    private boolean updateMapProgress(
            SkillContext context,
            CoreSkillFrame frame,
            TravelToParameters parameters
    ) {
        double closest = rollingPlanner.closestRecentSafeDistance(
                frame.navigation(),
                parameters,
                context.hardcore()
        );
        if (closest + MAP_PROGRESS_EPSILON
                < bestKnownMapDistance) {
            bestKnownMapDistance = closest;
            lastProgressTick = context.gameTick();
            scansWithoutGrowth = 0;
            return true;
        }
        return false;
    }

    private boolean arrived(
            CoreSkillFrame frame,
            TravelToParameters parameters,
            boolean hardcore
    ) {
        if (!rollingPlanner.isSafeArrival(
                frame.navigation(),
                frame.feet(),
                hardcore
        )) {
            return false;
        }
        double distance = frame.position()
                .subtract(parameters.target())
                .length();
        if (distance <= parameters.arrivalRadius()) {
            return true;
        }
        final double horizontalDistance = Math.hypot(
                frame.position().x() - parameters.x(),
                frame.position().z() - parameters.z()
        );
        return acceptedArrival != null
                && frame.feet().equals(acceptedArrival)
                && distance <= 3.0
                /*
                 * A blocked waypoint may resolve to an adjacent safe cell or
                 * the top of its solid block. It must not, however, silently
                 * turn a precise arrival radius into the old blanket
                 * three-block horizontal success radius.
                 */
                && horizontalDistance <= Math.max(
                    1.0,
                    parameters.arrivalRadius()
                );
    }

    private boolean unsafe(
            SkillContext context,
            CoreSkillFrame frame
    ) {
        double limit = context.hardcore()
                ? travelPolicy.hardcoreMaximumDanger()
                : travelPolicy.normalMaximumDanger();
        return context.riskScore() > limit || frame.danger() > limit;
    }

    private FrameValidation validateFrame(
            TravelToParameters parameters,
            long expectedGeneration
    ) {
        Optional<CoreSkillFrame> current = currentFrame();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    "travel_to.observation_unavailable"
            );
        }
        CoreSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(
                    frame,
                    "travel_to.player_mismatch"
            );
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(
                    frame,
                    "travel_to.dimension_mismatch"
            );
        }
        long currentGeneration;
        try {
            currentGeneration = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return FrameValidation.failed(
                    frame,
                    "travel_to.session_unavailable"
            );
        }
        if (currentGeneration < 0
                || currentGeneration != expectedGeneration) {
            return FrameValidation.failed(
                    frame,
                    "travel_to.session_changed"
            );
        }
        return FrameValidation.valid(frame);
    }

    private boolean cancelSegment(SkillContext context) {
        if (segment == null || segmentParameters == null) {
            return false;
        }
        try {
            segment.cancel(context, segmentParameters);
            return true;
        } catch (RuntimeException ignored) {
            // The outer quiesce still runs against the current body frame.
            return false;
        }
    }

    private SkillTickResult fail(
            Optional<CoreSkillFrame> frame,
            SkillContext context,
            TravelToParameters parameters,
            String code
    ) {
        if (!cancelSegment(context)) {
            frame.ifPresent(value ->
                    CoreSkillSafety.quiesce(actuator, value));
        }
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        clearSegment();
        return SkillTickResult.failed(failure);
    }

    private Optional<CoreSkillFrame> currentFrame() {
        try {
            return frames.current();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void clearSegment() {
        segment = null;
        segmentParameters = null;
        segmentEndpoint = null;
        segmentAnchor = null;
        segmentAnchorTick = -1;
        segmentStartDistance = Double.POSITIVE_INFINITY;
    }

    private void rememberRejectedFrontier(GridPos position) {
        if (position == null) {
            return;
        }
        if (rejectedFrontiers.size() >= MAXIMUM_REJECTED_FRONTIERS) {
            /*
             * Evict only the oldest bounded memory entry. Clearing the whole
             * set let a stale local ring reopen every 64 segments and made a
             * remote exploration target effectively unreachable.
             */
            GridPos oldest = rejectedFrontiers.iterator().next();
            rejectedFrontiers.remove(oldest);
        }
        rejectedFrontiers.add(position);
    }

    private static PerceptionVec3 scanTarget(
            CoreSkillFrame frame,
            TravelToParameters parameters
    ) {
        double dx = parameters.x() - frame.eyePosition().x();
        double dz = parameters.z() - frame.eyePosition().z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= 1.0E-6) {
            return new PerceptionVec3(
                    frame.eyePosition().x(),
                    parameters.y(),
                    frame.eyePosition().z() + 1.0
            );
        }
        double scale = Math.min(32.0, horizontal) / horizontal;
        return new PerceptionVec3(
                frame.eyePosition().x() + dx * scale,
                parameters.y(),
                frame.eyePosition().z() + dz * scale
        );
    }

    /**
     * Preserve the same short-lived, safety-grade first-person route memory
     * used by {@link MoveToSkill}. A rolling travel segment is selected after
     * a camera turn, so requiring every previously seen floor/support voxel
     * to be from that one exact sample can strand the body at a newly exposed
     * frontier. Only evidence that already proves clearance or a sturdy top
     * is refreshed, and it expires after a bounded number of observations.
     * This remains a semantic map operation; it does not inspect the world.
     */
    private static LocalNavSnapshot withPlanningMemory(
            final LocalNavSnapshot snapshot,
            final GridPos start
    ) {
        final java.util.List<ObservedVoxel> fused = new ArrayList<>(
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

    private static boolean safetyGradePlanningMemory(
            final ObservedVoxel voxel,
            final GridPos start
    ) {
        if (voxel.kind() == VoxelKind.CLOSED_DOOR) {
            return false;
        }
        if (NavigationEvidence.hasTraversalClearance(voxel)) {
            return true;
        }
        if (!voxel.kind().supportsWeight()) {
            return false;
        }
        if (voxel.position().equals(start.below())
                && voxel.occupancyEvidence() == OccupancyEvidence.BODY_CONTACT) {
            return true;
        }
        return voxel.occupancyEvidence() == OccupancyEvidence.SURFACE_HIT
                && voxel.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP;
    }

    private static String suffix(String code) {
        int separator = code.indexOf('.');
        return separator < 0 ? code : code.substring(separator + 1);
    }

    /**
     * Deliberate first-person scan pose. Positive pitch is downward in
     * Minecraft. Varying both axes lets the fixed ray fan observe a nearby
     * floor top plus feet- and head-height clearance in the same candidate
     * columns; yaw-only scanning can leave every forward column incomplete.
     */
    private record ScanOffset(
            float yawMultiplier,
            float pitchOffsetDegrees
    ) {
    }

    private enum Phase {
        IDLE,
        PLANNING,
        SCANNING,
        MOVING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        static FrameValidation valid(CoreSkillFrame frame) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }

        static FrameValidation failed(
                CoreSkillFrame frame,
                String code
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
