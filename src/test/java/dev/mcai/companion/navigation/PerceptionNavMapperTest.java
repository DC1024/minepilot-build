package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.CollisionAffordance;
import dev.mcai.companion.perception.BodySnapshot;
import dev.mcai.companion.perception.ClearSightRay;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.ObservationBudgetUsage;
import dev.mcai.companion.perception.PerceptionBudget;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Map;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PerceptionNavMapperTest {
    @Test
    void visibleCollisionlessCropIsTraversalClearInsteadOfASolidWall() {
        final PerceptionNavMapper mapper =
                new PerceptionNavMapper(128, 16.0);
        final VisibleBlockFace wheat = new VisibleBlockFace(
                new BlockCoordinate(1, 64, 0),
                "minecraft:wheat",
                "up",
                new PerceptionVec3(1.5, 64.875, 0.5),
                1.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of("age", "7"),
                TopSupportAffordance.NON_STURDY_OR_PARTIAL,
                CollisionAffordance.EMPTY,
                15
        );

        final ObservedVoxel mapped = mapper.ingest(observation(
                3,
                "minecraft:overworld",
                wheat
        )).voxelAt(new GridPos(1, 64, 0)).orElseThrow();

        assertEquals(VoxelKind.AIR, mapped.kind());
        assertEquals(
                OccupancyEvidence.COLLISION_SHAPE_CLEAR,
                mapped.occupancyEvidence()
        );
        assertTrue(NavigationEvidence.hasTraversalClearance(mapped));
        assertFalse(mapped.occupancyEvidence().isFullBodyFact());
    }

    @Test
    void mapsOnlyRayEvidenceAndNeverAssumesSpaceBehindHit() {
        final PerceptionNavMapper mapper = new PerceptionNavMapper(128, 16.0);
        final LocalNavSnapshot snapshot = mapper.ingest(observation(
            3,
            "minecraft:overworld",
            new VisibleBlockFace(
                new BlockCoordinate(3, 65, 0),
                "minecraft:stone",
                "west",
                new PerceptionVec3(3.0, 65.5, 0.5),
                2.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
            )
        ));

        assertEquals(
            VoxelKind.SOLID,
            snapshot.voxelAt(new GridPos(3, 65, 0)).orElseThrow().kind()
        );
        assertEquals(
            VoxelKind.AIR,
            snapshot.voxelAt(new GridPos(2, 65, 0)).orElseThrow().kind()
        );
        assertFalse(snapshot.isObserved(new GridPos(4, 65, 0)));
        assertEquals(new GridPos(0, 64, 0), mapper.currentFeet());
    }

    @Test
    void clearsMapAcrossDimensionsAndRejectsOldSamples() {
        final PerceptionNavMapper mapper = new PerceptionNavMapper(128, 16.0);
        mapper.ingest(observation(3, "minecraft:overworld", stoneAt(3)));
        final LocalNavSnapshot nether = mapper.ingest(
            observation(4, "minecraft:the_nether", stoneAt(8))
        );

        assertEquals(
            VoxelKind.AIR,
            nether.voxelAt(new GridPos(3, 65, 0)).orElseThrow().kind()
        );
        assertTrue(nether.isObserved(new GridPos(8, 65, 0)));
        assertThrows(
            IllegalArgumentException.class,
            () -> mapper.ingest(observation(4, "minecraft:the_nether", stoneAt(9)))
        );
    }

    @Test
    void resetDropsOldBodyEvidenceAndAcceptsNewSessionSequence() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        mapper.ingest(
            observation(30, "minecraft:overworld", stoneAt(3))
        );

        mapper.reset();

        assertEquals(0, mapper.observedVoxelCount());
        assertThrows(IllegalStateException.class, mapper::snapshot);
        final LocalNavSnapshot replacement = mapper.ingest(
            observation(1, "minecraft:overworld", stoneAt(8))
        );
        assertEquals(
            VoxelKind.AIR,
            replacement.voxelAt(new GridPos(3, 65, 0))
                .orElseThrow()
                .kind()
        );
        assertTrue(replacement.isObserved(new GridPos(8, 65, 0)));
    }

    @Test
    void reusesImmutableSnapshotWithinRevisionAndRebuildsAfterIngest() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final LocalNavSnapshot first = mapper.ingest(
            observation(3, "minecraft:overworld", stoneAt(3))
        );

        assertSame(first, mapper.snapshot());
        assertSame(first, mapper.snapshot());

        final LocalNavSnapshot second = mapper.ingest(
            observation(4, "minecraft:overworld", stoneAt(4))
        );
        assertNotSame(first, second);
        assertSame(second, mapper.snapshot());
        assertEquals(4, second.revision());
    }

    @Test
    void conservativelyClassifiesInteractiveAndDangerousBlocks() {
        assertEquals(VoxelKind.CLOSED_DOOR, PerceptionNavMapper.classify(
            "minecraft:oak_door"
        ));
        assertEquals(VoxelKind.CLIMBABLE, PerceptionNavMapper.classify(
            "minecraft:scaffolding"
        ));
        assertEquals(VoxelKind.LAVA, PerceptionNavMapper.classify("minecraft:lava"));
        assertEquals(VoxelKind.SOLID, PerceptionNavMapper.classify(
            "example:unknown_machine"
        ));
    }

    @Test
    void singleClearRayDoesNotEraseOlderSurfaceEvidence() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        mapper.ingest(observation(
            3,
            "minecraft:overworld",
            stoneAt(3)
        ));
        final PerceptionBudget budget =
            PerceptionBudget.defaults();
        final LocalNavSnapshot cleared = mapper.ingest(
            new SemanticObservation(
                4,
                body(4, "minecraft:overworld"),
                List.of(),
                List.of(),
                List.of(new ClearSightRay(
                    new PerceptionVec3(5.5, 65.5, 0.5),
                    5.0,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
                )),
                List.of(),
                java.util.Optional.empty(),
                budget,
                new ObservationBudgetUsage(
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
                )
            )
        );

        assertEquals(
            VoxelKind.SOLID,
            cleared.voxelAt(new GridPos(3, 65, 0))
                .orElseThrow()
                .kind()
        );
        assertEquals(
            3,
            cleared.voxelAt(new GridPos(3, 65, 0))
                .orElseThrow()
                .observationRevision()
        );
        assertEquals(
            VoxelKind.AIR,
            cleared.voxelAt(new GridPos(5, 65, 0))
                .orElseThrow()
                .kind()
        );
        assertEquals(
            OccupancyEvidence.SINGLE_RAY_CLEAR,
            cleared.voxelAt(new GridPos(5, 65, 0))
                .orElseThrow()
                .occupancyEvidence()
        );
        assertFalse(
            cleared.voxelAt(new GridPos(5, 65, 0))
                .orElseThrow()
                .occupancyEvidence()
                .isFullBodyFact()
        );
        assertEquals(
            OccupancyEvidence.BODY_OCCUPIED,
            cleared.voxelAt(new GridPos(0, 64, 0))
                .orElseThrow()
                .occupancyEvidence()
        );
        assertTrue(
            cleared.voxelAt(new GridPos(0, 64, 0))
                .orElseThrow()
                .occupancyEvidence()
                .isFullBodyFact()
        );
    }

    @Test
    void threeDistinctClearRaysMayRefreshOnlyAHeuristicAirCell() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        mapper.ingest(observation(
            3,
            "minecraft:overworld",
            stoneAt(3)
        ));
        final LocalNavSnapshot cleared = mapper.ingest(clearObservation(
            4,
            List.of(
                new PerceptionVec3(5.5, 65.35, 0.35),
                new PerceptionVec3(5.5, 65.50, 0.50),
                new PerceptionVec3(5.5, 65.65, 0.65)
            )
        ));
        final ObservedVoxel formerlySolid = cleared.voxelAt(
            new GridPos(3, 65, 0)
        ).orElseThrow();

        assertEquals(VoxelKind.AIR, formerlySolid.kind());
        assertEquals(4, formerlySolid.observationRevision());
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            formerlySolid.occupancyEvidence()
        );
        assertFalse(formerlySolid.occupancyEvidence().isFullBodyFact());
    }

    @Test
    void oneNewRayDoesNotDowngradeEstablishedMultiRayClearance() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final List<PerceptionVec3> establishedRays = List.of(
            new PerceptionVec3(5.5, 65.35, 0.35),
            new PerceptionVec3(5.5, 65.50, 0.50),
            new PerceptionVec3(5.5, 65.65, 0.65)
        );
        final LocalNavSnapshot established = mapper.ingest(
            clearObservation(3, establishedRays)
        );
        final GridPos position = new GridPos(3, 65, 0);
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            established.voxelAt(position)
                .orElseThrow()
                .occupancyEvidence()
        );

        final LocalNavSnapshot newer = mapper.ingest(
            clearObservation(
                4,
                List.of(new PerceptionVec3(5.5, 65.50, 0.50))
            )
        );
        final ObservedVoxel retained =
            newer.voxelAt(position).orElseThrow();

        assertEquals(VoxelKind.AIR, retained.kind());
        assertEquals(3, retained.observationRevision());
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            retained.occupancyEvidence()
        );
        assertTrue(
            NavigationEvidence.hasTraversalClearance(retained)
        );
    }

    @Test
    void threeSameFaceHitRaysProvideClearanceWithoutErasingTheHitBlock() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final VisibleBlockFace oneDeduplicatedFace =
            new VisibleBlockFace(
                new BlockCoordinate(4, 65, 0),
                "minecraft:stone",
                "west",
                new PerceptionVec3(4.0, 65.5, 0.5),
                3.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
            );
        final List<ClearSightRay> independentHitSegments =
            List.of(
                new ClearSightRay(
                    new PerceptionVec3(3.9999, 65.35, 0.35),
                    3.5063,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                ),
                new ClearSightRay(
                    new PerceptionVec3(3.9999, 65.50, 0.50),
                    3.4999,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                ),
                new ClearSightRay(
                    new PerceptionVec3(3.9999, 65.65, 0.65),
                    3.5063,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                )
            );
        final SemanticObservation observation =
            new SemanticObservation(
                4,
                body(4, "minecraft:overworld"),
                List.of(),
                List.of(oneDeduplicatedFace),
                independentHitSegments,
                List.of(),
                Optional.empty(),
                budget,
                new ObservationBudgetUsage(
                    0,
                    0,
                    0,
                    3,
                    0,
                    1,
                    0,
                    false,
                    false,
                    false,
                    false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                )
            );
        final LocalNavSnapshot mapped = mapper.ingest(observation);
        final ObservedVoxel traversed = mapped.voxelAt(
            new GridPos(3, 65, 0)
        ).orElseThrow();
        final ObservedVoxel hit = mapped.voxelAt(
            new GridPos(4, 65, 0)
        ).orElseThrow();

        assertEquals(VoxelKind.AIR, traversed.kind());
        assertEquals(4, traversed.observationRevision());
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            traversed.occupancyEvidence()
        );
        assertEquals(VoxelKind.SOLID, hit.kind());
        assertEquals(4, hit.observationRevision());
        assertEquals(
            OccupancyEvidence.SURFACE_HIT,
            hit.occupancyEvidence()
        );
        assertEquals(1, observation.visibleBlockFaces().size());
        assertEquals(3, observation.clearSightRays().size());
        assertTrue(
            observation.clearSightRays().size()
                <= observation.budgetUsage().blockRaysCast(),
            "One clear segment per cast must stay inside the ray budget"
        );
    }

    @Test
    void propagatesOnlyExplicitVisibleTopSupportAffordance() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final VisibleBlockFace support = new VisibleBlockFace(
            new BlockCoordinate(0, 63, 0),
            "minecraft:stone",
            "up",
            new PerceptionVec3(0.5, 64.0, 0.5),
            1.5,
            PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
            Map.of(),
            TopSupportAffordance.STURDY_FULL_TOP
        );
        final ObservedVoxel mapped = mapper.ingest(observation(
            3,
            "minecraft:overworld",
            support
        )).voxelAt(new GridPos(0, 63, 0)).orElseThrow();

        assertEquals(
            TopSupportAffordance.STURDY_FULL_TOP,
            mapped.topSupportAffordance()
        );
        assertEquals(
            OccupancyEvidence.BODY_CONTACT,
            mapped.occupancyEvidence()
        );
    }

    @Test
    void stationaryBodyContactSupportIsRefreshedEverySample() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        mapper.ingest(observation(
            3,
            "minecraft:overworld",
            stoneAt(3)
        ));
        final ObservedVoxel support = mapper.ingest(observation(
            4,
            "minecraft:overworld",
            stoneAt(3)
        )).voxelAt(new GridPos(0, 63, 0)).orElseThrow();

        assertEquals(4, support.observationRevision());
        assertEquals(
            OccupancyEvidence.BODY_CONTACT,
            support.occupancyEvidence()
        );
        assertTrue(
            NavigationEvidence.supportsCurrentBody(support, 4)
        );
        assertFalse(
            NavigationEvidence.isFreshStandingSupport(support, 4)
        );
    }

    @Test
    void edgeStraddlingGroundContactDoesNotOverwriteObservedAirBelowCentre() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final GridPos centreBelow = new GridPos(1, 63, 0);
        final List<PerceptionVec3> clearEnds = List.of(
            new PerceptionVec3(1.5, 63.35, 0.35),
            new PerceptionVec3(1.5, 63.50, 0.50),
            new PerceptionVec3(1.5, 63.65, 0.65)
        );
        final LocalNavSnapshot established = mapper.ingest(
            clearObservation(3, clearEnds)
        );
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            established.voxelAt(centreBelow)
                .orElseThrow().occupancyEvidence()
        );

        final BodySnapshot straddling = bodyAt(
            4,
            "minecraft:overworld",
            new PerceptionVec3(1.10, 64.0, 0.5)
        );
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final LocalNavSnapshot next = mapper.ingest(
            new SemanticObservation(
                4,
                straddling,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                budget,
                new ObservationBudgetUsage(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE
                )
            )
        );
        final ObservedVoxel retained =
            next.voxelAt(centreBelow).orElseThrow();

        assertEquals(VoxelKind.AIR, retained.kind());
        assertEquals(3, retained.observationRevision());
        assertEquals(
            OccupancyEvidence.MULTI_RAY_CLEAR,
            retained.occupancyEvidence()
        );
    }

    @Test
    void bodyHazardsNeverContaminateTheObservedNavigationVoxel() {
        final PerceptionNavMapper mapper =
            new PerceptionNavMapper(128, 16.0);
        final PerceptionBudget budget =
            PerceptionBudget.defaults();
        final DangerSignal falling = new DangerSignal(
            DangerKind.FALLING,
            0.9,
            0.0,
            Optional.empty(),
            PerceptionProvenance.BODY_HAZARD
        );
        final LocalNavSnapshot snapshot = mapper.ingest(
            new SemanticObservation(
                5,
                body(5, "minecraft:overworld"),
                List.of(),
                List.of(stoneAt(3)),
                List.of(),
                List.of(falling),
                Optional.empty(),
                budget,
                new ObservationBudgetUsage(
                    0,
                    0,
                    0,
                    1,
                    0,
                    1,
                    1,
                    false,
                    false,
                    false,
                    false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    PerceptionProvenance.BODY_HAZARD
                )
            )
        );

        assertEquals(
            0.0,
            snapshot.voxelAt(new GridPos(0, 64, 0))
                .orElseThrow()
                .effectiveDanger()
        );
    }

    private static VisibleBlockFace stoneAt(final int x) {
        return new VisibleBlockFace(
            new BlockCoordinate(x, 65, 0),
            "minecraft:stone",
            "west",
            new PerceptionVec3(x, 65.5, 0.5),
            x - 0.5,
            PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    private static SemanticObservation observation(
        final long revision,
        final String dimension,
        final VisibleBlockFace face
    ) {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        return new SemanticObservation(
            revision,
            body(revision, dimension),
            List.of(),
            List.of(face),
            List.of(),
            budget,
            new ObservationBudgetUsage(
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                false,
                false,
                false,
                false
            ),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
            )
        );
    }

    private static SemanticObservation clearObservation(
        final long revision,
        final List<PerceptionVec3> ends
    ) {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final List<ClearSightRay> rays = ends.stream()
            .map(end -> new ClearSightRay(
                end,
                end.subtract(body(revision, "minecraft:overworld")
                    .eyePosition()).length(),
                PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
            ))
            .toList();
        return new SemanticObservation(
            revision,
            body(revision, "minecraft:overworld"),
            List.of(),
            List.of(),
            rays,
            List.of(),
            Optional.empty(),
            budget,
            new ObservationBudgetUsage(
                0,
                0,
                0,
                rays.size(),
                0,
                0,
                0,
                false,
                false,
                false,
                false
            ),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
            )
        );
    }

    private static BodySnapshot body(
        final long revision,
        final String dimension
    ) {
        return bodyAt(
            revision,
            dimension,
            new PerceptionVec3(0.5, 64.0, 0.5)
        );
    }

    private static BodySnapshot bodyAt(
        final long revision,
        final String dimension,
        final PerceptionVec3 position
    ) {
        return new BodySnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                dimension,
                revision,
                position,
                new PerceptionVec3(
                    position.x(),
                    position.y() + 1.5,
                    position.z()
                ),
                new PerceptionVec3(1.0, 0.0, 0.0),
                20.0F,
                20.0F,
                0.0F,
                20,
                5.0F,
                300,
                300,
                true,
                false,
                false,
                0.0,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of(),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.OWN_INVENTORY,
                    PerceptionProvenance.OWN_STATUS_EFFECT
                )
        );
    }
}
