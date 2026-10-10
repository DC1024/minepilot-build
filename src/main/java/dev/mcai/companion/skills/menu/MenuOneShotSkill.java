package dev.mcai.companion.skills.menu;

import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared bounded state machine for one synchronous vanilla menu transaction.
 */
final class MenuOneShotSkill<P> implements Skill<P> {
    private final String skillName;
    private final SkillParameterParser<P> parser;
    private final Function<P, MenuOperationResult> preflight;
    private final Function<P, MenuOperationResult> operation;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;

    MenuOneShotSkill(
            final String skillName,
            final SkillParameterParser<P> parser,
            final Function<P, MenuOperationResult> preflight,
            final Function<P, MenuOperationResult> operation
    ) {
        this.skillName = Objects.requireNonNull(
                skillName,
                "skillName"
        );
        this.parser = Objects.requireNonNull(parser, "parser");
        this.preflight = Objects.requireNonNull(
                preflight,
                "preflight"
        );
        this.operation = Objects.requireNonNull(
                operation,
                "operation"
        );
    }

    @Override
    public SkillParameterParser<P> parameters() {
        return parser;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final P parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return preflight.apply(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final P parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.RUNNING;
        failure = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final P parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed(
                    skillName + ".invalid_state"
            );
        }
        final MenuOperationResult outcome = operation.apply(parameters);
        if (!outcome.succeeded()) {
            failure = outcome.failure().orElseThrow();
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final P parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"skill\":\""
                        + skillName
                        + "\",\"phase\":\""
                        + phase.name()
                        + "\"}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final P parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final P parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(skillName + ".invalid_state")
            );
        };
    }

    private enum Phase {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
