package dev.mcai.companion.skill;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Server-tick state machine for exactly one local atomic skill.
 *
 * <p>All public state-changing methods are serialized. A model decision can
 * only select a registered skill and typed parameters; the skill owns legal
 * 20 TPS player actions. No method waits for network, database, or checkpoint
 * persistence.</p>
 */
public final class SkillSupervisor implements AutoCloseable {
    private static final SkillFailure SUPERVISOR_CLOSED =
            SkillFailure.of("supervisor_closed");
    private static final SkillFailure DECISION_NOT_START =
            SkillFailure.of("decision_not_start_skill");
    private static final SkillFailure STALE_GOAL =
            SkillFailure.of("stale_goal_revision");
    private static final SkillFailure STALE_WORLD =
            SkillFailure.of("stale_world_revision");
    private static final SkillFailure SKILL_ALREADY_ACTIVE =
            SkillFailure.of("skill_already_active");
    private static final SkillFailure UNKNOWN_SKILL =
            SkillFailure.of("unknown_skill");
    private static final SkillFailure INVALID_ARGUMENTS =
            SkillFailure.of("invalid_skill_arguments");
    private static final SkillFailure PRECONDITION_EXCEPTION =
            SkillFailure.of("skill_precondition_exception");
    private static final SkillFailure START_EXCEPTION =
            SkillFailure.of("skill_start_exception");
    private static final SkillFailure TICK_EXCEPTION =
            SkillFailure.of("skill_tick_exception");
    private static final SkillFailure CHECKPOINT_EXCEPTION =
            SkillFailure.of("skill_checkpoint_exception");
    private static final SkillFailure RESULT_EXCEPTION =
            SkillFailure.of("skill_result_exception");
    private static final SkillFailure CANCEL_EXCEPTION =
            SkillFailure.of("skill_cancel_exception");
    private static final SkillFailure CHECKPOINT_PERSIST_FAILED =
            SkillFailure.of("checkpoint_persist_failed");
    private static final SkillFailure TICK_BUDGET_EXCEEDED =
            SkillFailure.of("tick_budget_exceeded");
    private static final SkillFailure SKILL_TIMEOUT =
            SkillFailure.of("skill_timeout");
    private static final SkillFailure SKILL_STALLED =
            SkillFailure.of("skill_stalled");
    private static final SkillFailure HARDCORE_RISK =
            SkillFailure.of("hardcore_risk_exceeded");
    private static final SkillFailure DISCONNECTED_RISK =
            SkillFailure.of("disconnected_risk_exceeded");
    private static final SkillFailure MODEL_DISCONNECTED =
            SkillFailure.of("model_disconnected");
    private static final SkillFailure MODE_CHANGED =
            SkillFailure.of("context_mode_changed");
    private static final SkillFailure NON_MONOTONIC_TICK =
            SkillFailure.of("non_monotonic_game_tick");
    private static final SkillFailure CLOCK_REGRESSED =
            SkillFailure.of("monotonic_clock_regressed");
    private static final SkillFailure INVALID_TICK_RESULT =
            SkillFailure.of("invalid_skill_tick_result");
    private static final SkillFailure INVALID_SKILL_RESULT =
            SkillFailure.of("invalid_skill_result");
    private static final SkillFailure NO_ACTIVE_SKILL =
            SkillFailure.of("no_active_skill");
    private static final SkillFailure BODY_SESSION_ENDED =
            SkillFailure.of("body_session_ended");

    private final SkillRegistry registry;
    private final SkillCheckpointSink checkpointSink;
    private final SkillRuntimePolicy policy;
    private final LongSupplier nanoTime;
    private final ExecutorService checkpointExecutor;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<SkillFailure> checkpointPersistenceFailure =
            new AtomicReference<>();
    private final AtomicReference<CheckpointSubmission> pendingCheckpoint =
            new AtomicReference<>();
    private final AtomicBoolean checkpointWriteInFlight = new AtomicBoolean();

