package dev.mcai.companion.integration.xaero;

import java.util.Objects;
import java.util.Optional;

public record XaeroReceiveResult(
        XaeroReceiveStatus status,
        Optional<ParsedXaeroWaypoint> waypoint
) {
    public XaeroReceiveResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(waypoint, "waypoint");
        if ((status == XaeroReceiveStatus.ACCEPTED) != waypoint.isPresent()) {
            throw new IllegalArgumentException("Only accepted results may contain a waypoint");
        }
    }

    static XaeroReceiveResult accepted(ParsedXaeroWaypoint waypoint) {
        return new XaeroReceiveResult(XaeroReceiveStatus.ACCEPTED, Optional.of(waypoint));
    }

    static XaeroReceiveResult rejected(XaeroReceiveStatus status) {
        return new XaeroReceiveResult(status, Optional.empty());
    }
}
