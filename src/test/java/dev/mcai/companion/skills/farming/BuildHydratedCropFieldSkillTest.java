package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.mechanism.HydratedCropFieldPlanner;
import dev.mcai.companion.mechanism.HydratedCropFieldRequest;
import dev.mcai.companion.mechanism.MechanismPlan;
import dev.mcai.companion.mechanism.MechanismSiteSurvey;
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
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BuildHydratedCropFieldSkillTest {
    private static final UUID PLAYER = UUID.fromString(
            "00000000-0000-0000-0000-00000000fa12"
    );

    @Test
    void nestedAtomicFailureRemainsSpecificAndBounded() {
        final SkillFailure mapped =
                BuildHydratedCropFieldSkill.childFailure(
                        SkillFailure.of(
                                "prepare_water_source."
                                        + "break_target_not_visible"
                        )
                );

        assertEquals(
                "build_hydrated_crop_field."
                        + "water_break_target_not_visible",
                mapped.code()
        );
        assertTrue(mapped.code().length()
                <= SkillFailure.MAX_CODE_CHARACTERS);
    }

    @Test
    void generatedExecutionOrderStartsWithWaterAndRetreatsTowardAisle() {
        final MechanismSiteSurvey survey = litFlatSurvey();
        final MechanismPlan plan = new HydratedCropFieldPlanner().plan(
                survey,
                new HydratedCropFieldRequest(
                        CropFieldVariant.WHEAT,
                        8,
                        false
                )
        ).plan().orElseThrow();

        final List<BuildHydratedCropFieldSkill.WorkJob> jobs =
                BuildHydratedCropFieldSkill.constructionJobs(
                        plan,
                        survey
                );

        assertEquals(9, jobs.size());
        assertEquals(
                BuildHydratedCropFieldSkill.JobKind.WATER,
                jobs.getFirst().kind()
        );
        assertEquals(plan.anchor(), jobs.getFirst().ground());
        assertTrue(jobs.stream().allMatch(job ->
                !job.stands().isEmpty()
                        && job.stands().stream().allMatch(stand ->
                            cardinallyAdjacent(stand, job.ground())
                        )
        ));
        assertTrue(jobs.stream()
                .filter(job -> job.kind()
                        == BuildHydratedCropFieldSkill.JobKind.PLOT)
                .allMatch(job -> !job.stands().contains(plan.anchor())));
        assertEquals(
                8,
                jobs.stream().map(
                        BuildHydratedCropFieldSkill.WorkJob::ground
                ).filter(position -> !position.equals(plan.anchor()))
                        .distinct().count()
        );

        final List<Integer> layers = jobs.stream().skip(1)
                .map(job -> distanceFromAisle(plan, job.ground()))
                .toList();
        for (int index = 1; index < layers.size(); index++) {
            assertTrue(layers.get(index) <= layers.get(index - 1));
        }
    }

    @Test
    void missingObservedHeadroomExcludesThatWorkStand() {
        final MechanismSiteSurvey complete = litFlatSurvey();
        final MechanismPlan plan = new HydratedCropFieldPlanner().plan(
                complete,
                new HydratedCropFieldRequest(
                        CropFieldVariant.WHEAT,
                        8,
                        false
                )
        ).plan().orElseThrow();
        final GridPos onlyWaterStand = plan.anchor().offset(
                switch (plan.serviceFacing()) {
                    case EAST -> 1;
                    case WEST -> -1;
                    default -> 0;
                },
                0,
                switch (plan.serviceFacing()) {
                    case SOUTH -> 1;
                    case NORTH -> -1;
                    default -> 0;
                }
        );
        final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels =
                new HashMap<>(complete.voxels());
        voxels.remove(onlyWaterStand.above(2));
        final MechanismSiteSurvey obstructed = new MechanismSiteSurvey(
                complete.playerId(),
                complete.sessionGeneration(),
                complete.dimension(),
                complete.currentGameTime(),
                complete.sourceRevision(),
                complete.feet(),
                complete.lookDirection(),
                complete.inventory(),
                complete.surfaces(),
                voxels,
                complete.skyObservation()
        );

        final List<BuildHydratedCropFieldSkill.WorkJob> jobs =
                BuildHydratedCropFieldSkill.constructionJobs(
                        plan,
                        obstructed
                );
        assertFalse(jobs.isEmpty());
        assertFalse(jobs.getFirst().stands().contains(onlyWaterStand));
    }

    private static MechanismSiteSurvey litFlatSurvey() {
        final List<MechanismSiteSurvey.SurfaceObservation> surfaces =
                new ArrayList<>();
        final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels =
                new HashMap<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                final GridPos ground = new GridPos(x, 0, z);
                surfaces.add(new MechanismSiteSurvey.SurfaceObservation(
                        new VisibleBlockFace(
                                new BlockCoordinate(x, 0, z),
                                "minecraft:grass_block",
                                "up",
                                new PerceptionVec3(
                                        x + 0.5,
                                        1.0,
                                        z + 0.5
                                ),
                                Math.hypot(x, z + 5),
                                PerceptionProvenance
                                        .BLOCK_SURFACE_RAY_CLIP,
                                Map.of(),
                                TopSupportAffordance.STURDY_FULL_TOP,
                                15
                        ),
                        100,
                        1
                ));
                put(voxels, new ObservedVoxel(
                        ground,
                        VoxelKind.SOLID,
                        0.0,
                        1,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                put(voxels, new ObservedVoxel(
                        ground.above(),
                        VoxelKind.AIR,
                        0.0,
                        1,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                put(voxels, new ObservedVoxel(
                        ground.above(2),
                        VoxelKind.AIR,
                        0.0,
                        1,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
            }
        }
        return new MechanismSiteSurvey(
                PLAYER,
                3,
                DimensionRef.OVERWORLD,
                100,
                1,
                new GridPos(0, 1, -5),
                new PerceptionVec3(0, 0, 1),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:water_bucket",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:stone_hoe",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:wheat_seeds",
                                8
                        )
                ),
                surfaces,
                voxels,
                Optional.empty()
        );
    }

    private static void put(
            final Map<GridPos, MechanismSiteSurvey.VoxelObservation> map,
            final ObservedVoxel voxel
    ) {
        map.put(
                voxel.position(),
                new MechanismSiteSurvey.VoxelObservation(voxel, 100)
        );
    }

    private static boolean cardinallyAdjacent(
            final GridPos left,
            final GridPos right
    ) {
        return left.y() == right.y()
                && Math.abs(left.x() - right.x())
                        + Math.abs(left.z() - right.z()) == 1;
    }

    private static int distanceFromAisle(
            final MechanismPlan plan,
            final GridPos target
    ) {
        final Set<GridPos> footprint = plan.steps().stream()
                .filter(step -> step.action()
                        == dev.mcai.companion.mechanism
                            .MechanismConstructionStep.Action.TILL
                        || step.action()
                        == dev.mcai.companion.mechanism
                            .MechanismConstructionStep.Action.EXCAVATE)
                .map(dev.mcai.companion.mechanism
                        .MechanismConstructionStep::target)
                .collect(java.util.stream.Collectors.toSet());
        final int minX = footprint.stream().mapToInt(GridPos::x)
                .min().orElseThrow();
        final int maxX = footprint.stream().mapToInt(GridPos::x)
                .max().orElseThrow();
        final int minZ = footprint.stream().mapToInt(GridPos::z)
                .min().orElseThrow();
        final int maxZ = footprint.stream().mapToInt(GridPos::z)
                .max().orElseThrow();
        return switch (plan.serviceFacing()) {
            case NORTH -> target.z() - minZ;
            case SOUTH -> maxZ - target.z();
            case WEST -> target.x() - minX;
            case EAST -> maxX - target.x();
        };
    }
}
