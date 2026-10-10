package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.waypoint.DimensionRef;
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
}
