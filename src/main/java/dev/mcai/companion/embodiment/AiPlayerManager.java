package dev.mcai.companion.embodiment;

import com.mojang.authlib.GameProfile;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import dev.mcai.companion.agent.AgentNameRules;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.world.CompanionWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-scoped owner of the companion embodiment.
 */
public final class AiPlayerManager {
    private static final Map<MinecraftServer, AiPlayerManager> MANAGERS = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final SessionLifecycle lifecycle = new SessionLifecycle();

    private PendingPlayerSpawn pendingSpawn;
    private AiPlayerSession session;
    private long sessionGeneration;
    private java.util.UUID deferredInitialAnchorPlayer;

    /*
     * Keep the already validated spawn anchor, not only the human UUID. A
     * player can disconnect in the same server tick that requests the one-time
     * initial re-anchor; losing the UUID at that boundary used to leave the AI
     * permanently absent. The value is process-local and is consumed only by
     * the normal ServerPlayer login lifecycle.
     */
    private SafeCompanionSpawnLocator.Anchor deferredInitialAnchor;

    private AiPlayerManager(MinecraftServer server) {
        this.server = server;
    }

    public static OperationResult requestSpawn(MinecraftServer server) {
        return manager(server).requestSpawn(Optional.empty());
    }

    public static OperationResult requestSpawnNear(
            final MinecraftServer server,
            final ServerPlayer anchor
    ) {
        final AiPlayerManager manager = manager(server);
        manager.requireServerThread();
        if (anchor.level().getServer() != server
                || !anchor.isAlive()) {
            return OperationResult.rejected("invalid_spawn_anchor");
        }
        return manager.requestSpawn(Optional.of(
                SafeCompanionSpawnLocator.capture(anchor)
        ));
    }

    /**
     * Development-fixture entry point for the same bounded safe-spawn path
     * used by {@link #requestSpawnNear(MinecraftServer, ServerPlayer)}.
     *
     * <p>The package-private boundary keeps arbitrary coordinates out of
     * commands, MCP, skills and model decisions. Production callers must
     * supply a real online player; the release-excluded GameTest bridge uses
     * this overload only after constructing a vanilla-safe fixture pad.</p>
     */
    static OperationResult requestSpawnAtFixtureAnchor(
            final MinecraftServer server,
            final ServerLevel level,
            final BlockPos origin,
            final float yaw
    ) {
        final AiPlayerManager manager = manager(server);
        manager.requireServerThread();
        if (level.getServer() != server) {
            return OperationResult.rejected("invalid_spawn_anchor");
        }
        return manager.requestSpawn(Optional.of(
                new SafeCompanionSpawnLocator.Anchor(
                        level,
                        origin.immutable(),
                        yaw
                )
        ));
    }

    public static OperationResult ensureSpawnNear(
            final MinecraftServer server,
            final ServerPlayer anchor
    ) {
        final AiPlayerManager manager = manager(server);
        manager.requireServerThread();
        if (manager.lifecycle.state() == SessionState.FAILED) {
            manager.requestRemove();
        }
        if (manager.lifecycle.state() == SessionState.ABSENT) {
            return requestSpawnNear(server, anchor);
        }
        if (manager.lifecycle.state() == SessionState.PREPARING
                && manager.pendingSpawn != null
                && !manager.pendingSpawn.anchored()) {
            /*
             * A server-started no-human preparation has no real-player
             * anchor yet. Let requestSpawnNear replace only that pending
             * preparation with the login player's safe anchor.
             */
            return requestSpawnNear(server, anchor);
        }
        if (manager.lifecycle.state() == SessionState.ACTIVE
                && CompanionWorldData.get(server).bodyNeedsInitialAnchor()) {
            final ServerPlayer body = manager.session == null
                    ? null
                    : manager.session.currentPlayer();
            if (body != null && body.level() != anchor.level()) {
                /*
                 * The first human may join a different dimension from a
                 * body that has already entered a portal or resumed a saved
                 * task.  Re-logging that body at the human's anchor would be
                 * a cross-dimension gameplay teleport in disguise and can
                 * destroy an otherwise valid task.  Claim the one-time
                 * startup provenance in place; ordinary cross-dimension
                 * travel remains an explicit skill decision.
                 */
                CompanionWorldData.get(server).markBodyAnchored();
                manager.deferredInitialAnchorPlayer = null;
                manager.deferredInitialAnchor = null;
                return OperationResult.accepted(
                        "initial_anchor_claimed_current_dimension"
                );
            }
            if (!manager.canReplaceInitialBody()) {
                manager.deferredInitialAnchorPlayer = anchor.getUUID();
                manager.deferredInitialAnchor =
                        SafeCompanionSpawnLocator.capture(anchor);
                return OperationResult.accepted(
                        "initial_anchor_deferred_busy"
                );
            }
            return manager.reanchorInitialBodyNear(anchor);
        }
        return OperationResult.accepted(
                manager.lifecycle.state() == SessionState.ACTIVE
                    ? "already_active"
                    : "already_preparing"
        );
    }

