package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mcai.companion.credential.ApiKeyManager;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.Protocol;
import dev.mcai.companion.model.ProviderCapabilities;
import dev.mcai.companion.model.ProviderCapabilityProbe;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ModelRuntimeTest {
    @Test
    void cachedVerifiedProfileRestoresWithoutProviderRequest()
            throws Exception {
        final ProviderCapabilities cached =
                ProviderCapabilities.chatJsonSchema(false);
        final AtomicReference<ProviderCapabilities> persisted =
                new AtomicReference<>();
        final AtomicInteger probesCreated = new AtomicInteger();
        try (ApiKeyManager keys = new ApiKeyManager()) {
            saveTestCredential(keys);
            try (ModelRuntime runtime = new ModelRuntime(
                    keys,
                    "https://provider.example/v1",
                    "cached-model",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    (endpoint, ignoredKeys) -> {
                        probesCreated.incrementAndGet();
                        return new PendingProbe();
                    },
                    (endpoint, ignoredKeys, capabilities,
                            connectTimeout, hardTimeout) ->
                        new StubGateway(),
                    Optional.of(cached),
                    (endpoint, capabilities) ->
                        persisted.set(capabilities)
            )) {
                assertTrue(runtime.snapshot().endpointConfigured());
                assertTrue(runtime.snapshot().cachedProfileAvailable());
                final CapabilityProbeOutcome.Supported restored =
                        assertInstanceOf(
                            CapabilityProbeOutcome.Supported.class,
                            runtime.prepareConfiguredProfile()
                                .toCompletableFuture()
                                .get(2, TimeUnit.SECONDS)
                        );

                assertEquals(0, restored.requestsMade());
                assertEquals(cached, restored.capabilities());
                assertTrue(runtime.snapshot().gatewayReady());
                assertEquals(0, probesCreated.get());
                assertEquals(
                    null,
                    persisted.get(),
                    "restoring an unchanged cache must not rewrite it"
                );
            }
        }
    }

    @Test
    void credentialRestoreIsLocalAndMakesAvailabilityVisible()
            throws Exception {
        final AtomicInteger probesCreated = new AtomicInteger();
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = new ModelRuntime(
                 keys,
                 "https://provider.example/v1",
                 "restore-model",
                 Duration.ofSeconds(1),
                 Duration.ofSeconds(2),
                 (endpoint, ignoredKeys) -> {
                     probesCreated.incrementAndGet();
                     return new PendingProbe();
                 },
                 (endpoint, ignoredKeys, capabilities,
                         connectTimeout, hardTimeout) ->
                     new StubGateway()
             )) {
            assertFalse(runtime.snapshot().credentialAvailable());
            final char[] credential = "restore-local-only".toCharArray();
            try {
                keys.saveFromSetup(credential, false);
            } finally {
                java.util.Arrays.fill(credential, '\0');
            }

            assertTrue(
                runtime.restoreCredential()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS)
            );
            assertTrue(runtime.snapshot().credentialAvailable());
            assertEquals(0, probesCreated.get());
        }
    }

    @Test
    void authenticationInvalidationRemainsVisibleToSetupSnapshot() {
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = localRuntime(
                 keys,
                 (endpoint, ignoredKeys) -> new PendingProbe()
             )) {
            final char[] credential = "auth-state-test".toCharArray();
            try {
                keys.saveFromSetup(credential, false);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                java.util.Arrays.fill(credential, '\0');
            }
            runtime.gateway().install(new StubGateway());
            runtime.gateway().invalidateAfterAuthenticationFailure();

            assertEquals(
                "api_key_rejected",
                runtime.snapshot().configurationErrorCode()
            );
            assertFalse(runtime.snapshot().gatewayReady());
            assertFalse(runtime.snapshot().credentialAvailable());
            assertFalse(
                runtime.restoreCredential()
                    .toCompletableFuture()
                    .join()
            );
            final CapabilityProbeOutcome.Failure blockedProbe =
                assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    runtime.prepareConfiguredProfile()
                        .toCompletableFuture()
                        .join()
                );
            assertEquals(
                ModelFailureKind.INVALID_CONFIGURATION,
                blockedProbe.error().kind()
            );

            final ModelRuntime.ProfileUpdateOutcome staleCredential =
                runtime.updateProfile(
                    "http://127.0.0.1:1/v1",
                    "unit-test-model",
                    null,
                    false
                ).toCompletableFuture().join();
            assertFalse(staleCredential.accepted());
            assertEquals(
                "api_key_rejected_requires_replacement",
                staleCredential.code()
            );

            final char[] replacement = "fresh-auth-test-key".toCharArray();
            try {
                final ModelRuntime.ProfileUpdateOutcome accepted =
                    runtime.updateProfile(
                        "http://127.0.0.1:1/v1",
                        "unit-test-model",
                        replacement,
                        false
                    ).toCompletableFuture().join();
                assertTrue(accepted.accepted());
            } finally {
                java.util.Arrays.fill(replacement, '\0');
            }
            assertTrue(runtime.snapshot().credentialAvailable());
        }
    }

    @Test
    void authenticationFailureDuringProbeQuarantinesCredential() {
        try (ApiKeyManager keys = new ApiKeyManager()) {
            saveTestCredentialUnchecked(keys);
            try (ModelRuntime runtime = new ModelRuntime(
                keys,
                "http://127.0.0.1:1/v1",
                "unit-test-model",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                (endpoint, ignoredKeys) -> new ProviderCapabilityProbe() {
                    @Override
                    public CompletionStage<CapabilityProbeOutcome> probe() {
                        return CompletableFuture.completedFuture(
                            new CapabilityProbeOutcome.Failure(
                                new ModelFailure(
                                    ModelFailureKind.AUTHENTICATION,
                                    401,
                                    "",
                                    "",
                                    "probe",
                                    "",
                                    Optional.empty(),
                                    "",
                                    "safe"
                                ),
                                1
                            )
                        );
                    }

                    @Override
                    public void close() {
                    }
                },
                (endpoint, ignoredKeys, capabilities,
                        connectTimeout, hardTimeout) -> new StubGateway()
            )) {
                final CapabilityProbeOutcome.Failure failure =
                    assertInstanceOf(
                        CapabilityProbeOutcome.Failure.class,
                        runtime.probeExplicitly()
                            .toCompletableFuture()
                            .join()
                    );
                assertEquals(
                    ModelFailureKind.AUTHENTICATION,
                    failure.error().kind()
                );
                assertEquals(
                    "api_key_rejected",
                    runtime.snapshot().configurationErrorCode()
                );
                assertFalse(runtime.snapshot().credentialAvailable());
                assertFalse(
                    runtime.restoreCredential()
                        .toCompletableFuture()
                        .join()
                );
            }
        }
    }

    @Test
    void explicitProbeIsSingleFlightAndInstallsOnlyAVerifiedGateway()
        throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer provider = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        provider.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                final byte[] response = """
                    {
                      "status":"completed",
                      "output":[{
                        "type":"message",
                        "content":[{
                          "type":"output_text",
                          "text":"{\\"probe\\":\\"ok\\"}"
                        }]
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
                );
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        provider.start();

        try (ApiKeyManager keys = new ApiKeyManager()) {
            final char[] secret = "local-runtime-test-only".toCharArray();
            keys.saveFromSetup(secret, false);
            java.util.Arrays.fill(secret, '\0');
            try (ModelRuntime runtime = new ModelRuntime(
                keys,
                "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                "opaque-test-model",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
            )) {
                assertEquals(0, requests.get());
                assertFalse(runtime.snapshot().gatewayReady());

                final var first = runtime.probeExplicitly();
                final var second = runtime.probeExplicitly();
                assertSame(first, second);
                final CapabilityProbeOutcome.Supported outcome = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    first.toCompletableFuture().get(3, TimeUnit.SECONDS)
                );

                assertEquals(2, outcome.requestsMade());
                assertEquals(Protocol.RESPONSES, outcome.capabilities().protocol());
                assertTrue(outcome.capabilities().imageInput());
                assertEquals(2, requests.get());
                assertTrue(runtime.snapshot().gatewayReady());
            }
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void evaluationFreezeRejectsWhileAProbeIsInFlightEvenWhenGatewayIsReady()
        throws Exception {
        final PendingProbe pendingProbe = new PendingProbe();
        try (ApiKeyManager keys = new ApiKeyManager()) {
            saveTestCredential(keys);
            try (ModelRuntime runtime = localRuntime(
                keys,
                (endpoint, ignoredKeys) -> pendingProbe
            )) {
                runtime.gateway().install(new StubGateway());
                final CompletionStage<CapabilityProbeOutcome> probe =
                    runtime.probeExplicitly();
                assertTrue(pendingProbe.started.await(2, TimeUnit.SECONDS));

                final ModelRuntime.EvaluationFreezeAttempt freeze =
                    runtime.freezeForEvaluation();

                assertFalse(freeze.acquired());
                assertEquals("model_probe_in_flight", freeze.code());
                pendingProbe.result.complete(new CapabilityProbeOutcome.Supported(
                    ProviderCapabilities.responsesJsonSchema(false),
                    1
                ));
                assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    probe.toCompletableFuture().get(2, TimeUnit.SECONDS)
                );
            }
        }
    }

    @Test
    void committedEvaluationFreezeRejectsEveryLaterProbeAndReplacementWindow() {
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = localRuntime(
                 keys,
                 (endpoint, ignoredKeys) -> new PendingProbe()
             )) {
            runtime.gateway().install(new StubGateway());
            final ModelRuntime.EvaluationFreezeAttempt attempt =
                runtime.freezeForEvaluation();
            assertTrue(attempt.acquired());

            final ModelRuntime.EvaluationModelFreeze freeze =
                attempt.freeze().orElseThrow();
            freeze.commit();
            freeze.close();

            assertTrue(runtime.snapshot().evaluationModelFrozen());
            assertTrue(runtime.snapshot().evaluationModelFreezeCommitted());
            final CapabilityProbeOutcome.Failure rejected = assertInstanceOf(
                CapabilityProbeOutcome.Failure.class,
                runtime.probeExplicitly().toCompletableFuture().join()
            );
            assertEquals(ModelFailureKind.BUSY, rejected.error().kind());
            assertEquals(0, rejected.requestsMade());
            assertEquals(
                "evaluation_model_frozen",
                runtime.freezeForEvaluation().code()
            );
        }
    }

    @Test
    void uncommittedEvaluationFreezeRollsBackAfterStartupFailure() {
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = localRuntime(
                 keys,
                 (endpoint, ignoredKeys) -> new PendingProbe()
             )) {
            runtime.gateway().install(new StubGateway());

            final ModelRuntime.EvaluationFreezeAttempt failedStart =
                runtime.freezeForEvaluation();
            assertTrue(failedStart.acquired());
            try (var ignored = failedStart.freeze().orElseThrow()) {
                // Simulates a rejected body spawn or evaluation goal start.
            }

            assertFalse(runtime.snapshot().evaluationModelFrozen());
            final ModelRuntime.EvaluationFreezeAttempt retry =
                runtime.freezeForEvaluation();
            assertTrue(retry.acquired());
            retry.freeze().orElseThrow().close();
        }
    }

    @Test
    void profileUpdateClearsOldGatewayAndUsesProcessCredential()
        throws Exception {
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = localRuntime(
                 keys,
                 (endpoint, ignoredKeys) -> new PendingProbe()
             )) {
            runtime.gateway().install(new StubGateway());
            final char[] credential = "replacement-test-key".toCharArray();
            final ModelRuntime.ProfileUpdateOutcome outcome =
                runtime.updateProfile(
                    "https://example.test/v1/",
                    "replacement-model",
                    credential,
                    false
                ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            java.util.Arrays.fill(credential, '\0');

            assertTrue(outcome.accepted());
            assertEquals(
                "https://example.test/v1",
                outcome.normalizedBaseUrl()
            );
            assertEquals("replacement-model", outcome.modelName());
            assertEquals("process_only", outcome.credentialStorage());
            assertFalse(outcome.credentialPersistent());
            assertFalse(runtime.gateway().configured());
            assertEquals(
                "replacement-model",
                runtime.snapshot().modelName()
            );
            assertEquals(
                "https://example.test/v1",
                runtime.profileForEvaluation().baseUrl()
            );
            assertEquals(
                "replacement-model",
                runtime.profileForEvaluation().modelName()
            );
        }
    }

    @Test
    void committedEvaluationFreezeRejectsProfileAndCredentialChanges() {
        try (ApiKeyManager keys = new ApiKeyManager();
             ModelRuntime runtime = localRuntime(
                 keys,
                 (endpoint, ignoredKeys) -> new PendingProbe()
             )) {
            runtime.gateway().install(new StubGateway());
            final var freeze = runtime.freezeForEvaluation()
                .freeze()
                .orElseThrow();
            freeze.commit();
            final char[] credential = "must-not-be-saved".toCharArray();
            final ModelRuntime.ProfileUpdateOutcome outcome =
                runtime.updateProfile(
                    "https://example.test/v1",
                    "replacement-model",
                    credential,
                    false
                ).toCompletableFuture().join();
            java.util.Arrays.fill(credential, '\0');

            assertFalse(outcome.accepted());
            assertEquals("evaluation_model_frozen", outcome.code());
            assertTrue(runtime.gateway().configured());
        }
    }

    private static ModelRuntime localRuntime(
        final ApiKeyManager keys,
        final ModelRuntime.ProbeFactory probeFactory
    ) {
        return new ModelRuntime(
            keys,
            "http://127.0.0.1:1/v1",
            "unit-test-model",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            probeFactory,
            (endpoint, apiKeys, capabilities, connectTimeout, hardTimeout) ->
                new StubGateway()
        );
    }

    private static void saveTestCredential(final ApiKeyManager keys)
        throws Exception {
        final char[] credential = "unit-test-placeholder".toCharArray();
        try {
            keys.saveFromSetup(credential, false);
        } finally {
            java.util.Arrays.fill(credential, '\0');
        }
    }

    private static void saveTestCredentialUnchecked(final ApiKeyManager keys) {
        try {
            saveTestCredential(keys);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class PendingProbe
        implements ProviderCapabilityProbe {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<CapabilityProbeOutcome> result =
            new CompletableFuture<>();

        @Override
        public CompletionStage<CapabilityProbeOutcome> probe() {
            started.countDown();
            return result;
        }

        @Override
        public void close() {
            result.cancel(false);
        }
    }

    private static final class StubGateway implements ModelGateway {
        private boolean closed;

        @Override
        public CompletionStage<ModelOutcome> decide(
            final PlannerInput input
        ) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void cancelForGoalRevision(final long currentGoalRevision) {
        }

        @Override
        public GatewayStatus status() {
            return closed ? GatewayStatus.CLOSED : GatewayStatus.IDLE;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
