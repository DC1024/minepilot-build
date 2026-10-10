package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EstablishFoundationWorkstationsSkillTest {
    private static final DimensionRef OVERWORLD =
            DimensionRef.parse("minecraft:overworld");
    private static final VisibleBlockFace SUPPORT =
            new VisibleBlockFace(
                    new BlockCoordinate(1, 63, 0),
                    "minecraft:dirt",
                    "up",
                    new PerceptionVec3(1.5, 64.0, 0.5),
                    2.0,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    TopSupportAffordance.STURDY_FULL_TOP
            );

    @Test
    void placementRequiresObservedAirForChestAndOpeningSpace() {
        final CoreSkillFrame clear = frame(
                List.of(
                        air(new GridPos(1, 64, 0)),
                        air(new GridPos(1, 65, 0))
                ),
                List.of(SUPPORT)
        );
        assertTrue(
                EstablishFoundationWorkstationsSkill
                        .hasObservedChestPlacementClearance(
                                clear,
                                SUPPORT
                        )
        );

        final CoreSkillFrame blocked = frame(
                List.of(
                        air(new GridPos(1, 64, 0)),
                        solid(new GridPos(1, 65, 0))
                ),
                List.of(SUPPORT)
        );
        assertFalse(
                EstablishFoundationWorkstationsSkill
                        .hasObservedChestPlacementClearance(
                                blocked,
                                SUPPORT
                        )
        );

        final CoreSkillFrame unknownOpeningSpace = frame(
                List.of(air(new GridPos(1, 64, 0))),
                List.of(SUPPORT)
        );
        assertFalse(
                EstablishFoundationWorkstationsSkill
                        .hasObservedChestPlacementClearance(
                                unknownOpeningSpace,
                                SUPPORT
                        )
        );
    }

    @Test
    void visiblePlacedChestIsReusableWithoutRecrafting() {
        final VisibleBlockFace chest = new VisibleBlockFace(
                new BlockCoordinate(1, 64, 0),
                "minecraft:chest",
                "east",
                new PerceptionVec3(2.0, 64.5, 0.5),
                1.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        assertTrue(
                EstablishFoundationWorkstationsSkill.hasVisibleStorage(
                        frame(List.of(), List.of(chest))
                )
        );
        assertFalse(
                EstablishFoundationWorkstationsSkill.hasVisibleStorage(
                        frame(List.of(), List.of(SUPPORT))
                )
        );
    }

    private static CoreSkillFrame frame(
            final List<ObservedVoxel> voxels,
            final List<VisibleBlockFace> visible
    ) {
        return new CoreSkillFrame(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000701"
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
                visible
        );
    }

    private static ObservedVoxel air(final GridPos position) {
        return new ObservedVoxel(
                position,
                VoxelKind.AIR,
                0.0,
                7L,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }

    private static ObservedVoxel solid(final GridPos position) {
        return new ObservedVoxel(
                position,
                VoxelKind.SOLID,
                0.0,
                7L,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.UNKNOWN
        );
    }
}
