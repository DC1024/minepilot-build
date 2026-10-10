package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.InventoryItemSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selects mature observed crops and safe ordinary-player work stands.  It
 * receives no level/chunk accessor and emits no block blueprint.
 */
public final class CropFieldMaintenancePlanner {
    private static final int MAXIMUM_STAND_RADIUS = 4;
    private static final int MAXIMUM_STANDS_PER_CELL = 8;
    /*
     * Dense fields have interior crops with no adjacent bare support. Normal
     * walking does not trample farmland, so an already observed crop support
     * is a legitimate work stand; the sorter still prefers an equally close
     * bare stand. Two horizontal blocks cover a dense four-wide plot while
     * keeping pickup travel short. The action layer performs its fresh
     * crosshair and reach validation.
     */
    private static final double MAXIMUM_WORK_STAND_DISTANCE = 2.0;
    private static final double MAXIMUM_SITE_DANGER = 0.20;
    private static final Set<String> CROP_BLOCKS = Set.of(
            "minecraft:wheat",
            "minecraft:carrots",
            "minecraft:potatoes",
            "minecraft:beetroots"
    );

    public CropFieldMaintenancePlanningResult plan(
            final MechanismSiteSurvey survey,
            final CropFieldMaintenanceRequest request
    ) {
        Objects.requireNonNull(survey, "survey");
        Objects.requireNonNull(request, "request");
        final Map<GridPos, MechanismSiteSurvey.SurfaceObservation> mature =
                matureSurfaces(survey, request.crop());
        if (mature.isEmpty()) {
            return CropFieldMaintenancePlanningResult.failed(
                    "maintenance.no_mature_crop"
            );
        }
        final Set<GridPos> cropSupports = observedCropSupports(survey);
        final List<CropFieldMaintenancePlan.Cell> candidates = mature
                .keySet().stream()
                .sorted()
                .map(position -> cell(
                        survey,
                        position,
                        cropSupports,
                        survey.feet()
                ))
                .flatMap(Optional::stream)
                .toList();
        if (candidates.isEmpty()) {
            return CropFieldMaintenancePlanningResult.failed(
                    "maintenance.no_safe_stand"
            );
        }
        final List<CropFieldMaintenancePlan.Cell> ordered = greedyOrder(
                candidates,
                survey.feet(),
                request.maximumPlants()
        );
        if (itemCount(
                survey.inventory(),
                request.crop().plantingItemId()
        ) < ordered.size()) {
            return CropFieldMaintenancePlanningResult.failed(
                    "maintenance.insufficient_planting_items"
            );
        }
        return CropFieldMaintenancePlanningResult.planned(
                new CropFieldMaintenancePlan(
                        survey.dimension(),
                        survey.sourceRevision(),
                        request.crop(),
                        ordered
                )
        );
    }

    private static Map<GridPos,
            MechanismSiteSurvey.SurfaceObservation> matureSurfaces(
                    final MechanismSiteSurvey survey,
                    final CropFieldVariant crop
            ) {
        final Map<GridPos, MechanismSiteSurvey.SurfaceObservation> result =
                new HashMap<>();
        survey.surfaces().stream().filter(observation -> {
            final var face = observation.face();
            return crop.plantedBlockId().equals(face.blockTypeId())
                    && Integer.toString(crop.matureAge()).equals(
                            face.stateProperties().get("age")
                    );
        }).forEach(observation -> {
            final var block = observation.face().block();
            final GridPos position = new GridPos(
                    block.x(),
                    block.y(),
                    block.z()
            );
            result.merge(position, observation, (left, right) ->
                    right.observationRevision()
                                    > left.observationRevision()
                            || right.observationRevision()
                                    == left.observationRevision()
                                    && right.face().distance()
                                        < left.face().distance()
                            ? right : left
            );
        });
        return Map.copyOf(result);
    }

    private static Set<GridPos> observedCropSupports(
            final MechanismSiteSurvey survey
    ) {
        final Set<GridPos> result = new HashSet<>();
        survey.surfaces().stream()
                .map(MechanismSiteSurvey.SurfaceObservation::face)
                .filter(face -> CROP_BLOCKS.contains(face.blockTypeId()))
                .forEach(face -> result.add(new GridPos(
                        face.block().x(),
                        face.block().y() - 1,
                        face.block().z()
                )));
        return Set.copyOf(result);
    }

