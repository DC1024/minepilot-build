package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FarmingSkillParameters {
    private static final int MAX_HORIZONTAL = 29_999_984;
    private static final int MAX_VERTICAL = 2_048;
    private static final Set<String> ARGUMENTS = Set.of(
            "dimension",
            "crop",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );
    private static final Set<String> WATER_ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );
    private static final Set<String> SUGARCANE_ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );
    private static final Set<String> FIELD_ARGUMENTS = Set.of(
            "dimension",
            "crop",
            "minimumPlots",
            "requireSingleChunk"
    );
    private static final Set<String> MAINTENANCE_ARGUMENTS = Set.of(
            "dimension",
            "crop",
            "maximumPlants"
    );

    private FarmingSkillParameters() {
    }

    static SkillParameterResult<HarvestAndReplantParameters> parse(
            List<SkillArgument> arguments
    ) {
        Map<String, String> values = exact(arguments, ARGUMENTS);
        if (values == null) {
            return invalid();
        }
        try {
            CropKind crop = CropKind.fromBlockId(
                    values.get("crop")
            ).orElseThrow();
            return SkillParameterResult.valid(
                    new HarvestAndReplantParameters(
                            DimensionRef.parse(values.get("dimension")),
                            crop,
                            new ObservedBlockTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face(values.get("face"))
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    static SkillParameterResult<PrepareAndPlantPlotParameters>
            parsePrepareAndPlant(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(arguments, ARGUMENTS);
        if (values == null) {
            return invalidPrepareAndPlant();
        }
        try {
            final CropKind crop = CropKind.fromBlockId(
                    values.get("crop")
            ).orElseThrow();
            final BlockFace face = face(values.get("face"));
            if (face != BlockFace.UP
                    || !"minecraft:farmland".equals(
                            crop.substrateBlockId()
                    )) {
                return invalidPrepareAndPlant();
            }
            return SkillParameterResult.valid(
                    new PrepareAndPlantPlotParameters(
                            DimensionRef.parse(values.get("dimension")),
                            crop,
                            new ObservedBlockTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalidPrepareAndPlant();
        }
    }

    static SkillParameterResult<PrepareWaterSourceParameters>
            parsePrepareWaterSource(final List<SkillArgument> arguments) {
        final Map<String, String> values = exact(
                arguments,
                WATER_ARGUMENTS
        );
        if (values == null) {
            return invalidPrepareWaterSource();
        }
        try {
            final BlockFace face = face(values.get("face"));
            if (face != BlockFace.UP) {
                return invalidPrepareWaterSource();
            }
            return SkillParameterResult.valid(
                    new PrepareWaterSourceParameters(
                            DimensionRef.parse(values.get("dimension")),
                            new ObservedBlockTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalidPrepareWaterSource();
        }
    }

    static SkillParameterResult<PlantObservedSugarcaneParameters>
            parsePlantObservedSugarcane(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                SUGARCANE_ARGUMENTS
        );
        if (values == null) {
            return invalidPlantObservedSugarcane();
        }
        try {
            final BlockFace face = face(values.get("face"));
            if (face != BlockFace.UP) {
                return invalidPlantObservedSugarcane();
            }
            return SkillParameterResult.valid(
                    new PlantObservedSugarcaneParameters(
                            DimensionRef.parse(values.get("dimension")),
                            new ObservedBlockTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return invalidPlantObservedSugarcane();
        }
    }

    static SkillParameterResult<BuildHydratedCropFieldParameters>
            parseBuildHydratedCropField(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                FIELD_ARGUMENTS
        );
        if (values == null) {
            return invalidBuildHydratedCropField();
        }
        try {
            final String rawCropValue = values.get("crop");
            final String cropValue = canonicalFieldCropValue(
                    rawCropValue
            );
            if (rawCropValue == null
                    || !rawCropValue.equals(rawCropValue.toLowerCase(
                            Locale.ROOT
                    ))) {
                return invalidBuildHydratedCropField();
            }
            final CropFieldVariant crop = CropFieldVariant.valueOf(
                    cropValue.toUpperCase(Locale.ROOT)
            );
            final String singleChunk = values.get(
                    "requireSingleChunk"
            );
            if (!"true".equals(singleChunk)
                    && !"false".equals(singleChunk)) {
                return invalidBuildHydratedCropField();
            }
            return SkillParameterResult.valid(
                    new BuildHydratedCropFieldParameters(
                            DimensionRef.parse(values.get("dimension")),
                            crop,
                            integer(values.get("minimumPlots"), 8, 80),
                            Boolean.parseBoolean(singleChunk)
                    )
            );
        } catch (RuntimeException exception) {
            return invalidBuildHydratedCropField();
        }
    }

    static SkillParameterResult<MaintainObservedCropFieldParameters>
            parseMaintainObservedCropField(
                    final List<SkillArgument> arguments
            ) {
        final Map<String, String> values = exact(
                arguments,
                MAINTENANCE_ARGUMENTS
        );
        if (values == null) {
            return invalidMaintainObservedCropField();
        }
        try {
            final String rawCropValue = values.get("crop");
            final String cropValue = canonicalFieldCropValue(
                    rawCropValue
            );
            if (rawCropValue == null
                    || !rawCropValue.equals(rawCropValue.toLowerCase(
                            Locale.ROOT
                    ))) {
                return invalidMaintainObservedCropField();
            }
            return SkillParameterResult.valid(
                    new MaintainObservedCropFieldParameters(
                            DimensionRef.parse(values.get("dimension")),
                            CropFieldVariant.valueOf(
                                    cropValue.toUpperCase(Locale.ROOT)
                            ),
                            integer(values.get("maximumPlants"), 1, 80)
                    )
            );
        } catch (RuntimeException exception) {
            return invalidMaintainObservedCropField();
        }
    }

    /**
     * Models commonly copy the full block identifier from the current
     * semantic observation (for example, minecraft:wheat), while the
     * coordinate-free field compound documents its enum short name
     * (wheat).  These are the same server-authored material, so normalize
     * only this bounded alias set before enum parsing; no arbitrary registry
     * name or world target is accepted.
     */
    private static String canonicalFieldCropValue(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.toLowerCase(Locale.ROOT);
        final String shortName = normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length())
                : normalized;
        return switch (shortName) {
            case "wheat" -> "wheat";
            case "carrot", "carrots" -> "carrot";
            case "potato", "potatoes" -> "potato";
            case "beetroot", "beetroots" -> "beetroot";
            default -> shortName;
        };
    }

    private static Map<String, String> exact(
            final List<SkillArgument> arguments,
            final Set<String> accepted
    ) {
        if (arguments == null || arguments.size() != accepted.size()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !accepted.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return null;
            }
        }
        return values.keySet().equals(accepted)
                ? Map.copyOf(values)
                : null;
    }

    private static BlockFace face(String value) {
        if (value == null
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid face");
        }
        return BlockFace.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static int integer(
            String value,
            int minimum,
            int maximum
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)
                || parsed < minimum
                || parsed > maximum) {
            throw new IllegalArgumentException("Integer outside bounds");
        }
        return parsed;
    }

    private static long nonNegativeLong(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid sequence");
        }
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value) || parsed < 0) {
            throw new IllegalArgumentException("Invalid sequence");
        }
        return parsed;
    }

    private static <P> SkillParameterResult<P> invalid() {
        return SkillParameterResult.invalid(
                "harvest_and_replant_step.invalid_arguments"
        );
    }

    private static <P> SkillParameterResult<P>
            invalidPrepareAndPlant() {
        return SkillParameterResult.invalid(
                "prepare_and_plant_plot.invalid_arguments"
        );
    }

    private static <P> SkillParameterResult<P>
            invalidPrepareWaterSource() {
        return SkillParameterResult.invalid(
                "prepare_water_source.invalid_arguments"
        );
    }

    private static <P> SkillParameterResult<P>
            invalidPlantObservedSugarcane() {
        return SkillParameterResult.invalid(
                "plant_observed_sugarcane.invalid_arguments"
        );
    }

    private static <P> SkillParameterResult<P>
            invalidBuildHydratedCropField() {
        return SkillParameterResult.invalid(
                "build_hydrated_crop_field.invalid_arguments"
        );
    }

    private static <P> SkillParameterResult<P>
            invalidMaintainObservedCropField() {
        return SkillParameterResult.invalid(
                "maintain_observed_crop_field.invalid_arguments"
        );
    }
}