    public static OperationResult requestRemove(MinecraftServer server) {
        return manager(server).requestRemove();
    }

    public static Status status(MinecraftServer server) {
        return manager(server).status();
    }

    public static Optional<ServerPlayer> onlinePlayer(MinecraftServer server) {
        AiPlayerManager manager = manager(server);
        manager.requireServerThread();
        return manager.session == null
                ? Optional.empty()
                : Optional.ofNullable(manager.session.currentPlayer());
    }

    static Optional<HeadlessConnectionPump.AuditSnapshot> connectionAudit(
            final MinecraftServer server
    ) {
        final AiPlayerManager manager = manager(server);
        manager.requireServerThread();
        return manager.session == null
                ? Optional.empty()
                : Optional.of(manager.session.auditSnapshot());
    }

    /**
     * Returns the bounded, log-safe transport counters for the live body.
     *
     * <p>The raw headless connection and Netty channel remain package-private;
     * runtime audit code receives only immutable counters and cannot use this
     * boundary to inject packets, teleport, or mutate the body.</p>
     */
    public static Optional<TransportAudit> transportAudit(
            final MinecraftServer server
    ) {
        return connectionAudit(server).map(AiPlayerManager::toTransportAudit);
    }

    static void tickServer(MinecraftServer server) {
        manager(server).tick();
    }

    static void shutdown(MinecraftServer server) {
        shutdownWithAudit(server);
    }

    /**
     * Stops the body and returns the final transport snapshot after vanilla's
     * disconnect callback has been handled.
     */
    static Optional<TransportAudit> shutdownWithAudit(
            final MinecraftServer server
    ) {
        AiPlayerManager manager = MANAGERS.remove(server);
        return manager == null
                ? Optional.empty()
                : manager.removeForShutdown();
    }

