package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuildHydratedCropFieldParametersTest {
    @Test
    void parsesOnlyCoordinateFreeCanonicalScaleAndCropArguments() {
        final SkillParameterResult<BuildHydratedCropFieldParameters> parsed =
                FarmingSkillParameters.parseBuildHydratedCropField(List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("crop", "wheat"),
                        new SkillArgument("minimumPlots", "24"),
                        new SkillArgument("requireSingleChunk", "true")
                ));

        final BuildHydratedCropFieldParameters value = parsed.value()
                .orElseThrow();
        assertEquals(
                dev.mcai.companion.mechanism.CropFieldVariant.WHEAT,
                value.crop()
        );
        assertEquals(24, value.minimumPlots());
        assertTrue(value.requireSingleChunk());
    }

    @Test
    void rejectsAliasesRangesAndAnyExtraCoordinate() {
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat_seeds"),
                argument("minimumPlots", "8"),
                argument("requireSingleChunk", "false")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat"),
                argument("minimumPlots", "81"),
                argument("requireSingleChunk", "false")
        ));
        assertRejected(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("crop", "wheat"),
                argument("minimumPlots", "8"),
                argument("requireSingleChunk", "false"),
                argument("x", "100")
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
                "build_hydrated_crop_field.invalid_arguments",
                FarmingSkillParameters
                        .parseBuildHydratedCropField(arguments)
                        .failure().orElseThrow().code()
        );
    }
}
