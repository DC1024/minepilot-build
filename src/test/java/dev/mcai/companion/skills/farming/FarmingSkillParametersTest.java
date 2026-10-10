package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FarmingSkillParametersTest {
    @Test
    void parsesOnlySupportedCropAndObservedFaceFields() {
        var parsed = FarmingSkillParameters.parse(arguments());

        assertTrue(parsed.value().isPresent());
        assertEquals(
                CropKind.WHEAT,
                parsed.value().orElseThrow().crop()
        );
        assertEquals(
                21,
                parsed.value().orElseThrow().target().sampleSequence()
        );
    }

    @Test
    void rejectsUnsupportedCropHiddenHitAndNonCanonicalSequence() {
        List<SkillArgument> unsupported =
                new ArrayList<>(arguments());
        unsupported.set(
                1,
                argument("crop", "examplemod:unknown_crop")
        );
        assertTrue(FarmingSkillParameters.parse(unsupported)
                .value().isEmpty());

        List<SkillArgument> hiddenHit = new ArrayList<>(arguments());
        hiddenHit.add(argument("hitX", "1.5"));
        assertTrue(FarmingSkillParameters.parse(hiddenHit)
                .value().isEmpty());

        List<SkillArgument> nonCanonical =
                new ArrayList<>(arguments());
        nonCanonical.set(
                2,
                argument("sampleSequence", "021")
        );
        assertTrue(FarmingSkillParameters.parse(nonCanonical)
                .value().isEmpty());
    }

    @Test
    void preparePlotRequiresAnUpwardObservedFarmlandCropTarget() {
        final List<SkillArgument> plot = new ArrayList<>(arguments());
        plot.set(6, argument("face", "up"));

        assertTrue(FarmingSkillParameters.parsePrepareAndPlant(plot)
                .value().isPresent());

        final List<SkillArgument> side = new ArrayList<>(plot);
        side.set(6, argument("face", "north"));
        assertTrue(FarmingSkillParameters.parsePrepareAndPlant(side)
                .value().isEmpty());

        final List<SkillArgument> wart = new ArrayList<>(plot);
        wart.set(1, argument("crop", "minecraft:nether_wart"));
        assertTrue(FarmingSkillParameters.parsePrepareAndPlant(wart)
                .value().isEmpty());
    }

    @Test
    void waterSourceUsesOnlyAnUpwardTargetAndNoCropField() {
        final List<SkillArgument> water = List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "21"),
                argument("x", "1"),
                argument("y", "63"),
                argument("z", "0"),
                argument("face", "up")
        );
        assertTrue(FarmingSkillParameters.parsePrepareWaterSource(water)
                .value().isPresent());

        final List<SkillArgument> side = new ArrayList<>(water);
        side.set(5, argument("face", "north"));
        assertTrue(FarmingSkillParameters.parsePrepareWaterSource(side)
                .value().isEmpty());

        final List<SkillArgument> extra = new ArrayList<>(water);
        extra.add(argument("crop", "minecraft:wheat"));
        assertTrue(FarmingSkillParameters.parsePrepareWaterSource(extra)
                .value().isEmpty());
    }

    @Test
    void sugarcaneUsesOnlyAnUpwardObservedSupportTarget() {
        final List<SkillArgument> sugarcane = List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "21"),
                argument("x", "1"),
                argument("y", "63"),
                argument("z", "0"),
                argument("face", "up")
        );
        assertTrue(FarmingSkillParameters.parsePlantObservedSugarcane(
                sugarcane
        ).value().isPresent());

        final List<SkillArgument> side = new ArrayList<>(sugarcane);
        side.set(5, argument("face", "north"));
        assertTrue(FarmingSkillParameters.parsePlantObservedSugarcane(side)
                .value().isEmpty());

        final List<SkillArgument> extra = new ArrayList<>(sugarcane);
        extra.add(argument("crop", "minecraft:wheat"));
        assertTrue(FarmingSkillParameters.parsePlantObservedSugarcane(extra)
                .value().isEmpty());
    }

    private static List<SkillArgument> arguments() {
        return List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "minecraft:wheat"),
                argument("sampleSequence", "21"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "west")
        );
    }

    private static SkillArgument argument(
            String name,
            String value
    ) {
        return new SkillArgument(name, value);
    }
}
