package dev.mcai.companion.skills.building;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.navigation.GridPos;
import java.util.List;
import java.util.Objects;

/**
 * Chooses a vanilla-clickable support face for one generated placement.
 *
 * <p>Both wall layers use only the top of the block directly below. The body
 * builds walls from inside the generated footprint, so a neighbouring
 * block's outward face can appear in a peripheral ray but cannot be selected
 * by the centre crosshair from that stance. A roof is different: after its
 * first jump-placed block, the exposed side of that block is the ordinary
 * player-like way to continue the layer without repeatedly jumping into an
 * increasingly low ceiling.</p>
 */
final class PlacementSupportPreference {
    private static final List<BlockFace> EDGE_FIRST = List.of(
            BlockFace.SOUTH,
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    );
    private static final List<BlockFace> TOP_FIRST = List.of(
            BlockFace.UP,
            BlockFace.SOUTH,
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.DOWN
    );
    private static final List<BlockFace> WALL_TOP_ONLY = List.of(
            BlockFace.UP
    );

    private PlacementSupportPreference() {
    }

    static List<BlockFace> orderedFaces(
            final ShelterStepRole role
    ) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case LOWER_WALL, UPPER_WALL -> WALL_TOP_ONLY;
            case ROOF -> EDGE_FIRST;
            default -> EDGE_FIRST;
        };
    }

    static int rank(
            final ShelterStepRole role,
            final BlockFace face
    ) {
        Objects.requireNonNull(face, "face");
        final int rank = orderedFaces(role).indexOf(face);
        return rank < 0 ? Integer.MAX_VALUE : rank;
    }

    /**
     * Returns the clicked block whose given face places into {@code target}.
     */
    static GridPos support(
            final GridPos target,
            final BlockFace face
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(face, "face");
        return switch (face) {
            case SOUTH -> target.offset(0, 0, -1);
            case NORTH -> target.offset(0, 0, 1);
            case EAST -> target.offset(-1, 0, 0);
            case WEST -> target.offset(1, 0, 0);
            case UP -> target.below();
            case DOWN -> target.above();
        };
    }
}
