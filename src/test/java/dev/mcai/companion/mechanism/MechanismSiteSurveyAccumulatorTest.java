package dev.mcai.companion.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.building.ShelterFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MechanismSiteSurveyAccumulatorTest {
    private static final UUID PLAYER = UUID.fromString(
            "00000000-0000-0000-0000-00000000cafe"
    );
    private static final GridPos FEET = new GridPos(0, 64, 0);

    @Test
    void accumulatesMoreThanOneFrameWithoutRewritingProvenance() {
        final var accumulator = new MechanismSiteSurveyAccumulator();
        final List<GridPos> field = field();
        MechanismSiteSurvey survey = null;

        for (int page = 0; page < 4; page++) {
            final int from = page * 24;
            final int to = Math.min(field.size(), from + 24);
            final List<GridPos> cells = field.subList(from, to);
            survey = accumulator.ingest(
                    frame(
                            page + 1,
                            100 + page * 20L,
                            11,
                            DimensionRef.OVERWORLD,
                            cells.stream().map(position ->
                                    face(position, "minecraft:dirt", "up")
                            ).toList(),
                            navigation(
                                    DimensionRef.OVERWORLD,
                                    page + 1,
                                    cells,
                                    page + 1
                            )
                    ),
                    page == 0
            );
        }

        assertEquals(81, survey.surfaces().size());
        assertEquals(243, survey.voxels().size());
        assertTrue(survey.skyVisible());
        assertTrue(survey.surfaces().stream().anyMatch(observation ->
                observation.observationRevision() == 1
        ));
        assertTrue(survey.surfaces().stream().anyMatch(observation ->
                observation.observationRevision() == 4
        ));
        assertTrue(survey.voxels().values().stream().anyMatch(observation ->
                observation.observationRevision() == 1
        ));
        assertTrue(survey.voxels().values().stream().anyMatch(observation ->
                observation.observationRevision() == 4
        ));
    }

    @Test
    void rollingSurveyPlansAFullFieldThatCannotFitOnePerceptionFrame() {
        final var accumulator = new MechanismSiteSurveyAccumulator();
        final List<GridPos> field = field();
        final List<GridPos> surveyedCells = new ArrayList<>(field);
        for (int coordinate = -5; coordinate <= 5; coordinate++) {
            surveyedCells.add(new GridPos(coordinate, 63, -5));
            surveyedCells.add(new GridPos(coordinate, 63, 5));
            if (coordinate > -5 && coordinate < 5) {
                surveyedCells.add(new GridPos(-5, 63, coordinate));
                surveyedCells.add(new GridPos(5, 63, coordinate));
            }
        }
        MechanismSiteSurvey survey = null;
        for (int from = 0, page = 0;
                from < surveyedCells.size();
                from += 24, page++) {
            final List<GridPos> cells = surveyedCells.subList(
                    from,
                    Math.min(surveyedCells.size(), from + 24)
            );
            final long revision = page + 1L;
            survey = accumulator.ingest(
                    frame(
                            revision,
                            100 + page * 20L,
                            11,
                            DimensionRef.OVERWORLD,
                            cells.stream()
                                    .filter(field::contains)
                                    .map(position -> face(
                                            position,
                                            "minecraft:dirt",
                                            "up"
                                    ))
                                    .toList(),
                            navigation(
                                    DimensionRef.OVERWORLD,
                                    revision,
                                    cells,
                                    revision
                            )
                    ),
                    page == 0
            );
        }

        final MechanismSiteSurvey completedSurvey = survey;
        assertTrue(completedSurvey.sourceRevision() > 4);
        assertTrue(completedSurvey.surfaces().stream().anyMatch(observation ->
                observation.observationRevision()
                        < completedSurvey.sourceRevision()
        ));
        final MechanismPlan plan = new HydratedCropFieldPlanner().plan(
                completedSurvey,
                new HydratedCropFieldRequest(
                        CropFieldVariant.WHEAT,
                        80,
                        false
                )
        ).plan().orElseThrow();
        assertEquals(9, plan.width());
        assertEquals(9, plan.depth());
        assertEquals(80, plan.productionCells());
        assertEquals(completedSurvey.sourceRevision(), plan.sourceRevision());
    }

    @Test
    void resetsAcrossBodySessionDimensionAndClockRollback() {
        final var accumulator = new MechanismSiteSurveyAccumulator();
        accumulator.ingest(single(1, 100, 11, DimensionRef.OVERWORLD, 1), true);

        MechanismSiteSurvey session = accumulator.ingest(
                single(2, 120, 12, DimensionRef.OVERWORLD, 2),
                false
        );
        assertEquals(1, session.surfaces().size());
        assertEquals(12, session.sessionGeneration());
        assertFalse(session.skyVisible());

        MechanismSiteSurvey dimension = accumulator.ingest(
                single(3, 140, 12, DimensionRef.END, 3),
                false
        );
        assertEquals(DimensionRef.END, dimension.dimension());
        assertEquals(1, dimension.surfaces().size());

        MechanismSiteSurvey rollback = accumulator.ingest(
                single(1, 20, 12, DimensionRef.END, 4),
                false
        );
        assertEquals(1, rollback.sourceRevision());
        assertEquals(1, rollback.surfaces().size());
        assertEquals(4, rollback.surfaces().getFirst().face().block().x());
    }

    @Test
    void expiresOldEvidenceAndNeverImportsUnknownNavigationHistory() {
        final var accumulator = new MechanismSiteSurveyAccumulator(
                new MechanismSurveyPolicy(10, 8.0, 81, 256)
        );
        final GridPos first = new GridPos(1, 63, 0);
        final List<ObservedVoxel> navigation = new ArrayList<>(
                navigation(
                        DimensionRef.OVERWORLD,
                        1,
                        List.of(first),
                        1
                ).observedVoxels().values()
        );
        final GridPos unknown = new GridPos(2, 63, 0);
        navigation.add(voxel(
                unknown,
                VoxelKind.SOLID,
                0,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.STURDY_FULL_TOP
        ));
        MechanismSiteSurvey firstSurvey = accumulator.ingest(
                frame(
                        1,
                        100,
                        11,
                        DimensionRef.OVERWORLD,
                        List.of(face(first, "minecraft:dirt", "up")),
                        new LocalNavSnapshot(
                                DimensionRef.OVERWORLD,
                                1,
                                navigation
                        )
                ),
                true
        );
        assertTrue(firstSurvey.voxelAt(first).isPresent());
        assertTrue(firstSurvey.voxelAt(unknown).isEmpty());

        final ShelterFrame later = new ShelterFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                111,
                111,
                2,
                11,
                FEET,
                new PerceptionVec3(0, 0, 1),
                HeldItemSummary.empty(),
                inventory(),
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        2,
                        List.of()
                ),
                List.of()
        );
        final MechanismSiteSurvey expired = accumulator.ingest(later, false);
        assertTrue(expired.surfaces().isEmpty());
        assertTrue(expired.voxels().isEmpty());
        assertFalse(expired.skyVisible());
    }

    @Test
    void newerViewInvalidatesAllOldFacesOfTheSameBlock() {
        final var accumulator = new MechanismSiteSurveyAccumulator();
        final GridPos position = new GridPos(1, 63, 0);
        accumulator.ingest(
                frame(
                        1,
                        100,
                        11,
                        DimensionRef.OVERWORLD,
                        List.of(
                                face(position, "minecraft:dirt", "up"),
                                face(position, "minecraft:dirt", "north")
                        ),
                        navigation(
                                DimensionRef.OVERWORLD,
                                1,
                                List.of(position),
                                1
                        )
                ),
                false
        );
        final MechanismSiteSurvey updated = accumulator.ingest(
                frame(
                        2,
                        120,
                        11,
                        DimensionRef.OVERWORLD,
                        List.of(
                                face(position, "minecraft:grass_block", "up"),
                                face(
                                        position,
                                        "minecraft:grass_block",
                                        "north"
                                )
                        ),
                        navigation(
                                DimensionRef.OVERWORLD,
                                2,
                                List.of(position),
                                2
                        )
                ),
                false
        );

        assertEquals(2, updated.surfaces().size());
        assertTrue(updated.surfaces().stream().allMatch(observation ->
                observation.observationRevision() == 2
                        && "minecraft:grass_block".equals(
                                observation.face().blockTypeId()
                        )
        ));
    }

    @Test
    void observeMergesOnlyTheNewestDeltaAndRetainsOlderEvidence() {
        final var accumulator = new MechanismSiteSurveyAccumulator();
        final GridPos first = new GridPos(1, 63, 0);
        final GridPos second = new GridPos(2, 63, 0);
        accumulator.observe(
                frame(
                        1,
                        100,
                        11,
                        DimensionRef.OVERWORLD,
                        List.of(face(first, "minecraft:dirt", "up")),
                        navigation(
                                DimensionRef.OVERWORLD,
                                1,
                                List.of(first),
                                1
                        )
                ),
                false
        );
        final List<ObservedVoxel> rolling = new ArrayList<>(
                navigation(
                        DimensionRef.OVERWORLD,
                        2,
                        List.of(first),
                        1
                ).observedVoxels().values()
        );
        rolling.addAll(navigation(
                DimensionRef.OVERWORLD,
                2,
                List.of(second),
                2
        ).observedVoxels().values());

        accumulator.observe(
                frame(
                        2,
                        120,
                        11,
                        DimensionRef.OVERWORLD,
                        List.of(face(second, "minecraft:dirt", "up")),
                        new LocalNavSnapshot(
                                DimensionRef.OVERWORLD,
                                2,
                                rolling
                        )
                ),
                false
        );

        final MechanismSiteSurvey survey = accumulator.current()
                .orElseThrow();
        assertTrue(survey.voxelAt(first).isPresent());
        assertTrue(survey.voxelAt(second).isPresent());
        assertEquals(
                1,
                survey.voxelAt(first).orElseThrow()
                        .observationRevision()
        );
        assertEquals(
                2,
                survey.voxelAt(second).orElseThrow()
                        .observationRevision()
        );
    }

    private static ShelterFrame single(
            final long revision,
            final long gameTime,
            final long session,
            final DimensionRef dimension,
            final int x
    ) {
        final GridPos ground = new GridPos(x, 63, 0);
        return frame(
                revision,
                gameTime,
                session,
                dimension,
                List.of(face(ground, "minecraft:dirt", "up")),
                navigation(dimension, revision, List.of(ground), revision)
        );
    }

    private static ShelterFrame frame(
            final long revision,
            final long gameTime,
            final long session,
            final DimensionRef dimension,
            final List<VisibleBlockFace> faces,
            final LocalNavSnapshot navigation
    ) {
        return new ShelterFrame(
                PLAYER,
                dimension,
                gameTime,
                gameTime,
                revision,
                session,
                FEET,
                new PerceptionVec3(0, 0, 1),
                HeldItemSummary.empty(),
                inventory(),
                navigation,
                faces
        );
    }

    private static List<InventoryItemSummary> inventory() {
        return List.of(
                new InventoryItemSummary("minecraft:water_bucket", 1),
                new InventoryItemSummary("minecraft:stone_hoe", 1),
                new InventoryItemSummary("minecraft:wheat_seeds", 80)
        );
    }

    private static List<GridPos> field() {
        final List<GridPos> result = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                result.add(new GridPos(x, 63, z));
            }
        }
        return List.copyOf(result);
    }

    private static LocalNavSnapshot navigation(
            final DimensionRef dimension,
            final long snapshotRevision,
            final List<GridPos> ground,
            final long evidenceRevision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (GridPos position : ground) {
            voxels.add(voxel(
                    position,
                    VoxelKind.SOLID,
                    evidenceRevision,
                    OccupancyEvidence.SURFACE_HIT,
                    TopSupportAffordance.STURDY_FULL_TOP
            ));
            voxels.add(voxel(
                    position.above(),
                    VoxelKind.AIR,
                    evidenceRevision,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
            voxels.add(voxel(
                    position.above(2),
                    VoxelKind.AIR,
                    evidenceRevision,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
        }
        return new LocalNavSnapshot(dimension, snapshotRevision, voxels);
    }

    private static ObservedVoxel voxel(
            final GridPos position,
            final VoxelKind kind,
            final long revision,
            final OccupancyEvidence occupancy,
            final TopSupportAffordance support
    ) {
        return new ObservedVoxel(
                position,
                kind,
                0.0,
                revision,
                occupancy,
                support
        );
    }

    private static VisibleBlockFace face(
            final GridPos position,
            final String blockId,
            final String face
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(
                        position.x(),
                        position.y(),
                        position.z()
                ),
                blockId,
                face,
                new PerceptionVec3(
                        position.x() + 0.5,
                        position.y() + 1.0,
                        position.z() + 0.5
                ),
                4.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                java.util.Map.of(),
                "up".equals(face)
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        );
    }
}
