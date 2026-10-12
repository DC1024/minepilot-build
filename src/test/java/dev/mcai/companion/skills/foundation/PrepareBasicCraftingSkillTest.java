package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PrepareBasicCraftingSkillTest {
    private static final DimensionRef OVERWORLD =
            DimensionRef.parse("minecraft:overworld");

    @Test
    void placementSupportSkipsAVisibleLivestockCollision() {
        final VisibleBlockFace occupied = support(
                new BlockCoordinate(1, 63, 1),
                2.0
        );
        final VisibleBlockFace clear = support(
                new BlockCoordinate(2, 63, 0),
                2.5
        );
        final VisibleEntity cow = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000741"
                ),
                "minecraft:cow",
                new PerceptionVec3(1.5, 64.0, 1.5),
                new PerceptionVec3(1.0, 0.0, 1.0),
                Math.sqrt(2.0),
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of()
        );
        final CoreSkillFrame frame = new CoreSkillFrame(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000742"
                ),
                OVERWORLD,
                100L,
                7L,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(OVERWORLD, 7L, List.of()),
                List.of(occupied, clear),
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(cow),
                List.of()
        );

        assertEquals(
                clear,
                PrepareBasicCraftingSkill.selectPlacementSupport(
                        frame,
                        Set.of()
                ).orElseThrow()
        );
    }

    @Test
    void aLonePickaxeDoesNotCountAsABasicCraftingSetup() {
        final CoreSkillFrame pickaxeOnly = frameWith(
                List.of(new InventoryItemSummary(
                        "minecraft:wooden_pickaxe",
                        1
                ))
        );
        assertTrue(
                PrepareBasicCraftingSkill.ownsPickaxe(pickaxeOnly),
                "a wooden pickaxe is owned"
        );
        assertFalse(
                PrepareBasicCraftingSkill.basicCraftingReady(
                        pickaxeOnly,
                        false
                ),
                "a pickaxe alone must not complete the route milestone"
        );
    }

    @Test
    void basicCraftingSetupNeedsAnOwnedTableOrAnOpenedOne() {
        final CoreSkillFrame pickaxeOnly = frameWith(
                List.of(new InventoryItemSummary(
                        "minecraft:wooden_pickaxe",
                        1
                ))
        );
        final CoreSkillFrame withTable = frameWith(
                List.of(
                        new InventoryItemSummary(
                                "minecraft:wooden_pickaxe",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:crafting_table",
                                1
                        )
                )
        );
        assertTrue(
                PrepareBasicCraftingSkill.basicCraftingReady(
                        withTable,
                        false
                ),
                "an owned crafting table satisfies the milestone"
        );
        assertTrue(
                PrepareBasicCraftingSkill.basicCraftingReady(
                        pickaxeOnly,
                        true
                ),
                "a table this body opened satisfies the milestone"
        );
    }

    private static CoreSkillFrame frameWith(
            final List<InventoryItemSummary> inventory
    ) {
        return new CoreSkillFrame(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000743"
                ),
                OVERWORLD,
                100L,
                7L,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(OVERWORLD, 7L, List.of()),
                List.of(),
                20.0F,
                20.0F,
                20,
                inventory,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static VisibleBlockFace support(
            final BlockCoordinate block,
            final double distance
    ) {
        return new VisibleBlockFace(
                block,
                "minecraft:dirt",
                "up",
                new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 1.0,
                        block.z() + 0.5
                ),
                distance,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    @Test
    void supportRepositionDescendsOutOfACanopyInsteadOfStayingInIt() {
        /*
         * A body with no legitimate table support in reach can only be freed
         * by leaving its own layer: a live body perched in a canopy spent over
         * an hour cycling no-support, no-pickaxe and no-stone while standing
         * six blocks above ground it had already surveyed, because the search
         * refused to look below its feet.
         */
        final GridPos sameLayer = new GridPos(2, 64, 0);
        final GridPos lower = new GridPos(3, 62, 0);
        final List<ObservedVoxel> voxels = new ArrayList<>();
        addSafeStand(voxels, sameLayer);
        addSafeStand(voxels, lower);

        final CoreSkillFrame frame = frameWithVoxels(voxels);

        assertEquals(
                lower,
                PrepareBasicCraftingSkill.selectSupportReposition(
                        frame,
                        Set.of(),
                        0.25
                ).orElseThrow(),
                "A canopy body must be allowed to descend"
        );
    }

    @Test
    void supportRepositionStaysPutWhenNoDescentIsObserved() {
        final GridPos sameLayer = new GridPos(2, 64, 0);
        final List<ObservedVoxel> voxels = new ArrayList<>();
        addSafeStand(voxels, sameLayer);

        final CoreSkillFrame frame = frameWithVoxels(voxels);

        assertEquals(
                sameLayer,
                PrepareBasicCraftingSkill.selectSupportReposition(
                        frame,
                        Set.of(),
                        0.25
                ).orElseThrow(),
                "The ordinary same-layer reposition must still work"
        );
    }

    /** Registers a standable cell: clear feet, clear head, weight-bearing floor. */
    private static void addSafeStand(
            final List<ObservedVoxel> voxels,
            final GridPos stand
    ) {
        voxels.add(clearAir(stand));
        voxels.add(clearAir(stand.above()));
        voxels.add(new ObservedVoxel(
                stand.below(),
                VoxelKind.SOLID,
                0.0,
                1L,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.STURDY_FULL_TOP
        ));
    }

    private static ObservedVoxel clearAir(final GridPos position) {
        return new ObservedVoxel(
                position,
                VoxelKind.AIR,
                0.0,
                1L,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }

    private static CoreSkillFrame frameWithVoxels(
            final List<ObservedVoxel> voxels
    ) {
        return new CoreSkillFrame(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000744"
                ),
                OVERWORLD,
                100L,
                7L,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(OVERWORLD, 7L, voxels),
                List.of(),
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }
}
