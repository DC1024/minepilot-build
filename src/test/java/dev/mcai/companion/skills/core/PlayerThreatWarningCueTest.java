package dev.mcai.companion.skills.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.PerceptionVec3;
import org.junit.jupiter.api.Test;

final class PlayerThreatWarningCueTest {
    @Test
    void mapsAuthorizedRearWarningOppositeCurrentLook() {
        final var parsed = PlayerThreatWarningCue.parse(
                "小心，你后面有僵尸！",
                new PerceptionVec3(0.0, 0.0, 1.0)
        );

        assertTrue(parsed.isPresent());
        assertEquals(
                new PerceptionVec3(0.0, 0.0, -1.0),
                parsed.orElseThrow()
                        .threatDirection()
                        .orElseThrow()
        );
    }

    @Test
    void ordinaryConversationDoesNotCreateThreatCue() {
        assertTrue(PlayerThreatWarningCue.parse(
                "我们去村庄看看吧",
                new PerceptionVec3(1.0, 0.0, 0.0)
        ).isEmpty());
    }
}
