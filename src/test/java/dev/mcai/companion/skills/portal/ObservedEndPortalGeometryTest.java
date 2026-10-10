package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ObservedEndPortalGeometryTest {
    private static final GridPos CENTER =
            new GridPos(10, 64, 20);

    @Test
    void oneVisibleFrameNeverInventsTheLateralCenter() {
        assertTrue(
                ObservedEndPortalGeometry.uniqueCenter(List.of(
                        frame(10, 64, 18, "south")
                )).isEmpty()
        );
    }

    @Test
    void perpendicularVisibleFramesProveOneCenter() {
        assertEquals(
                CENTER,
                ObservedEndPortalGeometry.uniqueCenter(List.of(
                        frame(10, 64, 18, "south"),
                        frame(8, 64, 20, "east")
                )).orElseThrow()
        );
    }

    @Test
    void completeVisibleSideProvesItsMiddleWithoutHiddenReads() {
        assertEquals(
                CENTER,
                ObservedEndPortalGeometry.uniqueCenter(List.of(
                        frame(9, 64, 18, "south"),
                        frame(10, 64, 18, "south"),
                        frame(11, 64, 18, "south")
                )).orElseThrow()
        );
    }

    @Test
    void contradictoryVisibleFacingFailsClosed() {
        assertTrue(
                ObservedEndPortalGeometry.uniqueCenter(List.of(
                        frame(10, 64, 18, "north"),
                        frame(8, 64, 20, "east")
                )).isEmpty()
        );
    }

    private static VisibleBlockFace frame(
            final int x,
            final int y,
            final int z,
            final String facing
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                "minecraft:end_portal_frame",
                "minecraft:up",
                new PerceptionVec3(
                        x + 0.5,
                        y + 0.8,
                        z + 0.5
                ),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(
                        "eye",
                        "false",
                        "facing",
                        facing
                )
        );
    }
}
