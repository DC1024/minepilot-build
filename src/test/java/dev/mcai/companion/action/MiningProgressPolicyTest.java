package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MiningProgressPolicyTest {
    @Test
    void neverUsesServersSeventyPercentToleranceAsMiningSpeed() {
        assertFalse(MiningProgressPolicy.readyToStop(0.1F, 7));
        assertFalse(MiningProgressPolicy.readyToStop(0.1F, 9));
        assertTrue(MiningProgressPolicy.readyToStop(0.1F, 10));
    }

    @Test
    void zeroProgressNeverCompletesAndInvalidTimelinesFailClosed() {
        assertFalse(MiningProgressPolicy.readyToStop(0.0F, 10_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiningProgressPolicy.readyToStop(-0.1F, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MiningProgressPolicy.timedOut(10, 9, 20)
        );
    }

    @Test
    void timeoutAllowsTheConfiguredFinalTick() {
        assertFalse(MiningProgressPolicy.timedOut(100, 120, 20));
        assertTrue(MiningProgressPolicy.timedOut(100, 121, 20));
    }
}
