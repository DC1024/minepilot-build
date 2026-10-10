package dev.mcai.companion.runtime;

import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stable brain-facing gateway whose verified provider delegate can be
 * installed only after an explicit capability probe succeeds.
 */
public final class SwitchableModelGateway implements ModelGateway {
    private ModelGateway delegate;
    private Runnable authenticationFailureHandler = () -> {
    };
    private boolean closed;

    @Override
    public synchronized CompletionStage<ModelOutcome> decide(
        final PlannerInput input
    ) {
        if (closed) {
            return CompletableFuture.completedFuture(failure(
                input,
                ModelFailureKind.CANCELLED,
                "The model gateway is closed"
            ));
        }
        if (delegate == null) {
            return CompletableFuture.completedFuture(failure(
                input,
                ModelFailureKind.INVALID_CONFIGURATION,
                "No verified provider capability profile is active"
            ));
        }
        return delegate.decide(input);
    }

    @Override
    public synchronized void cancelForGoalRevision(
        final long currentGoalRevision
    ) {
        if (delegate != null) {
            delegate.cancelForGoalRevision(currentGoalRevision);
        }
    }

    @Override
    public synchronized GatewayStatus status() {
        if (closed) {
            return GatewayStatus.CLOSED;
        }
        return delegate == null ? GatewayStatus.IDLE : delegate.status();
    }

    @Override
    public synchronized boolean configured() {
        return !closed && delegate != null;
    }

    @Override
    public synchronized boolean highLevelDecisionReady() {
        return !closed
                && delegate != null
                && delegate.highLevelDecisionReady();
    }

    public synchronized void install(final ModelGateway verifiedDelegate) {
        if (verifiedDelegate == null) {
            throw new IllegalArgumentException("verifiedDelegate is required");
        }
        if (closed) {
            verifiedDelegate.close();
            throw new IllegalStateException("Switchable gateway is closed");
        }
        final ModelGateway previous = delegate;
        delegate = verifiedDelegate;
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * Removes a previously verified delegate before a model profile changes.
     * The stable wrapper remains usable and will reject decisions until the
     * replacement profile has passed its explicit capability probe.
     */
    public synchronized void clearVerifiedDelegate() {
        if (closed) {
            return;
        }
        final ModelGateway previous = delegate;
        delegate = null;
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * Registers the runtime-owned cache invalidation callback.  The callback
     * is kept separate from the delegate so a provider 401 cannot leave a
     * stale capability profile looking healthy after the next world restart.
     */
    public synchronized void setAuthenticationFailureHandler(
        final Runnable handler
    ) {
        authenticationFailureHandler = handler == null
            ? () -> {
            }
            : handler;
    }

    @Override
    public void invalidateAfterAuthenticationFailure() {
        final Runnable handler;
        synchronized (this) {
            if (closed) {
                return;
            }
            clearVerifiedDelegate();
            handler = authenticationFailureHandler;
        }
        try {
            handler.run();
        } catch (RuntimeException ignored) {
            // Cache invalidation cannot turn a safe model failure into a
            // server-thread exception.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }

    private static ModelOutcome failure(
        final PlannerInput input,
        final ModelFailureKind kind,
        final String safeMessage
    ) {
        return new ModelOutcome.Failure(new ModelFailure(
            kind,
            0,
            "",
            "",
            input.decisionContext().requestId(),
            "",
            Optional.empty(),
            "",
            safeMessage
        ));
    }
}
