package dev.mcai.companion.skills.building;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.survey.SurveyResultBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Independent registration slice; the server runtime owns publication of
 * fair semantic observations into the supplied frame source.
 */
public final class DynamicShelterSkills {
    public static final String BUILD_SHELTER_STEP =
            "build_shelter_step";

    private DynamicShelterSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                new DynamicShelterPlanner(),
                (ignoredRevision, ignoredPlan) -> {
                }
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            DynamicShelterPlanner planner
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                planner,
                (ignoredRevision, ignoredPlan) -> {
                }
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(
                shelterCompleted,
                "shelterCompleted"
        );
        return registry.register(
                BUILD_SHELTER_STEP,
                new BuildShelterStepSkill(
                        playerId,
                        actuator,
                        frames,
                        planner,
                        shelterCompleted
                )
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            InventorySkillActuator inventoryActuator,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            SurveyResultBuffer surveyResults,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                inventoryActuator,
                coreActuator,
                coreFrames,
                surveyResults,
                planner,
                shelterCompleted,
                new OwnedStructureBlockIndex()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            InventorySkillActuator inventoryActuator,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            SurveyResultBuffer surveyResults,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted,
            OwnedStructureBlockIndex protectedStructures
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(
                inventoryActuator,
                "inventoryActuator"
        );
        Objects.requireNonNull(coreActuator, "coreActuator");
        Objects.requireNonNull(coreFrames, "coreFrames");
        Objects.requireNonNull(surveyResults, "surveyResults");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(
                shelterCompleted,
                "shelterCompleted"
        );
        Objects.requireNonNull(
                protectedStructures,
                "protectedStructures"
        );
        return registry.register(
                BUILD_SHELTER_STEP,
                new BuildShelterStepSkill(
                        playerId,
                        actuator,
                        frames,
                        inventoryActuator,
                        coreActuator,
                        coreFrames,
                        surveyResults,
                        planner,
                        shelterCompleted,
                        protectedStructures
                )
        );
    }

    public static String plannerGuide() {
        return """
            build_shelter_step: dimension,sampleSequence,scale
            compact/standard/spacious. Copy all three from the current
            observation; never send scale alone. Fairly surveys, ordinarily
            walks if crowded, equips, and builds a generated sealed 3x3x2+
            shelter with door/light. Keep scale fixed; reposition on
            no_visible_build_step.
            """;
    }
}
