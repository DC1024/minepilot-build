package dev.mcai.companion.modelsetup;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.agent.AgentAccentColor;
import dev.mcai.companion.agent.AgentNameRules;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.modelsetup.network.ClientboundModelSetupState;
import dev.mcai.companion.modelsetup.network.ModelSetupNetwork;
import dev.mcai.companion.modelsetup.network.ServerboundModelSetupApply;
import dev.mcai.companion.modelsetup.network.ServerboundModelSetupOpen;
import dev.mcai.companion.runtime.ModelRuntime;
import dev.mcai.companion.security.CompanionCommandAccess;
import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.world.CompanionWorldData;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Secure server authority and client registration boundary for the three
 * visible model setup fields.
 *
 * <p>The runtime owner must attach its server-scoped {@link ModelRuntime}
 * after construction and close the returned handle with that runtime. This
 * avoids a second credential manager and ensures all writes go through the
 * existing {@code ApiKeyManager} owned by the runtime.</p>
 */
public final class ModelSetupModule {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<MinecraftServer, ModelRuntime> RUNTIMES =
        new IdentityHashMap<>();
    private static final ModelSetupSessionRegistry SESSIONS =
        new ModelSetupSessionRegistry();

    private ModelSetupModule() {
    }

    /**
     * Returns whether the setup result depends on the current JVM session.
     * A profile edit with storage="unchanged" keeps the credential that was
     * restored from an OS store or an environment injection and must not show
     * a false restart warning.
     */
    static boolean requiresCredentialRestart(final String storage) {
        return "process_only".equals(storage)
            || "process_only_secure_store_unavailable".equals(storage);
    }

