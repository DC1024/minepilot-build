package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Objects;
import java.util.Optional;

public final class DropItemSkill implements Skill<DropItemParameters> {
    private final InventorySkillActuator actuator;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private int dropped;

    public DropItemSkill(final InventorySkillActuator actuator) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
    }

    @Override
    public SkillParameterParser<DropItemParameters> parameters() {
        return InventorySkillParameters::parseDrop;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return actuator.checkDrop(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        phase = Phase.RUNNING;
        failure = null;
        dropped = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed("drop_item.invalid_state");
        }
        final InventoryOperationResult outcome = actuator.drop(parameters);
        if (!outcome.succeeded()) {
            failure = outcome.failure().orElseThrow();
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        dropped = outcome.affectedCount();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\""
                        + phase.name()
                        + "\",\"itemId\":\""
                        + parameters.itemId()
                        + "\",\"requested\":"
                        + parameters.count()
                        + ",\"dropped\":"
                        + dropped
                        + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final DropItemParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(
                    SkillFailure.of("drop_item.invalid_state")
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
