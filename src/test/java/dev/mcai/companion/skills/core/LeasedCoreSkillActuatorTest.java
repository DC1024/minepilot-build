package dev.mcai.companion.skills.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LeasedCoreSkillActuatorTest {
    @Test
    void executesAtMostOnceAndFailsafeReleasesEveryLatchedAction() {
        MutableTick ticks = new MutableTick(10);
        FakeBinding binding = new FakeBinding();
        MutableBindings bindings = new MutableBindings(binding);
        LeasedCoreSkillActuator actuator = new LeasedCoreSkillActuator(
                bindings,
                ticks::current
        );

        assertEquals(
                ActionOutcome.QUEUED,
                actuator.move(new MovementIntent(1.0, 0.0, true, false))
        );
        LeasedCoreSkillActuator.PostTickReport first = actuator.postTick();
        assertEquals(
                LeasedCoreSkillActuator.PostTickStatus.EXECUTED,
                first.status()
        );
        assertFalse(first.failsafeQuiesced());
        assertEquals(1, binding.ticks);

        LeasedCoreSkillActuator.PostTickReport duplicate =
                actuator.postTick();
        assertEquals(
                LeasedCoreSkillActuator.PostTickStatus.ALREADY_EXECUTED,
                duplicate.status()
        );
        assertEquals(1, binding.ticks);

        ticks.value = 11;
        LeasedCoreSkillActuator.PostTickReport expired =
                actuator.postTick();
        assertTrue(expired.failsafeQuiesced());
        assertTrue(expired.quiesce().successful());
        assertEquals(ActionOutcome.NO_ACTIVE_ACTION,
                expired.quiesce().releaseUseOutcome());
        assertEquals(ActionOutcome.NO_ACTIVE_ACTION,
                expired.quiesce().abortMiningOutcome());
        assertEquals(1, binding.stops);
        assertEquals(1, binding.looks);
        assertEquals(1, binding.releaseUses);
        assertEquals(1, binding.abortMining);
        assertEquals(2, binding.ticks);
    }

    @Test
    void rebindInvalidatesOldLeaseAndNeverTicksBothBodies() {
        MutableTick ticks = new MutableTick(20);
        FakeBinding firstBinding = new FakeBinding();
        MutableBindings bindings = new MutableBindings(firstBinding);
        LeasedCoreSkillActuator actuator = new LeasedCoreSkillActuator(
                bindings,
                ticks::current
        );
        actuator.move(new MovementIntent(1.0, 0.0, false, false));
        actuator.postTick();

        ticks.value = 21;
        FakeBinding replacement = new FakeBinding();
        bindings.binding = replacement;
        actuator.look(new LookIntent(45.0F, 0.0F));
        LeasedCoreSkillActuator.PostTickReport rebound =
                actuator.postTick();

        assertTrue(rebound.rebound() || actuator.snapshot().bindingGeneration() == 2);
        assertEquals(1, firstBinding.ticks);
        assertEquals(1, replacement.ticks);
        assertEquals(1, firstBinding.stops);
        assertEquals(1, firstBinding.releaseUses);
        assertEquals(1, firstBinding.abortMining);
        assertFalse(rebound.failsafeQuiesced());
        assertEquals(2, actuator.snapshot().bindingGeneration());
    }

    @Test
    void terminalQuiesceAndExplicitExpiryExposeAuditableState() {
        MutableTick ticks = new MutableTick(30);
        FakeBinding binding = new FakeBinding();
        binding.releaseUseOutcome = ActionOutcome.DISPATCHED;
        binding.abortMiningOutcome = ActionOutcome.COMPLETED;
        LeasedCoreSkillActuator actuator = new LeasedCoreSkillActuator(
                new MutableBindings(binding),
                ticks::current
        );
        actuator.move(new MovementIntent(1.0, 0.0, false, false));

        LeasedCoreSkillActuator.QuiesceReport terminal =
                actuator.quiesceNow();
        assertTrue(terminal.applied());
        assertTrue(terminal.successful());
        assertEquals(ActionOutcome.DISPATCHED,
                terminal.releaseUseOutcome());
        assertEquals(ActionOutcome.COMPLETED,
                terminal.abortMiningOutcome());
        assertEquals(30, actuator.snapshot().leasedTick());

        actuator.expireLease();
        assertEquals(Long.MIN_VALUE, actuator.snapshot().leasedTick());
        LeasedCoreSkillActuator.PostTickReport post = actuator.postTick();
        assertTrue(post.failsafeQuiesced());
        assertTrue(post.quiesce().successful());
        assertEquals(2, binding.releaseUses);
        assertEquals(2, binding.abortMining);
    }

    @Test
    void failedStopStillAttemptsUseAndMiningRelease() {
        MutableTick ticks = new MutableTick(40);
        FakeBinding binding = new FakeBinding();
        binding.stopOutcome = ActionOutcome.PLAYER_INCAPACITATED;
        LeasedCoreSkillActuator actuator = new LeasedCoreSkillActuator(
                new MutableBindings(binding),
                ticks::current
        );

        LeasedCoreSkillActuator.QuiesceReport report =
                actuator.quiesceNow();

        assertTrue(report.applied());
        assertFalse(report.successful());
        assertEquals(ActionOutcome.PLAYER_INCAPACITATED,
                report.firstFailure());
        assertEquals(1, binding.releaseUses);
        assertEquals(1, binding.abortMining);
    }

    @Test
    void itemUseRenewsExactlyTheCurrentTickLease() {
        MutableTick ticks = new MutableTick(50);
        FakeBinding binding = new FakeBinding();
        LeasedCoreSkillActuator actuator = new LeasedCoreSkillActuator(
                new MutableBindings(binding),
                ticks::current
        );

        assertEquals(
                ActionOutcome.DISPATCHED,
                actuator.useItem(ActionHand.OFF_HAND)
        );
        LeasedCoreSkillActuator.PostTickReport report =
                actuator.postTick();

        assertFalse(report.failsafeQuiesced());
        assertEquals(1, binding.itemUses);
        assertEquals(1, binding.ticks);
    }

    private static final class MutableTick {
        long value;

        private MutableTick(long value) {
            this.value = value;
        }

        long current() {
            return value;
        }
    }

    private static final class MutableBindings
            implements LeasedCoreSkillActuator.BindingSource {
        FakeBinding binding;

        private MutableBindings(FakeBinding binding) {
            this.binding = binding;
        }

        @Override
        public Optional<LeasedCoreSkillActuator.Binding> current() {
            return Optional.ofNullable(binding);
        }
    }

    private static final class FakeBinding
            implements LeasedCoreSkillActuator.Binding {
        final Object playerToken = new Object();
        final Object connectionToken = new Object();
        int movements;
        int looks;
        int jumps;
        int stops;
        int blockUses;
        int itemUses;
        int releaseUses;
        int abortMining;
        int ticks;
        ActionOutcome stopOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome releaseUseOutcome = ActionOutcome.NO_ACTIVE_ACTION;
        ActionOutcome abortMiningOutcome = ActionOutcome.NO_ACTIVE_ACTION;

        @Override
        public Object playerIdentityToken() {
            return playerToken;
        }

        @Override
        public Object connectionIdentityToken() {
            return connectionToken;
        }

        @Override
        public LookIntent currentLook() {
            return new LookIntent(0.0F, 0.0F);
        }

        @Override
        public ActionOutcome move(MovementIntent intent) {
            movements++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(LookIntent intent) {
            looks++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return stopOutcome;
        }

        @Override
        public ActionOutcome useMainHandOn(
                BlockInteractionTarget target
        ) {
            blockUses++;
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            itemUses++;
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome releaseUse() {
            releaseUses++;
            return releaseUseOutcome;
        }

        @Override
        public ActionOutcome abortMining() {
            abortMining++;
            return abortMiningOutcome;
        }

        @Override
        public ActionOutcome tick() {
            ticks++;
            return ActionOutcome.DISPATCHED;
        }
    }
}
