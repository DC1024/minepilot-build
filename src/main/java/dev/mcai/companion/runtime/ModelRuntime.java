package dev.mcai.companion.runtime;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.model.EndpointValidationException;
import dev.mcai.companion.model.EndpointValidator;
import dev.mcai.companion.model.JdkModelGateway;
import dev.mcai.companion.model.JdkProviderCapabilityProbe;
import dev.mcai.companion.model.ModelEndpoint;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ProviderCapabilities;
import dev.mcai.companion.model.ProviderCapabilityProbe;
import dev.mcai.companion.credential.ApiKeyManager;
import dev.mcai.companion.credential.CredentialException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-scoped, non-blocking model setup. Construction performs no provider
 * I/O; only {@link #probeExplicitly()} may create capability requests.
 */
public final class ModelRuntime implements AutoCloseable {
    private static final int MAX_CREDENTIAL_CHARACTERS = 8_192;

    private final ApiKeyManager apiKeys;
    private Optional<ModelEndpoint> endpoint;
    private String configurationErrorCode;
    private final Duration connectTimeout;
    private final Duration hardTimeout;
    private final ProbeFactory probeFactory;
    private final GatewayFactory gatewayFactory;
    private final VerifiedProfileSink verifiedProfileSink;
    private final SwitchableModelGateway gateway = new SwitchableModelGateway();
    private final ExecutorService setupExecutor =
        Executors.newVirtualThreadPerTaskExecutor();

    private ProviderCapabilityProbe activeProbe;
    private CompletableFuture<CapabilityProbeOutcome> activeProbeStage;
    private CompletableFuture<ProfileUpdateOutcome> activeProfileUpdateStage;
    private boolean probeInFlight;
    private boolean profileUpdateInFlight;
    private ProviderCapabilities capabilities;
    private ProviderCapabilities cachedCapabilities;
    private EvaluationModelFreeze evaluationFreeze;
    private boolean evaluationFreezeCommitted;
    /**
     * Set after a provider rejects authentication.  A credential that was
     * loaded before the rejection must not silently come back from an OS
     * store or environment injection on the next probe; the setup screen
     * must receive a replacement explicitly.  This is process state only and
     * is deliberately reset after a user-supplied replacement is stored.
     */
    private boolean credentialRejected;
    private boolean closed;

    public ModelRuntime(
        final ApiKeyManager apiKeys,
        final String baseUrl,
        final String modelName,
        final Duration connectTimeout,
        final Duration hardTimeout
    ) {
        this(
            apiKeys,
            baseUrl,
            modelName,
            connectTimeout,
            hardTimeout,
            (endpoint, keys) -> new JdkProviderCapabilityProbe(
                    endpoint,
                    keys,
                    connectTimeout,
                    hardTimeout,
                    true
            ),
            JdkModelGateway::new,
            Optional.empty(),
            (endpoint, capabilities) -> {
            }
        );
    }

    public ModelRuntime(
        final ApiKeyManager apiKeys,
        final String baseUrl,
        final String modelName,
        final Duration connectTimeout,
        final Duration hardTimeout,
        final Optional<ProviderCapabilities> cachedCapabilities,
        final VerifiedProfileSink verifiedProfileSink
    ) {
        this(
            apiKeys,
            baseUrl,
            modelName,
            connectTimeout,
            hardTimeout,
            (endpoint, keys) -> new JdkProviderCapabilityProbe(
                endpoint,
                keys,
                connectTimeout,
                hardTimeout,
                true
            ),
            JdkModelGateway::new,
            cachedCapabilities,
            verifiedProfileSink
        );
    }

    ModelRuntime(
        final ApiKeyManager apiKeys,
        final String baseUrl,
        final String modelName,
        final Duration connectTimeout,
        final Duration hardTimeout,
        final ProbeFactory probeFactory,
        final GatewayFactory gatewayFactory
    ) {
        this(
            apiKeys,
            baseUrl,
            modelName,
            connectTimeout,
            hardTimeout,
            probeFactory,
            gatewayFactory,
            Optional.empty(),
            (endpoint, capabilities) -> {
            }
        );
    }

    ModelRuntime(
        final ApiKeyManager apiKeys,
        final String baseUrl,
        final String modelName,
        final Duration connectTimeout,
        final Duration hardTimeout,
        final ProbeFactory probeFactory,
        final GatewayFactory gatewayFactory,
        final Optional<ProviderCapabilities> cachedCapabilities,
        final VerifiedProfileSink verifiedProfileSink
    ) {
        this.apiKeys = Objects.requireNonNull(apiKeys, "apiKeys");
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.hardTimeout = requirePositive(hardTimeout, "hardTimeout");
        this.probeFactory = Objects.requireNonNull(probeFactory, "probeFactory");
        this.gatewayFactory = Objects.requireNonNull(
            gatewayFactory,
            "gatewayFactory"
        );
        this.verifiedProfileSink = Objects.requireNonNull(
            verifiedProfileSink,
            "verifiedProfileSink"
        );
        Optional<ModelEndpoint> validated = Optional.empty();
        String error = "";
        try {
            validated = Optional.of(
                new EndpointValidator().validate(baseUrl, modelName)
            );
        } catch (EndpointValidationException exception) {
            error = exception.code();
        }
        endpoint = validated;
        configurationErrorCode = error;
        this.cachedCapabilities = validated.isPresent()
            ? Objects.requireNonNull(
                    cachedCapabilities,
                    "cachedCapabilities"
            ).orElse(null)
            : null;
        gateway.setAuthenticationFailureHandler(
            this::invalidateAfterAuthenticationFailure
        );
    }

    public SwitchableModelGateway gateway() {
        return gateway;
    }

    /**
     * Fails closed after a provider 401/403.  Keeping the body online is
     * useful for identity/TAB and local safety, but no stale model delegate
     * or cached capability profile may be reused until the player verifies a
     * replacement credential in the setup screen.
     */
    private synchronized void invalidateAfterAuthenticationFailure() {
        if (closed) {
            return;
        }
        capabilities = null;
        cachedCapabilities = null;
        configurationErrorCode = "api_key_rejected";
        credentialRejected = true;
        // Do not delete a persistent secret automatically.  Clear only the
        // in-memory copy so the next setup action cannot reuse stale auth.
        apiKeys.clearSession();
        try {
            verifiedProfileSink.invalidateCapabilities();
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.warn(
                "Could not remove the cached model capability profile after "
                    + "provider authentication failure"
            );
        }
    }

    /**
     * Returns the exact validated, non-secret endpoint identity that is
     * pinned into a locked evaluation. Credentials remain exclusively in the
     * credential manager and are never part of this value.
     */
    public synchronized EvaluationProfile profileForEvaluation() {
        final ModelEndpoint configured = endpoint.orElseThrow(
            () -> new IllegalStateException(
                "The evaluation model endpoint is not configured"
            )
        );
        return new EvaluationProfile(
            configured.baseUri().toASCIIString(),
            configured.modelName()
        );
    }

    public synchronized SetupSnapshot snapshot() {
        return new SetupSnapshot(
            endpoint.isPresent(),
            endpoint.map(ModelEndpoint::origin).orElse(""),
            endpoint.map(ModelEndpoint::modelName).orElse(""),
            configurationErrorCode,
            !credentialRejected && hasCredential(),
            probeInFlight,
            cachedCapabilities != null,
            Optional.ofNullable(capabilities),
            gateway.configured(),
            evaluationFreeze != null,
            evaluationFreezeCommitted
        );
    }

    /**
     * Restores a persisted credential without contacting the provider.
     *
     * <p>The setup screen can be opened immediately after an integrated or
     * dedicated server starts.  Credential stores are deliberately accessed
     * off the server thread, so the screen must await this local operation
     * before deciding that the API key is missing.  A failed restore is
     * represented as {@code false}; the secret value and platform error are
     * never returned to the client.</p>
     */
    public CompletionStage<Boolean> restoreCredential() {
        synchronized (this) {
            if (closed || credentialRejected) {
                return CompletableFuture.completedFuture(false);
            }
            if (hasCredential()) {
                return CompletableFuture.completedFuture(true);
            }
        }
        final CompletableFuture<Boolean> result =
            new CompletableFuture<>();
        try {
            setupExecutor.submit(() -> {
                boolean restored = false;
                try {
                    synchronized (ModelRuntime.this) {
                        if (closed || credentialRejected) {
                            result.complete(false);
                            return;
                        }
                        restored = hasCredential() || apiKeys.unlockPersisted();
                    }
                } catch (CredentialException ignored) {
                    // The setup UI only needs a safe availability bit.
                }
                result.complete(restored);
            });
        } catch (RuntimeException exception) {
            result.complete(false);
        }
        return result;
    }

    /**
     * Atomically reserves the currently verified gateway for one Hardcore
     * evaluation start transaction. The reservation is available only while
     * no capability probe is in flight and prevents all later probes or
     * verified-gateway replacement attempts.
     *
     * <p>The caller must either {@link EvaluationModelFreeze#commit()} after
     * both body and goal startup succeed, or close the handle to roll back an
     * uncommitted reservation.</p>
     */
    public synchronized EvaluationFreezeAttempt freezeForEvaluation() {
        if (closed) {
            return EvaluationFreezeAttempt.rejected("model_runtime_closed");
        }
        if (evaluationFreeze != null) {
            return EvaluationFreezeAttempt.rejected(
                evaluationFreezeCommitted
                    ? "evaluation_model_frozen"
                    : "evaluation_model_reserved"
            );
        }
        if (probeInFlight) {
            return EvaluationFreezeAttempt.rejected("model_probe_in_flight");
        }
        if (profileUpdateInFlight) {
            return EvaluationFreezeAttempt.rejected(
                "model_profile_update_in_flight"
            );
        }
        if (!gateway.configured()) {
            return EvaluationFreezeAttempt.rejected("model_gateway_not_ready");
        }
        evaluationFreeze = new EvaluationModelFreeze(this);
        evaluationFreezeCommitted = false;
        return EvaluationFreezeAttempt.acquired(evaluationFreeze);
    }

    /**
     * Explicit user action. Concurrent calls share one attempt; after an
     * attempt completes, a later explicit action may intentionally re-check.
     */
    public synchronized CompletionStage<CapabilityProbeOutcome> probeExplicitly() {
        if (closed) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.CANCELLED,
                "The model runtime is closed"
            ));
        }
        if (evaluationFreeze != null) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.BUSY,
                "The model profile is frozen for a Hardcore evaluation"
            ));
        }
        if (credentialRejected) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "The API credential must be replaced after provider authentication rejection"
            ));
        }
        if (profileUpdateInFlight) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.BUSY,
                "A model profile update is in progress"
            ));
        }
        if (endpoint.isEmpty()) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "Base URL or model name is not configured"
            ));
        }
        if (probeInFlight) {
            return activeProbeStage;
        }

        final CompletableFuture<CapabilityProbeOutcome> result =
            new CompletableFuture<>();
        activeProbeStage = result;
        probeInFlight = true;
        try {
            setupExecutor.submit(() -> {
                try {
                    prepareAndRunProbe(result);
                } catch (RuntimeException exception) {
                    finishProbeAttempt(result, failure(
                        ModelFailureKind.INTERNAL,
                        "The capability probe failed locally"
                    ));
                }
            });
        } catch (RuntimeException exception) {
            finishProbeAttempt(result, failure(
                ModelFailureKind.CANCELLED,
                "The capability probe could not be started"
            ));
        }
        return result;
    }

    /**
     * Restores an exact endpoint-bound, previously verified wire profile
     * without contacting the provider. If no cached profile exists, this
     * falls through to the ordinary explicit capability probe.
     *
     * <p>Credential unlock and gateway construction run off-thread. Opening
     * an ordinary world therefore does not block a server tick and does not
     * spend a capability-probe request merely because the integrated server
     * was restarted.</p>
     */
    public synchronized CompletionStage<CapabilityProbeOutcome>
            prepareConfiguredProfile() {
        if (credentialRejected) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "The API credential must be replaced after provider authentication rejection"
            ));
        }
        if (gateway.configured() && capabilities != null) {
            return CompletableFuture.completedFuture(
                new CapabilityProbeOutcome.Supported(
                    capabilities,
                    0
                )
            );
        }
        if (cachedCapabilities == null) {
            return probeExplicitly();
        }
        if (closed) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.CANCELLED,
                "The model runtime is closed"
            ));
        }
        if (evaluationFreeze != null) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.BUSY,
                "The model profile is frozen for a Hardcore evaluation"
            ));
        }
        if (profileUpdateInFlight) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.BUSY,
                "A model profile update is in progress"
            ));
        }
        if (probeInFlight) {
            return activeProbeStage;
        }
        if (endpoint.isEmpty()) {
            return CompletableFuture.completedFuture(failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "Base URL or model name is not configured"
            ));
        }

        final CompletableFuture<CapabilityProbeOutcome> result =
            new CompletableFuture<>();
        activeProbeStage = result;
        probeInFlight = true;
        final ProviderCapabilities cached = cachedCapabilities;
        try {
            setupExecutor.submit(() -> {
                try {
                    prepareAndInstallCached(result, cached);
                } catch (RuntimeException exception) {
                    finishProbeAttempt(result, failure(
                        ModelFailureKind.INTERNAL,
                        "The cached model profile could not be restored"
                    ));
                }
            });
        } catch (RuntimeException exception) {
            finishProbeAttempt(result, failure(
                ModelFailureKind.CANCELLED,
                "The cached model profile could not be restored"
            ));
        }
        return result;
    }

    /**
     * Replaces the non-secret endpoint profile and, when supplied, stores a
     * new credential through {@link ApiKeyManager}. Credential persistence is
     * attempted on a virtual thread because the operating-system keychain may
     * block. When {@code preferPersistentCredential} is true, a process-only
     * fallback is accepted only when the secure store is unavailable and is
     * reported by its explicit storage code; it is never presented as
     * restart-safe. The verified gateway is cleared only after storage
     * succeeds.
     *
     * <p>A {@code null} credential preserves the currently available
     * credential. Callers retain ownership of the supplied array and should
     * wipe it immediately after this method returns.</p>
     */
    public CompletionStage<ProfileUpdateOutcome> updateProfile(
        final String baseUrl,
        final String modelName,
        final char[] credential,
        final boolean preferPersistentCredential
    ) {
        final ModelEndpoint validatedEndpoint;
        try {
            validatedEndpoint = new EndpointValidator().validate(
                baseUrl,
                modelName
            );
        } catch (EndpointValidationException exception) {
            return CompletableFuture.completedFuture(
                ProfileUpdateOutcome.rejected(exception.code())
            );
        }
        if (credential != null
            && (credential.length == 0
                || credential.length > MAX_CREDENTIAL_CHARACTERS)) {
            return CompletableFuture.completedFuture(
                ProfileUpdateOutcome.rejected("invalid_api_key")
            );
        }

        final char[] ownedCredential =
            credential == null ? null : credential.clone();
        final CompletableFuture<ProfileUpdateOutcome> result;
        synchronized (this) {
            if (closed) {
                wipe(ownedCredential);
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected("model_runtime_closed")
                );
            }
            if (evaluationFreeze != null) {
                wipe(ownedCredential);
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected(
                        evaluationFreezeCommitted
                            ? "evaluation_model_frozen"
                            : "evaluation_model_reserved"
                    )
                );
            }
            if (probeInFlight) {
                wipe(ownedCredential);
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected("model_probe_in_flight")
                );
            }
            if (profileUpdateInFlight) {
                wipe(ownedCredential);
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected(
                        "model_profile_update_in_flight"
                    )
                );
            }
            if (ownedCredential == null && credentialRejected) {
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected(
                        "api_key_rejected_requires_replacement"
                    )
                );
            }
            if (ownedCredential == null && !hasCredential()) {
                return CompletableFuture.completedFuture(
                    ProfileUpdateOutcome.rejected("api_key_required")
                );
            }
            result = new CompletableFuture<>();
            activeProfileUpdateStage = result;
            profileUpdateInFlight = true;
        }

        try {
            setupExecutor.submit(() -> storeAndInstallProfile(
                result,
                validatedEndpoint,
                ownedCredential,
                preferPersistentCredential
            ));
        } catch (RuntimeException exception) {
            wipe(ownedCredential);
            finishProfileUpdate(
                result,
                ProfileUpdateOutcome.rejected("profile_update_start_failed")
            );
        }
        return result;
    }

    private void storeAndInstallProfile(
        final CompletableFuture<ProfileUpdateOutcome> result,
        final ModelEndpoint validatedEndpoint,
        final char[] ownedCredential,
        final boolean preferPersistentCredential
    ) {
        String credentialStorage = "unchanged";
        boolean credentialPersistent = false;
        try {
            if (ownedCredential != null) {
                final ApiKeyManager.SaveResult saved = apiKeys.saveFromSetup(
                    ownedCredential,
                    preferPersistentCredential
                );
                if (preferPersistentCredential && !saved.persistent()) {
                    /*
                     * A headless Debian/server install often has no desktop
                     * Secret Service, and a restricted container cannot
                     * create one.  The product contract explicitly permits
                     * a process-only credential in that case (or a secret
                     * injected again through MCAI_API_KEY/_FILE on the next
                     * process start).  The old fail-closed branch made the
                     * setup screen unusable even though the model endpoint
                     * itself was valid.  Keep the storage result explicit so
                     * the client can show the restart caveat; never claim
                     * that the key is restart-safe.
                     */
                    credentialStorage = saved.storage();
                    credentialPersistent = false;
                } else {
                    credentialStorage = saved.storage();
                    credentialPersistent = saved.persistent();
                }
            }
        } catch (CredentialException exception) {
            finishProfileUpdate(
                result,
                ProfileUpdateOutcome.rejected("invalid_api_key")
            );
            return;
        } finally {
            wipe(ownedCredential);
        }

        synchronized (this) {
            if (closed || activeProfileUpdateStage != result) {
                finishProfileUpdate(
                    result,
                    ProfileUpdateOutcome.rejected("model_runtime_closed")
                );
                return;
            }
            endpoint = Optional.of(validatedEndpoint);
            configurationErrorCode = "";
            capabilities = null;
            cachedCapabilities = null;
            if (ownedCredential != null) {
                credentialRejected = false;
            }
            gateway.clearVerifiedDelegate();
        }
        finishProfileUpdate(
            result,
            ProfileUpdateOutcome.accepted(
                validatedEndpoint.baseUri().toASCIIString(),
                validatedEndpoint.modelName(),
                credentialPersistent,
                credentialStorage
            )
        );
    }

    private void finishProfileUpdate(
        final CompletableFuture<ProfileUpdateOutcome> attempt,
        final ProfileUpdateOutcome outcome
    ) {
        synchronized (this) {
            if (activeProfileUpdateStage == attempt) {
                activeProfileUpdateStage = null;
                profileUpdateInFlight = false;
            }
        }
        attempt.complete(outcome);
    }

    private void prepareAndRunProbe(
        final CompletableFuture<CapabilityProbeOutcome> result
    ) {
        synchronized (this) {
            if (credentialRejected) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.INVALID_CONFIGURATION,
                    "The API credential must be replaced after provider authentication rejection"
                ));
                return;
            }
        }
        try {
            if (!hasCredential() && !apiKeys.unlockPersisted()) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.INVALID_CONFIGURATION,
                    "The API credential is unavailable"
                ));
                return;
            }
        } catch (CredentialException exception) {
            finishProbeAttempt(result, failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "The persisted API credential could not be unlocked"
            ));
            return;
        }

        final ProviderCapabilityProbe probe;
        try {
            synchronized (this) {
                if (closed
                    || evaluationFreeze != null
                    || activeProbeStage != result) {
                    finishProbeAttempt(result, failure(
                        ModelFailureKind.CANCELLED,
                        evaluationFreeze == null
                            ? "The capability probe was cancelled"
                            : "The model profile is frozen for a Hardcore evaluation"
                    ));
                    return;
                }
                if (activeProbe != null) {
                    activeProbe.close();
                }
                probe = probeFactory.create(
                    endpoint.orElseThrow(),
                    apiKeys
                );
                activeProbe = probe;
            }
        } catch (RuntimeException exception) {
            finishProbeAttempt(result, failure(
                ModelFailureKind.INTERNAL,
                "The capability probe could not be prepared"
            ));
            return;
        }
        probe.probe().whenComplete((outcome, throwable) -> {
            if (throwable != null || outcome == null) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.INTERNAL,
                    "The capability probe failed locally"
                ));
                return;
            }
            try {
                if (outcome
                        instanceof CapabilityProbeOutcome.Supported supported) {
                    installVerified(result, supported.capabilities());
                } else if (outcome
                        instanceof CapabilityProbeOutcome.Failure failure
                        && failure.error().kind()
                            == ModelFailureKind.AUTHENTICATION) {
                    /*
                     * A capability probe is the first real provider request
                     * after Save & Verify.  It must quarantine a rejected
                     * credential just like a later gameplay request; otherwise
                     * the setup screen can report the failure while the same
                     * stale Keychain value remains available for the next
                     * world/probe cycle.
                     */
                    invalidateAfterAuthenticationFailure();
                }
                finishProbeAttempt(result, outcome);
            } catch (RuntimeException exception) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.INTERNAL,
                    "The verified model gateway could not be installed"
                ));
            }
        });
    }

    private void prepareAndInstallCached(
        final CompletableFuture<CapabilityProbeOutcome> result,
        final ProviderCapabilities cached
    ) {
        if (!unlockCredential(result)) {
            return;
        }
        synchronized (this) {
            if (closed
                || evaluationFreeze != null
                || endpoint.isEmpty()
                || activeProbeStage != result
                || cachedCapabilities != cached) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.CANCELLED,
                    "The cached model profile restore was cancelled"
                ));
                return;
            }
            capabilities = cached;
            gateway.install(gatewayFactory.create(
                endpoint.orElseThrow(),
                apiKeys,
                cached,
                connectTimeout,
                hardTimeout
            ));
        }
        finishProbeAttempt(
            result,
            new CapabilityProbeOutcome.Supported(cached, 0)
        );
    }

    private boolean unlockCredential(
        final CompletableFuture<CapabilityProbeOutcome> result
    ) {
        try {
            synchronized (this) {
                if (credentialRejected) {
                    finishProbeAttempt(result, failure(
                        ModelFailureKind.INVALID_CONFIGURATION,
                        "The API credential must be replaced after provider authentication rejection"
                    ));
                    return false;
                }
            }
            if (!hasCredential() && !apiKeys.unlockPersisted()) {
                finishProbeAttempt(result, failure(
                    ModelFailureKind.INVALID_CONFIGURATION,
                    "The API credential is unavailable"
                ));
                return false;
            }
            return true;
        } catch (CredentialException exception) {
            finishProbeAttempt(result, failure(
                ModelFailureKind.INVALID_CONFIGURATION,
                "The persisted API credential could not be unlocked"
            ));
            return false;
        }
    }

    private synchronized void installVerified(
        final CompletableFuture<CapabilityProbeOutcome> attempt,
        final ProviderCapabilities verified
    ) {
        if (closed
            || evaluationFreeze != null
            || endpoint.isEmpty()
            || activeProbeStage != attempt) {
            return;
        }
        capabilities = Objects.requireNonNull(verified, "verified");
        cachedCapabilities = verified;
        gateway.install(gatewayFactory.create(
            endpoint.orElseThrow(),
            apiKeys,
            verified,
            connectTimeout,
            hardTimeout
        ));
        try {
            verifiedProfileSink.persist(
                endpoint.orElseThrow(),
                verified
            );
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.warn(
                "Verified non-secret model capability profile could "
                    + "not be cached; the active gateway remains usable"
            );
        }
    }

    private void finishProbeAttempt(
        final CompletableFuture<CapabilityProbeOutcome> attempt,
        final CapabilityProbeOutcome outcome
    ) {
        synchronized (this) {
            if (activeProbeStage == attempt) {
                probeInFlight = false;
            }
        }
        attempt.complete(outcome);
    }

    private synchronized void commitEvaluationFreeze(
        final EvaluationModelFreeze requested
    ) {
        if (closed || evaluationFreeze != requested) {
            throw new IllegalStateException(
                "The evaluation model reservation is no longer active"
            );
        }
        evaluationFreezeCommitted = true;
    }

    private synchronized void releaseEvaluationFreeze(
        final EvaluationModelFreeze requested
    ) {
        if (evaluationFreeze == requested && !evaluationFreezeCommitted) {
            evaluationFreeze = null;
        }
    }

    private boolean hasCredential() {
        final char[] credential = apiKeys.acquire();
        try {
            return credential != null && credential.length > 0;
        } finally {
            if (credential != null) {
                Arrays.fill(credential, '\0');
            }
        }
    }

    private static void wipe(final char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (activeProbe != null) {
            activeProbe.close();
            activeProbe = null;
        }
        if (activeProbeStage != null && !activeProbeStage.isDone()) {
            activeProbeStage.complete(failure(
                ModelFailureKind.CANCELLED,
                "The model runtime is closed"
            ));
        }
        probeInFlight = false;
        if (activeProfileUpdateStage != null
            && !activeProfileUpdateStage.isDone()) {
            activeProfileUpdateStage.complete(
                ProfileUpdateOutcome.rejected("model_runtime_closed")
            );
        }
        activeProfileUpdateStage = null;
        profileUpdateInFlight = false;
        setupExecutor.shutdownNow();
        gateway.close();
    }

    private static CapabilityProbeOutcome failure(
        final ModelFailureKind kind,
        final String safeMessage
    ) {
        return new CapabilityProbeOutcome.Failure(new ModelFailure(
            kind,
            0,
            "",
            "",
            "",
            "",
            Optional.empty(),
            "",
            safeMessage
        ), 0);
    }

    private static Duration requirePositive(
        final Duration duration,
        final String name
    ) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    public record SetupSnapshot(
        boolean endpointConfigured,
        String origin,
        String modelName,
        String configurationErrorCode,
        boolean credentialAvailable,
        boolean probeInFlight,
        boolean cachedProfileAvailable,
        Optional<ProviderCapabilities> capabilities,
        boolean gatewayReady,
        boolean evaluationModelFrozen,
        boolean evaluationModelFreezeCommitted
    ) {
        public SetupSnapshot {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(modelName, "modelName");
            Objects.requireNonNull(configurationErrorCode, "configurationErrorCode");
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    public record EvaluationProfile(
        String baseUrl,
        String modelName
    ) {
        public EvaluationProfile {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(modelName, "modelName");
        }
    }

    public record ProfileUpdateOutcome(
        boolean accepted,
        String code,
        String normalizedBaseUrl,
        String modelName,
        boolean credentialPersistent,
        String credentialStorage
    ) {
        public ProfileUpdateOutcome {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(normalizedBaseUrl, "normalizedBaseUrl");
            Objects.requireNonNull(modelName, "modelName");
            Objects.requireNonNull(credentialStorage, "credentialStorage");
            if (accepted != code.equals("profile_updated")) {
                throw new IllegalArgumentException(
                    "Only profile_updated is an accepted outcome"
                );
            }
        }

        private static ProfileUpdateOutcome accepted(
            final String normalizedBaseUrl,
            final String modelName,
            final boolean credentialPersistent,
            final String credentialStorage
        ) {
            return new ProfileUpdateOutcome(
                true,
                "profile_updated",
                normalizedBaseUrl,
                modelName,
                credentialPersistent,
                credentialStorage
            );
        }

        private static ProfileUpdateOutcome rejected(final String code) {
            return new ProfileUpdateOutcome(
                false,
                code,
                "",
                "",
                false,
                ""
            );
        }
    }

    public record EvaluationFreezeAttempt(
        Optional<EvaluationModelFreeze> freeze,
        String code
    ) {
        public EvaluationFreezeAttempt {
            freeze = Objects.requireNonNull(freeze, "freeze");
            Objects.requireNonNull(code, "code");
            if (freeze.isPresent() != code.equals("acquired")) {
                throw new IllegalArgumentException(
                    "An evaluation freeze is present exactly when acquired"
                );
            }
        }

        public boolean acquired() {
            return freeze.isPresent();
        }

        private static EvaluationFreezeAttempt acquired(
            final EvaluationModelFreeze freeze
        ) {
            return new EvaluationFreezeAttempt(
                Optional.of(freeze),
                "acquired"
            );
        }

        private static EvaluationFreezeAttempt rejected(final String code) {
            return new EvaluationFreezeAttempt(Optional.empty(), code);
        }
    }

    public static final class EvaluationModelFreeze implements AutoCloseable {
        private final ModelRuntime owner;

        private EvaluationModelFreeze(final ModelRuntime owner) {
            this.owner = owner;
        }

        /**
         * Makes the freeze permanent for the lifetime of this model runtime.
         */
        public void commit() {
            owner.commitEvaluationFreeze(this);
        }

        /**
         * Rolls back only an uncommitted evaluation-start reservation.
         */
        @Override
        public void close() {
            owner.releaseEvaluationFreeze(this);
        }
    }

    @FunctionalInterface
    interface ProbeFactory {
        ProviderCapabilityProbe create(
            ModelEndpoint endpoint,
            ApiKeyManager apiKeys
        );
    }

    @FunctionalInterface
    interface GatewayFactory {
        ModelGateway create(
            ModelEndpoint endpoint,
            ApiKeyManager apiKeys,
            ProviderCapabilities capabilities,
            Duration connectTimeout,
            Duration hardTimeout
        );
    }

    @FunctionalInterface
    public interface VerifiedProfileSink {
        void persist(
            ModelEndpoint endpoint,
            ProviderCapabilities capabilities
        );

        /**
         * Removes only cached capability metadata while retaining endpoint
         * identity.  Implementations that do not persist a profile may keep
         * the default no-op.
         */
        default void invalidateCapabilities() {
            // Optional for in-memory and test runtimes.
        }
    }
}
