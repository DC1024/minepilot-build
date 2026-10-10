package dev.mcai.companion.skills.sleeping;

import java.util.Optional;

@FunctionalInterface
public interface SleepSkillFrameSource {
    Optional<SleepSkillFrame> current();
}
