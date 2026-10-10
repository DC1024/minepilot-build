package dev.mcai.companion.skill;

import dev.mcai.companion.model.SkillArgument;

import java.util.List;

/**
 * Pure, deterministic conversion from model wire arguments to a typed value.
 */
@FunctionalInterface
public interface SkillParameterParser<P> {
    SkillParameterResult<P> parse(List<SkillArgument> arguments);
}
