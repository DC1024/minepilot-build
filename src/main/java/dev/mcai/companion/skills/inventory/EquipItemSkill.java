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

public final class EquipItemSkill implements Skill<EquipItemParameters> {
    private final InventorySkillActuator actuator;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;

    public EquipItemSkill(final InventorySkillActuator actuator) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
    }

    @Override
    public SkillParameterParser<EquipItemParameters> parameters() {
        return InventorySkillParameters::parseEquip;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final EquipItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return actuator.checkEquip(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final EquipItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.RUNNING;
        failure = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final EquipItemParameters parameters
    ) {
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed("equip_item.invalid_state");
        }
        final InventoryOperationResult outcome = actuator.equip(parameters);
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
            final EquipItemParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\""
                        + phase.name()
                        + "\",\"itemId\":\""
                        + parameters.itemId()
                        + "\",\"slot\":\""
                        + parameters.slot().wireName()
                        + "\"}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final EquipItemParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final EquipItemParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(
                    SkillFailure.of("equip_item.invalid_state")
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
