package dev.mcai.companion.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CropFieldMaintenancePlannerTest {
    private static final long REVISION = 17;
    private static final UUID PLAYER = UUID.fromString(
            "9e9bdf82-e23e-41df-88ea-7e2b9fc60675"
    );

    private final CropFieldMaintenancePlanner planner =
            new CropFieldMaintenancePlanner();

    @Test
    void selectsEveryMatureCellAndOnlyObservedSafeStands() {
        final MechanismSiteSurvey survey = field(
                0,
                0,
                CropFieldVariant.WHEAT,
                7,
                8,
                true
        );
        final CropFieldMaintenancePlan plan = planner.plan(
                survey,
                new CropFieldMaintenanceRequest(
                        CropFieldVariant.WHEAT,
                        8
                )
        ).plan().orElseThrow();

        assertEquals(8, plan.cells().size());
        assertEquals(8, plan.cells().stream()
                .map(CropFieldMaintenancePlan.Cell::cropPosition)
                .distinct().count());
        assertTrue(plan.cells().stream()
                .flatMap(cell -> cell.workStandSupports().stream())
                .allMatch(stand -> safeStand(survey, stand)));
        assertTrue(plan.cells().stream().allMatch(cell ->
                cell.workStandSupports().getFirst().euclideanDistance(
                        cell.cropPosition().below()
                ) == 1.0
        ));
        assertTrue(plan.cells().stream()
                .flatMap(cell -> cell.workStandSupports().stream()
                        .map(stand -> stand.euclideanDistance(
                                cell.cropPosition().below()
                        )))
                .allMatch(distance -> distance <= 2.0));
    }

    @Test
    void translationMovesTheGeneratedWorkOrderWithoutFixedCoordinates() {
        final CropFieldMaintenanceRequest request =
                new CropFieldMaintenanceRequest(
                        CropFieldVariant.CARROT,
                        8
                );
        final CropFieldMaintenancePlan origin = planner.plan(
                field(0, 0, request.crop(), 7, 8, true),
                request
        ).plan().orElseThrow();
        final int dx = 53;
        final int dz = -41;
        final CropFieldMaintenancePlan translated = planner.plan(
                field(dx, dz, request.crop(), 7, 8, true),
                request
        ).plan().orElseThrow();

        assertEquals(origin.cells().size(), translated.cells().size());
        for (int index = 0; index < origin.cells().size(); index++) {
            final var left = origin.cells().get(index);
            final var right = translated.cells().get(index);
            assertEquals(
                    left.cropPosition().offset(dx, 0, dz),
                    right.cropPosition()
            );
            assertEquals(
                    left.workStandSupports().stream()
                            .map(position -> position.offset(dx, 0, dz))
                            .toList(),
                    right.workStandSupports()
            );
        }
    }

    @Test
    void maximumPlantsBoundsTheWorkOrderAndSeedReserve() {
        final MechanismSiteSurvey survey = field(
                0,
                0,
                CropFieldVariant.POTATO,
                7,
                4,
                true
        );
        final CropFieldMaintenancePlan plan = planner.plan(
                survey,
                new CropFieldMaintenanceRequest(
                        CropFieldVariant.POTATO,
                        4
                )
        ).plan().orElseThrow();

        assertEquals(4, plan.cells().size());
    }

    @Test
    void denseInteriorCropsCanUseObservedNeighbouringCropSupports() {
        final MechanismSiteSurvey survey = denseField();
        final CropFieldMaintenancePlan plan = planner.plan(
                survey,
                new CropFieldMaintenanceRequest(
                        CropFieldVariant.WHEAT,
                        16
                )
        ).plan().orElseThrow();
        final Set<GridPos> cropSupports = plan.cells().stream()
                .map(cell -> cell.cropPosition().below())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(16, plan.cells().size());
        assertTrue(plan.cells().stream()
                .flatMap(cell -> cell.workStandSupports().stream())
                .anyMatch(cropSupports::contains));
        assertTrue(plan.cells().stream().allMatch(cell ->
                cell.workStandSupports().getFirst().euclideanDistance(
                        cell.cropPosition().below()
                ) == 1.0
        ));
    }

    @Test
    void immatureCropsMissingReserveAndUnknownSupportsFailClosed() {
        assertFailure(
                field(0, 0, CropFieldVariant.WHEAT, 6, 8, true),
                CropFieldVariant.WHEAT,
                "maintenance.no_mature_crop"
        );
        assertFailure(
                field(0, 0, CropFieldVariant.WHEAT, 7, 7, true),
                CropFieldVariant.WHEAT,
                "maintenance.insufficient_planting_items"
        );
        assertFailure(
                field(0, 0, CropFieldVariant.WHEAT, 7, 8, false),
                CropFieldVariant.WHEAT,
                "maintenance.no_safe_stand"
        );
    }

    private static void assertFailure(
            final MechanismSiteSurvey survey,
            final CropFieldVariant crop,
            final String expected
    ) {
        assertEquals(
                expected,
                new CropFieldMaintenancePlanner().plan(
                        survey,
                        new CropFieldMaintenanceRequest(crop, 8)
                ).failureCode().orElseThrow()
        );
    }

    private static MechanismSiteSurvey field(
            final int centerX,
            final int centerZ,
            final CropFieldVariant crop,
            final int age,
            final int plantingItems,
            final boolean safeSupports
    ) {
        final List<MechanismSiteSurvey.SurfaceObservation> surfaces =
                new ArrayList<>();
        final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels =
                new HashMap<>();
        final Set<GridPos> crops = cropPositions(centerX, centerZ);
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                final GridPos support = new GridPos(x, 0, z);
                put(voxels, new ObservedVoxel(
                        support,
                        VoxelKind.SOLID,
                        0.0,
                        REVISION,
                        safeSupports
                                ? OccupancyEvidence.SURFACE_HIT
                                : OccupancyEvidence.UNKNOWN,
                        safeSupports
                                ? TopSupportAffordance.STURDY_FULL_TOP
                                : TopSupportAffordance.UNKNOWN
                ));
                put(voxels, clear(support.above()));
                put(voxels, clear(support.above(2)));
            }
        }
        for (GridPos position : crops) {
            surfaces.add(new MechanismSiteSurvey.SurfaceObservation(
                    new VisibleBlockFace(
                            new BlockCoordinate(
                                    position.x(),
                                    position.y(),
                                    position.z()
                            ),
                            crop.plantedBlockId(),
                            "north",
                            new PerceptionVec3(
                                    position.x() + 0.5,
                                    position.y() + 0.5,
                                    position.z()
                            ),
                            Math.hypot(
                                    position.x() - centerX,
                                    position.z() - centerZ
                            ),
                            PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                            Map.of("age", Integer.toString(age)),
                            TopSupportAffordance.UNKNOWN,
                            15
                    ),
                    100,
                    REVISION
            ));
        }
        return new MechanismSiteSurvey(
                PLAYER,
                2,
                DimensionRef.OVERWORLD,
                100,
                REVISION,
                new GridPos(centerX + 3, 1, centerZ),
                new PerceptionVec3(-1, 0, 0),
                List.of(new InventoryItemSummary(
                        crop.plantingItemId(),
                        plantingItems
                )),
                surfaces,
                voxels,
                Optional.empty()
        );
    }

    private static Set<GridPos> cropPositions(
            final int centerX,
            final int centerZ
    ) {
        final Set<GridPos> result = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    result.add(new GridPos(centerX + dx, 1, centerZ + dz));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static MechanismSiteSurvey denseField() {
        final List<MechanismSiteSurvey.SurfaceObservation> surfaces =
                new ArrayList<>();
        final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels =
                new HashMap<>();
        final Set<GridPos> crops = new HashSet<>();
        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) {
                crops.add(new GridPos(x, 1, z));
            }
        }
        for (int x = -6; x <= 5; x++) {
            for (int z = -6; z <= 5; z++) {
                final GridPos support = new GridPos(x, 0, z);
                put(voxels, new ObservedVoxel(
                        support,
                        VoxelKind.SOLID,
                        0.0,
                        REVISION,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                put(voxels, clear(support.above()));
                put(voxels, clear(support.above(2)));
            }
        }
        for (GridPos crop : crops) {
            surfaces.add(new MechanismSiteSurvey.SurfaceObservation(
                    new VisibleBlockFace(
                            new BlockCoordinate(
                                    crop.x(),
                                    crop.y(),
                                    crop.z()
                            ),
                            CropFieldVariant.WHEAT.plantedBlockId(),
                            "north",
                            new PerceptionVec3(
                                    crop.x() + 0.5,
                                    crop.y() + 0.5,
                                    crop.z()
                            ),
                            4.0,
                            PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                            Map.of("age", "7"),
                            TopSupportAffordance.UNKNOWN,
                            15
                    ),
                    100,
                    REVISION
            ));
        }
        return new MechanismSiteSurvey(
                PLAYER,
                2,
                DimensionRef.OVERWORLD,
                100,
                REVISION,
                new GridPos(3, 1, 0),
                new PerceptionVec3(-1, 0, 0),
                List.of(new InventoryItemSummary(
                        CropFieldVariant.WHEAT.plantingItemId(),
                        16
                )),
                surfaces,
                voxels,
                Optional.empty()
        );
    }

    private static void put(
            final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels,
            final ObservedVoxel voxel
    ) {
        voxels.put(
                voxel.position(),
                new MechanismSiteSurvey.VoxelObservation(voxel, 100)
        );
    }

    private static ObservedVoxel clear(final GridPos position) {
        return new ObservedVoxel(
                position,
                VoxelKind.AIR,
                0.0,
                REVISION,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }

    private static boolean safeStand(
            final MechanismSiteSurvey survey,
            final GridPos stand
    ) {
        return survey.voxelAt(stand).isPresent()
                && survey.voxelAt(stand.above()).isPresent()
                && survey.voxelAt(stand.above(2)).isPresent();
    }
}
