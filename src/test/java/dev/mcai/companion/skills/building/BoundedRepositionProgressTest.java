package dev.mcai.companion.skills.building;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BoundedRepositionProgressTest {
    @Test
    void lateralMotionCannotKeepAConstructionVantageAlive() {
        final BoundedRepositionProgress progress =
                new BoundedRepositionProgress(60, 160, 0.10);
        progress.start(10, 3.0);

        for (long tick = 11; tick < 70; tick++) {
            /*
             * A circling body can move continuously while remaining the same
             * distance from its selected stand.
             */
            progress.observe(tick, 3.0);
            assertEquals(
                    BoundedRepositionProgress.Expiration.NONE,
                    progress.expirationAt(tick)
            );
        }
        progress.observe(70, 3.0);

        assertEquals(
                BoundedRepositionProgress.Expiration.STALLED,
                progress.expirationAt(70)
        );
    }

    @Test
    void meaningfulApproachRenewsOnlyTheStallWindow() {
        final BoundedRepositionProgress progress =
                new BoundedRepositionProgress(60, 160, 0.10);
        progress.start(100, 4.0);
        progress.observe(150, 3.8);

        assertEquals(
                BoundedRepositionProgress.Expiration.NONE,
                progress.expirationAt(209)
        );
        assertEquals(
                BoundedRepositionProgress.Expiration.STALLED,
                progress.expirationAt(210)
        );
    }

    @Test
    void continuousImprovementsCannotBypassAbsoluteDeadline() {
        final BoundedRepositionProgress progress =
                new BoundedRepositionProgress(60, 160, 0.10);
        progress.start(0, 4.0);
        for (long tick = 20; tick <= 160; tick += 20) {
            progress.observe(tick, 4.0 - tick / 100.0);
        }

        assertEquals(
                BoundedRepositionProgress.Expiration.DEADLINE,
                progress.expirationAt(160)
        );
    }
}
