package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.building.ShelterFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;

/** Fair, immutable site evidence available to a mechanism layout solver. */
public record MechanismSiteFrame(
        DimensionRef dimension,
        long sourceRevision,
        GridPos feet,
        PerceptionVec3 lookDirection,
        List<InventoryItemSummary> inventory,
        LocalNavSnapshot navigation,
        List<VisibleBlockFace> visibleBlockFaces,
        boolean skyVisible
) {
    public MechanismSiteFrame {
        Objects.requireNonNull(dimension, "dimension");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException(
                    "Source revision must not be negative"
            );
        }
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(lookDirection, "lookDirection");
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException(
                    "Look direction must be normalized"
            );
        }
        inventory = List.copyOf(
                Objects.requireNonNull(inventory, "inventory")
        );
        Objects.requireNonNull(navigation, "navigation");
        if (!navigation.dimension().equals(dimension)
                || navigation.revision() > sourceRevision) {
            throw new IllegalArgumentException(
                    "Navigation does not match mechanism site evidence"
            );
        }
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
    }

    public static MechanismSiteFrame from(
            final ShelterFrame frame,
            final boolean skyVisible
    ) {
        Objects.requireNonNull(frame, "frame");
        return new MechanismSiteFrame(
                frame.dimension(),
                frame.observationRevision(),
                frame.feet(),
                frame.lookDirection(),
                frame.inventory(),
                frame.navigation(),
                frame.visibleBlockFaces(),
                skyVisible
        );
    }
}
