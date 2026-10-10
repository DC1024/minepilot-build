package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
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
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Produces a bounded stronghold search area from two ordinary Eye throws.
 *
 * <p>The compound equips owned Eyes through vanilla inventory transactions,
 * delegates each throw to {@link TraceStrongholdEyeSkill}, walks a
 * perpendicular baseline through the rolling first-person travel skill, then
 * turns back toward the measured bearing before taking the second sample. It
 * never receives a level, seed, chunk, or structure lookup.</p>
 */
public final class TriangulateStrongholdSearchAreaSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "triangulate_stronghold_search_area";

    public static final double DEFAULT_BASELINE_DISTANCE = 256.0;
    private static final double MINIMUM_BASELINE_DISTANCE = 64.0;
    private static final double MAXIMUM_BASELINE_DISTANCE = 512.0;
    private static final double ARRIVAL_RADIUS = 2.0;
    private static final int REQUIRED_EYES_FROM_SCRATCH = 2;
    private static final int REQUIRED_EYES_AFTER_ONE_TRACE = 1;
    private static final int EQUIP_CONFIRMATION_TICKS = 100;
    private static final int ALIGNMENT_TIMEOUT_TICKS = 80;
    private static final int MAXIMUM_TICKS = 150_000;
    private static final float SECOND_TRACE_PITCH = -10.0F;
    private static final float ALIGNMENT_TOLERANCE_DEGREES = 2.0F;
    private static final double NORMAL_MINIMUM_HEALTH = 0.55;
    private static final double HARDCORE_MINIMUM_HEALTH = 0.80;
    private static final int MINIMUM_FOOD_LEVEL = 7;
    private static final double NORMAL_MAXIMUM_DANGER = 0.25;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.10;

    private final java.util.UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource frames;
    private final InventorySkillActuator inventory;
    private final EyeTraceResultBuffer results;
    private final LongSupplier sessionGeneration;
    private final double baselineDistance;
    private final LongConsumer completionSink;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1L;
    private long phaseStartedAtTick = -1L;
    private long requiredObservationRevision = -1L;
    private long boundSessionGeneration = -1L;
    private int expectedTraceCount;
    private int completedTraceCount;
    private int travelCandidateIndex;
    private EyeTraceSnapshot firstTrace;
    private List<TravelToParameters> travelCandidates = List.of();
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private TraceStrongholdEyeSkill tracer;
    private TraceStrongholdEyeParameters traceParameters;
    private boolean completionPublished;

    public TriangulateStrongholdSearchAreaSkill(
            final java.util.UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration
    ) {
        this(
                expectedPlayerId,
                core,
                frames,
                inventory,
                results,
                sessionGeneration,
                DEFAULT_BASELINE_DISTANCE,
                ignored -> {
                }
        );
    }

    public TriangulateStrongholdSearchAreaSkill(
            final java.util.UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final LongConsumer completionSink
    ) {
        this(
                expectedPlayerId,
                core,
                frames,
                inventory,
                results,
                sessionGeneration,
                DEFAULT_BASELINE_DISTANCE,
                completionSink
        );
    }

    TriangulateStrongholdSearchAreaSkill(
            final java.util.UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final double baselineDistance
    ) {
        this(
                expectedPlayerId,
                core,
                frames,
                inventory,
                results,
                sessionGeneration,
                baselineDistance,
                ignored -> {
                }
        );
    }

    TriangulateStrongholdSearchAreaSkill(
            final java.util.UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final double baselineDistance,
            final LongConsumer completionSink
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.results = Objects.requireNonNull(results, "results");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.completionSink = Objects.requireNonNull(
                completionSink,
                "completionSink"
        );
        if (!Double.isFinite(baselineDistance)
                || baselineDistance < MINIMUM_BASELINE_DISTANCE
                || baselineDistance > MAXIMUM_BASELINE_DISTANCE) {
            throw new IllegalArgumentException(
                    "baselineDistance must be in [64, 512]"
            );
        }
        this.baselineDistance = baselineDistance;
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame current = frame.orElseThrow();
        if (!DimensionRef.OVERWORLD.equals(current.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".overworld_required"
            ));
        }
        if (!current.onGround() || current.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_pose_required"
            ));
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, current);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        final long generation;
        try {
            generation = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return Optional.of(SkillFailure.of(
                    NAME + ".session_unavailable"
            ));
        }
        if (generation < 0L) {
            return Optional.of(SkillFailure.of(
                    NAME + ".session_unavailable"
            ));
        }
        final Optional<EyeTraceHistorySnapshot> history =
                results.snapshot(context.goalRevision());
        if (history.flatMap(
                EyeTraceHistorySnapshot::estimatedIntersection
        ).isPresent()) {
            return Optional.empty();
        }
        if (history.map(value ->
                value.traces().size()
                    >= EyeTraceResultBuffer.MAXIMUM_TRACES
        ).orElse(false)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".trace_budget_exhausted"
            ));
        }
        final int required = history.isEmpty()
                ? REQUIRED_EYES_FROM_SCRATCH
                : REQUIRED_EYES_AFTER_ONE_TRACE;
        if (inventoryCount(current, TraceStrongholdEyeSkill.EYE_ITEM_ID)
                < required) {
            return Optional.of(SkillFailure.of(
                    NAME + ".insufficient_ender_eyes"
            ));
        }
        if (heldEye(current).isEmpty()) {
            final InventoryOperationResult equipCheck =
                    inventory.checkEquip(new EquipItemParameters(
                            TraceStrongholdEyeSkill.EYE_ITEM_ID,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipCheck.succeeded()) {
                return equipCheck.failure();
            }
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
                        "Companion body changed before triangulation"
                )
        );
        failure = null;
        cancelChildren(context);
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        requiredObservationRevision = -1L;
        boundSessionGeneration = sessionGeneration.getAsLong();
        expectedTraceCount = 0;
        completedTraceCount = 0;
        completionPublished = false;
        travelCandidateIndex = 0;
        firstTrace = null;
        travelCandidates = List.of();

        final Optional<EyeTraceHistorySnapshot> history =
                results.snapshot(context.goalRevision());
        if (history.flatMap(
                EyeTraceHistorySnapshot::estimatedIntersection
        ).isPresent()) {
            phase = Phase.COMPLETE_PENDING;
            return;
        }
        if (history.isPresent()) {
            final List<EyeTraceSnapshot> traces =
                    history.orElseThrow().traces();
            expectedTraceCount = traces.size();
            firstTrace = traces.getFirst();
            phase = Phase.PREPARE_TRAVEL;
            return;
        }
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            throw new IllegalStateException(
                    "Triangulation start dimension changed"
            );
        }
        phase = Phase.EQUIP_FIRST;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Stronghold triangulation internal failure in phase {}",
                    phase,
                    exception
            );
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final Optional<EyeTraceHistorySnapshot> history =
                results.snapshot(context.goalRevision());
        final int traces = history.map(value ->
                value.traces().size()
        ).orElse(0);
        final boolean intersection = history.flatMap(
                EyeTraceHistorySnapshot::estimatedIntersection
        ).isPresent();
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"traces\":%d,"
                            + "\"intersection\":%s,"
                            + "\"baselineDistance\":%.1f,"
                            + "\"travelCandidate\":%d,"
                            + "\"completedTraces\":%d}",
                        phase.name(),
                        traces,
                        intersection,
                        baselineDistance,
                        travelCandidateIndex,
                        completedTraceCount
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelChildren(context);
        core.stop();
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
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(final SkillContext context) {
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        if (sessionGeneration.getAsLong() != boundSessionGeneration) {
            return fail(context, NAME + ".session_changed");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            return fail(context, NAME + ".dimension_changed");
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow());
        }
        if (results.snapshot(context.goalRevision())
                .flatMap(
                        EyeTraceHistorySnapshot::estimatedIntersection
                )
                .isPresent()) {
            return complete(context);
        }
        return switch (phase) {
            case COMPLETE_PENDING -> complete(context);
            case EQUIP_FIRST -> ensureEyeReady(
                    context,
                    frame,
                    true
            );
            case WAIT_FIRST_EQUIP -> awaitEquippedEye(
                    context,
                    frame,
                    true
            );
            case TRACE_FIRST -> tickTrace(context, true);
            case PREPARE_TRAVEL -> beginTravel(context, frame);
            case TRAVELLING -> tickTravel(context, frame);
            case ALIGN_SECOND -> alignForSecondTrace(context, frame);
            case EQUIP_SECOND -> ensureEyeReady(
                    context,
                    frame,
                    false
            );
            case WAIT_SECOND_EQUIP -> awaitEquippedEye(
                    context,
                    frame,
                    false
            );
            case TRACE_SECOND -> tickTrace(context, false);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult ensureEyeReady(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean first
    ) {
        final Optional<ActionHand> held = heldEye(frame);
        if (held.isPresent()) {
            return beginTrace(context, frame, held.orElseThrow(), first);
        }
        final InventoryOperationResult equipped =
                inventory.equip(new EquipItemParameters(
                        TraceStrongholdEyeSkill.EYE_ITEM_ID,
                        EquipmentTarget.MAINHAND
                ));
        if (!equipped.succeeded()) {
            return fail(
                    context,
                    equipped.failure().orElseGet(() ->
                            SkillFailure.of(
                                    NAME + ".eye_equip_failed"
                            )
                    )
            );
        }
        requiredObservationRevision =
                frame.observationRevision() + 1L;
        phaseStartedAtTick = context.gameTick();
        phase = first
                ? Phase.WAIT_FIRST_EQUIP
                : Phase.WAIT_SECOND_EQUIP;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult awaitEquippedEye(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean first
    ) {
        if (context.gameTick() - phaseStartedAtTick
                >= EQUIP_CONFIRMATION_TICKS) {
            return fail(context, NAME + ".eye_equip_unconfirmed");
        }
        if (frame.observationRevision()
                < requiredObservationRevision) {
            core.stop();
            return SkillTickResult.running(false, true);
        }
        final Optional<ActionHand> held = heldEye(frame);
        if (held.isEmpty()) {
            return fail(context, NAME + ".eye_equip_unconfirmed");
        }
        return beginTrace(context, frame, held.orElseThrow(), first);
    }

    private SkillTickResult beginTrace(
            final SkillContext context,
            final CoreSkillFrame frame,
            final ActionHand hand,
            final boolean first
    ) {
        expectedTraceCount = results.snapshot(context.goalRevision())
                .map(value -> value.traces().size())
                .orElse(0);
        tracer = new TraceStrongholdEyeSkill(
                expectedPlayerId,
                core,
                frames,
                results
        );
        traceParameters = new TraceStrongholdEyeParameters(
                DimensionRef.OVERWORLD,
                frame.observationRevision(),
                hand
        );
        final Optional<SkillFailure> rejected = tracer.preconditions(
                context,
                traceParameters
        );
        if (rejected.isPresent()) {
            return fail(context, rejected.orElseThrow());
        }
        tracer.start(context, traceParameters);
        phaseStartedAtTick = context.gameTick();
        phase = first ? Phase.TRACE_FIRST : Phase.TRACE_SECOND;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickTrace(
            final SkillContext context,
            final boolean first
    ) {
        final SkillTickResult result = Objects.requireNonNull(tracer)
                .tick(context, Objects.requireNonNull(traceParameters));
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(NAME + ".trace_failed")
                    )
            );
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        tracer = null;
        traceParameters = null;
        final EyeTraceHistorySnapshot history =
                results.snapshot(context.goalRevision()).orElse(null);
        if (history == null
                || history.traces().size() <= expectedTraceCount) {
            return fail(context, NAME + ".trace_not_published");
        }
        completedTraceCount++;
        if (history.estimatedIntersection().isPresent()) {
            return complete(context);
        }
        if (!first) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Stronghold triangulation rejected measured rays: {}",
                    history.traces().stream()
                            .map(trace ->
                                    String.format(
                                            Locale.ROOT,
                                            "origin=(%.3f,%.3f),"
                                                + "direction=(%.6f,%.6f),"
                                                + "bearing=%.3f,"
                                                + "travel=%.3f",
                                            trace.throwOrigin().x(),
                                            trace.throwOrigin().z(),
                                            trace.directionX(),
                                            trace.directionZ(),
                                            trace.bearingDegrees(),
                                            trace.observedHorizontalTravel()
                                    )
                            )
                            .toList()
            );
            return fail(context, NAME + ".intersection_unavailable");
        }
        firstTrace = history.traces().getLast();
        expectedTraceCount = history.traces().size();
        phase = Phase.PREPARE_TRAVEL;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginTravel(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (firstTrace == null) {
            return fail(context, NAME + ".first_trace_missing");
        }
        travelCandidates = baselineTravelTargets(
                firstTrace,
                frame.position(),
                baselineDistance
        ).stream().map(target -> new TravelToParameters(
                DimensionRef.OVERWORLD,
                target.x(),
                frame.position().y(),
                target.z(),
                ARRIVAL_RADIUS
        )).toList();
        travelCandidateIndex = 0;
        return startTravelCandidate(context);
    }

    private SkillTickResult startTravelCandidate(
            final SkillContext context
    ) {
        if (travelCandidateIndex >= travelCandidates.size()) {
            return fail(context, NAME + ".baseline_unreachable");
        }
        travelParameters = travelCandidates.get(
                travelCandidateIndex
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                core,
                frames,
                sessionGeneration
        );
        final Optional<SkillFailure> rejected = travel.preconditions(
                context,
                travelParameters
        );
        if (rejected.isPresent()) {
            travel = null;
            travelParameters = null;
            travelCandidateIndex++;
            return startTravelCandidate(context);
        }
        travel.start(context, travelParameters);
        phaseStartedAtTick = context.gameTick();
        phase = Phase.TRAVELLING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickTravel(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final SkillTickResult result = Objects.requireNonNull(travel)
                .tick(context, Objects.requireNonNull(travelParameters));
        if (result.status() == SkillTickResult.Status.FAILED) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Stronghold baseline candidate {} failed: reason={}, "
                        + "target={}, position={}, firstRayDirection="
                        + "({},{})",
                    travelCandidateIndex,
                    result.failure().map(SkillFailure::code)
                            .orElse("missing_failure"),
                    travelParameters,
                    frame.position(),
                    firstTrace.directionX(),
                    firstTrace.directionZ()
            );
            travel = null;
            travelParameters = null;
            travelCandidateIndex++;
            return startTravelCandidate(context);
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        travel = null;
        travelParameters = null;
        if (horizontalDistance(
                frame.position(),
                firstTrace.throwOrigin()
        ) < baselineDistance - ARRIVAL_RADIUS - 1.0) {
            return fail(context, NAME + ".baseline_not_reached");
        }
        phase = Phase.ALIGN_SECOND;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult alignForSecondTrace(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (context.gameTick() - phaseStartedAtTick
                >= ALIGNMENT_TIMEOUT_TICKS) {
            return fail(context, NAME + ".alignment_timed_out");
        }
        final float targetYaw =
                (float) firstTrace.bearingDegrees();
        final ActionOutcome stopped = core.stop();
        final ActionOutcome looking = core.look(new LookIntent(
                targetYaw,
                SECOND_TRACE_PITCH
        ));
        if (!stopped.accepted() || !looking.accepted()) {
            return fail(context, NAME + ".alignment_rejected");
        }
        if (!aligned(frame, targetYaw, SECOND_TRACE_PITCH)) {
            return SkillTickResult.running(true, true);
        }
        phase = Phase.EQUIP_SECOND;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult complete(final SkillContext context) {
        cancelChildren(context);
        core.stop();
        if (!completionPublished) {
            completionSink.accept(context.goalRevision());
            completionPublished = true;
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
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
        cancelChildren(context);
        core.stop();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private void cancelChildren(final SkillContext context) {
        if (tracer != null && traceParameters != null) {
            tracer.cancel(context, traceParameters);
        }
        tracer = null;
        traceParameters = null;
        if (travel != null && travelParameters != null) {
            travel.cancel(context, travelParameters);
        }
        travel = null;
        travelParameters = null;
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return frames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    static List<PerceptionVec3> baselineTravelTargets(
            final EyeTraceSnapshot trace,
            final PerceptionVec3 current,
            final double distance
    ) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(current, "current");
        if (!Double.isFinite(distance) || distance <= 0.0) {
            throw new IllegalArgumentException(
                    "distance must be positive"
            );
        }
        final double perpendicularX = -trace.directionZ();
        final double perpendicularZ = trace.directionX();
        final PerceptionVec3 positive = new PerceptionVec3(
                trace.throwOrigin().x()
                    + perpendicularX * distance,
                current.y(),
                trace.throwOrigin().z()
                    + perpendicularZ * distance
        );
        final PerceptionVec3 negative = new PerceptionVec3(
                trace.throwOrigin().x()
                    - perpendicularX * distance,
                current.y(),
                trace.throwOrigin().z()
                    - perpendicularZ * distance
        );
        final List<PerceptionVec3> candidates = new ArrayList<>(
                List.of(positive, negative)
        );
        candidates.sort(Comparator.comparingDouble(
                target -> target.subtract(current).lengthSquared()
        ));
        return List.copyOf(candidates);
    }

    private static Optional<ActionHand> heldEye(
            final CoreSkillFrame frame
    ) {
        if (TraceStrongholdEyeSkill.EYE_ITEM_ID.equals(
                frame.mainHand().itemId()
        )) {
            return Optional.of(ActionHand.MAIN_HAND);
        }
        if (TraceStrongholdEyeSkill.EYE_ITEM_ID.equals(
                frame.offHand().itemId()
        )) {
            return Optional.of(ActionHand.OFF_HAND);
        }
        return Optional.empty();
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH
                : NORMAL_MINIMUM_HEALTH;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_low"
            ));
        }
        if (frame.foodLevel() < MINIMUM_FOOD_LEVEL) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_low"
            ));
        }
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (frame.danger() > maximumDanger
                || context.riskScore() > maximumDanger
                    && frame.danger() > 0.0) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_environment"
            ));
        }
        return Optional.empty();
    }

    private static boolean aligned(
            final CoreSkillFrame frame,
            final float targetYaw,
            final float targetPitch
    ) {
        return Math.abs(normalizeDegrees(
                    yaw(frame) - targetYaw
                )) <= ALIGNMENT_TOLERANCE_DEGREES
                && Math.abs(pitch(frame) - targetPitch)
                    <= ALIGNMENT_TOLERANCE_DEGREES;
    }

    private static float yaw(final CoreSkillFrame frame) {
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        )));
    }

    private static float pitch(final CoreSkillFrame frame) {
        return (float) -Math.toDegrees(Math.atan2(
                frame.lookDirection().y(),
                Math.hypot(
                        frame.lookDirection().x(),
                        frame.lookDirection().z()
                )
        ));
    }

    private static float normalizeDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private static double horizontalDistance(
            final PerceptionVec3 left,
            final PerceptionVec3 right
    ) {
        return Math.hypot(
                left.x() - right.x(),
                left.z() - right.z()
        );
    }

    private enum Phase {
        IDLE(false),
        COMPLETE_PENDING(true),
        EQUIP_FIRST(true),
        WAIT_FIRST_EQUIP(true),
        TRACE_FIRST(true),
        PREPARE_TRAVEL(true),
        TRAVELLING(true),
        ALIGN_SECOND(true),
        EQUIP_SECOND(true),
        WAIT_SECOND_EQUIP(true),
        TRACE_SECOND(true),
        COMPLETED(false),
        FAILED(false),
        CANCELLED(false);

        private final boolean active;

        Phase(final boolean active) {
            this.active = active;
        }

        boolean active() {
            return active;
        }
    }
}
