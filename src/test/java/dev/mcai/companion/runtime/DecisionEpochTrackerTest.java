package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class DecisionEpochTrackerTest {
    @Test
    void eventBoundaryCanReadInitialEpochBeforeFirstObservation() {
        final DecisionEpochTracker<String> tracker = new DecisionEpochTracker<>();

        assertEquals(0, tracker.currentOrInitial());
        assertThrows(IllegalStateException.class, tracker::current);
        assertEquals(0, tracker.update(0, "first-observation", OptionalLong.empty()));
        assertEquals(0, tracker.currentOrInitial());
        assertEquals(0, tracker.current());
    }

    @Test
    void advancesOnlyForMeaningfulFingerprintOrGoalChanges() {
        final DecisionEpochTracker<String> tracker = new DecisionEpochTracker<>();

        assertEquals(0, tracker.update(1, "safe-at-home", OptionalLong.empty()));
        assertEquals(0, tracker.update(1, "safe-at-home", OptionalLong.empty()));
        assertEquals(1, tracker.update(1, "hostile-nearby", OptionalLong.empty()));
        assertEquals(2, tracker.update(2, "hostile-nearby", OptionalLong.empty()));
    }

    @Test
    void activeAtomicSkillCanFreezeItsAuthorizedEpoch() {
        final DecisionEpochTracker<String> tracker = new DecisionEpochTracker<>();
        assertEquals(0, tracker.update(1, "before", OptionalLong.empty()));

        assertEquals(0, tracker.update(1, "world-changed", OptionalLong.of(0)));
        assertEquals(1, tracker.update(1, "world-changed", OptionalLong.empty()));
        assertThrows(
            IllegalArgumentException.class,
            () -> tracker.update(1, "invalid", OptionalLong.of(2))
        );
    }
}
