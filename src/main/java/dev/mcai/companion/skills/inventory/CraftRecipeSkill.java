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

/**
 * Executes at most one vanilla recipe result click per server tick.
 */
public final class CraftRecipeSkill implements Skill<CraftRecipeParameters> {
    private final InventorySkillActuator actuator;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private int completedCrafts;
    private int producedItems;

    public CraftRecipeSkill(final InventorySkillActuator actuator) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
    }

    @Override
    public SkillParameterParser<CraftRecipeParameters> parameters() {
        return InventorySkillParameters::parseCraft;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return actuator.checkCraft(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        phase = Phase.RUNNING;
        failure = null;
        completedCrafts = 0;
        producedItems = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed("craft_recipe.invalid_state");
        }
        final InventoryOperationResult outcome = actuator.craftOnce(parameters);
        if (!outcome.succeeded()) {
            failure = outcome.failure().orElseThrow();
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        completedCrafts++;
        producedItems += outcome.affectedCount();
        if (completedCrafts >= parameters.crafts()) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return SkillTickResult.running(true, true);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\""
                        + phase.name()
                        + "\",\"recipeId\":\""
                        + parameters.recipeId()
                        + "\",\"requestedCrafts\":"
                        + parameters.crafts()
                        + ",\"completedCrafts\":"
                        + completedCrafts
                        + ",\"producedItems\":"
                        + producedItems
                        + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final CraftRecipeParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(
                    SkillFailure.of("craft_recipe.invalid_state")
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
