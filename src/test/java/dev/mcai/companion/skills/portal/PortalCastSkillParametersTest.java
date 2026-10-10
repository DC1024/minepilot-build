package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PortalCastSkillParametersTest {
    @Test
    void parsesTheTwoExplicitStageContracts() {
        var cast = PortalCastSkillParameters.parse(List.of(
                arg("dimension", "minecraft:overworld"),
                arg("sampleSequence", "42"),
                arg("anchorX", "10"),
                arg("anchorY", "64"),
                arg("anchorZ", "-3"),
                arg("axis", "x"),
                arg("operation", "cast_next"),
                arg("frameIndex", "9"),
                arg("lavaX", "12"),
                arg("lavaY", "63"),
                arg("lavaZ", "-1")
        )).value().orElseThrow();
        var light = PortalCastSkillParameters.parse(List.of(
                arg("dimension", "minecraft:overworld"),
                arg("sampleSequence", "43"),
                arg("anchorX", "10"),
                arg("anchorY", "64"),
                arg("anchorZ", "-3"),
                arg("axis", "z"),
                arg("operation", "light")
        )).value().orElseThrow();

        assertEquals(9, cast.frameIndex().orElseThrow());
        assertEquals(new GridPos(12, 63, -1), cast.lavaSource().orElseThrow());
        assertTrue(light.frameIndex().isEmpty());
        assertTrue(light.lavaSource().isEmpty());
    }

    @Test
    void rejectsAutoAxisNonCanonicalNumbersAndHiddenExtraData() {
        List<SkillArgument> arguments = new ArrayList<>(List.of(
                arg("dimension", "minecraft:overworld"),
                arg("sampleSequence", "042"),
                arg("anchorX", "10"),
                arg("anchorY", "64"),
                arg("anchorZ", "-3"),
                arg("axis", "auto"),
                arg("operation", "light")
        ));
        assertTrue(
                PortalCastSkillParameters.parse(arguments)
                        .value()
                        .isEmpty()
        );

        arguments.set(1, arg("sampleSequence", "42"));
        arguments.set(5, arg("axis", "x"));
        arguments.add(arg("seed", "123"));
        assertTrue(
                PortalCastSkillParameters.parse(arguments)
                        .value()
                        .isEmpty()
        );
    }

    @Test
    void generatedMinimumFrameHasTenUniqueCornerlessBlocks() {
        var anchor = new GridPos(5, 70, 8);
        var plan = CastObservedNetherPortalSkill.minimumFramePlan(
                anchor,
                PortalBuildAxis.X
        );

        assertEquals(10, plan.size());
        assertEquals(10, new java.util.HashSet<>(plan).size());
        assertTrue(plan.contains(anchor.offset(1, 0, 0)));
        assertTrue(plan.contains(anchor.offset(2, 4, 0)));
        assertTrue(!plan.contains(anchor));
        assertTrue(!plan.contains(anchor.offset(3, 4, 0)));
    }

    private static SkillArgument arg(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }
}
