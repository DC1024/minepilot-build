package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FarmingSkillsRegistrationTest {
    @Test
    void registersExactPublicSkillAndDocumentsFairBinding() {
        var interactionFrames =
                new FarmingSkillTestFixtures.MutableFrames(
                        FarmingSkillTestFixtures.matureCropFrame(4)
                );
        SkillRegistry registry = FarmingSkills.registerAll(
                new SkillRegistry(),
                FarmingSkillTestFixtures.PLAYER_ID,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(
                        interactionFrames
                ),
                new FarmingSkillTestFixtures.RecordingActuator(),
                interactionFrames,
                Optional::empty,
                new dev.mcai.companion.mechanism.HydratedCropFieldPlanService() {
                    @Override
                    public CompletableFuture<dev.mcai.companion.mechanism.MechanismPlanningResult>
                            plan(
                                    final dev.mcai.companion.mechanism.MechanismSiteSurvey survey,
                                    final dev.mcai.companion.mechanism.HydratedCropFieldRequest request
                            ) {
                        return CompletableFuture.completedFuture(
                                dev.mcai.companion.mechanism.MechanismPlanningResult.failed(
                                        "crop_field.test_not_planned"
                                )
                        );
                    }

                    @Override
                    public CompletableFuture<dev.mcai.companion.mechanism.CropFieldMaintenancePlanningResult>
                            planMaintenance(
                                    final dev.mcai.companion.mechanism.MechanismSiteSurvey survey,
                                    final dev.mcai.companion.mechanism.CropFieldMaintenanceRequest request
                            ) {
                        return CompletableFuture.completedFuture(
                                dev.mcai.companion.mechanism.CropFieldMaintenancePlanningResult.failed(
                                        "crop_field.test_maintenance_not_planned"
                                )
                        );
                    }
                }
        );

        assertEquals(
                Set.of(
                        "harvest_and_replant_step",
                        "prepare_and_plant_plot",
                        "prepare_water_source",
                        "plant_observed_sugarcane",
                        "build_hydrated_crop_field",
                        "maintain_observed_crop_field"
                ),
                registry.names()
        );
        assertTrue(FarmingSkills.plannerGuide().contains(
                "sampleSequence"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "visibleBlockFaces"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "prepare_and_plant_plot"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "prepare_water_source"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "plant_observed_sugarcane"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "build_hydrated_crop_field"
        ));
        assertTrue(FarmingSkills.plannerGuide().contains(
                "maintain_observed_crop_field"
        ));
    }
}
