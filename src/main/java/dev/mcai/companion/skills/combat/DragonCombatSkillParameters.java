package dev.mcai.companion.skills.combat;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.List;

final class DragonCombatSkillParameters {
    private DragonCombatSkillParameters() {
    }

    static SkillParameterResult<FightEnderDragonParameters> parse(
            final List<SkillArgument> arguments
    ) {
        return arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(
                        FightEnderDragonParameters
                                .localControllerDefaults()
                )
                : invalid();
    }

    private static SkillParameterResult<FightEnderDragonParameters>
    invalid() {
        return SkillParameterResult.invalid(
                FightEnderDragonSkill.NAME + ".invalid_arguments"
        );
    }
}
