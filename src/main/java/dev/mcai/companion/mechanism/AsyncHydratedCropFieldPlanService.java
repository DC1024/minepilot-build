package dev.mcai.companion.mechanism;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-worker, one-queued-plan service. The game thread only submits immutable
 * evidence and polls a future; expensive candidate enumeration never blocks a
 * server tick and request buildup is impossible.
 */
public final class AsyncHydratedCropFieldPlanService
        implements HydratedCropFieldPlanService, AutoCloseable {
    private final HydratedCropFieldPlanner planner;
    private final CropFieldMaintenancePlanner maintenancePlanner;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AsyncHydratedCropFieldPlanService() {
        this(
                new HydratedCropFieldPlanner(),
                new CropFieldMaintenancePlanner()
        );
    }

    AsyncHydratedCropFieldPlanService(
            final HydratedCropFieldPlanner planner
    ) {
        this(planner, new CropFieldMaintenancePlanner());
    }

    AsyncHydratedCropFieldPlanService(
            final HydratedCropFieldPlanner planner,
            final CropFieldMaintenancePlanner maintenancePlanner
    ) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.maintenancePlanner = Objects.requireNonNull(
                maintenancePlanner,
                "maintenancePlanner"
        );
        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofVirtual()
                        .name("mcai-mechanism-planner-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public CompletableFuture<CropFieldMaintenancePlanningResult>
            planMaintenance(
                    final MechanismSiteSurvey survey,
                    final CropFieldMaintenanceRequest request
            ) {
        Objects.requireNonNull(survey, "survey");
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Mechanism planner is closed"
                    )
            );
        }
        try {
            return CompletableFuture.supplyAsync(
                    () -> maintenancePlanner.plan(survey, request),
                    executor
            );
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @Override
    public CompletableFuture<MechanismPlanningResult> plan(
            final MechanismSiteSurvey survey,
            final HydratedCropFieldRequest request
    ) {
        Objects.requireNonNull(survey, "survey");
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Mechanism planner is closed"
                    )
            );
        }
        try {
            return CompletableFuture.supplyAsync(
                    () -> planner.plan(survey, request),
                    executor
            );
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
            executor.getQueue().clear();
        }
    }
}
