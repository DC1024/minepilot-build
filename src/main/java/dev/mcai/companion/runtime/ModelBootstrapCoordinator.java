package dev.mcai.companion.runtime;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.evaluation.EvaluationVictoryTracker;
import dev.mcai.companion.evaluation.EvaluationRoute;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

/**
 * Bridges a persisted/running goal to one verified model gateway without
 * blocking the server thread.
 *
 * <p>The model probe is single-flight in {@link ModelRuntime}. Its completion
 * is only deposited in a bounded mailbox; all world, goal, body and evaluation
 * mutations happen later from {@link #tick()} on the server thread. A failed
 * probe is never retried automatically, so authentication, rate-limit and
 * provider failures cannot cause duplicate billable requests.</p>
 */
public final class ModelBootstrapCoordinator implements AutoCloseable {
    /**
     * A locked desktop keychain/Secret Service can be unavailable for a
     * short period while the world is already running.  Retrying the
     * credential unlock is local-only; it never retries a provider request
     * after an authentication, billing, or rate-limit failure.
     */
    private static final long CREDENTIAL_RESTORE_RETRY_TICKS = 100L;

    private final Backend backend;
    private final AtomicReference<ProbeCompletion> mailbox =
        new AtomicReference<>();

    private PendingProbe pending;
    private long sequence;
    private long attemptedRunningGoalRevision = -1L;
    private final long eligibleOrdinaryRestoreRevision;
    private boolean ordinaryStartupRestoreRequested;
    private boolean ordinaryStartupRestoreAttempted;
    private long nextOrdinaryCredentialRetryTick;
    private boolean ordinaryCredentialWaitAnnounced;
    private boolean closed;

    public ModelBootstrapCoordinator(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final GoalCoordinator goals,
        final ModelRuntime model
    ) {
        this(new MinecraftBackend(server, worldData, goals, model));
    }

    ModelBootstrapCoordinator(final Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        final GoalSnapshot startupGoal = backend.goal();
        eligibleOrdinaryRestoreRevision =
            startupGoal.status() == GoalStatus.RUNNING
                    && !startupGoal.externalWritesLocked()
                ? startupGoal.revision()
                : -1L;
    }

    /**
     * Requests one ordinary world-start credential restore attempt.
     *
     * <p>The request is only armed here. The actual probe remains centralized
     * in {@link #tick()}, after the persistent goal/evaluation lock has been
     * inspected on the server thread. A locked Hardcore evaluation therefore
     * continues through the stricter restored-evaluation path and can never
     * be replaced by this ordinary startup flow.</p>
     */
    public synchronized void requestOrdinaryStartupRestore() {
        requireServerThread();
        if (!closed) {
            ordinaryStartupRestoreRequested = true;
        }
    }

    /**
     * Handles the sole explicit evaluation-start command. When the configured
     * profile has not yet been verified in this server process, the same
     * command starts one asynchronous capability probe and automatically
     * finishes the start transaction after a successful completion.
     */
    public synchronized StartRequest requestEvaluationStart() {
        return requestEvaluationStart(EvaluationRoute.COMPLETION);
    }

    public synchronized StartRequest requestEvaluationStart(
            final EvaluationRoute route
    ) {
        Objects.requireNonNull(route, "route");
        requireServerThread();
        if (closed) {
            return StartRequest.rejected("bootstrap_closed");
        }
        if (pending != null) {
            return StartRequest.rejected("model_probe_in_flight");
        }
        final String preflight = backend.freshEvaluationPreflight();
        if (!preflight.isEmpty()) {
            return StartRequest.rejected(preflight);
        }
        if (backend.modelGatewayReady()) {
            final MutationResult started =
                backend.startFreshEvaluation(route.initialGoal());
            return started.accepted()
                ? StartRequest.startedRequest()
                : StartRequest.rejected(started.code());
        }
        if (!backend.modelEndpointConfigured()) {
            return StartRequest.rejected(
                "model_endpoint_not_configured"
            );
        }

        beginProbe(
                ProbeMode.FRESH_EVALUATION,
                0L,
                route.initialGoal()
        );
        return StartRequest.preparing();
    }

