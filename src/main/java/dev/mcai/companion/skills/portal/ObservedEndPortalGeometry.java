package dev.mcai.companion.skills.portal;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an End portal center exclusively from first-person-visible frame
 * blocks and their visible block-state properties.
 *
 * <p>One frame never proves a center: even with its facing there are three
 * possible lateral positions. Candidate sets are intersected until exactly
 * one canonical ring center remains. Missing facing data falls back to all
 * geometrically possible ring positions instead of guessing.</p>
 */
public final class ObservedEndPortalGeometry {
    private static final String END_PORTAL_FRAME =
            "minecraft:end_portal_frame";

    private ObservedEndPortalGeometry() {
    }

    public static Optional<GridPos> uniqueCenter(
            final List<VisibleBlockFace> visibleFaces
    ) {
        Objects.requireNonNull(visibleFaces, "visibleFaces");
        Set<GridPos> candidates = null;
        final Set<GridPos> consumedFrames = new HashSet<>();
        for (VisibleBlockFace face : visibleFaces) {
            if (face == null
                    || !END_PORTAL_FRAME.equals(
                            face.blockTypeId()
                    )) {
                continue;
            }
            final GridPos frame = new GridPos(
                    face.block().x(),
                    face.block().y(),
                    face.block().z()
            );
            if (!consumedFrames.add(frame)) {
                continue;
            }
            final Set<GridPos> visibleCandidates =
                    candidatesFor(frame, face.stateProperties().get(
                            "facing"
                    ));
            if (candidates == null) {
                candidates = new HashSet<>(visibleCandidates);
            } else {
                candidates.retainAll(visibleCandidates);
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
        }
        if (candidates == null || candidates.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(candidates.iterator().next());
    }

    static Set<GridPos> candidatesFor(
            final GridPos frame,
            final String facing
    ) {
        Objects.requireNonNull(frame, "frame");
        final Set<GridPos> result = new HashSet<>(12);
        switch (facing == null ? "" : facing) {
            case "south" -> addNorthSideCandidates(result, frame);
            case "north" -> addSouthSideCandidates(result, frame);
            case "east" -> addWestSideCandidates(result, frame);
            case "west" -> addEastSideCandidates(result, frame);
            default -> {
                addNorthSideCandidates(result, frame);
                addSouthSideCandidates(result, frame);
                addWestSideCandidates(result, frame);
                addEastSideCandidates(result, frame);
            }
        }
        return Set.copyOf(result);
    }

    private static void addNorthSideCandidates(
            final Set<GridPos> candidates,
            final GridPos frame
    ) {
        for (int lateral = -1; lateral <= 1; lateral++) {
            candidates.add(frame.offset(-lateral, 0, 2));
        }
    }

    private static void addSouthSideCandidates(
            final Set<GridPos> candidates,
            final GridPos frame
    ) {
        for (int lateral = -1; lateral <= 1; lateral++) {
            candidates.add(frame.offset(-lateral, 0, -2));
        }
    }

    private static void addWestSideCandidates(
            final Set<GridPos> candidates,
            final GridPos frame
    ) {
        for (int lateral = -1; lateral <= 1; lateral++) {
            candidates.add(frame.offset(2, 0, -lateral));
        }
    }

    private static void addEastSideCandidates(
            final Set<GridPos> candidates,
            final GridPos frame
    ) {
        for (int lateral = -1; lateral <= 1; lateral++) {
            candidates.add(frame.offset(-2, 0, -lateral));
        }
    }
}
