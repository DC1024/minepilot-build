package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.evaluation.EvaluationRoute;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ProviderCapabilities;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class ModelBootstrapCoordinatorTest {
    @Test
    void oneEvaluationCommandProbesOnceThenStartsAfterServerTickRevalidation() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);

        final var requested = coordinator.requestEvaluationStart();
        assertTrue(requested.accepted());
        assertFalse(requested.started());
        assertEquals("model_probe_started", requested.code());
        assertEquals(1, backend.probeCalls);
        assertEquals(
            "model_probe_in_flight",
            coordinator.requestEvaluationStart().code()
        );

        for (int tick = 0; tick < 20; tick++) {
            coordinator.tick();
        }
        assertEquals(1, backend.probeCalls);
        assertEquals(0, backend.startCalls);

        backend.completeSupported();
        assertEquals(0, backend.startCalls);
        coordinator.tick();

        assertEquals(1, backend.startCalls);
        assertTrue(backend.goal.externalWritesLocked());
        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertEquals(List.of("evaluation_started"), backend.announcements);
    }

    @Test
    void asynchronousStartKeepsTheSelectedFoundationRoute() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
                new ModelBootstrapCoordinator(backend);

        assertTrue(coordinator.requestEvaluationStart(
                EvaluationRoute.FOUNDATION
        ).accepted());
        backend.completeSupported();
        coordinator.tick();

        assertEquals(
                dev.mcai.companion.control.GoalCoordinator
                        .HARDCORE_FOUNDATION_GOAL,
                backend.startedGoal
        );
    }

    @Test
    void asyncEvaluationStartRechecksEveryFreshnessConditionBeforeMutation() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);
        assertTrue(coordinator.requestEvaluationStart().accepted());

        backend.freshPreflight = "spectator_observers_required";
        backend.completeSupported();
        coordinator.tick();

        assertEquals(0, backend.startCalls);
        assertEquals(GoalStatus.IDLE, backend.goal.status());
        assertEquals(
            List.of(
                "evaluation_start_revalidation_"
                    + "spectator_observers_required"
            ),
            backend.announcements
        );
    }

    @Test
    void authenticationFailureDoesNotRetryOrStartAnEvaluation() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);
        assertTrue(coordinator.requestEvaluationStart().accepted());

        backend.completeFailure(ModelFailureKind.AUTHENTICATION);
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(0, backend.startCalls);
        assertEquals(GoalStatus.IDLE, backend.goal.status());
        assertEquals(
            List.of(
                "evaluation_start_probe_failed_authentication"
            ),
            backend.announcements
        );
    }

    @Test
    void ordinaryWorldStartupRestoresSavedModelExactlyOnce() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
                new ModelBootstrapCoordinator(backend);

        coordinator.requestOrdinaryStartupRestore();
        coordinator.tick();
        coordinator.tick();
        assertEquals(1, backend.probeCalls);
        assertEquals(GoalStatus.IDLE, backend.goal.status());

        backend.completeSupported();
        coordinator.tick();
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(
                List.of("startup_model_restored"),
                backend.announcements
        );
        assertEquals(GoalStatus.IDLE, backend.goal.status());
    }

    @Test
    void ordinaryStartupFailureNeverRetriesOrChangesTheGoal() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
                new ModelBootstrapCoordinator(backend);

        coordinator.requestOrdinaryStartupRestore();
        coordinator.tick();
        backend.completeFailure(ModelFailureKind.AUTHENTICATION);
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(GoalStatus.IDLE, backend.goal.status());
        assertEquals(
                List.of(
                    "startup_model_unavailable_authentication"
                ),
                backend.announcements
        );
    }

    @Test
    void ordinaryStartupRetriesOnlyLocalCredentialRestore() {
        final FakeBackend backend = new FakeBackend();
        backend.credentialAvailable = false;
        final ModelBootstrapCoordinator coordinator =
                new ModelBootstrapCoordinator(backend);

        coordinator.requestOrdinaryStartupRestore();
        coordinator.tick();
        assertEquals(1, backend.probeCalls);

        backend.completeFailure(ModelFailureKind.INVALID_CONFIGURATION);
        coordinator.tick();
        assertEquals(
                List.of("startup_model_waiting_for_credential"),
                backend.announcements
        );

        for (int tick = 0; tick < 100; tick++) {
            backend.serverTick = tick;
            coordinator.tick();
        }
        assertEquals(1, backend.probeCalls);

        backend.serverTick = 100L;
        backend.credentialAvailable = true;
        coordinator.tick();
        assertEquals(2, backend.probeCalls);
        backend.completeSupported();
        coordinator.tick();

        assertEquals(
                List.of(
                    "startup_model_waiting_for_credential",
                    "startup_model_restored"
                ),
                backend.announcements
        );
    }

    @Test
    void ordinaryStartupRequestCannotBypassLockedEvaluationRestore() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(3L, true);
        final ModelBootstrapCoordinator coordinator =
                new ModelBootstrapCoordinator(backend);

        coordinator.requestOrdinaryStartupRestore();
        coordinator.tick();
        assertEquals(1, backend.probeCalls);

        backend.completeSupported();
        coordinator.tick();
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(1, backend.freezeCalls);
        assertTrue(backend.modelFrozen);
        assertTrue(backend.goal.externalWritesLocked());
        assertEquals(
                List.of("evaluation_model_restored"),
                backend.announcements
        );
    }

    @Test
    void persistedOrdinaryRunningGoalGetsOneRestartProbe() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(4L, false);
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);

        coordinator.tick();
        coordinator.tick();
        assertEquals(1, backend.probeCalls);

        backend.completeSupported();
        coordinator.tick();

        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertEquals(0, backend.freezeCalls);
        assertEquals(1, backend.probeCalls);
    }

    @Test
    void runtimeCreatedRecoveryGoalIsUntouchedAndNeverProbed() {
        final FakeBackend backend = new FakeBackend();
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);
        backend.goal = runningGoal(
            1L,
            GoalSource.RECOVERY,
            false
        );

        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(0, backend.probeCalls);
        assertEquals(1L, backend.goal.revision());
        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertTrue(backend.announcements.isEmpty());
    }

    @Test
    void failedOrdinaryRestartProbeKeepsGoalRevisionForExplicitRetry() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(9L, false);
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);

        coordinator.tick();
        backend.completeFailure(ModelFailureKind.MALFORMED_RESPONSE);
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(9L, backend.goal.revision());
        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertFalse(backend.goal.externalWritesLocked());
        assertEquals(
            List.of("model_restore_malformed_response"),
            backend.announcements
        );
    }

    @Test
    void lockedEvaluationRestartRefreezesProfileBeforeControlResumes() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(1L, true);
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);

        coordinator.tick();
        assertEquals(1, backend.probeCalls);
        backend.completeSupported();
        coordinator.tick();

        assertEquals(1, backend.freezeCalls);
        assertTrue(backend.modelFrozen);
        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertEquals(
            List.of("evaluation_model_restored"),
            backend.announcements
        );
    }

    @Test
    void missingCredentialOnRestartEndsLockedGoalSafelyWithoutUnlockOrRetry() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(7L, true);
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);

        coordinator.tick();
        backend.completeFailure(ModelFailureKind.INVALID_CONFIGURATION);
        for (int tick = 0; tick < 100; tick++) {
            coordinator.tick();
        }

        assertEquals(1, backend.probeCalls);
        assertEquals(GoalStatus.SAFE_IDLE, backend.goal.status());
        assertTrue(backend.goal.externalWritesLocked());
        assertEquals(
            "evaluation_model_restore_invalid_configuration",
            backend.goal.detailCode()
        );
    }

    @Test
    void staleProbeCompletionCannotTerminateReplacementGoalRevision() {
        final FakeBackend backend = new FakeBackend();
        backend.goal = runningGoal(2L, false);
        final ModelBootstrapCoordinator coordinator =
            new ModelBootstrapCoordinator(backend);
        coordinator.tick();

        backend.goal = runningGoal(3L, false);
        backend.completeFailure(ModelFailureKind.NETWORK_TRANSIENT);
        coordinator.tick();

        assertEquals(GoalStatus.RUNNING, backend.goal.status());
        assertEquals(3L, backend.goal.revision());
        assertEquals(1, backend.probeCalls);
    }

    private static GoalSnapshot runningGoal(
        final long revision,
        final boolean locked
    ) {
        return runningGoal(
            revision,
            locked
                ? GoalSource.HARDCORE_EVALUATION
                : GoalSource.PLAYER_CHAT,
            locked
        );
    }

    private static GoalSnapshot runningGoal(
        final long revision,
        final GoalSource source,
        final boolean locked
    ) {
        return new GoalSnapshot(
            Optional.of(UUID.randomUUID()),
            revision,
            GoalStatus.RUNNING,
            source,
            locked ? "通关 Minecraft" : "survive",
            "",
            Instant.EPOCH,
            locked
        );
    }

    private static GoalSnapshot idleGoal() {
        return new GoalSnapshot(
            Optional.empty(),
            0L,
            GoalStatus.IDLE,
            GoalSource.RECOVERY,
            "",
            "",
            Instant.EPOCH,
            false
        );
    }

    private static final class FakeBackend
        implements ModelBootstrapCoordinator.Backend {
        private final ArrayDeque<
            CompletableFuture<CapabilityProbeOutcome>
        > probes = new ArrayDeque<>();
        private final List<String> announcements = new ArrayList<>();
        private GoalSnapshot goal = idleGoal();
        private String freshPreflight = "";
        private boolean endpointConfigured = true;
        private boolean gatewayReady;
        private boolean credentialAvailable = true;
        private boolean modelFrozen;
        private long serverTick;
        private int probeCalls;
        private int startCalls;
        private int freezeCalls;
        private String startedGoal = "";

        @Override
        public boolean isServerThread() {
            return true;
        }

        @Override
        public GoalSnapshot goal() {
            return goal;
        }

        @Override
        public String freshEvaluationPreflight() {
            return freshPreflight;
        }

        @Override
        public boolean modelEndpointConfigured() {
            return endpointConfigured;
        }

        @Override
        public boolean modelGatewayReady() {
            return gatewayReady;
        }

        @Override
        public boolean modelCredentialAvailable() {
            return credentialAvailable;
        }

        @Override
        public long serverTick() {
            return serverTick;
        }

        @Override
        public boolean modelEvaluationFrozen() {
            return modelFrozen;
        }

        @Override
        public CompletionStage<CapabilityProbeOutcome> probeModel() {
            probeCalls++;
            final CompletableFuture<CapabilityProbeOutcome> probe =
                new CompletableFuture<>();
            probes.addLast(probe);
            return probe;
        }

        @Override
        public ModelBootstrapCoordinator.MutationResult
        startFreshEvaluation(final String initialGoal) {
            startCalls++;
            startedGoal = initialGoal;
            modelFrozen = true;
            goal = new GoalSnapshot(
                    Optional.of(UUID.randomUUID()),
                    1L,
                    GoalStatus.RUNNING,
                    GoalSource.HARDCORE_EVALUATION,
                    initialGoal,
                    "",
                    Instant.EPOCH,
                    true
            );
            return ModelBootstrapCoordinator.MutationResult.success();
        }

        @Override
        public ModelBootstrapCoordinator.MutationResult
        freezeRestoredEvaluationModel() {
            freezeCalls++;
            modelFrozen = true;
            return ModelBootstrapCoordinator.MutationResult.success();
        }

        @Override
        public void markRunningGoalSafeIdle(final String code) {
            goal = new GoalSnapshot(
                goal.goalId(),
                goal.revision() + 1L,
                GoalStatus.SAFE_IDLE,
                goal.source(),
                goal.goal(),
                code,
                Instant.EPOCH,
                goal.externalWritesLocked()
            );
        }

        @Override
        public void announce(final String code) {
            announcements.add(code);
        }

        private void completeSupported() {
            gatewayReady = true;
            probes.removeFirst().complete(
                new CapabilityProbeOutcome.Supported(
                    ProviderCapabilities.responsesJsonSchema(false),
                    1
                )
            );
        }

        private void completeFailure(
            final ModelFailureKind kind
        ) {
            probes.removeFirst().complete(
                new CapabilityProbeOutcome.Failure(
                    new ModelFailure(
                        kind,
                        0,
                        "",
                        "",
                        "",
                        "",
                        Optional.empty(),
                        "",
                        "safe"
                    ),
                    1
                )
            );
        }
    }
}