    private Active<?> active;
    private State state = State.IDLE;
    private String skillName = "";
    private long boundGoalRevision;
    private long boundWorldRevision;
    private long executedTicks;
    private int consecutiveNoProgressTicks;
    private int consecutiveTickBudgetBreaches;
    private long checkpointSequence;
    private boolean cancelPending;
    private boolean disconnectedPending;
    /**
     * One-tick handoff marker for the emergency controller.  A completed
     * skill may leave a bounded hostile reacquisition cue behind; the runtime
     * consumes this edge before the next skill is admitted.
     */
    private boolean activeSkillEndedHandoff;
    private SkillResult terminalResult;
    private SkillFailure lastStartRejection;
    /** Last fair checkpoint payload retained for bounded diagnostics only. */
    private String lastCheckpointPayload;

    public SkillSupervisor(
            SkillRegistry registry,
            SkillCheckpointSink checkpointSink
    ) {
        this(registry, checkpointSink, SkillRuntimePolicy.DEFAULT);
    }

    public SkillSupervisor(
            SkillRegistry registry,
            SkillCheckpointSink checkpointSink,
            SkillRuntimePolicy policy
    ) {
        this(
                registry,
                checkpointSink,
                policy,
                System::nanoTime,
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    SkillSupervisor(
            SkillRegistry registry,
            SkillCheckpointSink checkpointSink,
            SkillRuntimePolicy policy,
            LongSupplier nanoTime,
            ExecutorService checkpointExecutor
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.checkpointSink = Objects.requireNonNull(checkpointSink, "checkpointSink");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.checkpointExecutor =
                Objects.requireNonNull(checkpointExecutor, "checkpointExecutor");
    }

    /**
     * Starts one registered skill from a still-current model decision.
     */
    public synchronized StartOutcome start(
            DecisionEnvelope decision,
            SkillContext context
    ) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(context, "context");
        if (closed.get()) {
            return rejectedStart(SUPERVISOR_CLOSED);
        }
        Optional<SkillFailure> decisionFailure = validateDecisionBinding(decision, context);
        if (decisionFailure.isPresent()) {
            return rejectedStart(decisionFailure.get());
        }
        if (active != null) {
            return rejectedStart(SKILL_ALREADY_ACTIVE);
        }

        Optional<SkillRegistry.Registration<?>> registration =
                registry.find(decision.skillName());
        if (registration.isEmpty()) {
            return rejectedStart(UNKNOWN_SKILL);
        }
        return startTyped(registration.get(), decision, context);
    }

    /**
     * Advances the active skill at most once for the supplied game tick.
     */
    public synchronized Snapshot tick(SkillContext context) {
        Objects.requireNonNull(context, "context");
        if (closed.get() || active == null) {
            return snapshot();
        }
        return tickTyped(active, context);
    }

    /**
     * Requests cancellation. The skill's cancel callback is invoked only at a
     * safe checkpoint; otherwise local ticks continue solely to reach one.
     */
    public synchronized MutationOutcome requestCancel(SkillContext context) {
        Objects.requireNonNull(context, "context");
        if (closed.get()) {
            return rejectedMutation(SUPERVISOR_CLOSED);
        }
        if (active == null) {
            return rejectedMutation(NO_ACTIVE_SKILL);
        }
        Optional<SkillFailure> bindingFailure = validateActiveContext(active, context, false);
        if (bindingFailure.isPresent()) {
            return rejectedMutation(bindingFailure.get());
        }
        cancelPending = true;
        state = State.CANCEL_PENDING;
        if (active.atSafeCheckpoint) {
            cancelAtCheckpoint(active, context, SkillResult.cancelled());
        }
        return new MutationOutcome(true, Optional.empty(), snapshot());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                state,
                skillName,
                boundGoalRevision,
                boundWorldRevision,
                executedTicks,
                consecutiveNoProgressTicks,
                consecutiveTickBudgetBreaches,
                checkpointSequence,
                cancelPending,
                disconnectedPending,
                Optional.ofNullable(terminalResult),
                Optional.ofNullable(checkpointPersistenceFailure.get()),
                Optional.ofNullable(lastStartRejection)
        );
    }

    /**
     * Returns the most recent checkpoint emitted by the active skill.  This
     * is intentionally diagnostic-only: callers cannot mutate or replay it,
     * and it is cleared whenever a new skill starts or the supervisor is
     * detached from its body session.
     */
    public synchronized Optional<String> lastCheckpointPayload() {
        return Optional.ofNullable(lastCheckpointPayload);
    }

