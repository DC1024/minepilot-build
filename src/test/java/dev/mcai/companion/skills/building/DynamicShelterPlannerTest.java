package dev.mcai.companion.skills.building;

import static dev.mcai.companion.skills.building.ShelterTestFixtures.flatFrame;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.flatSnapshot;
import static dev.mcai.companion.skills.building.ShelterTestFixtures.voxel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.TopSupportAffordance;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DynamicShelterPlannerTest {
    private final DynamicShelterPlanner planner =
            new DynamicShelterPlanner();

    @Test
    void recentFairlyVisibleCowDisqualifiesIntersectingShell() {
        final ShelterFrame clear = flatFrame(
                10,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan original = planner.plan(
                clear,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final GridPos occupiedWall = original.steps().stream()
                .filter(step ->
                        step.role() == ShelterStepRole.LOWER_WALL)
                .findFirst()
                .orElseThrow()
                .target();
        final PerceptionVec3 cowPosition = new PerceptionVec3(
                occupiedWall.x() - 0.25,
                occupiedWall.y(),
                occupiedWall.z() + 0.50
        );
        final RecentVisibleEntity cow = new RecentVisibleEntity(
                new VisibleEntity(
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000321"
                        ),
                        "minecraft:cow",
                        cowPosition,
                        cowPosition.subtract(new PerceptionVec3(
                                clear.feet().x() + 0.5,
                                clear.feet().y(),
                                clear.feet().z() + 0.5
                        )),
                        3.0,
                        false,
                        false,
                        PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
                ),
                clear.observedAtGameTime(),
                clear.observationRevision()
        );
        final ShelterFrame occupied = new ShelterFrame(
                clear.playerId(),
                clear.dimension(),
                clear.currentGameTime(),
                clear.observedAtGameTime(),
                clear.observationRevision(),
                clear.sessionGeneration(),
                clear.feet(),
                clear.lookDirection(),
                clear.mainHand(),
                clear.inventory(),
                clear.navigation(),
                clear.visibleBlockFaces(),
                List.of(cow)
        );

        final ShelterPlan replanned = planner.plan(
                occupied,
                ShelterScale.COMPACT
        ).plan().orElseThrow();

        assertEquals(
                cow,
                DynamicShelterPlanner
                        .visiblePlacementObstruction(
                                occupied,
                                occupiedWall
                        )
                        .orElseThrow(),
                "Runtime placement recovery must use the same fair "
                        + "collision predicate as initial planning"
        );
        assertNotEquals(
                original.origin(),
                replanned.origin(),
                "A recently and fairly seen cow intersecting the old wall "
                        + "must force a different observed footprint"
        );
        assertTrue(
                occupied.atGameTime(
                        occupied.currentGameTime()
                                + ShelterFrame
                                    .MAXIMUM_RECENT_ENTITY_AGE_TICKS
                                + 1
                ).recentVisibleEntities().isEmpty(),
                "Entity occupancy must expire instead of becoming hidden "
                        + "persistent radar"
        );
    }

    @Test
    void fairlySeenLivestockSurvivesACompleteBuildingSurveyWindow() {
        final ShelterFrame clear = flatFrame(
                10,
                new PerceptionVec3(0, 0, 1),
                100,
                List.of()
        );
        final PerceptionVec3 cowPosition =
                new PerceptionVec3(0.5, 0.0, 0.5);
        final RecentVisibleEntity cow = new RecentVisibleEntity(
                new VisibleEntity(
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000322"
                        ),
                        "minecraft:cow",
                        cowPosition,
                        cowPosition.subtract(new PerceptionVec3(
                                clear.feet().x() + 0.5,
                                clear.feet().y(),
                                clear.feet().z() + 0.5
                        )),
                        3.0,
                        false,
                        false,
                        PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
                ),
                clear.observedAtGameTime(),
                clear.observationRevision()
        );
        final ShelterFrame observed = new ShelterFrame(
                clear.playerId(),
                clear.dimension(),
                clear.currentGameTime(),
                clear.observedAtGameTime(),
                clear.observationRevision(),
                clear.sessionGeneration(),
                clear.feet(),
                clear.lookDirection(),
                clear.mainHand(),
                clear.inventory(),
                clear.navigation(),
                clear.visibleBlockFaces(),
                List.of(cow)
        );

        assertFalse(
                observed.atGameTime(
                        observed.currentGameTime() + 400
                ).recentVisibleEntities().isEmpty(),
                "A 360-degree fair survey can take more than 200 ticks; "
                        + "forgetting its first livestock observations "
                        + "lets the generated shell intersect an entity"
        );
    }

    @Test
    void repairReusesConfirmedBlocksWithoutInventingMaterials() {
        final ShelterFrame base = flatFrame(
                20,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final ShelterPlan original = planner.plan(
                base,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        final List<ShelterBuildStep> corners =
                original.steps().stream()
                        .filter(step ->
                                step.role()
                                        == ShelterStepRole.LOWER_WALL)
                        .filter(step ->
                                BuildShelterStepSkill
                                        .constructionPriority(
                                                original,
                                                step
                                        ) == 0)
                        .toList();
        final var reusable = Set.of(
                corners.get(0).target(),
                corners.get(1).target()
        );
        final GridPos forbidden =
                corners.get(2).target();
        final LocalNavSnapshot navigation = flatSnapshot(
                21,
                reusable.stream()
                        .map(position -> voxel(
                                position.x(),
                                position.y(),
                                position.z(),
                                VoxelKind.SOLID,
                                21
                        ))
                        .toList()
        );
        final ShelterFrame depleted = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                21,
                21,
                21,
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        53,
                        0,
                        0
                ),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:cobblestone",
                                53
                        ),
                        new InventoryItemSummary(
                                "minecraft:oak_door",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:torch",
                                1
                        )
                ),
                navigation,
                List.of()
        );

        final ShelterPlan repaired = planner.repair(
                depleted,
                ShelterScale.COMPACT,
                "minecraft:cobblestone",
                reusable,
                Set.of(forbidden)
        ).plan().orElseThrow();
        final long reused = repaired.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .filter(step ->
                        reusable.contains(step.target()))
                .count();

        assertNotEquals(original.origin(), repaired.origin());
        assertTrue(
                repaired.steps().stream().noneMatch(step ->
                        step.target().equals(forbidden)),
                "The physically rejected cell cannot re-enter the shell"
        );
        assertEquals(
                2,
                reused,
                "With only 53 blocks left, a 55-block repair must inherit "
                        + "both causal placements"
        );
    }

    @Test
    void compactPlanSatisfiesVolumeEnclosureDoorLightAndMaterialCount() {
        ShelterPlan plan = planner.plan(
                flatFrame(
                        1,
                        new PerceptionVec3(1, 0, 0),
                        100,
                        List.of()
                ),
                ShelterScale.COMPACT
        ).plan().orElseThrow();

        assertEquals(3, plan.interiorWidth());
        assertEquals(3, plan.interiorDepth());
        assertEquals(2, plan.interiorHeight());
        assertTrue(plan.walkableInteriorVolume() >= 18);
        assertEquals(
                DynamicShelterPlanner.structuralBlockCount(3, 3),
                plan.requiredStructuralBlocks()
        );
        assertEquals(1, plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.DOOR)
                .count());
        assertEquals(1, plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.LIGHT)
                .count());
        assertFalse(plan.steps().stream().anyMatch(step ->
                step.role().usesStructuralMaterial()
                        && (step.target().equals(plan.doorLower())
                        || step.target().equals(plan.doorUpper()))
        ));
        assertEquals(
                plan.steps().size(),
                new HashSet<>(plan.steps().stream()
                        .map(ShelterBuildStep::target)
                        .toList()).size()
        );
    }

    @Test
    void scaleAndLookDirectionProduceDifferentGeneratedGeometry() {
        ShelterFrame east = flatFrame(
                2,
                new PerceptionVec3(1, 0, 0),
                128,
                List.of()
        );
        ShelterFrame north = flatFrame(
                2,
                new PerceptionVec3(0, 0, -1),
                128,
                List.of()
        );

        ShelterPlan compactEast = planner.plan(
                east,
                ShelterScale.COMPACT
        ).plan().orElseThrow();
        ShelterPlan spaciousNorth = planner.plan(
                north,
                ShelterScale.SPACIOUS
        ).plan().orElseThrow();

        assertEquals(ShelterFacing.EAST, compactEast.entranceFacing());
        assertEquals(
                ShelterFacing.NORTH,
                spaciousNorth.entranceFacing()
        );
        assertTrue(
                spaciousNorth.walkableInteriorVolume()
                        > compactEast.walkableInteriorVolume()
        );
        assertNotEquals(compactEast.planId(), spaciousNorth.planId());
        assertNotEquals(
                compactEast.steps().stream()
                        .map(ShelterBuildStep::target)
                        .toList(),
                spaciousNorth.steps().stream()
                        .map(ShelterBuildStep::target)
                        .toList()
        );
    }

    @Test
    void refusesUnknownOrConflictingTerrainAndMissingSafetyResources() {
        ShelterFrame missingDoor = new ShelterFrame(
                ShelterTestFixtures.PLAYER_ID,
                eastFrame().dimension(),
                3,
                3,
                3,
                ShelterTestFixtures.SESSION,
                eastFrame().feet(),
                eastFrame().lookDirection(),
                eastFrame().mainHand(),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:cobblestone",
                                64
                        ),
                        new InventoryItemSummary(
                                "minecraft:torch",
                                1
                        )
                ),
                eastFrame().navigation(),
                List.of()
        );
        ShelterPlanningResult result = planner.plan(
                missingDoor,
                ShelterScale.COMPACT
        );

        assertTrue(result.plan().isEmpty());
        assertEquals(
                "shelter.missing_door",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void selectsObservedRelocationSiteWhenWorkstationsCrowdCurrentFeet() {
        final ShelterFrame base = flatFrame(
                5,
                new PerceptionVec3(0, 0, 1),
                100,
                List.of()
        );
        final var crowdedNavigation = flatSnapshot(
                5,
                List.of(
                        voxel(2, 0, 0, VoxelKind.SOLID, 5),
                        voxel(0, 0, 0, VoxelKind.SOLID, 5),
                        voxel(1, 0, 1, VoxelKind.SOLID, 5),
                        traversableAir(6, 0, 2, 5),
                        traversableAir(6, 1, 2, 5),
                        standingSupport(6, -1, 2, 5)
                )
        );
        final ShelterFrame crowded = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                crowdedNavigation,
                base.visibleBlockFaces()
        );

        final ShelterPlanningResult local = planner.plan(
                crowded,
                ShelterScale.COMPACT
        );
        assertTrue(local.plan().isEmpty());
        assertEquals(
                "shelter.no_safe_footprint",
                local.failure().orElseThrow().code()
        );

        final GridPos relocation = planner.relocationTarget(
                crowded,
                ShelterScale.COMPACT
        ).orElseThrow();
        assertTrue(
                relocation.euclideanDistance(crowded.feet()) >= 4.0,
                "Relocation must leave the physically crowded workstation "
                        + "cluster"
        );

        final ShelterFrame relocated = new ShelterFrame(
                crowded.playerId(),
                crowded.dimension(),
                crowded.currentGameTime(),
                crowded.observedAtGameTime(),
                crowded.observationRevision(),
                crowded.sessionGeneration(),
                relocation,
                crowded.lookDirection(),
                crowded.mainHand(),
                crowded.inventory(),
                crowded.navigation(),
                crowded.visibleBlockFaces()
        );
        assertTrue(
                planner.plan(
                        relocated,
                        ShelterScale.COMPACT
                ).plan().isPresent(),
                "Walking to the selected observed interior must make normal "
                        + "terrain-aware planning feasible"
        );
    }

    @Test
    void relocationUsesObservedStandWithoutInventingFutureBuildingVolume() {
        final ShelterFrame base = flatFrame(
                6,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final GridPos stand = base.feet().offset(4, 0, 0);
        final LocalNavSnapshot sparse = new LocalNavSnapshot(
                base.dimension(),
                6,
                List.of(
                        traversableAir(
                                base.feet().x(),
                                base.feet().y(),
                                base.feet().z(),
                                6
                        ),
                        traversableAir(
                                base.feet().x(),
                                base.feet().y() + 1,
                                base.feet().z(),
                                6
                        ),
                        standingSupport(
                                base.feet().x(),
                                base.feet().y() - 1,
                                base.feet().z(),
                                6
                        ),
                        traversableAir(
                                stand.x(),
                                stand.y(),
                                stand.z(),
                                6
                        ),
                        traversableAir(
                                stand.x(),
                                stand.y() + 1,
                                stand.z(),
                                6
                        ),
                        standingSupport(
                                stand.x(),
                                stand.y() - 1,
                                stand.z(),
                                6
                        )
                )
        );
        final ShelterFrame sparseFrame = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                sparse,
                base.visibleBlockFaces()
        );

        assertEquals(
                stand,
                planner.relocationTarget(
                        sparseFrame,
                        ShelterScale.COMPACT
                ).orElseThrow()
        );
        assertTrue(
                planner.plan(
                        sparseFrame,
                        ShelterScale.COMPACT
                ).plan().isEmpty(),
                "Relocation may defer unknown footprint cells, but the "
                        + "builder itself must still reject them"
        );
    }

    @Test
    void relocationExcludesPreviouslySurveyedSiteInsteadOfRepeatingIt() {
        final ShelterFrame base = flatFrame(
                6,
                new PerceptionVec3(1, 0, 0),
                100,
                List.of()
        );
        final GridPos east = base.feet().offset(4, 0, 0);
        final GridPos west = base.feet().offset(-4, 0, 0);
        final LocalNavSnapshot sparse = new LocalNavSnapshot(
                base.dimension(),
                6,
                List.of(
                        traversableAir(
                                east.x(),
                                east.y(),
                                east.z(),
                                6
                        ),
                        traversableAir(
                                east.x(),
                                east.y() + 1,
                                east.z(),
                                6
                        ),
                        standingSupport(
                                east.x(),
                                east.y() - 1,
                                east.z(),
                                6
                        ),
                        traversableAir(
                                west.x(),
                                west.y(),
                                west.z(),
                                6
                        ),
                        traversableAir(
                                west.x(),
                                west.y() + 1,
                                west.z(),
                                6
                        ),
                        standingSupport(
                                west.x(),
                                west.y() - 1,
                                west.z(),
                                6
                        )
                )
        );
        final ShelterFrame sparseFrame = new ShelterFrame(
                base.playerId(),
                base.dimension(),
                base.currentGameTime(),
                base.observedAtGameTime(),
                base.observationRevision(),
                base.sessionGeneration(),
                base.feet(),
                base.lookDirection(),
                base.mainHand(),
                base.inventory(),
                sparse,
                base.visibleBlockFaces()
        );

        final GridPos first = planner.relocationTarget(
                sparseFrame,
                ShelterScale.COMPACT
        ).orElseThrow();
        final GridPos second = planner.relocationTarget(
                sparseFrame,
                ShelterScale.COMPACT,
                Set.of(first)
        ).orElseThrow();

        assertNotEquals(
                first,
                second,
                "A failed first-person site survey must not send the body "
                        + "back to the same deterministic candidate"
        );
        assertTrue(
                Set.of(east, west).contains(second)
        );
    }

    private static ObservedVoxel traversableAir(
            final int x,
            final int y,
            final int z,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                VoxelKind.AIR,
                0.0,
                revision,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }

    private static ObservedVoxel standingSupport(
            final int x,
            final int y,
            final int z,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                VoxelKind.SOLID,
                0.0,
                revision,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private static ShelterFrame eastFrame() {
        return flatFrame(
                3,
                new PerceptionVec3(1, 0, 0),
                64,
                List.of()
        );
    }
}