    private static Optional<CropFieldMaintenancePlan.Cell> cell(
            final MechanismSiteSurvey survey,
            final GridPos crop,
            final Set<GridPos> cropSupports,
            final GridPos from
    ) {
        final GridPos substrate = crop.below();
        final List<GridPos> stands = new ArrayList<>();
        for (int dx = -MAXIMUM_STAND_RADIUS;
                dx <= MAXIMUM_STAND_RADIUS; dx++) {
            for (int dz = -MAXIMUM_STAND_RADIUS;
                    dz <= MAXIMUM_STAND_RADIUS; dz++) {
                if (dx == 0 && dz == 0
                        || Math.hypot(dx, dz)
                                > MAXIMUM_STAND_RADIUS) {
                    continue;
                }
                final GridPos stand = substrate.offset(dx, 0, dz);
                if (stand.euclideanDistance(substrate)
                            <= MAXIMUM_WORK_STAND_DISTANCE
                        && safeStandingCell(survey, stand)) {
                    stands.add(stand);
                }
            }
        }
        stands.sort(Comparator
                .comparingDouble((GridPos stand) ->
                        stand.euclideanDistance(substrate))
                .thenComparing(cropSupports::contains)
                .thenComparingDouble(stand ->
                        from.euclideanDistance(stand.above()))
                .thenComparing(GridPos::compareTo));
        if (stands.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CropFieldMaintenancePlan.Cell(
                crop,
                stands.stream().limit(MAXIMUM_STANDS_PER_CELL).toList()
        ));
    }

    private static List<CropFieldMaintenancePlan.Cell> greedyOrder(
            final List<CropFieldMaintenancePlan.Cell> candidates,
            final GridPos initialFeet,
            final int maximum
    ) {
        final List<CropFieldMaintenancePlan.Cell> remaining =
                new ArrayList<>(candidates);
        final Set<GridPos> cropSupports = candidates.stream()
                .map(cell -> cell.cropPosition().below())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final List<CropFieldMaintenancePlan.Cell> result =
                new ArrayList<>();
        GridPos current = initialFeet;
        while (!remaining.isEmpty() && result.size() < maximum) {
            final GridPos from = current;
            final CropFieldMaintenancePlan.Cell next = remaining.stream()
                    .min(Comparator
                            .comparingDouble(
                                    (CropFieldMaintenancePlan.Cell cell) ->
                                        cell.workStandSupports().stream()
                                            .mapToDouble(stand ->
                                                from.euclideanDistance(
                                                    stand.above()
                                                ))
                                            .min().orElseThrow()
                            )
                            .thenComparing(
                                    CropFieldMaintenancePlan.Cell::cropPosition
                            ))
                    .orElseThrow();
            final List<GridPos> stands = next.workStandSupports().stream()
                    .sorted(Comparator
                            .comparingDouble((GridPos stand) ->
                                    stand.euclideanDistance(
                                            next.cropPosition().below()
                                    ))
                            .thenComparing(cropSupports::contains)
                            .thenComparingDouble(stand ->
                                    from.euclideanDistance(stand.above()))
                            .thenComparing(GridPos::compareTo))
                    .toList();
            final CropFieldMaintenancePlan.Cell ordered =
                    new CropFieldMaintenancePlan.Cell(
                            next.cropPosition(),
                            stands
                    );
            result.add(ordered);
            remaining.remove(next);
            current = stands.getFirst().above();
        }
        return List.copyOf(result);
    }

    private static boolean safeStandingCell(
            final MechanismSiteSurvey survey,
            final GridPos supportPosition
    ) {
        final Optional<ObservedVoxel> support = survey.voxelAt(
                supportPosition
        );
        final Optional<ObservedVoxel> feet = survey.voxelAt(
                supportPosition.above()
        );
        final Optional<ObservedVoxel> head = survey.voxelAt(
                supportPosition.above(2)
        );
        return support.isPresent()
                && feet.isPresent()
                && head.isPresent()
                && NavigationEvidence.isFreshStandingSupport(
                        support.orElseThrow(),
                        support.orElseThrow().observationRevision()
                )
                && NavigationEvidence.hasTraversalClearance(
                        feet.orElseThrow()
                )
                && NavigationEvidence.hasTraversalClearance(
                        head.orElseThrow()
                )
                && support.orElseThrow().effectiveDanger()
                        <= MAXIMUM_SITE_DANGER
                && feet.orElseThrow().effectiveDanger()
                        <= MAXIMUM_SITE_DANGER
                && head.orElseThrow().effectiveDanger()
                        <= MAXIMUM_SITE_DANGER;
    }

    private static int itemCount(
            final List<InventoryItemSummary> inventory,
            final String itemId
    ) {
        return inventory.stream()
                .filter(item -> itemId.equals(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }
}
