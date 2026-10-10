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
                63,
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
                63,
                List.of(outsideRayBudget)
        ).isEmpty());
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
