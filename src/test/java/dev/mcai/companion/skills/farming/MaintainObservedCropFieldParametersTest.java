package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.model.SkillArgument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MaintainObservedCropFieldParametersTest {
    @Test
    void parsesOnlyCanonicalCoordinateFreeArguments() {
        final var result = FarmingSkillParameters
                .parseMaintainObservedCropField(List.of(
                        argument("dimension", "minecraft:overworld"),
                        argument("crop", "beetroot"),
                        argument("maximumPlants", "24")
                ));

        final MaintainObservedCropFieldParameters value = result.value()
                .orElseThrow();
        assertEquals(CropFieldVariant.BEETROOT, value.crop());
        assertEquals(24, value.maximumPlants());
    }

    @Test
    void acceptsEquivalentObservedBlockIdentifiers() {
        final var result = FarmingSkillParameters
                .parseMaintainObservedCropField(List.of(
                        argument("dimension", "minecraft:overworld"),
                        argument("crop", "minecraft:wheat"),
                        argument("maximumPlants", "3")
                ));

        assertEquals(
                CropFieldVariant.WHEAT,
                result.value().orElseThrow().crop()
        );
    }

    @Test
    void rejectsAliasesCaseRangesCoordinatesAndMissingArguments() {
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat_seeds"),
                argument("maximumPlants", "8")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "WHEAT"),
                argument("maximumPlants", "8")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat"),
                argument("maximumPlants", "0")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat"),
                argument("maximumPlants", "81")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat"),
                argument("maximumPlants", "8"),
                argument("x", "4")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat")
        ));
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }

    private static void assertRejected(
            final List<SkillArgument> arguments
    ) {
        assertEquals(
                "maintain_observed_crop_field.invalid_arguments",
                FarmingSkillParameters.parseMaintainObservedCropField(
                        arguments
                ).failure().orElseThrow().code()
        );
    }
}
