package dev.mcai.companion.runtime;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Separates high-level decision invalidation from the raw 20 TPS game clock.
 *
 * <p>An executing atomic skill freezes the epoch it was authorized against;
 * the skill's own local controller remains responsible for current collision
 * and danger checks. Outside an atomic skill, a meaningful observation or
 * goal change advances the epoch and makes older model output stale.</p>
 */
public final class DecisionEpochTracker<T> {
    private long epoch;
    private long goalRevision = -1;
    private T fingerprint;
    private boolean initialized;

    public synchronized long update(
        final long currentGoalRevision,
        final T currentFingerprint,
        final OptionalLong frozenEpoch
    ) {
        if (currentGoalRevision < 0) {
            throw new IllegalArgumentException("goal revision must be non-negative");
        }
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        Objects.requireNonNull(frozenEpoch, "frozenEpoch");
        if (frozenEpoch.isPresent()) {
            final long frozen = frozenEpoch.getAsLong();
            if (frozen < 0 || frozen > epoch) {
                throw new IllegalArgumentException("frozen epoch is invalid");
            }
            return frozen;
        }

        if (!initialized
            || goalRevision != currentGoalRevision
            || !Objects.equals(fingerprint, currentFingerprint)) {
            epoch = initialized ? Math.incrementExact(epoch) : 0L;
            goalRevision = currentGoalRevision;
            fingerprint = currentFingerprint;
            initialized = true;
        }
        return epoch;
    }

    public synchronized long current() {
        if (!initialized) {
            throw new IllegalStateException("No decision epoch has been established");
        }
        return epoch;
    }

    /**
     * Returns the epoch exposed at event boundaries that may run before the
     * first semantic observation of a newly spawned companion.
     *
     * <p>The first successful {@link #update(long, Object, OptionalLong)}
     * establishes epoch {@code 0}, so returning {@code 0} here neither
     * advances the tracker nor invents a later world observation. Internal
     * callers that require an established observation must continue to use
     * {@link #current()}.</p>
     */
    public synchronized long currentOrInitial() {
        return initialized ? epoch : 0L;
    }
}
