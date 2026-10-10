package dev.mcai.companion.skills.memory;

import java.util.List;
import java.util.Objects;

public record WaypointRecallSnapshot(
    boolean present,
    String queryUntrusted,
    List<WaypointRecallEntry> matches
) {
    public static final int MAXIMUM_MATCHES = 5;

    public WaypointRecallSnapshot {
        queryUntrusted = Objects.requireNonNull(queryUntrusted, "queryUntrusted");
        if (queryUntrusted.length() > 128 || queryUntrusted.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Recall query is invalid");
        }
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if (matches.size() > MAXIMUM_MATCHES) {
            throw new IllegalArgumentException("Too many recalled waypoints");
        }
        if (!present && (!queryUntrusted.isEmpty() || !matches.isEmpty())) {
            throw new IllegalArgumentException(
                "Absent recall cannot contain data"
            );
        }
    }

    public static WaypointRecallSnapshot empty() {
        return new WaypointRecallSnapshot(false, "", List.of());
    }
}