    private static AiPlayerManager manager(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, AiPlayerManager::new);
    }

    private OperationResult requestSpawn(
            final Optional<SafeCompanionSpawnLocator.Anchor> anchor
    ) {
        requireServerThread();
        if (lifecycle.state() == SessionState.PREPARING
                && anchor.isPresent()
                && pendingSpawn != null
                && !pendingSpawn.anchored()) {
            /*
             * ServerStartedEvent can begin the no-human, vanilla spawn
             * preparation before a single-player client finishes logging in.
             * Reusing that preparation leaves the body at the saved/world
             * spawn instead of beside the real player.  This is the only
             * safe replacement point: no ServerPlayer has joined yet, so we
             * cancel the local preparation and restart against the player's
             * bounded SafeCompanionSpawnLocator anchor.  Once ACTIVE, this
             * path is deliberately unavailable; following is a gameplay
             * skill, not a spawn-time teleport.
             */
            pendingSpawn.close();
            pendingSpawn = null;
            advanceSessionGeneration();
            lifecycle.beginStop();
            lifecycle.stopped();
            return startSpawn(anchor);
        }
        return switch (lifecycle.state()) {
            case ABSENT -> startSpawn(anchor);
            case PREPARING -> OperationResult.rejected("already_preparing");
            case ACTIVE -> OperationResult.rejected("already_active");
            case STOPPING -> OperationResult.rejected("stopping");
            case FAILED -> OperationResult.rejected("failed_remove_first");
        };
    }

    /**
     * Reconciles the one exceptional startup case where a dedicated server
     * had no human online when the body completed its vanilla spawn.  This is
     * deliberately a remove-and-relogin lifecycle, not a teleport: the
     * current ServerPlayer is closed through the normal connection path and
     * a fresh bounded {@link SafeCompanionSpawnLocator} anchor is prepared.
     * It is allowed only while the companion is idle, so a player joining a
     * running task cannot move the body under the model's feet.
     */
    private OperationResult reanchorInitialBodyNear(
            final ServerPlayer anchor
    ) {
        if (!canReplaceInitialBody()) {
            deferredInitialAnchorPlayer = anchor.getUUID();
            deferredInitialAnchor = SafeCompanionSpawnLocator.capture(anchor);
            return OperationResult.accepted("initial_anchor_deferred_busy");
        }
        final SafeCompanionSpawnLocator.Anchor validatedAnchor =
                SafeCompanionSpawnLocator.capture(anchor);
        /*
         * Never remove the only authoritative body before proving that the
         * bounded vanilla dismount search can produce a complete player
         * placement.  A GameTest void, a transiently unloaded login area, or
         * a protected/occupied edge can have no valid adjacent feet position.
         * Keeping the current body and claiming the one-time provenance is
         * safer than turning a visible companion into an absent one.
         */
        if (SafeCompanionSpawnLocator.locate(validatedAnchor).isEmpty()) {
            CompanionWorldData.get(server).markBodyAnchored();
            deferredInitialAnchorPlayer = null;
            deferredInitialAnchor = null;
            return OperationResult.accepted(
                    "initial_anchor_kept_current_body_no_safe_placement"
            );
        }
        /*
         * The normal disconnect callback removes the old ServerPlayer from
         * PlayerList as part of closing its headless connection.  That
         * callback can finish after this server-thread invocation returns;
         * starting the replacement in the same call therefore races the
         * UUID-already-online guard in startSpawn().  Record the real player
         * anchor and let the next lifecycle tick start the replacement only
         * after the old authoritative entry has disappeared.
         */
        deferredInitialAnchorPlayer = anchor.getUUID();
        deferredInitialAnchor = validatedAnchor;
        final OperationResult removed = requestRemove();
        if (!removed.accepted()) {
            return OperationResult.rejected("initial_anchor_remove_failed");
        }
        return OperationResult.accepted("initial_anchor_relogin_pending");
    }

    private boolean canReplaceInitialBody() {
        final var runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server() == server);
        if (runtime.isEmpty()) {
            return true;
        }
        final GoalStatus goalStatus = runtime.orElseThrow()
                .goals()
                .snapshot()
                .status();
        if (goalStatus == GoalStatus.RUNNING
                || goalStatus == GoalStatus.CANCEL_PENDING) {
            return false;
        }
        if (runtime.orElseThrow().survival().state()
                != dev.mcai.companion.skills.core.EmergencySurvivalController.State.CLEAR) {
            return false;
        }
        if (runtime.orElseThrow().behaviorArbiter().latest()
                .filter(resolution -> resolution.claimedBy(
                        dev.mcai.companion.control.BehaviorArbiter.Lane.EMERGENCY_SURVIVAL
                ))
                .isPresent()) {
            return false;
        }
        final var skillState = runtime.orElseThrow()
                .skillSupervisor()
                .snapshot()
                .state();
        return skillState != SkillSupervisor.State.RUNNING
                && skillState != SkillSupervisor.State.CANCEL_PENDING;
    }

    private OperationResult startSpawn(
            final Optional<SafeCompanionSpawnLocator.Anchor> anchor
    ) {
        CompanionWorldData identity = CompanionWorldData.get(server);
        if (identity.hardcoreDead()) {
            lifecycle.beginSpawn();
            lifecycle.fail("hardcore_permanent_death");
            return OperationResult.rejected("hardcore_permanent_death");
        }
        final AgentNameRules.Validation nameAvailability =
            AgentNameRules.validateAvailable(
                server,
                identity.companionUuid(),
                identity.displayName(),
                identity.displayName(),
                identity.knownPlayerNames()
            );
        if (!nameAvailability.accepted()) {
            return OperationResult.rejected(nameAvailability.code());
        }
        if (server.getPlayerList().getPlayer(identity.companionUuid()) != null) {
            return OperationResult.rejected("uuid_already_online");
        }

        lifecycle.beginSpawn();
        try {
            pendingSpawn = new PendingPlayerSpawn(
                    server,
                    new GameProfile(
                            identity.companionUuid(),
                            identity.displayName()
                    ),
                    anchor
            );
            advanceSessionGeneration();
            return OperationResult.accepted("spawn_started");
        } catch (RuntimeException exception) {
            lifecycle.fail("spawn_initialization_failed");
            return OperationResult.rejected("spawn_initialization_failed");
        }
    }

    private OperationResult requestRemove() {
        requireServerThread();
        if (lifecycle.state() == SessionState.ABSENT) {
            return OperationResult.rejected("already_absent");
        }
        if (lifecycle.state() == SessionState.STOPPING) {
            return OperationResult.rejected("already_stopping");
        }

        lifecycle.beginStop();
        closeOwnedResources();
        advanceSessionGeneration();
        lifecycle.stopped();
        return OperationResult.accepted("removed");
    }

    private void tick() {
        requireServerThread();
        if (lifecycle.state() == SessionState.ABSENT
                && tryDeferredInitialAnchor()) {
            return;
        }
        if (lifecycle.state() == SessionState.PREPARING) {
            tickPendingSpawn();
        }
        if (lifecycle.state() == SessionState.ACTIVE) {
            if (tryDeferredInitialAnchor()) {
                return;
            }
            if (lifecycle.state() == SessionState.ACTIVE) {
                tickActiveSession();
            }
        }
    }

    private boolean tryDeferredInitialAnchor() {
        if (!CompanionWorldData.get(server).bodyNeedsInitialAnchor()
                || deferredInitialAnchor == null) {
            return false;
        }
        final SafeCompanionSpawnLocator.Anchor anchor =
                deferredInitialAnchor;
        if (anchor.level().getServer() != server) {
            deferredInitialAnchorPlayer = null;
            deferredInitialAnchor = null;
            return false;
        }
        if (lifecycle.state() == SessionState.ABSENT) {
            /*
             * A just-closed headless login may still be present in PlayerList
             * for one or more callbacks. Wait without replacing it, then
             * start one ordinary anchored login once the UUID is genuinely
             * free. The captured safe anchor remains valid even if the human
             * disconnects before this retry tick; this is still a lifecycle
             * retry, never a gameplay teleport.
             */
            if (server.getPlayerList().getPlayer(
                        CompanionWorldData.get(server).companionUuid()
                    ) != null) {
                return false;
            }
            final OperationResult result = requestSpawn(Optional.of(anchor));
            if (result.accepted()) {
                deferredInitialAnchorPlayer = null;
                deferredInitialAnchor = null;
                return true;
            }
            return false;
        }
        if (lifecycle.state() != SessionState.ACTIVE) {
            return false;
        }
        final ServerPlayer currentAnchor = deferredInitialAnchorPlayer == null
                ? null
                : server.getPlayerList().getPlayer(
                        deferredInitialAnchorPlayer
                );
        if (currentAnchor == null) {
            return requestRemove().accepted();
        }
        final OperationResult result = reanchorInitialBodyNear(currentAnchor);
        return result.accepted()
                && ("initial_anchor_replacement_started".equals(
                        result.code()
                    ) || "initial_anchor_relogin_pending".equals(
                        result.code()
                    ));
    }

    private void tickPendingSpawn() {
        try {
            final boolean anchored = pendingSpawn.anchored();
            Optional<AiPlayerSession> completed = pendingSpawn.tick();
            if (completed.isPresent()) {
                session = completed.orElseThrow();
                pendingSpawn = null;
                CompanionWorldData.get(server).markBodySpawned(anchored);
                lifecycle.activate();
            }
        } catch (RuntimeException exception) {
            if (pendingSpawn != null) {
                pendingSpawn.close();
                pendingSpawn = null;
            }
            advanceSessionGeneration();
            lifecycle.fail("spawn_failed");
        }
    }

    private void tickActiveSession() {
        try {
            AiPlayerSession.TickResult result = session.tick();
            if (result == AiPlayerSession.TickResult.HARDCORE_DEATH) {
                session.close();
                session = null;
                CompanionWorldData.get(server).markHardcoreDead();
                advanceSessionGeneration();
                lifecycle.fail("hardcore_death");
            } else if (result == AiPlayerSession.TickResult.RESPAWNED) {
                /*
                 * Vanilla replaces the ServerPlayer object during respawn
                 * while retaining this transport session. Every body-bound
                 * observation, action lease and local navigation map must be
                 * invalidated just as it would be for a fresh login.
                 */
                advanceSessionGeneration();
            } else if (result == AiPlayerSession.TickResult.DISCONNECTED) {
                session.close();
                session = null;
                advanceSessionGeneration();
                lifecycle.fail("connection_closed");
            }
        } catch (RuntimeException exception) {
            session.close();
            session = null;
            advanceSessionGeneration();
            lifecycle.fail("session_tick_failed");
        }
    }

    private Status status() {
        requireServerThread();
        boolean online = session != null && session.isOnline();
        return new Status(
                lifecycle.state(),
                CompanionWorldData.get(server).displayName(),
                online,
                lifecycle.failureCode(),
                sessionGeneration
        );
    }

    private Optional<TransportAudit> removeForShutdown() {
        requireServerThread();
        if (lifecycle.state() == SessionState.ABSENT) {
            return Optional.empty();
        }
        if (lifecycle.state() != SessionState.STOPPING) {
            lifecycle.beginStop();
        }
        final AiPlayerSession closedSession = session;
        closeOwnedResources();
        advanceSessionGeneration();
        lifecycle.stopped();
        return closedSession == null
                ? Optional.empty()
                : Optional.of(toTransportAudit(closedSession.auditSnapshot()));
    }

    private void advanceSessionGeneration() {
        sessionGeneration = Math.addExact(sessionGeneration, 1L);
    }

    /** Log-safe, immutable transport evidence exported to the runtime audit. */
    public record TransportAudit(
            long discardedPackets,
            long keepAliveAcknowledgements,
            long teleportAcknowledgements,
            long chunkBatchAcknowledgements,
            long endCreditsRespawnRequests,
            int largestDrain,
            int outboundQueueHighWatermark,
            int unreleasedOutboundPackets,
            boolean disconnectionHandled
    ) {
    }

    private static TransportAudit toTransportAudit(
            final HeadlessConnectionPump.AuditSnapshot snapshot
    ) {
        return new TransportAudit(
                snapshot.discardedPackets(),
                snapshot.keepAliveAcknowledgements(),
                snapshot.teleportAcknowledgements(),
                snapshot.chunkBatchAcknowledgements(),
                snapshot.endCreditsRespawnRequests(),
                snapshot.largestDrain(),
                snapshot.outboundQueueHighWatermark(),
                snapshot.unreleasedOutboundPackets(),
                snapshot.disconnectionHandled()
        );
    }

    private void closeOwnedResources() {
        if (pendingSpawn != null) {
            pendingSpawn.close();
            pendingSpawn = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("AI player lifecycle must run on the server thread");
        }
    }

    public record OperationResult(boolean accepted, String code) {
        static OperationResult accepted(String code) {
            return new OperationResult(true, code);
        }

        static OperationResult rejected(String code) {
            return new OperationResult(false, code);
        }
    }

    public record Status(
            SessionState state,
            String profileName,
            boolean online,
            String failureCode,
            long sessionGeneration
    ) {
    }
}
