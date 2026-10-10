package dev.mcai.companion.model;

import java.util.Objects;

public record RequestedObservation(ObservationKind kind, String reason) {
    public RequestedObservation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }

    public static RequestedObservation none() {
        return new RequestedObservation(ObservationKind.NONE, "");
    }
}
