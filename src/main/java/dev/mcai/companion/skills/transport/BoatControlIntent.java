package dev.mcai.companion.skills.transport;

/**
 * One bounded vanilla-style boat input frame.
 */
public record BoatControlIntent(
        boolean left,
        boolean right,
        boolean forward,
        boolean backward
) {
    public static final BoatControlIntent NEUTRAL =
            new BoatControlIntent(false, false, false, false);

    public BoatControlIntent {
        if (left && right || forward && backward) {
            throw new IllegalArgumentException(
                    "Opposing boat controls cannot be pressed together"
            );
        }
    }

    public static BoatControlIntent forwardLeft() {
        return new BoatControlIntent(true, false, true, false);
    }

    public static BoatControlIntent forwardRight() {
        return new BoatControlIntent(false, true, true, false);
    }

    public static BoatControlIntent forwardIntent() {
        return new BoatControlIntent(false, false, true, false);
    }

    public static BoatControlIntent backwardIntent() {
        return new BoatControlIntent(false, false, false, true);
    }

    public static BoatControlIntent backwardLeft() {
        return new BoatControlIntent(true, false, false, true);
    }

    public static BoatControlIntent backwardRight() {
        return new BoatControlIntent(false, true, false, true);
    }
}
