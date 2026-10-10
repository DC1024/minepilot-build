package dev.mcai.companion.skills.building;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.navigation.GridPos;
import org.junit.jupiter.api.Test;

final class PlacementSupportPreferenceTest {
    @Test
    void upperWallUsesBelowWhileRoofPrefersAnExposedEdge() {
        assertEquals(
                BlockFace.UP,
                PlacementSupportPreference.orderedFaces(
                        ShelterStepRole.UPPER_WALL
                ).getFirst()
        );
        assertEquals(
                BlockFace.SOUTH,
                PlacementSupportPreference.orderedFaces(
                        ShelterStepRole.ROOF
                ).getFirst()
        );
        assertEquals(
                0,
                PlacementSupportPreference.rank(
                        ShelterStepRole.UPPER_WALL,
                        BlockFace.UP
                )
        );
        assertEquals(
                0,
                PlacementSupportPreference.rank(
                        ShelterStepRole.ROOF,
                        BlockFace.SOUTH
                )
        );
    }

    @Test
    void bothWallLayersRequireTheBlockBelow() {
        assertEquals(
                BlockFace.UP,
                PlacementSupportPreference.orderedFaces(
                        ShelterStepRole.LOWER_WALL
                ).getFirst()
        );
        assertEquals(
                0,
                PlacementSupportPreference.rank(
                        ShelterStepRole.LOWER_WALL,
                        BlockFace.UP
                )
        );
        assertEquals(
                Integer.MAX_VALUE,
                PlacementSupportPreference.rank(
                        ShelterStepRole.LOWER_WALL,
                        BlockFace.SOUTH
                )
        );
    }

    @Test
    void supportCoordinatesAreInverseOfPlacementDirection() {
        final GridPos target = new GridPos(10, 20, 30);

        assertEquals(
                target.below(),
                PlacementSupportPreference.support(
                        target,
                        BlockFace.UP
                )
        );
        assertEquals(
                target.offset(0, 0, -1),
                PlacementSupportPreference.support(
                        target,
                        BlockFace.SOUTH
                )
        );
    }
}
