package dev.mcai.companion.skills.sleeping;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SleepSkillParametersTest {
    @Test
    void acceptsOnlyExactCanonicalObservedTargetArguments() {
        var valid = arguments();
        assertInstanceOf(
                SkillParameterResult.Valid.class,
                SleepSkillParameters.parse(valid)
        );

        assertInstanceOf(
                SkillParameterResult.Invalid.class,
                SleepSkillParameters.parse(valid.subList(0, 5))
        );
        assertInstanceOf(
                SkillParameterResult.Invalid.class,
                SleepSkillParameters.parse(List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("sampleSequence", "+31"),
                        new SkillArgument("x", "1"),
                        new SkillArgument("y", "64"),
                        new SkillArgument("z", "0"),
                        new SkillArgument("face", "west")
                ))
        );
        assertInstanceOf(
                SkillParameterResult.Invalid.class,
                SleepSkillParameters.parse(List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("sampleSequence", "31"),
                        new SkillArgument("x", "1"),
                        new SkillArgument("y", "64"),
                        new SkillArgument("z", "0"),
                        new SkillArgument("face", "WEST")
                ))
        );
    }

    private static List<SkillArgument> arguments() {
        return List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("sampleSequence", "31"),
                new SkillArgument("x", "1"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "0"),
                new SkillArgument("face", "west")
        );
    }
}
