package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DangerSignalTest {
    @Test
    void authorizedPlayerWarningMaySupplyOnlyBroadThreatDirection() {
        assertDoesNotThrow(() -> new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.85,
                4.0,
                Optional.of(new PerceptionVec3(0.0, 0.0, -1.0)),
                PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
        ));
    }

    @Test
    void audibleHostileCueMaySupplyOnlyBroadThreatDirection() {
        assertDoesNotThrow(() -> new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.60,
                12.0,
                Optional.of(new PerceptionVec3(1.0, 0.0, 0.0)),
                PerceptionProvenance.AUDIBLE_HOSTILE_SOUND
        ));
    }

    @Test
    void authorizedPlayerWarningCannotInventPhysicalContact() {
        assertThrows(IllegalArgumentException.class, () ->
                new DangerSignal(
                        DangerKind.THREAT_CONTACT,
                        0.85,
                        0.0,
                        Optional.of(new PerceptionVec3(0.0, 0.0, -1.0)),
                        PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
                )
        );
    }
}
