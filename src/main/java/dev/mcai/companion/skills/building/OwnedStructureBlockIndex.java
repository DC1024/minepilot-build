package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.progression.VerifiedShelterEvidence;
import dev.mcai.companion.skills.interaction.BlockBreakProtection;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small immutable-snapshot index of companion-owned construction blocks.
 *
 * <p>The active reservation is replaced when local terrain constraints force
 * the shelter planner to move. Completed reservations remain protected for
 * the lifetime of the server, and the persisted verified shelter can seed the
 * index after a restart. Reads are lock-free because the mining boundary is
 * latency-sensitive; updates publish one immutable snapshot.</p>
 */
public final class OwnedStructureBlockIndex
        implements BlockBreakProtection {
    private volatile Snapshot snapshot = Snapshot.empty();

    public void activateShelter(
            final long goalRevision,
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        final Reservation active = Reservation.fromPlan(
                goalRevision,
                plan
        );
        final Snapshot current = snapshot;
        snapshot = new Snapshot(
                active,
                current.completed()
        );
    }

    public void completeShelter(
            final long goalRevision,
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        final Reservation completed = Reservation.fromPlan(
                goalRevision,
                plan
        );
        final Map<String, Reservation> retained = new HashMap<>(
                snapshot.completed()
        );
        retained.put(completed.id(), completed);
        snapshot = new Snapshot(
                completed,
                Map.copyOf(retained)
        );
    }

    public void restoreVerifiedShelter(
            final VerifiedShelterEvidence evidence
    ) {
        Objects.requireNonNull(evidence, "evidence");
        final Reservation completed =
                Reservation.fromEvidence(evidence);
        final Map<String, Reservation> retained = new HashMap<>(
                snapshot.completed()
        );
        retained.put(completed.id(), completed);
        snapshot = new Snapshot(
                snapshot.active(),
                Map.copyOf(retained)
        );
    }

    @Override
    public boolean protects(
            final DimensionRef dimension,
            final GridPos position
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        final Snapshot current = snapshot;
        if (current.active() != null
                && current.active().contains(dimension, position)) {
            return true;
        }
        return current.completed().values().stream()
                .anyMatch(reservation ->
                        reservation.contains(dimension, position)
                );
    }

    int protectedPositionCount() {
        final Snapshot current = snapshot;
        final Set<DimensionPosition> unique = new HashSet<>();
        if (current.active() != null) {
            current.active().addTo(unique);
        }
        current.completed().values().forEach(
                reservation -> reservation.addTo(unique)
        );
        return unique.size();
    }

    private record Snapshot(
            Reservation active,
            Map<String, Reservation> completed
    ) {
        private Snapshot {
            completed = Map.copyOf(
                    Objects.requireNonNull(completed, "completed")
            );
        }

        private static Snapshot empty() {
            return new Snapshot(null, Map.of());
        }
    }

    private record Reservation(
            String id,
            long goalRevision,
            DimensionRef dimension,
            Set<GridPos> positions
    ) {
        private Reservation {
            Objects.requireNonNull(id, "id");
            if (goalRevision < 0) {
                throw new IllegalArgumentException(
                        "Goal revision is negative"
                );
            }
            Objects.requireNonNull(dimension, "dimension");
            positions = Set.copyOf(
                    Objects.requireNonNull(positions, "positions")
            );
            if (positions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Structure reservation is empty"
                );
            }
        }

        private static Reservation fromPlan(
                final long goalRevision,
                final ShelterPlan plan
        ) {
            final Set<GridPos> positions = new HashSet<>();
            plan.steps().stream()
                    .map(ShelterBuildStep::target)
                    .forEach(positions::add);
            /*
             * Placing a vanilla door at its lower coordinate also occupies
             * the upper coordinate even though the generated plan needs only
             * one use-on-block transaction.
             */
            positions.add(plan.doorUpper());
            return new Reservation(
                    "plan:" + plan.planId(),
                    goalRevision,
                    plan.dimension(),
                    positions
            );
        }

        private static Reservation fromEvidence(
                final VerifiedShelterEvidence evidence
        ) {
            final Set<GridPos> positions = new HashSet<>();
            final int minimumX = evidence.originX();
            final int maximumX =
                    minimumX + evidence.exteriorWidth() - 1;
            final int minimumZ = evidence.originZ();
            final int maximumZ =
                    minimumZ + evidence.exteriorDepth() - 1;
            final int minimumY = evidence.originY();
            final int roofY =
                    minimumY + evidence.interiorHeight();
            final GridPos doorLower = evidence.doorLower();
            final GridPos doorUpper = doorLower.above();
            for (int y = minimumY; y < roofY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    for (int z = minimumZ; z <= maximumZ; z++) {
                        if (x != minimumX
                                && x != maximumX
                                && z != minimumZ
                                && z != maximumZ) {
                            continue;
                        }
                        final GridPos wall = new GridPos(x, y, z);
                        if (!wall.equals(doorLower)
                                && !wall.equals(doorUpper)) {
                            positions.add(wall);
                        }
                    }
                }
            }
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    positions.add(new GridPos(x, roofY, z));
                }
            }
            positions.add(doorLower);
            positions.add(doorUpper);
            positions.add(evidence.lightPosition());
            return new Reservation(
                    "verified:"
                            + evidence.goalRevision()
                            + ':'
                            + evidence.dimension()
                            + ':'
                            + evidence.originX()
                            + ':'
                            + evidence.originY()
                            + ':'
                            + evidence.originZ(),
                    evidence.goalRevision(),
                    DimensionRef.parse(evidence.dimension()),
                    positions
            );
        }

        private boolean contains(
                final DimensionRef requestedDimension,
                final GridPos position
        ) {
            return dimension.equals(requestedDimension)
                    && positions.contains(position);
        }

        private void addTo(
                final Set<DimensionPosition> destination
        ) {
            positions.forEach(position ->
                    destination.add(
                            new DimensionPosition(dimension, position)
                    )
            );
        }
    }

    private record DimensionPosition(
            DimensionRef dimension,
            GridPos position
    ) {
    }
}
