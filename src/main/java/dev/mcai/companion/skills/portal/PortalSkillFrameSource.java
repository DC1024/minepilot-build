package dev.mcai.companion.skills.portal;

import java.util.Optional;

@FunctionalInterface
public interface PortalSkillFrameSource {
    Optional<PortalSkillFrame> current();
}
