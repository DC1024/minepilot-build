package dev.mcai.companion.progression;

/**
 * Shared, loader-independent material targets for the ordinary completion
 * route. Counts are resource-equivalent units rather than direct evidence of
 * a structure or future drop.
 */
public final class CompletionResourceReadiness {
    public static final int BLAZE_ROUTE_UNITS = 14;
    public static final int ENDER_ROUTE_UNITS = 14;
    /**
     * Twelve Eyes are the worst-case portal-frame reserve. Two additional
     * Eyes are budgeted for fair, ordinary stronghold triangulation.
     */
    public static final int EYES_READY = 14;
    /** Reserve for a dynamic dragon flight rather than a one-shot demo. */
    public static final int END_BUILDING_BLOCKS = 64;
    public static final int END_BOWS = 1;
    public static final int END_ARROWS = 64;

    private static final int MAX_UNITS = 36 * 64;

    private CompletionResourceReadiness() {
    }

    public static int blazeRouteUnits(
            final int blazeRods,
            final int blazePowder,
            final int craftedEyes
    ) {
        requireNonNegative(blazeRods, blazePowder, craftedEyes);
        return bounded(
                blazeRods * 2L + blazePowder + craftedEyes
        );
    }

    public static int enderRouteUnits(
            final int enderPearls,
            final int craftedEyes
    ) {
        requireNonNegative(enderPearls, craftedEyes);
        return bounded((long) enderPearls + craftedEyes);
    }

    private static int bounded(final long value) {
        return (int) Math.min(MAX_UNITS, value);
    }

    private static void requireNonNegative(final int... counts) {
        for (int count : counts) {
            if (count < 0) {
                throw new IllegalArgumentException(
                        "Completion resource count is negative"
                );
            }
        }
    }
}
