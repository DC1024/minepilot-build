package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Server lifecycle owner for core-skill action execution.
 *
 * <p>This is the production integration port. It resolves the companion by
 * UUID on every operation and rebuilds its fair actuator when either the
 * {@link ServerPlayer} object or play connection changes. Skills only enqueue
 * intents. The runtime must call {@link #postServerTick()} once after skill
 * supervision in every server post tick.</p>
 */
public final class ServerOwnedCoreSkillActuator
        implements CoreSkillActuator {
    private final MinecraftServer server;
    private final LeasedCoreSkillActuator leased;
    private final Consumer<AcceptedAction> actionAudit;

    public ServerOwnedCoreSkillActuator(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this(server, expectedPlayerId, ignored -> {
        });
    }

    public ServerOwnedCoreSkillActuator(
            MinecraftServer server,
            UUID expectedPlayerId,
            Consumer<AcceptedAction> actionAudit
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.actionAudit = Objects.requireNonNull(
            actionAudit,
            "actionAudit"
        );
        UUID playerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        leased = new LeasedCoreSkillActuator(
                new MinecraftBindingSource(server, playerId),
                () -> Integer.toUnsignedLong(server.getTickCount())
        );
    }

    @Override
    public ActionOutcome move(MovementIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (!onServerThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final ActionOutcome outcome = leased.move(intent);
        if ((intent.forward() != 0.0 || intent.strafeLeft() != 0.0)
                && outcome.accepted()) {
            audit("move", outcome);
        }
        return outcome;
    }

    @Override
    public ActionOutcome look(LookIntent intent) {
        return onServerThread()
                ? leased.look(intent)
                : ActionOutcome.WRONG_THREAD;
    }

    @Override
    public ActionOutcome jump() {
        if (!onServerThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final ActionOutcome outcome = leased.jump();
        if (outcome.accepted()) {
            audit("jump", outcome);
        }
        return outcome;
    }

    @Override
    public ActionOutcome stop() {
        return onServerThread()
                ? leased.stop()
                : ActionOutcome.WRONG_THREAD;
    }

    @Override
    public ActionOutcome useMainHandOn(BlockInteractionTarget target) {
        Objects.requireNonNull(target, "target");
        if (!onServerThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final ActionOutcome outcome = leased.useMainHandOn(target);
        if (outcome.accepted()) {
            audit("use_on_block", outcome);
        }
        return outcome;
    }

    @Override
    public ActionOutcome useItem(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        if (!onServerThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final ActionOutcome outcome = leased.useItem(hand);
        if (outcome.accepted()) {
            audit("use_item", outcome);
        }
        return outcome;
    }

    @Override
    public ActionOutcome releaseUse() {
        return onServerThread()
                ? leased.releaseUse()
                : ActionOutcome.WRONG_THREAD;
    }

    /**
     * Performs exactly one leased action-frame execution for this server tick.
     */
    public LeasedCoreSkillActuator.PostTickReport postServerTick() {
        requireServerThread();
        return leased.postTick();
    }

    /**
     * Emergency release for terminal goals, supervisor failures, body
     * failures, and runtime close. It stops input, holds current look, releases
     * item use, and aborts mining. A no-active use/mining operation is treated
     * as a successful release.
     */
    public LeasedCoreSkillActuator.QuiesceReport quiesceNow() {
        requireServerThread();
        return leased.quiesceNow();
    }

    /**
     * Forces the normal post-tick path to apply the full emergency release.
     */
    public void expireLease() {
        requireServerThread();
        leased.expireLease();
    }

    public LeasedCoreSkillActuator.LeaseSnapshot snapshot() {
        requireServerThread();
        return leased.snapshot();
    }

    private boolean onServerThread() {
        return server.isSameThread();
    }

    private void audit(
        final String action,
        final ActionOutcome outcome
    ) {
        try {
            actionAudit.accept(new AcceptedAction(
                action,
                Integer.toUnsignedLong(server.getTickCount()),
                outcome
            ));
        } catch (RuntimeException ignored) {
            // Audit output must never gain authority over a legal action.
        }
    }

    private void requireServerThread() {
        if (!onServerThread()) {
            throw new IllegalStateException(
                    "Core action execution must run on the server thread"
            );
        }
    }

    private static final class MinecraftBindingSource
            implements LeasedCoreSkillActuator.BindingSource {
        private final MinecraftServer server;
        private final UUID playerId;

        private ServerPlayer boundPlayer;
        private ServerGamePacketListenerImpl boundConnection;
        private MinecraftBinding binding;

        private MinecraftBindingSource(
                MinecraftServer server,
                UUID playerId
        ) {
            this.server = server;
            this.playerId = playerId;
        }

        @Override
        public Optional<LeasedCoreSkillActuator.Binding> current() {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null
                    || player.connection == null
                    || player.isRemoved()
                    || !playerId.equals(player.getUUID())) {
                boundPlayer = null;
                boundConnection = null;
                binding = null;
                return Optional.empty();
            }
            if (binding == null
                    || boundPlayer != player
                    || boundConnection != player.connection) {
                boundPlayer = player;
                boundConnection = player.connection;
                binding = new MinecraftBinding(player);
            }
            return Optional.of(binding);
        }
    }

    private static final class MinecraftBinding
            implements LeasedCoreSkillActuator.Binding {
        private final ServerPlayer player;
        private final ServerGamePacketListenerImpl connection;
        private final FairPlayerActuator actuator;

        private MinecraftBinding(ServerPlayer player) {
            this.player = Objects.requireNonNull(player, "player");
            connection = Objects.requireNonNull(
                    player.connection,
                    "player connection"
            );
            actuator = new FairPlayerActuator(player);
        }

        @Override
        public Object playerIdentityToken() {
            return player;
        }

        @Override
        public Object connectionIdentityToken() {
            return connection;
        }

        @Override
        public LookIntent currentLook() {
            ServerPlayer current = connection.getPlayer();
            return new LookIntent(current.getYRot(), current.getXRot());
        }

        @Override
        public ActionOutcome move(MovementIntent intent) {
            return actuator.setMovement(intent);
        }

        @Override
        public ActionOutcome look(LookIntent intent) {
            return actuator.turnTo(intent);
        }

        @Override
        public ActionOutcome jump() {
            return actuator.jump();
        }

        @Override
        public ActionOutcome stop() {
            return actuator.stop();
        }

        @Override
        public ActionOutcome useMainHandOn(
                BlockInteractionTarget target
        ) {
            return actuator.useOnBlock(ActionHand.MAIN_HAND, target);
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            return actuator.useItem(hand);
        }

        @Override
        public ActionOutcome releaseUse() {
            return actuator.releaseUse();
        }

        @Override
        public ActionOutcome abortMining() {
            return actuator.abortMining();
        }

        @Override
        public ActionOutcome tick() {
            return actuator.tick();
        }
    }

    public record AcceptedAction(
        String action,
        long serverTick,
        ActionOutcome outcome
    ) {
        public AcceptedAction {
            action = Objects.requireNonNull(action, "action");
            if (action.isBlank()
                    || action.length() > 64
                    || serverTick < 0) {
                throw new IllegalArgumentException(
                    "Accepted action audit is invalid"
                );
            }
            Objects.requireNonNull(outcome, "outcome");
            if (!outcome.accepted()) {
                throw new IllegalArgumentException(
                    "Only accepted actions may be audited"
                );
            }
        }
    }
}
