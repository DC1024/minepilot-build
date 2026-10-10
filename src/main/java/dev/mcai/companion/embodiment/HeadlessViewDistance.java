package dev.mcai.companion.embodiment;

/**
 * Normalizes the clientless player's requested chunk radius to vanilla's
 * supported range.
 */
final class HeadlessViewDistance {
    private static final int MINIMUM = 2;
    private static final int MAXIMUM = 32;

    private HeadlessViewDistance() {
    }

    static int requested(final int serverViewDistance) {
        return Math.max(
                MINIMUM,
                Math.min(MAXIMUM, serverViewDistance)
        );
    }
}
