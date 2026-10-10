package dev.mcai.companion.perception;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded safety signal. Proximity-only signals deliberately omit hidden
 * entity identity and exact position.
 */
public record DangerSignal(
        DangerKind kind,
        double severity,
        double distanceUpperBound,
        Optional<PerceptionVec3> contactDirection,
        PerceptionProvenance provenance
) {
    public DangerSignal {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contactDirection, "contactDirection");
        Objects.requireNonNull(provenance, "provenance");
        severity = PerceptionValidation.finite(severity, "severity");
        distanceUpperBound = PerceptionValidation.finite(
                distanceUpperBound,
                "distanceUpperBound"
        );
        if (severity < 0.0 || severity > 1.0 || distanceUpperBound < 0.0) {
            throw new IllegalArgumentException("Invalid danger signal");
        }
        if (provenance != PerceptionProvenance.BODY_HAZARD
                && provenance != PerceptionProvenance.PHYSICAL_CONTACT
                && provenance
                    != PerceptionProvenance.RECENT_DAMAGE_EVENT
                && provenance
                    != PerceptionProvenance.AUDIBLE_HOSTILE_SOUND
                && provenance != PerceptionProvenance.PROXIMITY_THREAT
                && provenance
                    != PerceptionProvenance.AUTHORIZED_PLAYER_WARNING) {
            throw new IllegalArgumentException("Invalid danger provenance");
        }
        if (provenance != PerceptionProvenance.PHYSICAL_CONTACT
                && provenance
                    != PerceptionProvenance.RECENT_DAMAGE_EVENT
                && provenance
                    != PerceptionProvenance.AUDIBLE_HOSTILE_SOUND
                && provenance
                    != PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
                && contactDirection.isPresent()) {
            throw new IllegalArgumentException(
                    "Only contact, a recent damage cue, an audible hostile "
                        + "cue, or an authorized player warning may expose "
                        + "a direction"
            );
        }
        if (provenance
                == PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
                && kind != DangerKind.HOSTILE_PROXIMITY) {
            throw new IllegalArgumentException(
                    "A player warning may only request a bounded hostile "
                        + "proximity response"
            );
        }
    }
}
