package dev.mcai.companion.skills.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MiningSkillParametersTest {
    @Test
    void acceptsOnlyTheExactBoundedCanonicalSchema() {
        final var result = MiningSkillParameters.parse(validArguments());
        final var valid = assertInstanceOf(
                SkillParameterResult.Valid.class,
                result
        );
        final var parameters = assertInstanceOf(
                ExcavateSafeTunnelParameters.class,
                valid.value().orElseThrow()
        );
        assertEquals(12, parameters.sampleSequence());
        assertEquals(TunnelDirection.NORTH, parameters.direction());
        assertEquals(TunnelMode.DESCENDING, parameters.mode());
        assertEquals(
                List.of(
                        "minecraft:diamond_ore",
                        "minecraft:deepslate_diamond_ore"
                ),
                parameters.targetBlockIds()
        );
    }

    @Test
    void acceptsTheObservedOwnedPickaxeCompatibilityAlias() {
        final List<SkillArgument> arguments =
                new ArrayList<>(validArguments());
        final int pickaxeIndex = java.util.stream.IntStream.range(
                        0,
                        arguments.size()
                )
                .filter(index ->
                        arguments.get(index).name()
                                .equals("pickaxeItemId")
                )
                .findFirst()
                .orElseThrow();
        arguments.set(
                pickaxeIndex,
                new SkillArgument(
                        "ownedPickaxeItemId",
                        "minecraft:iron_pickaxe"
                )
        );

        final var valid = assertInstanceOf(
                SkillParameterResult.Valid.class,
                MiningSkillParameters.parse(arguments)
        );
        final var parameters = assertInstanceOf(
                ExcavateSafeTunnelParameters.class,
                valid.value().orElseThrow()
        );
        assertEquals(
                "minecraft:iron_pickaxe",
                parameters.pickaxeItemId()
        );
    }

    @Test
    void rejectsMissingDuplicateUnknownAndNonCanonicalArguments() {
        final List<SkillArgument> missing =
                new ArrayList<>(validArguments());
        missing.removeLast();
        assertInvalid(missing);

        final List<SkillArgument> duplicate =
                new ArrayList<>(validArguments());
        duplicate.set(
                duplicate.size() - 1,
                new SkillArgument("dimension", "minecraft:overworld")
        );
        assertInvalid(duplicate);

        final List<SkillArgument> unknown =
                new ArrayList<>(validArguments());
        unknown.set(
                unknown.size() - 1,
                new SkillArgument("seed", "secret")
        );
        assertInvalid(unknown);

        final List<SkillArgument> leadingZero =
                replace("maximumSteps", "01");
        assertInvalid(leadingZero);

        assertInvalid(replace(
                "targetBlockIds",
                "minecraft:diamond_ore, minecraft:gold_ore"
        ));
        assertInvalid(replace("maximumSteps", "49"));
        assertInvalid(replace("torchInterval", "3"));
        assertInvalid(replace("pickaxeItemId", "minecraft:iron_sword"));
        assertInvalid(replace("targetBlockIds", "minecraft:water"));
        assertInvalid(replace("targetBlockIds", "minecraft:lava"));
        assertInvalid(replace("targetBlockIds", "example:water"));
        assertInvalid(replace("targetBlockIds", "example:lava"));
    }

    @Test
    void recordDefensivelyCopiesTargetsAndEnforcesBounds() {
        final List<String> targets = new ArrayList<>();
        targets.add("minecraft:diamond_ore");
        final var parameters = new ExcavateSafeTunnelParameters(
                DimensionRef.OVERWORLD,
                1,
                TunnelDirection.EAST,
                TunnelMode.HORIZONTAL,
                48,
                8,
                "minecraft:iron_pickaxe",
                targets
        );
        targets.add("minecraft:gold_ore");
        assertEquals(
                List.of("minecraft:diamond_ore"),
                parameters.targetBlockIds()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExcavateSafeTunnelParameters(
                        DimensionRef.OVERWORLD,
                        1,
                        TunnelDirection.EAST,
                        TunnelMode.HORIZONTAL,
                        1,
                        4,
                        "minecraft:iron_pickaxe",
                        List.of(
                                "minecraft:diamond_ore",
                                "minecraft:diamond_ore"
                        )
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExcavateSafeTunnelParameters(
                        DimensionRef.OVERWORLD,
                        1,
                        TunnelDirection.EAST,
                        TunnelMode.HORIZONTAL,
                        1,
                        4,
                        "minecraft:iron_pickaxe",
                        List.of("minecraft:water")
                )
        );
    }

    private static List<SkillArgument> validArguments() {
        return List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("sampleSequence", "12"),
                new SkillArgument("direction", "north"),
                new SkillArgument("mode", "descending"),
                new SkillArgument("maximumSteps", "16"),
                new SkillArgument("torchInterval", "6"),
                new SkillArgument(
                        "pickaxeItemId",
                        "minecraft:iron_pickaxe"
                ),
                new SkillArgument(
                        "targetBlockIds",
                        "minecraft:diamond_ore,"
                            + "minecraft:deepslate_diamond_ore"
                )
        );
    }

    private static List<SkillArgument> replace(
            final String name,
            final String value
    ) {
        return validArguments().stream()
                .map(argument -> argument.name().equals(name)
                        ? new SkillArgument(name, value)
                        : argument)
                .toList();
    }

    private static void assertInvalid(
            final List<SkillArgument> arguments
    ) {
        final var invalid = assertInstanceOf(
                SkillParameterResult.Invalid.class,
                MiningSkillParameters.parse(arguments)
        );
        assertEquals(
                "excavate_safe_tunnel.invalid_arguments",
                invalid.rejection().code()
        );
    }
}
