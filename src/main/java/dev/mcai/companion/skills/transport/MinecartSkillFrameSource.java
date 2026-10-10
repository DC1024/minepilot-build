package dev.mcai.companion.skills.transport;

import java.util.Optional;

@FunctionalInterface
public interface MinecartSkillFrameSource {
    Optional<MinecartSkillFrame> current();
}
