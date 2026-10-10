package dev.mcai.companion.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class HydratedCropFieldPlannerTest {
    private static final long REVISION = 7;
    private static final String STONE_HOE = "minecraft:stone_hoe";
    private static final List<String> HOES = List.of(
            "minecraft:wooden_hoe",
            STONE_HOE,
            "minecraft:iron_hoe",
            "minecraft:diamond_hoe",
            "minecraft:netherite_hoe"
    );

    private final HydratedCropFieldPlanner planner =
            new HydratedCropFieldPlanner();

    @Test
    void specificationCarriesCausalCommissioningAndRepairKnowledge() {
        final MechanismSpec spec = planner.spec();

        assertEquals(1, spec.schemaVersion());
        assertEquals(
                "mcai_companion:hydrated_crop_field",
                spec.id()
        );
        assertFalse(spec.invariants().isEmpty());
        assertFalse(spec.componentGraph().components().isEmpty());
        assertFalse(spec.componentGraph().edges().isEmpty());
        assertFalse(spec.inputs().isEmpty());
        assertFalse(spec.outputs().isEmpty());
        assertFalse(spec.materialSubstitutions().isEmpty());
        assertFalse(spec.siteConstraints().isEmpty());
        assertFalse(spec.chunkAndDimensionConstraints().isEmpty());
        assertFalse(spec.safetyClearances().isEmpty());
        assertFalse(spec.commissioningProbes().isEmpty());
        assertFalse(spec.knownFailureModes().isEmpty());
        assertFalse(spec.repairStrategies().isEmpty());

        final Set<String> repairIds = new HashSet<>();
        spec.repairStrategies().forEach(repair ->
                repairIds.add(repair.id())
        );
        assertTrue(spec.knownFailureModes().stream().allMatch(failure ->
                repairIds.contains(failure.repairStrategyId())
        ));
    }

    @Test
    void allSupportedCropMaterialsProduceARecomputedPlan() {
        for (CropFieldVariant crop : CropFieldVariant.values()) {
            final MechanismPlan plan = planner.plan(
                    flatSite(0, 0, crop, STONE_HOE),
                    new HydratedCropFieldRequest(crop, 8, false)
            ).plan().orElseThrow();

            assertEquals(crop.plantingItemId(),
                    plan.selectedMaterials().get("crop"));
            assertEquals(STONE_HOE,
                    plan.selectedMaterials().get("tool"));
            assertEquals(8, plan.productionCells());
            assertEquals(8, count(
                    plan,
                    MechanismConstructionStep.Action.PLANT
            ));
        }
    }

    @Test
    void translatedEvidenceTranslatesTheGeneratedDagWithoutABlueprint() {
        final HydratedCropFieldRequest request =
                new HydratedCropFieldRequest(
                        CropFieldVariant.WHEAT,
                        24,
                        false
                );
        final MechanismPlan origin = planner.plan(
                flatSite(0, 0, request.crop(), STONE_HOE),
                request
        ).plan().orElseThrow();
        final int deltaX = 64;
        final int deltaZ = -48;
        final MechanismPlan translated = planner.plan(
                flatSite(deltaX, deltaZ, request.crop(), STONE_HOE),
                request
        ).plan().orElseThrow();

        assertEquals(
                origin.anchor().offset(deltaX, 0, deltaZ),
                translated.anchor()
        );
        assertEquals(origin.width(), translated.width());
        assertEquals(origin.depth(), translated.depth());
        assertEquals(origin.serviceFacing(), translated.serviceFacing());
        assertNotEquals(origin.planId(), translated.planId());
        assertEquals(origin.steps().size(), translated.steps().size());
        for (int index = 0; index < origin.steps().size(); index++) {
            assertEquals(
                    origin.steps().get(index).target()
                            .offset(deltaX, 0, deltaZ),
                    translated.steps().get(index).target()
            );
        }
    }

    @Test
    void observedObstacleForcesSiteReplanningAndIsNeverBuiltThrough() {
        final CropFieldVariant crop = CropFieldVariant.CARROT;
        final HydratedCropFieldRequest request =
                new HydratedCropFieldRequest(crop, 24, false);
        final MechanismPlan clear = planner.plan(
                flatSite(0, 0, crop, STONE_HOE),
                request
        ).plan().orElseThrow();
        final GridPos obstacle = clear.anchor();
        final MechanismPlan replanned = planner.plan(
                flatSite(
                        0,
                        0,
                        crop,
                        STONE_HOE,
                        Map.of(obstacle, "minecraft:stone"),
                        true,
                        DimensionRef.OVERWORLD
                ),
                request
        ).plan().orElseThrow();

        assertNotEquals(clear.anchor(), replanned.anchor());
        assertTrue(replanned.steps().stream()
                .filter(step -> step.action()
                        != MechanismConstructionStep.Action.PROBE)
                .noneMatch(step -> step.target().equals(obstacle)
                        || step.target().below().equals(obstacle)));
    }

    @Test
    void sideFacesCannotMasqueradeAsObservedTillableGround() {
        final CropFieldVariant crop = CropFieldVariant.WHEAT;
        final MechanismSiteFrame sideOnly = flatSite(
                0,
                0,
                crop,
                STONE_HOE,
                Map.of(),
                false,
                DimensionRef.OVERWORLD
        );

        assertEquals(
                "crop_field.insufficient_observation",
                planner.plan(
                        sideOnly,
                        new HydratedCropFieldRequest(crop, 8, false)
                ).failureCode().orElseThrow()
        );
    }

    @Test
    void constructionDagPreservesHydrationAndCommissioningDependencies() {
        final MechanismPlan plan = planner.plan(
                flatSite(0, 0, CropFieldVariant.POTATO, STONE_HOE),
                new HydratedCropFieldRequest(
                        CropFieldVariant.POTATO,
                        80,
                        false
                )
        ).plan().orElseThrow();

        assertEquals(9, plan.width());
        assertEquals(9, plan.depth());
        assertEquals(80, plan.productionCells());
        assertEquals(1, count(
                plan,
                MechanismConstructionStep.Action.EXCAVATE
        ));
        assertEquals(1, count(
                plan,
                MechanismConstructionStep.Action.PLACE_FLUID
        ));
        assertEquals(80, count(
                plan,
                MechanismConstructionStep.Action.TILL
        ));
        assertEquals(80, count(
                plan,
                MechanismConstructionStep.Action.PLANT
        ));
        assertEquals(4, count(
                plan,
                MechanismConstructionStep.Action.PROBE
        ));
        assertTrue(plan.steps().stream().allMatch(step ->
                step.dependencies().stream().allMatch(dependency ->
                        dependency < step.index()
                )
        ));
        assertTrue(plan.steps().stream()
                .filter(step -> step.action()
                        == MechanismConstructionStep.Action.TILL)
                .allMatch(step ->
                        Math.abs(step.target().x() - plan.anchor().x()) <= 4
                                && Math.abs(step.target().z()
                                    - plan.anchor().z()) <= 4
                                && step.target().y() == plan.anchor().y()
                ));
    }

    @Test
    void singleChunkRequestIncludesProductionAndServiceAisle() {
        final MechanismPlan plan = planner.plan(
                flatSite(15, 8, CropFieldVariant.BEETROOT, STONE_HOE),
                new HydratedCropFieldRequest(
                        CropFieldVariant.BEETROOT,
                        80,
                        true
                )
        ).plan().orElseThrow();

        final Set<Long> chunks = new HashSet<>();
        footprint(plan).forEach(position ->
                chunks.add(chunkKey(position))
        );
        serviceAisle(plan).forEach(position ->
                chunks.add(chunkKey(position))
        );
        assertEquals(Set.of(chunkKey(plan.anchor())), chunks);
    }

    @Test
    void missingOrInvalidEvidenceFailsClosedWithSpecificReasons() {
        final CropFieldVariant crop = CropFieldVariant.WHEAT;
        final MechanismSiteFrame normal =
                flatSite(0, 0, crop, STONE_HOE);
        final HydratedCropFieldRequest request =
                new HydratedCropFieldRequest(crop, 8, false);

        assertFailure(
                withInventory(normal, List.of(
                        new InventoryItemSummary(STONE_HOE, 1),
                        new InventoryItemSummary(crop.plantingItemId(), 80)
                )),
                request,
                "crop_field.missing_water_bucket"
        );
        assertFailure(
                withInventory(normal, List.of(
                        new InventoryItemSummary(
                                "minecraft:water_bucket", 1
                        ),
                        new InventoryItemSummary(crop.plantingItemId(), 80)
                )),
                request,
                "crop_field.missing_hoe"
        );
        assertFailure(
                withInventory(normal, List.of(
                        new InventoryItemSummary(
                                "minecraft:water_bucket", 1
                        ),
                        new InventoryItemSummary(STONE_HOE, 1),
                        new InventoryItemSummary(crop.plantingItemId(), 7)
                )),
                request,
                "crop_field.insufficient_planting_items"
        );
        assertFailure(
                withLight(withSky(normal, false), -1),
                request,
                "crop_field.insufficient_observation"
        );
        assertFailure(
                withLight(withSky(normal, false), 8),
                request,
                "crop_field.no_safe_site"
        );
        assertTrue(planner.plan(
                withLight(withSky(normal, false), 9),
                request
        ).plan().isPresent());
        assertFailure(
                withDimension(normal, DimensionRef.NETHER),
                request,
                "crop_field.water_invalid_dimension"
        );
        assertFailure(
                withNavigation(normal, staleNavigation(normal.navigation())),
                request,
                "crop_field.no_safe_site"
        );
    }

    @Test
    void randomizedLocationsDirectionsCropsAndToolsAreSolvedFromEvidence() {
        final Random random = new Random(0x4d434149L);
        final int[] plotCounts = {8, 15, 24, 35, 48, 63, 80};
        final PerceptionVec3[] looks = {
            new PerceptionVec3(1, 0, 0),
            new PerceptionVec3(-1, 0, 0),
            new PerceptionVec3(0, 0, 1),
            new PerceptionVec3(0, 0, -1)
        };
        final Set<String> planIds = new HashSet<>();

        for (int trial = 0; trial < 30; trial++) {
            final int centerX = random.nextInt(-2_000, 2_001);
            final int centerZ = random.nextInt(-2_000, 2_001);
            final CropFieldVariant crop = CropFieldVariant.values()[
                    random.nextInt(CropFieldVariant.values().length)
            ];
            final String hoe = HOES.get(random.nextInt(HOES.size()));
            final int plots = plotCounts[random.nextInt(plotCounts.length)];
            final MechanismSiteFrame base =
                    flatSite(centerX, centerZ, crop, hoe);
            final MechanismSiteFrame directed = new MechanismSiteFrame(
                    base.dimension(),
                    base.sourceRevision(),
                    base.feet(),
                    looks[random.nextInt(looks.length)],
                    base.inventory(),
                    base.navigation(),
                    base.visibleBlockFaces(),
                    true
            );

            final MechanismPlan plan = planner.plan(
                    directed,
                    new HydratedCropFieldRequest(crop, plots, false)
            ).plan().orElseThrow();

            assertTrue(plan.productionCells() >= plots);
            assertEquals(crop.plantingItemId(),
                    plan.selectedMaterials().get("crop"));
            assertEquals(hoe, plan.selectedMaterials().get("tool"));
            assertTrue(planIds.add(plan.planId()));
            assertTrue(plan.steps().stream().allMatch(step ->
                    directed.navigation().isObserved(
                            step.action()
                                            == MechanismConstructionStep
                                                    .Action.PLANT
                                    ? step.target().below()
                                    : step.target().y()
                                            > directed.feet().y() - 1
                                        ? step.target().below()
                                        : step.target()
                    )
            ));
        }
    }

    private static long count(
            final MechanismPlan plan,
            final MechanismConstructionStep.Action action
    ) {
        return plan.steps().stream()
                .filter(step -> step.action() == action)
                .count();
    }

    private void assertFailure(
            final MechanismSiteFrame frame,
            final HydratedCropFieldRequest request,
            final String expected
    ) {
        assertEquals(
                expected,
                planner.plan(frame, request).failureCode().orElseThrow()
        );
    }

    private static MechanismSiteFrame flatSite(
            final int centerX,
            final int centerZ,
            final CropFieldVariant crop,
            final String hoe
    ) {
        return flatSite(
                centerX,
                centerZ,
                crop,
                hoe,
                Map.of(),
                true,
                DimensionRef.OVERWORLD
        );
    }

    private static MechanismSiteFrame flatSite(
            final int centerX,
            final int centerZ,
            final CropFieldVariant crop,
            final String hoe,
            final Map<GridPos, String> groundOverrides,
            final boolean topFaces,
            final DimensionRef dimension
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        final List<VisibleBlockFace> faces = new ArrayList<>();
        final GridPos feet = new GridPos(centerX, 1, centerZ);
        for (int x = centerX - 8; x <= centerX + 8; x++) {
            for (int z = centerZ - 8; z <= centerZ + 8; z++) {
                final GridPos ground = new GridPos(x, 0, z);
                final String block = groundOverrides.getOrDefault(
                        ground,
                        "minecraft:grass_block"
                );
                voxels.add(new ObservedVoxel(
                        ground,
                        VoxelKind.SOLID,
                        0.0,
                        REVISION,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                voxels.add(clear(ground.above()));
                voxels.add(clear(ground.above(2)));
                faces.add(new VisibleBlockFace(
                        new BlockCoordinate(x, 0, z),
                        block,
                        topFaces ? "up" : "north",
                        new PerceptionVec3(
                                x + 0.5,
                                topFaces ? 1.0 : 0.5,
                                z + 0.5
                        ),
                        Math.hypot(x - centerX, z - centerZ),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of(),
                        TopSupportAffordance.STURDY_FULL_TOP,
                        15
                ));
            }
        }
        return new MechanismSiteFrame(
                dimension,
                REVISION,
                feet,
                new PerceptionVec3(1, 0, 0),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:water_bucket", 1
                        ),
                        new InventoryItemSummary(hoe, 1),
                        new InventoryItemSummary(
                                crop.plantingItemId(), 80
                        )
                ),
                new LocalNavSnapshot(dimension, REVISION, voxels),
                faces,
                true
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

    private static MechanismSiteFrame withInventory(
            final MechanismSiteFrame frame,
            final List<InventoryItemSummary> inventory
    ) {
        return new MechanismSiteFrame(
                frame.dimension(),
                frame.sourceRevision(),
                frame.feet(),
                frame.lookDirection(),
                inventory,
                frame.navigation(),
                frame.visibleBlockFaces(),
                frame.skyVisible()
        );
    }

    private static MechanismSiteFrame withSky(
            final MechanismSiteFrame frame,
            final boolean skyVisible
    ) {
        return new MechanismSiteFrame(
                frame.dimension(),
                frame.sourceRevision(),
                frame.feet(),
                frame.lookDirection(),
                frame.inventory(),
                frame.navigation(),
                frame.visibleBlockFaces(),
                skyVisible
        );
    }

    private static MechanismSiteFrame withLight(
            final MechanismSiteFrame frame,
            final int adjacentLightLevel
    ) {
        return new MechanismSiteFrame(
                frame.dimension(),
                frame.sourceRevision(),
                frame.feet(),
                frame.lookDirection(),
                frame.inventory(),
                frame.navigation(),
                frame.visibleBlockFaces().stream()
                        .map(face -> new VisibleBlockFace(
                                face.block(),
                                face.blockTypeId(),
                                face.face(),
                                face.hitPosition(),
                                face.distance(),
                                face.provenance(),
                                face.stateProperties(),
                                face.topSupportAffordance(),
                                adjacentLightLevel
                        ))
                        .toList(),
                frame.skyVisible()
        );
    }

    private static MechanismSiteFrame withDimension(
            final MechanismSiteFrame frame,
            final DimensionRef dimension
    ) {
        return new MechanismSiteFrame(
                dimension,
                frame.sourceRevision(),
                frame.feet(),
                frame.lookDirection(),
                frame.inventory(),
                new LocalNavSnapshot(
                        dimension,
                        frame.navigation().revision(),
                        frame.navigation().observedVoxels().values()
                ),
                frame.visibleBlockFaces(),
                frame.skyVisible()
        );
    }

    private static MechanismSiteFrame withNavigation(
            final MechanismSiteFrame frame,
            final LocalNavSnapshot navigation
    ) {
        return new MechanismSiteFrame(
                frame.dimension(),
                frame.sourceRevision(),
                frame.feet(),
                frame.lookDirection(),
                frame.inventory(),
                navigation,
                frame.visibleBlockFaces(),
                frame.skyVisible()
        );
    }

    private static LocalNavSnapshot staleNavigation(
            final LocalNavSnapshot navigation
    ) {
        return new LocalNavSnapshot(
                navigation.dimension(),
                navigation.revision(),
                navigation.observedVoxels().values().stream()
                        .map(voxel -> new ObservedVoxel(
                                voxel.position(),
                                voxel.kind(),
                                voxel.danger(),
                                REVISION - 1,
                                voxel.occupancyEvidence(),
                                voxel.topSupportAffordance()
                        ))
                        .toList()
        );
    }

    private static Set<GridPos> footprint(final MechanismPlan plan) {
        final Set<GridPos> result = new HashSet<>();
        final GridPos origin = plan.anchor().offset(
                -plan.width() / 2,
                0,
                -plan.depth() / 2
        );
        for (int x = 0; x < plan.width(); x++) {
            for (int z = 0; z < plan.depth(); z++) {
                result.add(origin.offset(x, 0, z));
            }
        }
        return Set.copyOf(result);
    }

    private static List<GridPos> serviceAisle(
            final MechanismPlan plan
    ) {
        final List<GridPos> result = new ArrayList<>();
        final GridPos origin = plan.anchor().offset(
                -plan.width() / 2,
                0,
                -plan.depth() / 2
        );
        switch (plan.serviceFacing()) {
            case NORTH -> {
                for (int x = 0; x < plan.width(); x++) {
                    result.add(origin.offset(x, 0, -1));
                }
            }
            case SOUTH -> {
                for (int x = 0; x < plan.width(); x++) {
                    result.add(origin.offset(x, 0, plan.depth()));
                }
            }
            case WEST -> {
                for (int z = 0; z < plan.depth(); z++) {
                    result.add(origin.offset(-1, 0, z));
                }
            }
            case EAST -> {
                for (int z = 0; z < plan.depth(); z++) {
                    result.add(origin.offset(plan.width(), 0, z));
                }
            }
        }
        return List.copyOf(result);
    }

    private static long chunkKey(final GridPos position) {
        final long chunkX = Math.floorDiv(position.x(), 16);
        final long chunkZ = Math.floorDiv(position.z(), 16);
        return chunkX << 32 ^ chunkZ & 0xffffffffL;
    }
}