    /**
     * Chooses a truthful, non-secret setup status after a successful live
     * capability check. The storage identifier originates only from the
     * platform credential adapter; it is not supplied by a client packet.
     *
     * <p>Do not collapse every non-macOS value into "process only". That
     * made a successfully round-tripped Windows DPAPI or Linux Secret Service
     * credential look as though it would disappear on the next world start,
     * which led players to re-enter the key unnecessarily.</p>
     */
    static String verifiedCredentialStatus(
            final ModelRuntime.ProfileUpdateOutcome outcome
    ) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome.credentialStorage()) {
            case "macos_keychain" -> "saved_verified_keychain";
            case "windows_dpapi_current_user", "windows_dpapi" ->
                    "saved_verified_windows_dpapi";
            case "linux_secret_service" ->
                    "saved_verified_linux_secret_service";
            case "unchanged" -> "saved_verified_unchanged";
            case "process_only_secure_store_unavailable" ->
                    "saved_verified_process_restart_required";
            case "process_only" -> "saved_verified_process";
            default -> outcome.credentialPersistent()
                    ? "saved_verified_secure_store"
                    : "saved_verified_process";
        };
    }

    public static void register(final FMLJavaModLoadingContext context) {
        Objects.requireNonNull(context, "context");
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ModelSetupNetwork.initialize();
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                SESSIONS.remove(player.getUUID());
            }
        });
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (!(event.getEntity() instanceof ServerPlayer player)
                || AiProfileMarker.isMarked(player.getGameProfile())) {
                return;
            }
            final MinecraftServer server = player.level().getServer();
            final CompanionWorldData data = CompanionWorldData.get(server);
            data.rememberPlayerName(player.getGameProfile().name());
            if (data.displayName().equalsIgnoreCase(
                    player.getGameProfile().name()
                )
                && AiPlayerManager.onlinePlayer(server).isPresent()) {
                AiPlayerManager.requestRemove(server);
            }
        });
        ServerStoppedEvent.BUS.addListener(event -> {
            synchronized (RUNTIMES) {
                RUNTIMES.remove(event.getServer());
            }
            SESSIONS.clear();
        });
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClient(context.getContainer());
        }
    }

    public static RuntimeAttachment attach(
        final MinecraftServer server,
        final ModelRuntime runtime
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(runtime, "runtime");
        synchronized (RUNTIMES) {
            final ModelRuntime previous = RUNTIMES.put(server, runtime);
            if (previous != null && previous != runtime) {
                RUNTIMES.put(server, previous);
                throw new IllegalStateException(
                    "A model setup runtime is already attached"
                );
            }
        }
        return new RuntimeAttachment(server, runtime);
    }

    public static void handleOpen(
        final ServerPlayer player,
        final ServerboundModelSetupOpen request
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        final MinecraftServer server = player.level().getServer();
        final ModelRuntime runtime = runtime(server);
        if (runtime == null) {
            sendDenied(player, request.requestId(), "server_runtime_unavailable");
            return;
        }
        if (!mayEdit(player)) {
            sendDenied(player, request.requestId(), "permission_denied");
            return;
        }
        if (!hasSecureTransport(player)) {
            sendDenied(
                player,
                request.requestId(),
                "encrypted_transport_required"
            );
            return;
        }
        /*
         * A new server runtime owns a fresh ApiKeyManager.  The platform
         * keychain/Secret Service restore is intentionally asynchronous, so
         * sending the state immediately would make a valid persisted key look
         * missing and force the player to enter it again.  Wait only for this
         * local restore; no provider request is made here.
         */
        runtime.restoreCredential().whenComplete((restored, throwable) ->
            server.execute(() -> {
                if (runtime(server) != runtime || player.hasDisconnected()) {
                    return;
                }
                sendFreshState(
                    player,
                    runtime,
                    request.requestId(),
                    throwable == null ? "ready" : "credential_restore_failed",
                    false
                );
            })
        );
    }

    public static void handleApply(
        final ServerPlayer player,
        final ServerboundModelSetupApply request
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        final MinecraftServer server = player.level().getServer();
        final ModelRuntime runtime = runtime(server);
        if (runtime == null) {
            sendDenied(player, request.requestId(), "server_runtime_unavailable");
            return;
        }
        if (!mayEdit(player)) {
            SESSIONS.remove(player.getUUID());
            sendDenied(player, request.requestId(), "permission_denied");
            return;
        }
        if (!hasSecureTransport(player)) {
            SESSIONS.remove(player.getUUID());
            sendDenied(
                player,
                request.requestId(),
                "encrypted_transport_required"
            );
            return;
        }
        if (EvaluationModelLock.isLocked(server, runtime)) {
            SESSIONS.remove(player.getUUID());
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                "evaluation_model_frozen",
                false
            );
            return;
        }
        final byte[] candidateToken = request.sessionToken();
        final boolean validSession;
        try {
            validSession = SESSIONS.consume(
                player.getUUID(),
                player.connection.getConnection(),
                candidateToken,
                request.requestId()
            );
        } finally {
            Arrays.fill(candidateToken, (byte) 0);
        }
        if (!validSession) {
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                "invalid_setup_session",
                false
            );
            return;
        }

        final CompanionWorldData worldData = CompanionWorldData.get(server);
        final AgentNameRules.Validation nameValidation =
            AgentNameRules.validateAvailable(
                server,
                worldData.companionUuid(),
                worldData.displayName(),
                request.agentName(),
                worldData.knownPlayerNames()
            );
        if (!nameValidation.accepted()) {
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                nameValidation.code(),
                false
            );
            return;
        }
        final AgentAccentColor accentColor;
        try {
            accentColor = AgentAccentColor.requireKnown(
                request.accentColor()
            );
        } catch (IllegalArgumentException exception) {
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                "accent_color_unknown",
                false
            );
            return;
        }

        final byte[] encodedCredential = request.apiKeyUtf8();
        final char[] credential;
        try {
            credential = encodedCredential.length == 0
                ? null
                : decodeCredential(encodedCredential);
        } catch (CharacterCodingException exception) {
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                "invalid_api_key_encoding",
                false
            );
            return;
        } finally {
            Arrays.fill(encodedCredential, (byte) 0);
        }

        final java.util.concurrent.CompletionStage<
            ModelRuntime.ProfileUpdateOutcome
        > update;
        try {
            update = runtime.updateProfile(
                request.baseUrl(),
                request.modelName(),
                credential,
                request.preferPersistentCredential()
            );
        } catch (RuntimeException exception) {
            sendFreshState(
                player,
                runtime,
                request.requestId(),
                "profile_update_failed",
                false
            );
            return;
        } finally {
            if (credential != null) {
                Arrays.fill(credential, '\0');
            }
        }
        update.whenComplete((outcome, throwable) ->
            server.execute(() -> {
                if (throwable != null || outcome == null) {
                    sendFreshState(
                        player,
                        runtime,
                        request.requestId(),
                        "profile_update_failed",
                        false
                    );
                    return;
                }
                if (!outcome.accepted()) {
                    sendFreshState(
                        player,
                        runtime,
                        request.requestId(),
                        outcome.code(),
                        false
                    );
                    return;
                }
                if (runtime(server) != runtime) {
                    return;
                }
                try {
                    final AgentNameRules.Validation currentValidation =
                        AgentNameRules.validateAvailable(
                            server,
                            worldData.companionUuid(),
                            worldData.displayName(),
                            nameValidation.normalized(),
                            worldData.knownPlayerNames()
                        );
                    if (!currentValidation.accepted()) {
                        sendFreshState(
                            player,
                            runtime,
                            request.requestId(),
                            currentValidation.code(),
                            false
                        );
                        return;
                    }
                    final boolean refreshIdentity =
                        !worldData.displayName().equals(
                            currentValidation.normalized()
                        )
                            && AiPlayerManager.onlinePlayer(
                                server
                            ).isPresent();
                    if (refreshIdentity) {
                        final var removed =
                            AiPlayerManager.requestRemove(server);
                        if (!removed.accepted()) {
                            sendFreshState(
                                player,
                                runtime,
                                request.requestId(),
                                "agent_identity_refresh_failed",
                                false
                            );
                            return;
                        }
                    }
                    CompanionConfig.MODEL_BASE_URL.set(
                        outcome.normalizedBaseUrl()
                    );
                    CompanionConfig.MODEL_NAME.set(outcome.modelName());
                    CompanionConfig.SPEC.save();
                    new ModelProfileStore(
                            FMLPaths.CONFIGDIR.get()
                    ).save(
                            outcome.normalizedBaseUrl(),
                            outcome.modelName()
                    );
                    worldData.updateAgentPresentation(
                        currentValidation.normalized(),
                        accentColor,
                        request.temperature(),
                        request.systemPrompt(),
                        request.onboardingCompleted()
                    );
                    if (refreshIdentity) {
                        final var respawned =
                            AiPlayerManager.requestSpawnNear(
                                    server,
                                    player
                            );
                        if (!respawned.accepted()) {
                            sendFreshState(
                                player,
                                runtime,
                                request.requestId(),
                                "agent_identity_refresh_failed",
                                false
                            );
                            return;
                        }
                    } else {
                        final var present =
                                AiPlayerManager.ensureSpawnNear(
                                        server,
                                        player
                                );
                        if (!present.accepted()) {
                            sendFreshState(
                                player,
                                runtime,
                                request.requestId(),
                                "agent_body_start_failed",
                                false
                            );
                            return;
                        }
                    }
                } catch (RuntimeException exception) {
                    sendFreshState(
                        player,
                        runtime,
                        request.requestId(),
                        "profile_active_config_save_failed",
                        false
                    );
                    return;
                }
                runtime.probeExplicitly().whenComplete(
                    (probe, probeThrowable) ->
                        server.execute(() -> {
                            final String code;
                            /*
                             * Only an explicitly process-only credential
                             * needs a restart warning.  An update that keeps
                             * an already restored Keychain/DPAPI/Secret
                             * Service or environment credential reports
                             * storage="unchanged" and is restart-safe; using
                             * !credentialPersistent() here incorrectly
                             * marked every endpoint/model-only edit as a
                             * restart failure.
                             */
                            final boolean restartRequired =
                                requiresCredentialRestart(
                                    outcome.credentialStorage()
                                );
                            if (probeThrowable != null || probe == null) {
                                code = "saved_probe_failed";
                            } else if (probe
                                instanceof CapabilityProbeOutcome.Supported) {
                                code = verifiedCredentialStatus(outcome);
                            } else {
                                final CapabilityProbeOutcome.Failure failure =
                                    (CapabilityProbeOutcome.Failure) probe;
                                code = "saved_probe_"
                                    + failure.error()
                                        .kind()
                                        .name()
                                        .toLowerCase(Locale.ROOT);
                            }
                            sendFreshState(
                                player,
                                runtime,
                                request.requestId(),
                                code,
                                restartRequired
                            );
                        })
                );
            })
        );
    }

    private static char[] decodeCredential(final byte[] encoded)
        throws CharacterCodingException {
        final CharBuffer decoded = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded));
        final char[] result = new char[decoded.remaining()];
        decoded.get(result);
        if (decoded.hasArray()) {
            Arrays.fill(decoded.array(), '\0');
        }
        return result;
    }

    private static void sendFreshState(
        final ServerPlayer player,
        final ModelRuntime runtime,
        final long requestId,
        final String requestedCode,
        final boolean restartRequired
    ) {
        if (player.hasDisconnected()) {
            return;
        }
        final ModelRuntime.SetupSnapshot snapshot = runtime.snapshot();
        final CompanionWorldData worldData = CompanionWorldData.get(
            player.level().getServer()
        );
        final boolean authorized = mayEdit(player);
        final boolean locked = EvaluationModelLock.isLocked(
            player.level().getServer(),
            runtime
        );
        if (locked) {
            SESSIONS.remove(player.getUUID());
        }
        final boolean canEdit =
            authorized
                && hasSecureTransport(player)
                && !locked
                && !snapshot.probeInFlight();
        final byte[] token = canEdit
            ? SESSIONS.issue(
                player.getUUID(),
                player.connection.getConnection(),
                requestId
            )
            : new byte[0];
        final String code;
        if (locked) {
            code = "evaluation_model_frozen";
        } else if ("ready".equals(requestedCode)
                && !snapshot.configurationErrorCode().isBlank()) {
            /*
             * Preserve a provider-authentication/configuration failure when
             * the player reopens the screen.  Returning "ready" here made a
             * rejected key look like a healthy gateway and obscured the
             * reason the body was safely idle.
             */
            code = snapshot.configurationErrorCode();
        } else {
            code = requestedCode;
        }
        ModelSetupNetwork.sendState(
            player,
            new ClientboundModelSetupState(
                requestId,
                token,
                snapshot.endpointConfigured()
                    ? runtime.profileForEvaluation().baseUrl()
                    : CompanionConfig.MODEL_BASE_URL.get(),
                snapshot.endpointConfigured()
                    ? snapshot.modelName()
                    : CompanionConfig.MODEL_NAME.get(),
                worldData.displayName(),
                worldData.accentColorName(),
                worldData.temperature(),
                worldData.agentSystemPrompt(),
                worldData.onboardingCompleted(),
                AiPlayerManager.onlinePlayer(
                    player.level().getServer()
                ).isPresent(),
                canEdit,
                snapshot.credentialAvailable(),
                locked,
                snapshot.probeInFlight(),
                snapshot.gatewayReady(),
                restartRequired,
                code
            )
        );
        Arrays.fill(token, (byte) 0);
    }

    private static void sendDenied(
        final ServerPlayer player,
        final long requestId,
        final String code
    ) {
        ModelSetupNetwork.sendState(
            player,
            new ClientboundModelSetupState(
                requestId,
                new byte[0],
                "",
                "",
                "",
                "emerald",
                0.2,
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                code
            )
        );
    }

    private static ModelRuntime runtime(final MinecraftServer server) {
        synchronized (RUNTIMES) {
            return RUNTIMES.get(server);
        }
    }

    private static boolean mayEdit(final ServerPlayer player) {
        return CompanionCommandAccess.mayAdmin(
            player.createCommandSourceStack()
        );
    }

    private static boolean hasSecureTransport(final ServerPlayer player) {
        final var connection = player.connection.getConnection();
        if (connection.isMemoryConnection()) {
            return true;
        }
        /*
         * Minecraft 26.2 removed Connection#isEncrypted.  The vanilla
         * encryption setup installs both named handlers atomically; inspect
         * the actual live pipeline instead of weakening the remote-admin
         * transport requirement.
         */
        final var pipeline = connection.channel().pipeline();
        return pipeline.get("decrypt") != null
                && pipeline.get("encrypt") != null;
    }

    private static void registerClient(final ModContainer container) {
        try {
            Class.forName(
                "dev.mcai.companion.client.modelsetup.ClientModelSetupRegistration"
            ).getMethod("register", ModContainer.class).invoke(null, container);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Could not initialize the model setup client screen",
                exception
            );
        }
    }

    public static final class RuntimeAttachment implements AutoCloseable {
        private final MinecraftServer server;
        private final ModelRuntime runtime;
        private boolean closed;

        private RuntimeAttachment(
            final MinecraftServer server,
            final ModelRuntime runtime
        ) {
            this.server = server;
            this.runtime = runtime;
        }

        @Override
        public void close() {
            synchronized (RUNTIMES) {
                if (!closed && RUNTIMES.get(server) == runtime) {
                    RUNTIMES.remove(server);
                }
                closed = true;
            }
        }
    }
}
