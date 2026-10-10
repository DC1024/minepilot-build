package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PrepareIronToolkitSkillTest {
    @Test
    void acceptsOnlyTheNoArgumentContract() {
        final var accepted =
                PrepareIronToolkitSkill.parseNone(List.of());
        assertEquals(
                NoParameters.INSTANCE,
                accepted.value().orElseThrow()
        );

        final var rejected = PrepareIronToolkitSkill.parseNone(
                List.of(new SkillArgument("item", "minecraft:iron"))
        );
        assertEquals(
                "prepare_iron_toolkit.invalid_arguments",
                rejected.failure().orElseThrow().code()
        );
    }

    @Test
    void plannerGuideDescribesThePhysicalCompoundTransaction() {
        final String guide = FoundationCraftingSkills.plannerGuide();
        assertTrue(guide.contains("prepare_iron_toolkit"));
        assertTrue(guide.contains("furnace smelting"));
        assertTrue(guide.contains("iron pickaxe/bucket/shield"));
    }

    @Test
    void furnacePlacementAvoidsBlocksWhoseUseWinsOverPlacement() {
        assertTrue(
                PrepareIronToolkitSkill
                        .isInteractivePlacementSupport(
                                "minecraft:crafting_table"
                        )
        );
        assertTrue(
                PrepareIronToolkitSkill
                        .isInteractivePlacementSupport(
                                "minecraft:chest"
                        )
        );
        assertTrue(
                !PrepareIronToolkitSkill
                        .isInteractivePlacementSupport(
                                "minecraft:stone"
                        )
        );
    }

    @Test
    void workstationAndMachineCatalogDoesNotTreatInteractiveBlocksAsSupport() {
        final List<String> interactive = List.of(
                "minecraft:brewing_stand",
                "minecraft:cauldron",
                "minecraft:fletching_table",
                "minecraft:hopper",
                "minecraft:dispenser",
                "minecraft:dropper",
                "minecraft:observer",
                "minecraft:piston",
                "minecraft:sticky_piston",
                "minecraft:repeater",
                "minecraft:comparator",
                "minecraft:redstone_lamp"
        );
        interactive.forEach(block -> assertTrue(
                PrepareIronToolkitSkill.isInteractivePlacementSupport(block),
                () -> block + " must be treated as an interactive block"
        ));
    }

    @Test
    void visibleResourceBeyondInteractionReachIsDelegatedToApproach() {
        final VisibleBlockFace distantCoal = new VisibleBlockFace(
                new BlockCoordinate(0, 64, 9),
                "minecraft:coal_ore",
                "north",
                new PerceptionVec3(0.5, 64.5, 9.0),
                9.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final var selected =
                PrepareIronToolkitSkill.nearestResourceFace(
                        List.of(distantCoal),
                        Set.of("minecraft:coal_ore")
                );
        assertEquals(distantCoal, selected.orElseThrow());
    }

    @Test
    void fairResourceRediscoveryMissDoesNotAbortTheCompoundSkill() {
        assertTrue(
                PrepareIronToolkitSkill.recoverableGatherFailure(
                        "gather_visible_block_cluster"
                                + ".cluster_not_rediscovered"
                )
        );
        assertTrue(
                PrepareIronToolkitSkill.recoverableGatherFailure(
                        "gather_visible_block_cluster.stuck"
                )
        );
        assertTrue(
                !PrepareIronToolkitSkill.recoverableGatherFailure(
                        "gather_visible_block_cluster"
                                + ".danger_detected"
                )
        );
        assertTrue(
                !PrepareIronToolkitSkill.recoverableGatherFailure(
                        "gather_visible_block_cluster"
                                + ".tool_durability_reserve"
                )
        );
    }

    @Test
    void partialPickupDebtMustRecoverDropBeforeOreExploration() {
        final var debt =
                new dev.mcai.companion.skills.gathering
                        .GatherVisibleBlockClusterSkill
                        .DropCollectionDebt(
                                new GridPos(4, 64, 4),
                                "minecraft:raw_iron",
                                7,
                                6
                        );
        assertTrue(
                PrepareIronToolkitSkill.shouldRecoverMinedDrop(
                        "gather_visible_block_cluster"
                                + ".drop_not_collected",
                        java.util.Optional.of(debt),
                        false
                ),
                "Six pickups after seven mined ores is an item-recovery "
                        + "task, not evidence that another ore vein is needed"
        );
        assertTrue(
                !PrepareIronToolkitSkill.shouldRecoverMinedDrop(
                        "gather_visible_block_cluster"
                                + ".drop_not_collected",
                        java.util.Optional.of(debt),
                        true
                )
        );
        assertTrue(
                !PrepareIronToolkitSkill.shouldRecoverMinedDrop(
                        "gather_visible_block_cluster.stuck",
                        java.util.Optional.of(debt),
                        false
                )
        );
    }

    @Test
    void rememberedTableOcclusionSelectsAnotherObservedVantage() {
        final GridPos fixture = new GridPos(0, 64, 0);
        final GridPos blockedStand = new GridPos(0, 64, 3);
        final GridPos clearStand = new GridPos(2, 64, 0);
        final List<ObservedVoxel> voxels = new ArrayList<>();
        addSafeStand(voxels, blockedStand);
        addSafeStand(voxels, clearStand);
        voxels.add(new ObservedVoxel(
                new GridPos(0, 64, 1),
                VoxelKind.SOLID,
                0.0,
                9L,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.UNKNOWN
        ));
        final DimensionRef overworld =
                DimensionRef.parse("minecraft:overworld");
        final CoreSkillFrame frame = new CoreSkillFrame(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000711"
                ),
                overworld,
                100L,
                9L,
                new PerceptionVec3(1.5, 64.0, 2.5),
                new PerceptionVec3(1.5, 65.62, 2.5),
                new PerceptionVec3(0.0, 0.0, -1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(overworld, 9L, voxels),
                List.of()
        );

        assertEquals(
                clearStand,
                PrepareIronToolkitSkill.selectFixtureVantage(
                        frame,
                        fixture,
                        Set.of(),
                        0.08
                ).orElseThrow()
        );
    }

    @Test
    void fixtureRepositionHasTotalAndNoProgressDeadlines() {
        assertTrue(
                !PrepareIronToolkitSkill.fixtureMovementExpired(
                        199L,
                        0L,
                        100L
                )
        );
        assertTrue(
                PrepareIronToolkitSkill.fixtureMovementExpired(
                        200L,
                        0L,
                        100L
                )
        );
        assertTrue(
                PrepareIronToolkitSkill.fixtureMovementExpired(
                        240L,
                        0L,
                        239L
                )
        );
    }

    @Test
    void onlyLocalGeometryFailuresSelectAnotherFixtureVantage() {
        assertTrue(
                PrepareIronToolkitSkill
                        .recoverableFixtureMovementFailure(
                                "move_to.route_unknown"
                        )
        );
        assertTrue(
                PrepareIronToolkitSkill
                        .recoverableFixtureMovementFailure(
                                "move_to.stuck"
                        )
        );
        assertTrue(
                !PrepareIronToolkitSkill
                        .recoverableFixtureMovementFailure(
                                "move_to.hardcore_danger"
                        )
        );
        assertTrue(
                !PrepareIronToolkitSkill
                        .recoverableFixtureMovementFailure(
                                "move_to.actuator_rejected"
                        )
        );
    }

    private static void addSafeStand(
            final List<ObservedVoxel> voxels,
            final GridPos stand
    ) {
        voxels.add(new ObservedVoxel(
                stand.below(),
                VoxelKind.SOLID,
                0.0,
                9L,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.STURDY_FULL_TOP
        ));
        for (GridPos clear : List.of(stand, stand.above())) {
            voxels.add(new ObservedVoxel(
                    clear,
                    VoxelKind.AIR,
                    0.0,
                    9L,
                    OccupancyEvidence.MULTI_RAY_CLEAR,
                    TopSupportAffordance.UNKNOWN
            ));
        }
    }
}
