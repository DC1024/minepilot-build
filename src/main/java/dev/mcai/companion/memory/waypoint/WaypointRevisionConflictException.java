package dev.mcai.companion.memory.waypoint;

import java.util.UUID;

public final class WaypointRevisionConflictException extends IllegalStateException {
    private final UUID waypointId;
    private final long storedRevision;
    private final long attemptedRevision;

    public WaypointRevisionConflictException(
        UUID waypointId,
        long storedRevision,
        long attemptedRevision
    ) {
        super(
            "Waypoint " + waypointId + " has revision " + storedRevision
                + "; attempted revision was " + attemptedRevision
        );
        this.waypointId = waypointId;
        this.storedRevision = storedRevision;
        this.attemptedRevision = attemptedRevision;
    }

    public UUID waypointId() {
        return waypointId;
    }

    public long storedRevision() {
        return storedRevision;
    }

    public long attemptedRevision() {
        return attemptedRevision;
    }
}