    /**
     * Advances asynchronous setup. A locked evaluation restart and an
     * ordinary goal already persisted as RUNNING at construction get at most
     * one automatic probe attempt; runtime-created ordinary goals never do.
     */
    public synchronized void tick() {
        requireServerThread();
        if (closed) {
            return;
        }
        final ProbeCompletion completion = mailbox.getAndSet(null);
        if (completion != null) {
            applyCompletion(completion);
        }
        if (pending != null) {
            return;
        }

        final GoalSnapshot goal = backend.goal();
        final boolean evaluationRestore =
            goal.status() == GoalStatus.RUNNING
                && goal.externalWritesLocked()
                && goal.source()
                    == GoalSource.HARDCORE_EVALUATION;
        final boolean ordinaryRestartRestore =
            goal.status() == GoalStatus.RUNNING
                && !goal.externalWritesLocked()
                && goal.revision()
                    == eligibleOrdinaryRestoreRevision;
        /*
         * Runtime-created chat, MCP, RECOVERY and GameTest goals must never
         * trigger implicit provider I/O or have their revision rewritten.
         * Only a goal that was already persisted as RUNNING when this
         * coordinator was constructed receives one ordinary restart probe.
         */
        if (evaluationRestore || ordinaryRestartRestore) {
            if (backend.modelGatewayReady()) {
                if (evaluationRestore
                        && !backend.modelEvaluationFrozen()) {
                    final MutationResult frozen =
                        backend.freezeRestoredEvaluationModel();
                    if (!frozen.accepted()) {
                        failRunningGoal(
                            goal,
                            "evaluation_model_restore_"
                                + frozen.code()
                        );
                    }
                }
                return;
            }
            if (attemptedRunningGoalRevision == goal.revision()) {
                return;
            }
            attemptedRunningGoalRevision = goal.revision();
            if (!backend.modelEndpointConfigured()) {
                if (evaluationRestore) {
                    failRunningGoal(
                        goal,
                        "model_bootstrap_invalid_configuration"
                    );
                } else {
                    backend.announce(
                        "model_restore_invalid_configuration"
                    );
                }
                return;
            }
            beginProbe(
                evaluationRestore
                    ? ProbeMode.RESTORED_EVALUATION
                    : ProbeMode.RESTORED_RUNNING_GOAL,
                goal.revision(),
                ""
            );
            return;
        }

        if (!ordinaryStartupRestoreRequested
                || goal.externalWritesLocked()) {
            return;
        }
        if (ordinaryStartupRestoreAttempted) {
            return;
        }
        if (serverTickBefore(nextOrdinaryCredentialRetryTick)) {
            return;
        }
        ordinaryStartupRestoreAttempted = true;
        if (backend.modelGatewayReady()) {
            backend.announce("startup_model_restored");
            return;
        }
        if (!backend.modelEndpointConfigured()) {
            backend.announce(
                    "startup_model_unavailable_invalid_configuration"
            );
            return;
        }
        beginProbe(
            ProbeMode.ORDINARY_STARTUP,
            0L,
            ""
        );
    }

    private void beginProbe(
        final ProbeMode mode,
        final long goalRevision,
        final String freshEvaluationGoal
    ) {
        final PendingProbe request = new PendingProbe(
            ++sequence,
            mode,
            goalRevision,
            freshEvaluationGoal
        );
        pending = request;
        final CompletionStage<CapabilityProbeOutcome> stage;
        try {
            stage = Objects.requireNonNull(
                backend.probeModel(),
                "probeModel()"
            );
        } catch (RuntimeException exception) {
            mailbox.compareAndSet(
                null,
                ProbeCompletion.failed(request.sequence())
            );
            return;
        }
        try {
            stage.whenComplete((outcome, throwable) ->
                mailbox.compareAndSet(
                    null,
                    new ProbeCompletion(
                        request.sequence(),
                        outcome,
                        throwable != null
                    )
                )
            );
        } catch (RuntimeException exception) {
            mailbox.compareAndSet(
                null,
                ProbeCompletion.failed(request.sequence())
            );
        }
    }

