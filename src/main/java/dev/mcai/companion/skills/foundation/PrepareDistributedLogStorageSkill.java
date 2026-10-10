package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillActuator;
import dev.mcai.companion.skills.menu.MenuSkillFrameSource;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * Four-chest storage transaction used by the quantified teammate task.
 *
 * <p>The delegate performs ordinary recipe, placement, block-use, and exact
 * vanilla menu-click actions. Completion evidence is emitted only after all
 * four independent chest placements and all four exact log transfers have
 * completed.</p>
 */
public final class PrepareDistributedLogStorageSkill
        implements Skill<NoParameters> {
    public static final String NAME = "prepare_distributed_log_storage";

    private final EstablishFoundationWorkstationsSkill delegate;

    public PrepareDistributedLogStorageSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final ResourceInventorySource resourceInventory,
            final MenuSkillActuator menus,
            final MenuSkillFrameSource menuFrames,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownCraftingTable,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownFurnace,
            final LongFunction<Optional<VerifiedFixtureLocation>>
                    knownStorage,
            final LongConsumer completionEvidence
    ) {
        delegate = EstablishFoundationWorkstationsSkill
                .distributedLogStorage(
                        expectedPlayerId,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        inventory,
                        resourceInventory,
                        menus,
                        menuFrames,
                        knownCraftingTable,
                        knownFurnace,
                        knownStorage,
                        completionEvidence
                );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(NAME + ".invalid_arguments");
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return delegate.preconditions(context, parameters);
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        delegate.start(context, parameters);
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return delegate.tick(context, parameters);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return delegate.checkpoint(context, parameters);
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        delegate.cancel(context, parameters);
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return delegate.result(context, parameters);
    }
}
