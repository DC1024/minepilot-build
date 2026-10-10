package dev.mcai.companion.skills.building;

import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.HeldItemSummary;
import java.util.Objects;

/**
 * Conservative fallback receipt for an occluded vanilla placement.
 *
 * <p>This is deliberately stronger than an inventory delta. The ordinary
 * actuator must have returned {@code COMPLETED} after synchronously processing
 * a use packet whose exact block and face matched the player's centre ray.
 * The clicked support must have been fairly observed as solid, the clicked
 * face must lead to the generated target, and the same vanilla item must have
 * decreased by exactly one. No destination block lookup is performed here.</p>
 */
final class CausalPlacementEvidence {
    private CausalPlacementEvidence() {
    }

    static boolean confirms(
            final String expectedItem,
            final GridPos intendedTarget,
            final BlockInteractionTarget clickedTarget,
            final boolean actionCompleted,
            final boolean observedSolidSupport,
            final String beforeItem,
            final int beforeCount,
            final HeldItemSummary after
    ) {
        Objects.requireNonNull(expectedItem, "expectedItem");
        Objects.requireNonNull(intendedTarget, "intendedTarget");
        Objects.requireNonNull(clickedTarget, "clickedTarget");
        Objects.requireNonNull(beforeItem, "beforeItem");
        Objects.requireNonNull(after, "after");
        if (!actionCompleted
                || !observedSolidSupport
                || !expectedItem.startsWith("minecraft:")
                || !expectedItem.equals(beforeItem)
                || beforeCount < 1
                || !adjacent(clickedTarget).equals(intendedTarget)) {
            return false;
        }
        final int expectedAfter = beforeCount - 1;
        if (after.count() != expectedAfter) {
            return false;
        }
        return expectedAfter == 0
                ? after.emptyHand()
                : expectedItem.equals(after.itemId());
    }

    private static GridPos adjacent(
            final BlockInteractionTarget clicked
    ) {
        final GridPos support = new GridPos(
                clicked.x(),
                clicked.y(),
                clicked.z()
        );
        return switch (clicked.face()) {
            case DOWN -> support.offset(0, -1, 0);
            case UP -> support.offset(0, 1, 0);
            case NORTH -> support.offset(0, 0, -1);
            case SOUTH -> support.offset(0, 0, 1);
            case WEST -> support.offset(-1, 0, 0);
            case EAST -> support.offset(1, 0, 0);
        };
    }
}
