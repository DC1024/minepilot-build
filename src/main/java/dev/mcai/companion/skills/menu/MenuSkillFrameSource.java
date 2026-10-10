package dev.mcai.companion.skills.menu;

import java.util.Optional;

@FunctionalInterface
public interface MenuSkillFrameSource {
    Optional<MenuSkillFrame> current();

    /**
     * Returns the exact fair menu frame supplied to a pending model request.
     *
     * <p>The default preserves existing single-frame test sources. Production
     * keeps a bounded history because semantic sampling continues while the
     * model is thinking. The actuator still revalidates the live container,
     * state ID, slot layout and item contents before every click.</p>
     */
    default Optional<MenuSkillFrame> retained(
            final long sampleSequence
    ) {
        return current().filter(
                frame -> frame.sampleSequence() == sampleSequence
        );
    }
}
