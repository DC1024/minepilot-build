package dev.mcai.companion.action;

/**
 * Pure client-side mining timing rule. A STOP packet is sent only after the
 * locally predicted progress reaches 100%; the server's latency tolerance is
 * never treated as a faster mining threshold.
 */
public final class MiningProgressPolicy {
    private MiningProgressPolicy() {
    }

    public static boolean readyToStop(float progressPerTick, long elapsedTicks) {
        progressPerTick = ActionValidation.finite(
                progressPerTick,
                "progressPerTick"
        );
        if (progressPerTick < 0.0F || elapsedTicks <= 0) {
            throw new IllegalArgumentException("Invalid mining progress");
        }
        return progressPerTick > 0.0F
                && (double) progressPerTick * elapsedTicks >= 1.0;
    }

    public static boolean timedOut(
            long startedAtGameTime,
            long currentGameTime,
            int timeoutTicks
    ) {
        if (startedAtGameTime < 0
                || currentGameTime < startedAtGameTime
                || timeoutTicks <= 0) {
            throw new IllegalArgumentException("Invalid mining timeline");
        }
        return currentGameTime - startedAtGameTime > timeoutTicks;
    }
}
