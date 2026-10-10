package dev.mcai.companion.model;

import java.util.List;
import java.util.Optional;

/**
 * Performs the final local, typed validation for a registered skill.
 */
@FunctionalInterface
public interface SkillArgumentValidator {
    /**
     * @return an empty value when valid, otherwise a safe diagnostic message
     */
    Optional<String> validate(List<SkillArgument> arguments);
}
