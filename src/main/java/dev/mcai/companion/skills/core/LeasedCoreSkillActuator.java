package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Dynamic action binding with a one-server-tick intent lease.
 *
 * <p>Skills enqueue legal player intents through this object. The server owner
 * must call {@link #postTick()} once from its post-tick path. The call invokes
 * the bound action driver's physics tick at most once for a given tick number.
 * If no accepted intent renewed the current tick's lease, movement is stopped
 * and the current look is held before physics advances. This makes a stalled,
 * cancelled, or failed supervisor fail closed instead of leaving sticky
 * movement input behind.</p>
 *
 * <p>The binding source is resolved for every operation. Identity-token
 * changes invalidate the lease, allowing respawn or connection replacement to
 * rebind without a stale player reference. This class is deliberately
 * loader-independent so its lease and rebind behavior can be tested without a
 * Minecraft process.</p>
 */
public final class LeasedCoreSkillActuator implements CoreSkillActuator {
    private static final long NO_TICK = Long.MIN_VALUE;

    private final BindingSource bindings;
    private final LongSupplier tickSource;

    private Binding activeBinding;
    private Object playerToken;
    private Object connectionToken;
    private long bindingGeneration;
    private long leasedTick = NO_TICK;
    private long leasedGeneration = NO_TICK;
    private long lastExecutedTick = NO_TICK;

    public LeasedCoreSkillActuator(
            BindingSource bindings,
            LongSupplier tickSource
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
    }

    @Override
    public ActionOutcome move(MovementIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return dispatch(binding -> binding.move(intent));
    }

    @Override
    public ActionOutcome look(LookIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return dispatch(binding -> binding.look(intent));
    }

    @Override
    public ActionOutcome jump() {
        return dispatch(Binding::jump);
    }

    @Override
    public ActionOutcome stop() {
        return dispatch(Binding::stop);
    }

    @Override
    public ActionOutcome useMainHandOn(BlockInteractionTarget target) {
        Objects.requireNonNull(target, "target");
        return dispatch(binding -> binding.useMainHandOn(target));
    }

    @Override
    public ActionOutcome useItem(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        return dispatch(binding -> binding.useItem(hand));
    }

    @Override
    public ActionOutcome releaseUse() {
        return dispatch(Binding::releaseUse);
    }

    /**
     * Advances the active action driver at most once for the current tick.
     *
     * <p>This is the only method in the core-skill integration that calls the
     * driver's tick operation.</p>
     */
    public PostTickReport postTick() {
        long tick = currentTick();
        Resolution resolution = resolve();
        if (resolution.binding().isEmpty()) {
            return PostTickReport.unavailable(
                    tick,
                    bindingGeneration,
                    ActionOutcome.PLAYER_UNAVAILABLE
            );
        }
        Binding binding = resolution.binding().orElseThrow();

        if (tick == lastExecutedTick) {
            QuiesceReport quiesce = QuiesceReport.notApplied();
            if (resolution.rebound()) {
                quiesce = quiesce(binding);
            }
            return PostTickReport.alreadyExecuted(
                    tick,
                    bindingGeneration,
                    quiesce
            );
        }
        if (lastExecutedTick != NO_TICK && tick < lastExecutedTick) {
            QuiesceReport quiet = quiesce(binding);
            return PostTickReport.unavailable(
                    tick,
                    bindingGeneration,
                    quiet.successful()
                            ? ActionOutcome.INVALID_PLAYER_STATE
                            : quiet.firstFailure()
            );
        }

        boolean leased = leasedTick == tick
                && leasedGeneration == bindingGeneration;
        QuiesceReport quiesce = QuiesceReport.notApplied();
        if (!leased) {
            quiesce = quiesce(binding);
        }
        // Mark before dispatch so even an unexpected driver exception cannot
        // permit a second physics advance in the same server tick.
        lastExecutedTick = tick;
        ActionOutcome tickOutcome = safeAction(binding::tick);
        return PostTickReport.executed(
                tick,
                bindingGeneration,
                quiesce,
                tickOutcome,
                resolution.rebound()
        );
    }

    /**
     * Immediately releases movement and item use, aborts mining, and pins look
     * to the current orientation. Runtime terminal-state transitions should
     * call this, then still invoke {@link #postTick()} in the normal post-tick
     * path.
     */
    public QuiesceReport quiesceNow() {
        Resolution resolution = resolve();
        if (resolution.binding().isEmpty()) {
            return QuiesceReport.unavailable();
        }
        Binding binding = resolution.binding().orElseThrow();
        QuiesceReport outcome = quiesce(binding);
        if (outcome.successful()) {
            renewLease();
        } else {
            expireLease();
        }
        return outcome;
    }

    /**
     * Invalidates the intent lease without touching game state. The next
     * {@link #postTick()} applies the fail-safe stop.
     */
    public void expireLease() {
        leasedTick = NO_TICK;
        leasedGeneration = NO_TICK;
    }

    public LeaseSnapshot snapshot() {
        return new LeaseSnapshot(
                activeBinding != null,
                bindingGeneration,
                leasedTick,
                lastExecutedTick
        );
    }

    private ActionOutcome dispatch(
            Function<Binding, ActionOutcome> operation
    ) {
        Resolution resolution = resolve();
        if (resolution.binding().isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        ActionOutcome outcome = safeAction(
                () -> operation.apply(resolution.binding().orElseThrow())
        );
        if (outcome.accepted()) {
            renewLease();
        }
        return outcome;
    }

    private void renewLease() {
        leasedTick = currentTick();
        leasedGeneration = bindingGeneration;
    }

    private Resolution resolve() {
        Optional<Binding> candidate = Objects.requireNonNull(
                bindings.current(),
                "binding source result"
        );
        if (candidate.isEmpty()) {
            activeBinding = null;
            playerToken = null;
            connectionToken = null;
            expireLease();
            return Resolution.unavailable();
        }
        Binding resolved = Objects.requireNonNull(
                candidate.orElseThrow(),
                "binding"
        );
        Object nextPlayer = Objects.requireNonNull(
                resolved.playerIdentityToken(),
                "player identity token"
        );
        Object nextConnection = Objects.requireNonNull(
                resolved.connectionIdentityToken(),
                "connection identity token"
        );
        boolean rebound = activeBinding == null
                || playerToken != nextPlayer
                || connectionToken != nextConnection;
        if (rebound) {
            if (activeBinding != null) {
                // Best effort only. The old connection may already be gone.
                quiesce(activeBinding);
            }
            activeBinding = resolved;
            playerToken = nextPlayer;
            connectionToken = nextConnection;
            bindingGeneration++;
            expireLease();
        } else {
            activeBinding = resolved;
        }
        return Resolution.available(activeBinding, rebound);
    }

    private long currentTick() {
        long tick = tickSource.getAsLong();
        if (tick < 0) {
            throw new IllegalStateException(
                    "Server tick source must be non-negative"
            );
        }
        return tick;
    }

    private static QuiesceReport quiesce(Binding binding) {
        // Every release is attempted independently: a dead or replaced body
        // must not let one rejected operation suppress the remaining ones.
        ActionOutcome stopped = safeAction(binding::stop);
        ActionOutcome held = safeAction(
                () -> binding.look(binding.currentLook())
        );
        ActionOutcome releasedUse = safeAction(binding::releaseUse);
        ActionOutcome abortedMining = safeAction(binding::abortMining);
        return new QuiesceReport(
                true,
                stopped,
                held,
                releasedUse,
                abortedMining
        );
    }

    private static ActionOutcome safeAction(
            Supplier<ActionOutcome> operation
    ) {
        try {
            return Objects.requireNonNull(
                    operation.get(),
                    "binding action outcome"
            );
        } catch (RuntimeException exception) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
    }

    /**
     * One dynamically resolved action/physics endpoint.
     *
     * <p>Identity tokens are compared by object identity, not
     * {@link Object#equals(Object)}.</p>
     */
    public interface Binding extends CoreSkillActuator {
        Object playerIdentityToken();

        Object connectionIdentityToken();

        LookIntent currentLook();

        ActionOutcome releaseUse();

        ActionOutcome abortMining();

        ActionOutcome tick();
    }

    @FunctionalInterface
    public interface BindingSource {
        Optional<Binding> current();
    }

    public enum PostTickStatus {
        EXECUTED,
        ALREADY_EXECUTED,
        PLAYER_UNAVAILABLE
    }

    public record PostTickReport(
            PostTickStatus status,
            long serverTick,
            long bindingGeneration,
            QuiesceReport quiesce,
            ActionOutcome tickOutcome,
            boolean rebound
    ) {
        public PostTickReport {
            Objects.requireNonNull(status, "status");
            if (serverTick < 0 || bindingGeneration < 0) {
                throw new IllegalArgumentException(
                        "Post-tick counters must be non-negative"
                );
            }
            Objects.requireNonNull(quiesce, "quiesce");
            Objects.requireNonNull(tickOutcome, "tickOutcome");
        }

        public boolean failsafeQuiesced() {
            return quiesce.applied();
        }

        private static PostTickReport executed(
                long tick,
                long generation,
                QuiesceReport quiesce,
                ActionOutcome tickOutcome,
                boolean rebound
        ) {
            return new PostTickReport(
                    PostTickStatus.EXECUTED,
                    tick,
                    generation,
                    quiesce,
                    tickOutcome,
                    rebound
            );
        }

        private static PostTickReport alreadyExecuted(
                long tick,
                long generation,
                QuiesceReport quiesce
        ) {
            return new PostTickReport(
                    PostTickStatus.ALREADY_EXECUTED,
                    tick,
                    generation,
                    quiesce,
                    ActionOutcome.NO_ACTIVE_ACTION,
                    false
            );
        }

        private static PostTickReport unavailable(
                long tick,
                long generation,
                ActionOutcome outcome
        ) {
            return new PostTickReport(
                    PostTickStatus.PLAYER_UNAVAILABLE,
                    tick,
                    generation,
                    QuiesceReport.notApplied(),
                    outcome,
                    false
            );
        }
    }

    /**
     * Full emergency-release audit. {@code NO_ACTIVE_ACTION} is successful for
     * release-use and abort-mining because it proves there is nothing left
     * latched.
     */
    public record QuiesceReport(
            boolean applied,
            ActionOutcome stopOutcome,
            ActionOutcome holdLookOutcome,
            ActionOutcome releaseUseOutcome,
            ActionOutcome abortMiningOutcome
    ) {
        public QuiesceReport {
            Objects.requireNonNull(stopOutcome, "stopOutcome");
            Objects.requireNonNull(holdLookOutcome, "holdLookOutcome");
            Objects.requireNonNull(releaseUseOutcome, "releaseUseOutcome");
            Objects.requireNonNull(abortMiningOutcome, "abortMiningOutcome");
        }

        public boolean successful() {
            return applied
                    && stopOutcome.accepted()
                    && holdLookOutcome.accepted()
                    && releaseSucceeded(releaseUseOutcome)
                    && releaseSucceeded(abortMiningOutcome);
        }

        public ActionOutcome firstFailure() {
            if (!stopOutcome.accepted()) {
                return stopOutcome;
            }
            if (!holdLookOutcome.accepted()) {
                return holdLookOutcome;
            }
            if (!releaseSucceeded(releaseUseOutcome)) {
                return releaseUseOutcome;
            }
            if (!releaseSucceeded(abortMiningOutcome)) {
                return abortMiningOutcome;
            }
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        private static QuiesceReport notApplied() {
            return new QuiesceReport(
                    false,
                    ActionOutcome.NO_ACTIVE_ACTION,
                    ActionOutcome.NO_ACTIVE_ACTION,
                    ActionOutcome.NO_ACTIVE_ACTION,
                    ActionOutcome.NO_ACTIVE_ACTION
            );
        }

        private static QuiesceReport unavailable() {
            return new QuiesceReport(
                    false,
                    ActionOutcome.PLAYER_UNAVAILABLE,
                    ActionOutcome.PLAYER_UNAVAILABLE,
                    ActionOutcome.PLAYER_UNAVAILABLE,
                    ActionOutcome.PLAYER_UNAVAILABLE
            );
        }

        private static boolean releaseSucceeded(ActionOutcome outcome) {
            return outcome.accepted()
                    || outcome == ActionOutcome.NO_ACTIVE_ACTION;
        }
    }

    public record LeaseSnapshot(
            boolean bound,
            long bindingGeneration,
            long leasedTick,
            long lastExecutedTick
    ) {
    }

    private record Resolution(Optional<Binding> binding, boolean rebound) {
        private static Resolution available(
                Binding binding,
                boolean rebound
        ) {
            return new Resolution(Optional.of(binding), rebound);
        }

        private static Resolution unavailable() {
            return new Resolution(Optional.empty(), false);
        }
    }
}