    private void applyCompletion(
        final ProbeCompletion completion
    ) {
        if (pending == null
                || pending.sequence() != completion.sequence()) {
            backend.announce("model_bootstrap_stale_completion");
            return;
        }
        final PendingProbe completed = pending;
        pending = null;

        if (completion.transportFailure()
                || !(completion.outcome()
                    instanceof CapabilityProbeOutcome.Supported)
                || !backend.modelGatewayReady()) {
            final String failureCode = probeFailureCode(completion);
            if (completed.mode() == ProbeMode.FRESH_EVALUATION) {
                backend.announce(
                    "evaluation_start_probe_failed_" + failureCode
                );
                return;
            }
            if (completed.mode() == ProbeMode.ORDINARY_STARTUP) {
                if (isCredentialUnavailable(completion)) {
                    ordinaryStartupRestoreAttempted = false;
                    nextOrdinaryCredentialRetryTick = saturatingAdd(
                            backend.serverTick(),
                            CREDENTIAL_RESTORE_RETRY_TICKS
                    );
                    if (!ordinaryCredentialWaitAnnounced) {
                        ordinaryCredentialWaitAnnounced = true;
                        backend.announce(
                                "startup_model_waiting_for_credential"
                        );
                    }
                } else {
                    backend.announce(
                            "startup_model_unavailable_" + failureCode
                    );
                }
                return;
            }
            final GoalSnapshot current = backend.goal();
            if (sameRunningGoal(current, completed.goalRevision())) {
                if (completed.mode()
                        == ProbeMode.RESTORED_EVALUATION) {
                    failRunningGoal(
                        current,
                        "evaluation_model_restore_" + failureCode
                    );
                } else {
                    backend.announce(
                        "model_restore_" + failureCode
                    );
                }
            }
            return;
        }

        switch (completed.mode()) {
            case FRESH_EVALUATION -> {
                final String preflight =
                    backend.freshEvaluationPreflight();
                if (!preflight.isEmpty()) {
                    backend.announce(
                        "evaluation_start_revalidation_" + preflight
                    );
                    return;
                }
                final MutationResult started =
                    backend.startFreshEvaluation(
                            completed.freshEvaluationGoal()
                    );
                backend.announce(
                    started.accepted()
                        ? "evaluation_started"
                        : "evaluation_start_failed_" + started.code()
                );
            }
            case RESTORED_EVALUATION -> {
                final GoalSnapshot current = backend.goal();
                if (!sameRunningGoal(
                        current,
                        completed.goalRevision()
                    )
                        || !current.externalWritesLocked()) {
                    return;
                }
                final MutationResult frozen =
                    backend.freezeRestoredEvaluationModel();
                if (!frozen.accepted()) {
                    failRunningGoal(
                        current,
                        "evaluation_model_restore_"
                            + frozen.code()
                    );
                    return;
                }
                backend.announce("evaluation_model_restored");
            }
            case RESTORED_RUNNING_GOAL -> {
                // The next runtime tick may now issue the first decision.
            }
            case ORDINARY_STARTUP -> {
                ordinaryStartupRestoreAttempted = true;
                ordinaryCredentialWaitAnnounced = false;
                backend.announce("startup_model_restored");
            }
        }
    }

    private boolean isCredentialUnavailable(
            final ProbeCompletion completion
    ) {
        if (completion.transportFailure()
                || !(completion.outcome()
                    instanceof CapabilityProbeOutcome.Failure failure)
                || failure.error().kind()
                    != dev.mcai.companion.model.ModelFailureKind
                        .INVALID_CONFIGURATION) {
            return false;
        }
        return !backend.modelCredentialAvailable();
    }

