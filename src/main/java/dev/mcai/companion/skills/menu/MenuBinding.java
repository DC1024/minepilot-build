package dev.mcai.companion.skills.menu;

/**
 * Optimistic binding to one fair observation of one vanilla menu.
 *
 * <p>All three counters are supplied from {@code openMenu}. A menu action is
 * rejected unless the observation frame and the live menu still match every
 * counter.</p>
 */
public record MenuBinding(
        long sampleSequence,
        int containerId,
        int stateId
) {
    public MenuBinding {
        if (sampleSequence < 0 || containerId < 0 || stateId < 0) {
            throw new IllegalArgumentException(
                    "Menu binding counters must be non-negative"
            );
        }
    }
}
