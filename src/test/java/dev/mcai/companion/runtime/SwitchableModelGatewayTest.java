package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.DecisionContext;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class SwitchableModelGatewayTest {
    @Test
    void failsClosedUntilVerifiedDelegateIsInstalled() {
        final SwitchableModelGateway gateway = new SwitchableModelGateway();
        final ModelOutcome outcome = gateway.decide(input())
            .toCompletableFuture().join();

        assertEquals(
            ModelFailureKind.INVALID_CONFIGURATION,
            ((ModelOutcome.Failure) outcome).error().kind()
        );
        assertEquals(GatewayStatus.IDLE, gateway.status());
        assertFalse(gateway.configured());
    }

    @Test
    void closesReplacedAndFinalDelegates() {
        final SwitchableModelGateway gateway = new SwitchableModelGateway();
        final StubGateway first = new StubGateway();
        final StubGateway second = new StubGateway();

        gateway.install(first);
        assertTrue(gateway.configured());
        assertTrue(gateway.highLevelDecisionReady());
        gateway.install(second);
        assertTrue(first.closed);
        gateway.close();
        assertTrue(second.closed);
        assertFalse(gateway.configured());
        assertFalse(gateway.highLevelDecisionReady());
        assertEquals(GatewayStatus.CLOSED, gateway.status());
    }

    @Test
    void authenticationFailureClearsDelegateAndNotifiesRuntime() {
        final SwitchableModelGateway gateway = new SwitchableModelGateway();
        final StubGateway delegate = new StubGateway();
        final boolean[] invalidated = {false};
        gateway.setAuthenticationFailureHandler(() -> invalidated[0] = true);
        gateway.install(delegate);

        gateway.invalidateAfterAuthenticationFailure();

        assertTrue(invalidated[0]);
        assertTrue(delegate.closed);
        assertEquals(GatewayStatus.IDLE, gateway.status());
        assertEquals(
                ModelFailureKind.INVALID_CONFIGURATION,
                ((ModelOutcome.Failure) gateway.decide(input())
                        .toCompletableFuture().join()).error().kind()
        );
    }

    private static PlannerInput input() {
        return new PlannerInput(
            new DecisionContext("test", 0, 0, false, Map.of()),
            "system",
            "{}",
            16
        );
    }

    private static final class StubGateway implements ModelGateway {
        private boolean closed;

        @Override
        public CompletionStage<ModelOutcome> decide(final PlannerInput input) {
            return CompletableFuture.failedFuture(
                new AssertionError("not expected")
            );
        }

        @Override
        public void cancelForGoalRevision(final long currentGoalRevision) {
        }

        @Override
        public GatewayStatus status() {
            return closed ? GatewayStatus.CLOSED : GatewayStatus.IDLE;
        }

        @Override
        public boolean configured() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
