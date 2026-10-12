package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PrepareStoneToolsSkillTest {
    @Test
    void acceptsFairlyVisibleStoneBeyondImmediateInteractionReach() {
        final VisibleBlockFace stone = visibleStone(
                4,
                63,
                7,
                7.25
        );

        final var selected = PrepareStoneToolsSkill.selectNaturalStone(
                new BlockCoordinate(0, 63, 0),
                List.of(stone)
        );

        assertEquals(stone, selected.orElseThrow());
    }

    @Test
    void remainsBoundedByFirstPersonBlockRayRange() {
        final VisibleBlockFace outsideRayBudget = visibleStone(
                4,
                63,
                24,
                PrepareStoneToolsSkill
                        .MAXIMUM_VISIBLE_STONE_SEED_DISTANCE
                    + 0.01
        );

        assertTrue(PrepareStoneToolsSkill.selectNaturalStone(
                new BlockCoordinate(0, 63, 0),
                List.of(outsideRayBudget)
        ).isEmpty());
    }

    @Test
    void acceptsStoneBelowTheFeetWhenItIsNotTheSupportBlock() {
        /*
         * 修复前这块石头（脚 y=63、石头 y=60）会被 `y >= feetY` 丢掉，
         * 而扫描的 -25 度俯视恰恰就是为了看它 —— 两者本来互相矛盾。
         */
        final VisibleBlockFace stone = visibleStone(
                9,
                60,
                7,
                5.5
        );

        final var selected = PrepareStoneToolsSkill.selectNaturalStone(
                new BlockCoordinate(4, 63, 7),
                List.of(stone)
        );

        assertEquals(stone, selected.orElseThrow());
    }

    @Test
    void refusesTheSingleBlockDirectlyUnderfoot() {
        final VisibleBlockFace support = visibleStone(
                4,
                62,
                7,
                1.0
        );

        assertTrue(PrepareStoneToolsSkill.selectNaturalStone(
                new BlockCoordinate(4, 63, 7),
                List.of(support)
        ).isEmpty());
    }

    @Test
    void stillMinesAnotherStoneWhenTheSupportBlockIsAlsoVisible() {
        final VisibleBlockFace support = visibleStone(
                4,
                62,
                7,
                1.0
        );
        final VisibleBlockFace other = visibleStone(
                10,
                58,
                7,
                6.0
        );

        final var selected = PrepareStoneToolsSkill.selectNaturalStone(
                new BlockCoordinate(4, 63, 7),
                List.of(support, other)
        );

        assertEquals(other, selected.orElseThrow());
    }

    @Test
    void resolvesTheBlockDirectlyUnderfootIncludingNegativeCoordinates() {
        /*
         * 线上实测位置 (7.906, 72.0, -21.13)：脚下方块应为 (7, 71, -22)。
         * 负数坐标必须向下取整，否则往下一格会落在自己身上。
         */
        assertEquals(
                new BlockCoordinate(7, 71, -22),
                PrepareStoneToolsSkill.supportBlockAt(
                        7.906,
                        72.0,
                        -21.13
                )
        );
        assertEquals(
                new BlockCoordinate(4, 62, 7),
                PrepareStoneToolsSkill.supportBlockAt(4.0, 63.0, 7.0)
        );
    }

    @Test
    void bindsTheDescentTargetToTheExactObservedCoordinate() {
        final VisibleBlockFace wanted = visibleStone(4, 62, 7, 1.2);
        final VisibleBlockFace other = visibleStone(5, 62, 7, 1.4);

        assertEquals(
                wanted,
                PrepareStoneToolsSkill.visibleFaceAt(
                        List.of(other, wanted),
                        new BlockCoordinate(4, 62, 7)
                ).orElseThrow()
        );
    }

    @Test
    void refusesToMineABlockThatWasNeverObserved() {
        assertTrue(PrepareStoneToolsSkill.visibleFaceAt(
                List.of(visibleStone(4, 62, 7, 1.2)),
                new BlockCoordinate(4, 68, 7)
        ).isEmpty());
    }

    @Test
    void prefersTheNearestObservedFaceOfTheDescentTarget() {
        final VisibleBlockFace top = visibleStone(4, 62, 7, 1.2);
        final VisibleBlockFace side = visibleStone(4, 62, 7, 3.4);

        assertEquals(
                top,
                PrepareStoneToolsSkill.visibleFaceAt(
                        List.of(side, top),
                        new BlockCoordinate(4, 62, 7)
                ).orElseThrow()
        );
    }

    private static VisibleBlockFace visibleStone(
            final int x,
            final int y,
            final int z,
            final double distance
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                "minecraft:stone",
                "north",
                new PerceptionVec3(x + 0.5, y + 0.5, z),
                distance,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }
}
