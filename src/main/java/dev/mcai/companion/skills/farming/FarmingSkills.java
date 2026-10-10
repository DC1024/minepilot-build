package dev.mcai.companion.skills.farming;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.mechanism.HydratedCropFieldPlanService;
import dev.mcai.companion.skills.building.ShelterFrameSource;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for fair, resumable field work.
 */
public final class FarmingSkills {
    public static final String HARVEST_AND_REPLANT_STEP =
            "harvest_and_replant_step";
    public static final String PREPARE_AND_PLANT_PLOT =
            PrepareAndPlantPlotSkill.NAME;
    public static final String PREPARE_WATER_SOURCE =
            PrepareWaterSourceSkill.NAME;
    public static final String PLANT_OBSERVED_SUGARCANE =
            PlantObservedSugarcaneSkill.NAME;
    public static final String BUILD_HYDRATED_CROP_FIELD =
            BuildHydratedCropFieldSkill.NAME;
    public static final String MAINTAIN_OBSERVED_CROP_FIELD =
            MaintainObservedCropFieldSkill.NAME;

    private FarmingSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                coreActuator,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );
    }

    /** Registers atomic field work plus the on-site generated field task. */
    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            ShelterFrameSource shelterFrames,
            HydratedCropFieldPlanService planService
    ) {
        registerAll(
                registry,
                playerId,
                coreActuator,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );
        registry.register(
                BUILD_HYDRATED_CROP_FIELD,
                new BuildHydratedCropFieldSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        Objects.requireNonNull(
                                shelterFrames,
                                "shelterFrames"
                        ),
                        Objects.requireNonNull(planService, "planService")
                )
        );
        return registry.register(
                MAINTAIN_OBSERVED_CROP_FIELD,
                new MaintainObservedCropFieldSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        shelterFrames,
                        planService
                )
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            FarmingSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(coreActuator, "coreActuator");
        Objects.requireNonNull(coreFrames, "coreFrames");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        registry.register(
                HARVEST_AND_REPLANT_STEP,
                new HarvestAndReplantStepSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        policy
                )
        );
        registry.register(
                PREPARE_AND_PLANT_PLOT,
                new PrepareAndPlantPlotSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        policy
                )
        );
        registry.register(
                PLANT_OBSERVED_SUGARCANE,
                new PlantObservedSugarcaneSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        policy
                )
        );
        return registry.register(
                PREPARE_WATER_SOURCE,
                new PrepareWaterSourceSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        actuator,
                        frames,
                        policy
                )
        );
    }

    public static String plannerGuide() {
        return """
            build_hydrated_crop_field: dimension,crop wheat|carrot|potato|
            beetroot,minimumPlots 8..80,requireSingleChunk; no coordinates.
            maintain_observed_crop_field: dimension,crop,maximumPlants 1..80;
            surveys, walks, harvests, replants, verifies.
            Atomic targets copy dimension,sampleSequence,x/y/z,face from one
            visibleBlockFaces entry. harvest_and_replant_step verifies one
            mature crop and replant. prepare_and_plant_plot tills/plants a
            visible dirt/grass/farmland top with owned items.
            prepare_water_source: visible top+owned water; face=up; no Nether.
            plant_observed_sugarcane:support+water.
            """;
    }
}