    /**
     * Consumes the edge produced when an active skill leaves the supervisor.
     * The method is deliberately separate from {@link #snapshot()} so a
     * replayed status cannot clear an emergency cue twice.
     */
    public synchronized boolean consumeActiveSkillEndedHandoff() {
        final boolean ended = activeSkillEndedHandoff;
        activeSkillEndedHandoff = false;
        return ended;
    }

    /**
     * Clears goal-local planner feedback when orchestration moves to a new
     * goal revision. The rejection code contains no model-authored text, but
     * carrying it into an unrelated goal would still be misleading.
     */
    public synchronized void clearStartRejection() {
        lastStartRejection = null;
    }

    /**
     * Returns the active skill's narrowly scoped hostile-proximity ownership.
     * A faulty optional declaration fails closed to emergency ownership.
     */
    public synchronized boolean activeSkillManagesVisibleHostileProximity() {
        if (active == null || closed.get()) {
            return false;
        }
        try {
            return active.registration.skill()
                    .managesVisibleHostileProximity();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns the active skill's narrowly scoped physical-contact ownership.
     * A faulty optional declaration fails closed to emergency ownership.
     */
    public synchronized boolean activeSkillManagesPhysicalContactThreats() {
        if (active == null || closed.get()) {
            return false;
        }
        try {
            return active.registration.skill()
                    .managesPhysicalContactThreats();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns whether the active skill owns a bounded visible-projectile
     * response.  Faulty declarations fail closed to the emergency lane.
     */
    public synchronized boolean activeSkillManagesVisibleProjectileThreats() {
        if (active == null || closed.get()) {
            return false;
        }
        try {
            return active.registration.skill()
                    .managesVisibleProjectileThreats();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns whether the active skill explicitly owns a bounded
     * world/dimension transition. A faulty declaration fails closed, just as
     * the other optional skill capabilities do.
     */
    public synchronized boolean activeSkillAllowsWorldRevisionTransition() {
        if (active == null || closed.get()) {
            return false;
        }
        try {
            return active.registration.skill()
                    .allowsWorldRevisionTransition();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Irreversibly detaches an execution from a body session that no longer
     * exists. The caller must first release real player controls; invoking a
     * skill's ordinary cancel callback away from a safe checkpoint would be
     * unsafe and could target a replacement body.
     */
    public synchronized Snapshot abandonForSessionEnd() {
        if (closed.get()) {
            return snapshot();
        }
        pendingCheckpoint.set(null);
        lastStartRejection = null;
        if (active != null) {
            finish(SkillResult.safeIdle(BODY_SESSION_ENDED));
        }
        lastCheckpointPayload = null;
        return snapshot();
    }

    /**
     * Detaches an already-running skill when the verified model connection
     * disappears before the normal tick lane can reach its checkpoint.
     *
     * <p>This is intentionally different from {@link
     * #abandonForSessionEnd()}: the body session is still alive and may
     * resume a new goal after the model is verified again.  The runtime must
     * release all player inputs before calling this method; no skill cancel
     * callback is invoked because it may be bound to a stale model/world
     * decision.  The terminal result is explicit so the next healthy brain
     * tick can record the safe handoff instead of leaving an invisible
     * RUNNING skill behind.</p>
     */
    public synchronized Snapshot abandonForModelDisconnect() {
        if (closed.get()) {
            return snapshot();
        }
        pendingCheckpoint.set(null);
        lastStartRejection = null;
        if (active != null) {
            finish(SkillResult.safeIdle(MODEL_DISCONNECTED));
        }
        lastCheckpointPayload = null;
        return snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            if (active != null && active.atSafeCheckpoint) {
                cancelForClose(active);
            }
            active = null;
            state = State.CLOSED;
            cancelPending = false;
            disconnectedPending = false;
            lastStartRejection = null;
        }
        pendingCheckpoint.set(null);
        checkpointExecutor.shutdownNow();
    }

    private Optional<SkillFailure> validateDecisionBinding(
            DecisionEnvelope decision,
            SkillContext context
    ) {
        if (decision.decision() != DecisionKind.START_SKILL) {
            return Optional.of(DECISION_NOT_START);
        }
        if (decision.goalRevision() != context.goalRevision()) {
            return Optional.of(STALE_GOAL);
        }
        if (decision.observedWorldRevision() != context.worldRevision()) {
            return Optional.of(STALE_WORLD);
        }
        return Optional.empty();
    }

    private <P> StartOutcome startTyped(
            SkillRegistry.Registration<P> registration,
            DecisionEnvelope decision,
            SkillContext context
    ) {
        List<dev.mcai.companion.model.SkillArgument> arguments =
                List.copyOf(decision.typedArguments());
        Optional<SkillFailure> wireFailure = SkillRegistry.validateWireArguments(arguments);
        if (wireFailure.isPresent()) {
            return rejectedStart(wireFailure.get());
        }

        final SkillParameterResult<P> parsed;
        try {
            parsed = registration.parser().parse(arguments);
        } catch (RuntimeException exception) {
            return rejectedStart(INVALID_ARGUMENTS);
        }
        if (parsed == null || parsed.value().isEmpty()) {
            return rejectedStart(
                    parsed == null
                            ? INVALID_ARGUMENTS
                            : parsed.failure().orElse(INVALID_ARGUMENTS)
            );
        }
        P parameters = parsed.value().orElseThrow();

        if (!context.modelConnected()) {
            return rejectedStart(MODEL_DISCONNECTED);
        }
        if (context.hardcore()
                && context.riskScore() > hardcoreRiskThreshold(
                        registration,
                        context,
                        parameters
                )) {
            return rejectedStart(HARDCORE_RISK);
        }

        final Optional<SkillFailure> preconditionFailure;
        try {
            preconditionFailure = Objects.requireNonNull(
                    registration.skill().preconditions(context, parameters),
                    "skill.preconditions()"
            );
        } catch (Exception exception) {
            return rejectedStart(PRECONDITION_EXCEPTION);
        }
        if (preconditionFailure.isPresent()) {
            return rejectedStart(preconditionFailure.get());
        }

        try {
            registration.skill().start(context, parameters);
        } catch (Exception exception) {
            return rejectedStart(START_EXCEPTION);
        }

        long startedAt = nanoTime.getAsLong();
        active = new Active<>(
                registration,
                parameters,
                startedAt,
                context.gameTick(),
                context,
                true
        );
        state = State.RUNNING;
        skillName = registration.name();
        boundGoalRevision = context.goalRevision();
        boundWorldRevision = context.worldRevision();
        executedTicks = 0;
        consecutiveNoProgressTicks = 0;
        consecutiveTickBudgetBreaches = 0;
        checkpointSequence = 0;
        cancelPending = false;
        disconnectedPending = false;
        terminalResult = null;
        lastStartRejection = null;
        checkpointPersistenceFailure.set(null);
        lastCheckpointPayload = null;
        return new StartOutcome(true, Optional.empty(), snapshot());
    }

    private <P> Snapshot tickTyped(Active<P> execution, SkillContext context) {
        Optional<SkillFailure> bindingFailure = validateActiveContext(
                execution,
                context,
                true
        );
        if (bindingFailure.isPresent()) {
            finish(SkillResult.failed(bindingFailure.get()));
            return snapshot();
        }

        long beforeTick = nanoTime.getAsLong();
        long activeElapsed = beforeTick - execution.startedAtNanos;
        if (activeElapsed < 0) {
            finish(SkillResult.failed(CLOCK_REGRESSED));
            return snapshot();
        }
        if (activeElapsed >= policy.skillTimeout().toNanos()) {
            failAndCancelIfSafe(execution, context, SKILL_TIMEOUT);
            return snapshot();
        }

        if (context.hardcore()
                && context.riskScore() > hardcoreRiskThreshold(
                        execution.registration,
                        context,
                        execution.parameters
                )) {
            stopForSafety(execution, context, HARDCORE_RISK);
            return snapshot();
        }

        if (!context.modelConnected()) {
            disconnectedPending = true;
            state = State.CANCEL_PENDING;
        }
        if (disconnectedPending) {
            if (execution.atSafeCheckpoint) {
                cancelAtCheckpoint(
                        execution,
                        context,
                        SkillResult.safeIdle(MODEL_DISCONNECTED)
                );
                return snapshot();
            }
            if (context.riskScore() > policy.disconnectedRiskThreshold()) {
                stopForSafety(execution, context, DISCONNECTED_RISK);
                return snapshot();
            }
        }

        execution.lastGameTick = context.gameTick();
        execution.lastContext = context;
        execution.atSafeCheckpoint = false;

        final SkillTickResult tickResult;
        try {
            tickResult = execution.registration.skill().tick(context, execution.parameters);
        } catch (Exception exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Skill tick threw for {} type={} frames={}",
                    execution.registration.name(),
                    exception.getClass().getName(),
                    List.of(exception.getStackTrace()).stream()
                            .limit(12)
                            .map(StackTraceElement::toString)
                            .collect(Collectors.joining(" <- "))
            );
            finish(SkillResult.failed(TICK_EXCEPTION));
            return snapshot();
        }
        if (tickResult == null) {
            finish(SkillResult.failed(INVALID_TICK_RESULT));
            return snapshot();
        }

        SkillCheckpoint checkpoint = null;
        if (tickResult.safeCheckpoint()) {
            execution.atSafeCheckpoint = true;
            try {
                checkpoint = Objects.requireNonNull(
                        execution.registration.skill().checkpoint(
                                context,
                                execution.parameters
                        ),
                        "skill.checkpoint()"
                );
            } catch (Exception exception) {
                failAndCancelIfSafe(execution, context, CHECKPOINT_EXCEPTION);
                return snapshot();
            }
        }

        long afterTick = nanoTime.getAsLong();
        long tickElapsed = afterTick - beforeTick;
        if (tickElapsed < 0) {
            failAndCancelIfSafe(execution, context, CLOCK_REGRESSED);
            return snapshot();
        }

        executedTicks++;
        if (tickResult.madeProgress()) {
            consecutiveNoProgressTicks = 0;
        } else {
            consecutiveNoProgressTicks++;
        }
        if (tickElapsed > policy.tickBudget().toNanos()) {
            consecutiveTickBudgetBreaches++;
        } else {
            consecutiveTickBudgetBreaches = 0;
        }

        if (checkpoint != null) {
            checkpointSequence++;
            lastCheckpointPayload = checkpoint.payload();
            dispatchCheckpoint(new CheckpointSubmission(
                    skillName,
                    boundGoalRevision,
                    boundWorldRevision,
                    checkpointSequence,
                    context.gameTick(),
                    checkpoint
            ));
        }

        /*
         * nanoTime measures elapsed wall clock, not just skill CPU. A cold
         * class load, GC, or host scheduling pause can therefore make one
         * valid tick look slow. RuntimeTickMetrics continues to enforce the
         * release p95 target; this local kill switch is reserved for a
         * sustained overrun by the same active skill.
         */
        if (consecutiveTickBudgetBreaches
                >= policy.maxConsecutiveTickBudgetBreaches()) {
            failAndCancelIfSafe(execution, context, TICK_BUDGET_EXCEEDED);
            return snapshot();
        }
        if (tickResult.status() == SkillTickResult.Status.RUNNING
                && consecutiveNoProgressTicks
                >= policy.maxConsecutiveNoProgressTicks()) {
            failAndCancelIfSafe(execution, context, SKILL_STALLED);
            return snapshot();
        }
        if (tickResult.status() == SkillTickResult.Status.FAILED) {
            finish(SkillResult.failed(
                    tickResult.failure().orElse(INVALID_TICK_RESULT)
            ));
            return snapshot();
        }

        if (execution.atSafeCheckpoint && cancelPending) {
            cancelAtCheckpoint(execution, context, SkillResult.cancelled());
            return snapshot();
        }
        if (execution.atSafeCheckpoint && disconnectedPending) {
            cancelAtCheckpoint(
                    execution,
                    context,
                    SkillResult.safeIdle(MODEL_DISCONNECTED)
            );
            return snapshot();
        }

        if (tickResult.status() == SkillTickResult.Status.COMPLETED) {
            final SkillResult result;
            try {
                result = execution.registration.skill().result(
                        context,
                        execution.parameters
                );
            } catch (Exception exception) {
                finish(SkillResult.failed(RESULT_EXCEPTION));
                return snapshot();
            }
            if (result == null) {
                finish(SkillResult.failed(INVALID_SKILL_RESULT));
                return snapshot();
            }
            finish(result);
            return snapshot();
        }

        state = cancelPending || disconnectedPending
                ? State.CANCEL_PENDING
                : State.RUNNING;
        return snapshot();
    }

    private <P> double hardcoreRiskThreshold(
            final SkillRegistry.Registration<P> registration,
            final SkillContext context,
            final P parameters
    ) {
        try {
            final var override = registration.skill()
                    .hardcoreRiskThresholdOverride(
                            context,
                            parameters
                    );
            if (override == null || override.isEmpty()) {
                return policy.hardcoreRiskThreshold();
            }
            final double value = override.orElseThrow();
            if (!Double.isFinite(value)
                    || value < policy.hardcoreRiskThreshold()
                    || value > 1.0) {
                return policy.hardcoreRiskThreshold();
            }
            return value;
        } catch (RuntimeException exception) {
            return policy.hardcoreRiskThreshold();
        }
    }

    private Optional<SkillFailure> validateActiveContext(
            Active<?> execution,
            SkillContext context,
            boolean requireIncreasingTick
    ) {
        if (context.goalRevision() != boundGoalRevision) {
            return Optional.of(STALE_GOAL);
        }
        if (context.worldRevision() != boundWorldRevision) {
            return Optional.of(STALE_WORLD);
        }
        if (context.hardcore() != execution.lastContext.hardcore()) {
            return Optional.of(MODE_CHANGED);
        }
        if (requireIncreasingTick && context.gameTick() <= execution.lastGameTick) {
            return Optional.of(NON_MONOTONIC_TICK);
        }
        return Optional.empty();
    }

    private <P> void failAndCancelIfSafe(
            Active<P> execution,
            SkillContext context,
            SkillFailure failure
    ) {
        if (execution.atSafeCheckpoint) {
            try {
                execution.registration.skill().cancel(context, execution.parameters);
            } catch (Exception exception) {
                finish(SkillResult.failed(CANCEL_EXCEPTION));
                return;
            }
        }
        finish(SkillResult.failed(failure));
    }

    private <P> void stopForSafety(
            Active<P> execution,
            SkillContext context,
            SkillFailure reason
    ) {
        if (execution.atSafeCheckpoint) {
            try {
                execution.registration.skill().cancel(context, execution.parameters);
            } catch (Exception exception) {
                finish(SkillResult.failed(CANCEL_EXCEPTION));
                return;
            }
        }
        finish(SkillResult.safeIdle(reason));
    }

    private <P> void cancelAtCheckpoint(
            Active<P> execution,
            SkillContext context,
            SkillResult successfulCancellation
    ) {
        if (!execution.atSafeCheckpoint) {
            throw new IllegalStateException("Cancellation attempted away from a checkpoint");
        }
        try {
            execution.registration.skill().cancel(context, execution.parameters);
        } catch (Exception exception) {
            finish(SkillResult.failed(CANCEL_EXCEPTION));
            return;
        }
        finish(successfulCancellation);
    }

    private <P> void cancelForClose(Active<P> execution) {
        try {
            execution.registration.skill().cancel(
                    execution.lastContext,
                    execution.parameters
            );
        } catch (Exception ignored) {
            // close() cannot surface dynamic exception data.
        }
    }

    private void finish(SkillResult result) {
        terminalResult = Objects.requireNonNull(result, "result");
        state = switch (result.status()) {
            case COMPLETED -> State.COMPLETED;
            case FAILED -> State.FAILED;
            case CANCELLED -> State.CANCELLED;
            case SAFE_IDLE -> State.SAFE_IDLE;
        };
        activeSkillEndedHandoff = active != null;
        active = null;
        cancelPending = false;
        disconnectedPending = false;
    }

    private StartOutcome rejectedStart(SkillFailure failure) {
        lastStartRejection = Objects.requireNonNull(
                failure,
                "failure"
        );
        return new StartOutcome(false, Optional.of(failure), snapshot());
    }

    private MutationOutcome rejectedMutation(SkillFailure failure) {
        return new MutationOutcome(false, Optional.of(failure), snapshot());
    }

    private void dispatchCheckpoint(CheckpointSubmission submission) {
        if (closed.get()) {
            return;
        }
        pendingCheckpoint.set(submission);
        scheduleCheckpointWrite();
    }

    private void scheduleCheckpointWrite() {
        if (closed.get()
                || !checkpointWriteInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            checkpointExecutor.execute(this::beginCheckpointWrite);
        } catch (RejectedExecutionException exception) {
            checkpointWriteInFlight.set(false);
            pendingCheckpoint.set(null);
            checkpointPersistenceFailure.compareAndSet(
                    null,
                    CHECKPOINT_PERSIST_FAILED
            );
        }
    }

    private void beginCheckpointWrite() {
        CheckpointSubmission submission = pendingCheckpoint.getAndSet(null);
        if (submission == null || closed.get()) {
            finishCheckpointWrite(null);
            return;
        }

        final CompletionStage<Void> write;
        try {
            write = checkpointSink.save(
                    submission.skillName(),
                    submission.goalRevision(),
                    submission.worldRevision(),
                    submission.sequence(),
                    submission.gameTick(),
                    submission.checkpoint()
            );
        } catch (RuntimeException exception) {
            finishCheckpointWrite(exception);
            return;
        }
        if (write == null) {
            finishCheckpointWrite(new IllegalStateException("Checkpoint sink returned null"));
            return;
        }
        write.whenComplete((ignored, throwable) -> finishCheckpointWrite(throwable));
    }

    private void finishCheckpointWrite(Throwable throwable) {
        if (throwable != null) {
            checkpointPersistenceFailure.compareAndSet(
                    null,
                    CHECKPOINT_PERSIST_FAILED
            );
        }
        checkpointWriteInFlight.set(false);
        if (pendingCheckpoint.get() != null) {
            scheduleCheckpointWrite();
        }
    }

    public enum State {
        IDLE,
        RUNNING,
        CANCEL_PENDING,
        COMPLETED,
        FAILED,
        CANCELLED,
        SAFE_IDLE,
        CLOSED
    }

    public record Snapshot(
            State state,
            String skillName,
            long boundGoalRevision,
            long boundWorldRevision,
            long executedTicks,
            int consecutiveNoProgressTicks,
            int consecutiveTickBudgetBreaches,
            long checkpointSequence,
            boolean cancelPending,
            boolean disconnectedPending,
            Optional<SkillResult> terminalResult,
            Optional<SkillFailure> checkpointPersistenceFailure,
            Optional<SkillFailure> lastStartRejection
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(skillName, "skillName");
            Objects.requireNonNull(terminalResult, "terminalResult");
            Objects.requireNonNull(
                    checkpointPersistenceFailure,
                    "checkpointPersistenceFailure"
            );
            Objects.requireNonNull(
                    lastStartRejection,
                    "lastStartRejection"
            );
        }
    }

    public record StartOutcome(
            boolean accepted,
            Optional<SkillFailure> failure,
            Snapshot snapshot
    ) {
        public StartOutcome {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(snapshot, "snapshot");
            if (accepted == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted starts cannot contain a failure"
                );
            }
        }
    }

    public record MutationOutcome(
            boolean accepted,
            Optional<SkillFailure> failure,
            Snapshot snapshot
    ) {
        public MutationOutcome {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(snapshot, "snapshot");
            if (accepted == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted mutations cannot contain a failure"
                );
            }
        }
    }

    private static final class Active<P> {
        private final SkillRegistry.Registration<P> registration;
        private final P parameters;
        private final long startedAtNanos;
        private long lastGameTick;
        private SkillContext lastContext;
        private boolean atSafeCheckpoint;

        private Active(
                SkillRegistry.Registration<P> registration,
                P parameters,
                long startedAtNanos,
                long lastGameTick,
                SkillContext lastContext,
                boolean atSafeCheckpoint
        ) {
            this.registration = registration;
            this.parameters = parameters;
            this.startedAtNanos = startedAtNanos;
            this.lastGameTick = lastGameTick;
            this.lastContext = lastContext;
            this.atSafeCheckpoint = atSafeCheckpoint;
        }
    }

    private record CheckpointSubmission(
            String skillName,
            long goalRevision,
            long worldRevision,
            long sequence,
            long gameTick,
            SkillCheckpoint checkpoint
    ) {}
}
