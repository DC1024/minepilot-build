package dev.mcai.companion.action;

import java.util.Objects;

/**
 * Minimal, authority-free evidence that a fair player action was accepted.
 *
 * <p>The record deliberately contains no callback, player reference, target,
 * or mutable game object. Runtime audit sinks may persist it, but cannot use
 * it to influence the action that already happened.</p>
 */
public record AcceptedLowLevelAction(
        String action,
        long serverTick,
        String outcome
) {
    public AcceptedLowLevelAction {
        action = requireToken(action, "action");
        outcome = requireToken(outcome, "outcome");
        if (serverTick < 0) {
            throw new IllegalArgumentException(
                    "serverTick must be non-negative"
            );
        }
    }

    public static AcceptedLowLevelAction from(
            final String action,
            final long serverTick,
            final ActionOutcome outcome
    ) {
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.accepted()) {
            throw new IllegalArgumentException(
                    "Only accepted actions may be audited"
            );
        }
        return new AcceptedLowLevelAction(
                action,
                serverTick,
                outcome.name()
        );
    }

    private static String requireToken(
            final String value,
            final String name
    ) {
        final String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || checked.length() > 64) {
            throw new IllegalArgumentException(
                    name + " must contain 1-64 characters"
            );
        }
        return checked;
    }
}