    private boolean serverTickBefore(final long target) {
        return Long.compareUnsigned(backend.serverTick(), target) < 0;
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void failRunningGoal(
        final GoalSnapshot expected,
        final String requestedCode
    ) {
        final String code = boundedCode(requestedCode);
        final GoalSnapshot current = backend.goal();
        if (sameRunningGoal(current, expected.revision())) {
            backend.markRunningGoalSafeIdle(code);
        }
        backend.announce(code);
    }

    private static boolean sameRunningGoal(
        final GoalSnapshot goal,
        final long revision
    ) {
        return goal.revision() == revision
            && goal.status() == GoalStatus.RUNNING;
    }

    private static String probeFailureCode(
        final ProbeCompletion completion
    ) {
        if (completion.transportFailure()
                || completion.outcome() == null) {
            return "local_failure";
        }
        if (completion.outcome()
                instanceof CapabilityProbeOutcome.Failure failure) {
            return failure.error()
                .kind()
                .name()
                .toLowerCase(Locale.ROOT);
        }
        return "gateway_not_ready";
    }

    private static String boundedCode(final String raw) {
        final String normalized = raw == null
            ? "model_bootstrap_failure"
            : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        return normalized.substring(
            0,
            Math.min(normalized.length(), 64)
        );
    }

    private void requireServerThread() {
        if (!backend.isServerThread()) {
            throw new IllegalStateException(
                "Model bootstrap must run on the server thread"
            );
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending = null;
        mailbox.set(null);
    }

    public record StartRequest(
        boolean accepted,
        boolean started,
        String code
    ) {
        public StartRequest {
            Objects.requireNonNull(code, "code");
            if (started && !accepted) {
                throw new IllegalArgumentException(
                    "A started request must be accepted"
                );
            }
        }

        private static StartRequest preparing() {
            return new StartRequest(
                true,
                false,
                "model_probe_started"
            );
        }

        private static StartRequest startedRequest() {
            return new StartRequest(true, true, "evaluation_started");
        }

        private static StartRequest rejected(final String code) {
            return new StartRequest(false, false, code);
        }
    }

    interface Backend {
        boolean isServerThread();

        GoalSnapshot goal();

        String freshEvaluationPreflight();

        boolean modelEndpointConfigured();

        boolean modelGatewayReady();

        /**
         * Returns only whether a credential is already available in process
         * memory or an injected source.  Implementations must not expose the
         * secret or persist it in a world/evidence file.
         */
        default boolean modelCredentialAvailable() {
            return true;
        }

        /** Current unsigned server tick, used only for local retry timing. */
        default long serverTick() {
            return 0L;
        }

        boolean modelEvaluationFrozen();

        CompletionStage<CapabilityProbeOutcome> probeModel();

        MutationResult startFreshEvaluation(String initialGoal);

        MutationResult freezeRestoredEvaluationModel();

        void markRunningGoalSafeIdle(String code);

        void announce(String code);
    }

    record MutationResult(boolean accepted, String code) {
        MutationResult {
            Objects.requireNonNull(code, "code");
        }

        static MutationResult success() {
            return new MutationResult(true, "accepted");
        }

        static MutationResult rejected(final String code) {
            return new MutationResult(false, code);
        }
    }

    private enum ProbeMode {
        FRESH_EVALUATION,
        RESTORED_RUNNING_GOAL,
        RESTORED_EVALUATION,
        ORDINARY_STARTUP
    }

    private record PendingProbe(
        long sequence,
        ProbeMode mode,
        long goalRevision,
        String freshEvaluationGoal
    ) {
        private PendingProbe {
            Objects.requireNonNull(
                    freshEvaluationGoal,
                    "freshEvaluationGoal"
            );
            if (mode == ProbeMode.FRESH_EVALUATION
                    != !freshEvaluationGoal.isEmpty()) {
                throw new IllegalArgumentException(
                        "Fresh evaluation goal binding is invalid"
                );
            }
        }
    }

    private record ProbeCompletion(
        long sequence,
        CapabilityProbeOutcome outcome,
        boolean transportFailure
    ) {
        private static ProbeCompletion failed(
            final long sequence
        ) {
            return new ProbeCompletion(sequence, null, true);
        }
    }

    private static final class MinecraftBackend implements Backend {
        private final MinecraftServer server;
        private final CompanionWorldData worldData;
        private final GoalCoordinator goals;
        private final ModelRuntime model;

        private MinecraftBackend(
            final MinecraftServer server,
            final CompanionWorldData worldData,
            final GoalCoordinator goals,
            final ModelRuntime model
        ) {
            this.server = Objects.requireNonNull(server, "server");
            this.worldData = Objects.requireNonNull(
                worldData,
                "worldData"
            );
            this.goals = Objects.requireNonNull(goals, "goals");
            this.model = Objects.requireNonNull(model, "model");
        }

        @Override
        public boolean isServerThread() {
            return server.isSameThread();
        }

        @Override
        public GoalSnapshot goal() {
            return goals.snapshot();
        }

        @Override
        public String freshEvaluationPreflight() {
            if (!EvaluationVictoryTracker.isTrueHardcoreWorld(server)) {
                return "hardcore_rules_required";
            }
            final boolean nonSpectatorObserver =
                server.getPlayerList().getPlayers().stream()
                    .anyMatch(player ->
                        !worldData.companionUuid().equals(
                            player.getUUID()
                        )
                            && player.gameMode
                                .getGameModeForPlayer()
                                != GameType.SPECTATOR
                    );
            if (nonSpectatorObserver) {
                return "spectator_observers_required";
            }
            final var body = AiPlayerManager.status(server);
            if (body.state() != SessionState.ABSENT) {
                return "fresh_body_required";
            }
            final GoalSnapshot goal = goals.snapshot();
            if (worldData.bodyEverSpawned()
                    || worldData.hardcoreDead()
                    || goal.revision() != 0L
                    || goal.status() != GoalStatus.IDLE
                    || goal.externalWritesLocked()
                    || worldData.evaluationContaminated()
                    || !worldData.evaluationAuditFresh()) {
                return "fresh_world_required";
            }
            return "";
        }

        @Override
        public boolean modelEndpointConfigured() {
            return model.snapshot().endpointConfigured();
        }

        @Override
        public boolean modelGatewayReady() {
            return model.snapshot().gatewayReady();
        }

        @Override
        public boolean modelCredentialAvailable() {
            return model.snapshot().credentialAvailable();
        }

        @Override
        public long serverTick() {
            return Integer.toUnsignedLong(server.getTickCount());
        }

        @Override
        public boolean modelEvaluationFrozen() {
            return model.snapshot().evaluationModelFrozen();
        }

        @Override
        public CompletionStage<CapabilityProbeOutcome> probeModel() {
            return model.prepareConfiguredProfile();
        }

        @Override
        public MutationResult startFreshEvaluation(
                final String initialGoal
        ) {
            final String preflight = freshEvaluationPreflight();
            if (!preflight.isEmpty()) {
                return MutationResult.rejected(preflight);
            }
            final ModelRuntime.EvaluationFreezeAttempt freezeAttempt =
                model.freezeForEvaluation();
            if (!freezeAttempt.acquired()) {
                return MutationResult.rejected(freezeAttempt.code());
            }
            try (var freeze =
                    freezeAttempt.freeze().orElseThrow()) {
                /*
                 * Resolve and validate the non-secret profile before any
                 * world mutation. PendingPlayerSpawn cannot complete until a
                 * later server tick, after the goal and audit lock below.
                 */
                final ModelRuntime.EvaluationProfile profile =
                    model.profileForEvaluation();
                final var spawn = AiPlayerManager.requestSpawn(server);
                if (!spawn.accepted()) {
                    return MutationResult.rejected(spawn.code());
                }
                final var goal = goals.startHardcoreEvaluation(
                    initialGoal
                );
                if (!goal.accepted()) {
                    AiPlayerManager.requestRemove(server);
                    return MutationResult.rejected(goal.code());
                }
                worldData.beginEvaluation(
                    server.overworld().getGameTime(),
                    profile.baseUrl(),
                    profile.modelName()
                );
                freeze.commit();
                return MutationResult.success();
            }
        }

        @Override
        public MutationResult freezeRestoredEvaluationModel() {
            if (!worldData.evaluationLocked()) {
                return MutationResult.rejected(
                    "evaluation_lock_missing"
                );
            }
            if (model.snapshot().evaluationModelFrozen()) {
                return MutationResult.success();
            }
            final ModelRuntime.EvaluationFreezeAttempt attempt =
                model.freezeForEvaluation();
            if (!attempt.acquired()) {
                return MutationResult.rejected(attempt.code());
            }
            final var freeze = attempt.freeze().orElseThrow();
            freeze.commit();
            freeze.close();
            return MutationResult.success();
        }

        @Override
        public void markRunningGoalSafeIdle(final String code) {
            final GoalSnapshot goal = goals.snapshot();
            if (goal.status() == GoalStatus.RUNNING) {
                goals.markTerminal(GoalStatus.SAFE_IDLE, code);
            }
        }

        @Override
        public void announce(final String code) {
            final String message = startupStatusMessage(code);
            server.getPlayerList().broadcastSystemMessage(
                Component.literal("[AI] " + message),
                false
            );
            MinecraftAiCompanion.LOGGER.info(
                "Companion model bootstrap event: {}",
                code
            );
        }

        private static String startupStatusMessage(final String code) {
            final String safeCode = boundedCode(code);
            return switch (safeCode) {
                case "evaluation_started" ->
                    "极限评测已在模型能力验证成功后开始；聊天、MCP 写入、标点和模型配置已锁定。";
                case "evaluation_model_restored" ->
                    "极限评测的锁定模型配置已恢复，自动控制正在继续。";
                case "startup_model_restored" ->
                    "已恢复保存的模型配置，AI 陪玩控制已就绪。";
                case "startup_model_waiting_for_credential" ->
                    "没有找到可恢复的 API Key；AI 身体仍在世界中，但不会执行模型动作。请在 AI 陪玩设置中保存并验证 API Key。";
                case "startup_model_unavailable_invalid_configuration",
                        "model_restore_invalid_configuration" ->
                    "模型配置不完整；AI 身体仍在世界中，但不会执行模型动作。请在 AI 陪玩设置中检查 Base URL、模型名和 API Key。";
                default -> {
                    if (safeCode.startsWith("startup_model_unavailable_authentication")
                            || safeCode.startsWith("model_restore_authentication")) {
                        yield "模型 API Key 未通过验证；AI 身体仍在世界中，但我已暂停自动动作，请重新保存并验证 API Key。";
                    }
                    if (safeCode.startsWith("startup_model_unavailable_")) {
                        yield "模型验证失败；AI 身体仍在世界中，但我已暂停自动动作，请在 AI 陪玩设置中检查模型服务。";
                    }
                    if (safeCode.startsWith("model_restore_")) {
                        yield "保存的模型配置恢复失败；AI 身体仍在世界中，但我已暂停自动动作，请重新验证配置。";
                    }
                    yield "模型启动已安全停止（" + safeCode
                        + "）；没有执行自动重试或供应商回退。";
                }
            };
        }
    }
}
