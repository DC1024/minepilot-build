package dev.mcai.companion.skills.building;

/**
 * Tick-based progress evidence for a short, local construction reposition.
 *
 * <p>Ordinary navigation may legitimately move sideways around an obstacle.
 * A construction vantage is different: it is a nearby, explicitly selected
 * stand. Mere body motion must therefore not keep the attempt alive forever.
 * This watchdog requires a material reduction in distance to that stand and
 * also imposes an absolute per-candidate deadline.</p>
 */
final class BoundedRepositionProgress {
    enum Expiration {
        NONE,
        STALLED,
        DEADLINE
    }

    private final long stallTicks;
    private final long maximumTicks;
    private final double improvementEpsilon;

    private long startedAtTick = -1;
    private long lastProgressAtTick = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;

    BoundedRepositionProgress(
            final long stallTicks,
            final long maximumTicks,
            final double improvementEpsilon
    ) {
        if (stallTicks <= 0
                || maximumTicks < stallTicks
                || !Double.isFinite(improvementEpsilon)
                || improvementEpsilon <= 0.0) {
            throw new IllegalArgumentException(
                    "Invalid reposition progress bounds"
            );
        }
        this.stallTicks = stallTicks;
        this.maximumTicks = maximumTicks;
        this.improvementEpsilon = improvementEpsilon;
    }

    void start(final long gameTick, final double distance) {
        requireTick(gameTick);
        requireDistance(distance);
        startedAtTick = gameTick;
        lastProgressAtTick = gameTick;
        bestDistance = distance;
    }

    void observe(final long gameTick, final double distance) {
        requireTick(gameTick);
        requireDistance(distance);
        if (startedAtTick < 0) {
            start(gameTick, distance);
            return;
        }
        if (gameTick < startedAtTick
                || gameTick < lastProgressAtTick) {
            throw new IllegalArgumentException(
                    "Reposition ticks must be monotonic"
            );
        }
        if (distance + improvementEpsilon < bestDistance) {
            bestDistance = distance;
            lastProgressAtTick = gameTick;
        }
    }

    Expiration expirationAt(final long gameTick) {
        requireTick(gameTick);
        if (startedAtTick < 0) {
            return Expiration.NONE;
        }
        if (gameTick < startedAtTick
                || gameTick < lastProgressAtTick) {
            throw new IllegalArgumentException(
                    "Reposition ticks must be monotonic"
            );
        }
        if (gameTick - startedAtTick >= maximumTicks) {
            return Expiration.DEADLINE;
        }
        if (gameTick - lastProgressAtTick >= stallTicks) {
            return Expiration.STALLED;
        }
        return Expiration.NONE;
    }

    long elapsedTicks(final long gameTick) {
        requireTick(gameTick);
        if (startedAtTick < 0) {
            return -1;
        }
        return Math.max(0, gameTick - startedAtTick);
    }

    long ticksSinceProgress(final long gameTick) {
        requireTick(gameTick);
        if (lastProgressAtTick < 0) {
            return -1;
        }
        return Math.max(0, gameTick - lastProgressAtTick);
    }

    double bestDistance() {
        return bestDistance;
    }

    void clear() {
        startedAtTick = -1;
        lastProgressAtTick = -1;
        bestDistance = Double.POSITIVE_INFINITY;
    }

    private static void requireTick(final long gameTick) {
        if (gameTick < 0) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative"
            );
        }
    }

    private static void requireDistance(final double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException(
                    "distance must be finite and non-negative"
            );
        }
    }
}
