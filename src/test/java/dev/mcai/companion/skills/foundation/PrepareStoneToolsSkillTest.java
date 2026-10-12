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

    /*
     * The descent is a staircase, not a straight shaft. Live telemetry
     * (FIX10) proved a straight shaft is impossible: the block underfoot is
     * the collision surface carrying the body, and
     * ServerOwnedInteractionSkillActuator refuses it with WORLD_DENIED via
     * PlayerSupportBlockGuard. The guard's own contract says a skill that
     * needs the block must first stand on a different observed support --
     * so the neighbouring column is mined and the body steps into it.
     */

    @Test
    void selectsTheHighestObservedBlockInTheChosenStepColumn() {
        /*
         * 高度优先、距离只做次级排序：竖直楼梯必须从目标列“顶端”往下挖。
         * 若按距离取最近，就会侧向挖到列中间，把上面的方块悬空 —— 那不是
         * 台阶，走不进去。
         */
        final VisibleBlockFace lowerButNearer =
                visibleStone(5, 66, -18, 1.1);
        final VisibleBlockFace upper = visibleStone(5, 67, -18, 3.2);

        assertEquals(
                upper,
                PrepareStoneToolsSkill.selectStairBlock(
                        new BlockCoordinate(5, 68, -17),
                        0,
                        -1,
                        68.6,
                        List.of(lowerButNearer, upper)
                ).orElseThrow()
        );
    }

    @Test
    void stillSeesTheNextColumnAfterSteppingOneBlockDown() {
        /*
         * 已经站在 Y-1 这一级时，下一列的地表方块顶面正好与我的脚齐平，
         * 它所在的方块层与“眼睛所在层”只差一层。用 floor(eyeY) 当阈值会
         * 把它整列滤掉，楼梯随即卡死在 stair_column_empty。
         */
        final VisibleBlockFace nextSurface = visibleStone(5, 67, -19, 1.6);

        assertEquals(
                nextSurface,
                PrepareStoneToolsSkill.selectStairBlock(
                        new BlockCoordinate(5, 67, -18),
                        0,
                        -1,
                        68.62,
                        List.of(nextSurface)
                ).orElseThrow()
        );
    }

    @Test
    void neverSelectsTheColumnDirectlyUnderfoot() {
        final VisibleBlockFace support = visibleStone(5, 67, -17, 1.0);

        assertTrue(PrepareStoneToolsSkill.selectStairBlock(
                new BlockCoordinate(5, 68, -17),
                0,
                -1,
                68.6,
                List.of(support)
        ).isEmpty());
    }

    @Test
    void ignoresObservedBlocksOutsideTheChosenStepColumn() {
        final VisibleBlockFace otherColumn = visibleStone(6, 67, -18, 1.2);

        assertTrue(PrepareStoneToolsSkill.selectStairBlock(
                new BlockCoordinate(5, 68, -17),
                0,
                -1,
                68.6,
                List.of(otherColumn)
        ).isEmpty());
    }

    @Test
    void ignoresBlocksWhoseBaseIsAtOrAboveTheBodysOwnEye() {
        final VisibleBlockFace aboveEye = visibleStone(5, 69, -18, 1.2);

        assertTrue(PrepareStoneToolsSkill.selectStairBlock(
                new BlockCoordinate(5, 68, -17),
                0,
                -1,
                68.6,
                List.of(aboveEye)
        ).isEmpty());
    }

    @Test
    void acceptsBlocksJustBelowTheEyeEvenInTheEyesOwnBlockLayer() {
        final VisibleBlockFace levelWithFeet =
                visibleStone(5, 68, -18, 1.2);

        assertEquals(
                levelWithFeet,
                PrepareStoneToolsSkill.selectStairBlock(
                        new BlockCoordinate(5, 68, -17),
                        0,
                        -1,
                        68.6,
                        List.of(levelWithFeet)
                ).orElseThrow()
        );
    }

    @Test
    void refusesStairBlocksBeyondArmReach() {
        final VisibleBlockFace tooFar = visibleStone(
                5,
                67,
                -18,
                PrepareStoneToolsSkill.STAIR_REACH + 0.01
        );

        assertTrue(PrepareStoneToolsSkill.selectStairBlock(
                new BlockCoordinate(5, 68, -17),
                0,
                -1,
                68.6,
                List.of(tooFar)
        ).isEmpty());
    }

    @Test
    void returnsNothingUntilAStepDirectionHasBeenChosen() {
        assertTrue(PrepareStoneToolsSkill.selectStairBlock(
                new BlockCoordinate(5, 68, -17),
                0,
                0,
                68.6,
                List.of(visibleStone(5, 67, -18, 1.0))
        ).isEmpty());
    }

    @Test
    void handlesNegativeColumnsWhenSteppingTowardsNegativeZ() {
        final VisibleBlockFace block = visibleStone(-6, 62, -22, 2.0);

        assertEquals(
                block,
                PrepareStoneToolsSkill.selectStairBlock(
                        new BlockCoordinate(-5, 63, -21),
                        0,
                        -1,
                        63.6,
                        List.of(block)
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
