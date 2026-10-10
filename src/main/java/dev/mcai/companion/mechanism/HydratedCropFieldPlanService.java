package dev.mcai.companion.mechanism;

import java.util.concurrent.CompletableFuture;

/**
 * World-neutral asynchronous boundary for crop-field constraint solving.
 * Implementations receive only an immutable fair survey and never a level or
 * chunk accessor.
 */
public interface HydratedCropFieldPlanService {
    CompletableFuture<MechanismPlanningResult> plan(
            MechanismSiteSurvey survey,
            HydratedCropFieldRequest request
    );

    CompletableFuture<CropFieldMaintenancePlanningResult>
            planMaintenance(
                    final MechanismSiteSurvey survey,
                    final CropFieldMaintenanceRequest request
            );
}
