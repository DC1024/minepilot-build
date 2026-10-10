package dev.mcai.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionConfigTest {
    @Test
    void keepsConfiguredSoftDeadlineWhenItPrecedesHardDeadline() {
        assertEquals(
                12,
                CompanionConfig.effectiveModelSoftTimeoutSeconds(12, 90)
        );
    }

    @Test
    void clampsSoftDeadlineBeforeAUserSelectedShortHardDeadline() {
        assertEquals(
                4,
                CompanionConfig.effectiveModelSoftTimeoutSeconds(12, 5)
        );
    }

    @Test
    void rejectsValuesOutsideForgeValidatedRanges() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionConfig.effectiveModelSoftTimeoutSeconds(0, 5)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionConfig.effectiveModelSoftTimeoutSeconds(1, 1)
        );
    }
}
