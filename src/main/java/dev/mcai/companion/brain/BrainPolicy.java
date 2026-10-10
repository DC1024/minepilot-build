package dev.mcai.companion.brain;

import java.time.Duration;
import java.util.Objects;

public record BrainPolicy(
        Duration minimumReplanBackoff,
        Duration softRequestTimeout,
        Duration requestTimeout,
        int maxConsecutiveModelFailures
) {
    public static final BrainPolicy DEFAULT = new BrainPolicy(
            Duration.ofMillis(250),
            Duration.ofSeconds(12),
            Duration.ofSeconds(90),
            8
    );

    /**
     * Compatibility constructor for focused tests and small embedders that
     * only supplied the hard request deadline. The soft deadline is placed
     * before that hard deadline without changing the old call shape.
     */
    public BrainPolicy(
            Duration minimumReplanBackoff,
            Duration requestTimeout,
            int maxConsecutiveModelFailures
    ) {
        this(
                minimumReplanBackoff,
                defaultSoftTimeout(requestTimeout),
                requestTimeout,
                maxConsecutiveModelFailures
        );
    }

    public BrainPolicy {
        minimumReplanBackoff =
                requirePositive(minimumReplanBackoff, "minimumReplanBackoff");
        softRequestTimeout = requirePositive(
                softRequestTimeout,
                "softRequestTimeout"
        );
        requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (softRequestTimeout.compareTo(requestTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "softRequestTimeout must be shorter than requestTimeout"
            );
        }
        if (maxConsecutiveModelFailures < 1) {
            throw new IllegalArgumentException(
                    "maxConsecutiveModelFailures must be positive"
            );
        }
    }

    private static Duration defaultSoftTimeout(Duration requestTimeout) {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }
        Duration twelveSeconds = Duration.ofSeconds(12);
        if (twelveSeconds.compareTo(requestTimeout) < 0) {
            return twelveSeconds;
        }
        try {
            return requestTimeout.minusNanos(1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "requestTimeout is too small for a soft deadline",
                    exception
            );
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large", exception);
        }
        return duration;
    }
}
