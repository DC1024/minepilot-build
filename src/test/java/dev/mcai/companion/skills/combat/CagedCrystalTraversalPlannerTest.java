package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CagedCrystalTraversalPlannerTest {
    @Test
    void derivesColumnLandingAndHeightOnlyFromObservedCells() {
        final CoreSkillFrame frame = frame(
                navigation(true),
                50
        );
        final var plan =
                CagedCrystalTraversalPlanner.plan(
                        context(),
                        frame,
                        barAtHeight(73.0)
                ).orElseThrow();

        assertEquals(new GridPos(0, 64, 0), plan.approach());
        assertEquals(4, plan.towerBlocks());
        assertEquals(1L, plan.approach()
                .manhattanDistance(plan.landing()));
        assertEquals(64, plan.landing().y());
    }

    @Test
    void refusesUnknownLandingAndOutOfRangeTowerHeight() {
        assertTrue(
                CagedCrystalTraversalPlanner.plan(
                        context(),
                        frame(navigation(false), 51),
                        barAtHeight(73.0)
                ).isEmpty()
        );
        assertTrue(
                CagedCrystalTraversalPlanner.plan(
                        context(),
                        frame(navigation(true), 52),
                        barAtHeight(96.0)
                ).isEmpty()
        );
    }

    private static SkillContext context() {
        return new SkillContext(
                1,
                1,
                1,
                true,
                true,
                0.0
        );
    }

    private static VisibleBlockFace barAtHeight(
            final double height
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(4, (int) height, 0),
                "minecraft:iron_bars",
                "west",
                new PerceptionVec3(4.0, height, 0.5),
                9.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        );
    }

    private static CoreSkillFrame frame(
            final LocalNavSnapshot navigation,
            final long revision
    ) {
        return new CoreSkillFrame(
                java.util.UUID.fromString(
                        "78000000-0000-0000-0000-000000000001"
                ),
                DimensionRef.END,
                100,
                revision,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                navigation,
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

    private static LocalNavSnapshot navigation(
            final boolean includeAdjacentLanding
    ) {
        final long revision = includeAdjacentLanding ? 50 : 51;
        final List<ObservedVoxel> voxels = new ArrayList<>();
        final int minimum = includeAdjacentLanding ? -1 : 0;
        final int maximum = includeAdjacentLanding ? 1 : 0;
        for (int x = minimum; x <= maximum; x++) {
            for (int z = minimum; z <= maximum; z++) {
                voxels.add(voxel(
                        x,
                        63,
                        z,
                        VoxelKind.SOLID,
                        revision
                ));
                voxels.add(voxel(
                        x,
                        64,
                        z,
                        VoxelKind.AIR,
                        revision
                ));
                voxels.add(voxel(
                        x,
                        65,
                        z,
                        VoxelKind.AIR,
                        revision
                ));
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.END,
                revision,
                voxels
        );
    }

    private static ObservedVoxel voxel(
            final int x,
            final int y,
            final int z,
            final VoxelKind kind,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                kind,
                0.0,
                revision
        );
    }
}
