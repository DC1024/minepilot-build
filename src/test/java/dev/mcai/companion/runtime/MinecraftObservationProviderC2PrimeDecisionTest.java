package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the behavioural core of C2' without booting a Minecraft server. The
 * production code paths ({@code mustRefreshSemantic}, {@code
 * Fingerprint#relevantDynamicChanged(ServerPlayer)}) are delegated to the two
 * pure static helpers below, which this test drives with plain primitives.
 *
 * <p>Real cadence mirrored here: {@code semanticIntervalTicks = 5} (20 TPS -> 4 Hz)
 * and {@code maxStalenessTicks = 80} (4 s absolute safety cap). C2' claims that a
 * still companion whose world has not materially changed must NOT rebuild its
 * ~60 KB semantic observation on the fixed 4 Hz floor — only the 4 s cap may
 * force a rebuild.</p>
 */
class MinecraftObservationProviderC2PrimeDecisionTest {
    private static final int SEMANTIC_INTERVAL = 5;
    private static final int MAX_STALENESS = 80;

    // --- decideSemanticRefresh: the idle-skip core of C2' ---

    @Test
    void idleAndUnchangedSkipsFourHertzRebuild() {
        // A still companion one 4 Hz tick after the last sample: must NOT refresh.
        assertFalse(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, false, SEMANTIC_INTERVAL, SEMANTIC_INTERVAL, MAX_STALENESS),
            "idle + unchanged at the 4 Hz boundary must skip the rebuild"
        );
    }

    @Test
    void idleAndUnchangedOnlyRebuildsAtAbsoluteStalenessCap() {
        assertFalse(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, false, MAX_STALENESS - 1, SEMANTIC_INTERVAL, MAX_STALENESS),
            "one tick before the 4 s cap must still skip"
        );
        assertTrue(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, false, MAX_STALENESS, SEMANTIC_INTERVAL, MAX_STALENESS),
            "the 4 s absolute cap must force a rebuild even when nothing changed"
        );
    }

    @Test
    void coarseBodyChangeAlwaysRefreshes() {
        assertTrue(
            MinecraftObservationProvider.decideSemanticRefresh(
                true, false, 0, SEMANTIC_INTERVAL, MAX_STALENESS),
            "a coarse body invalidator must refresh immediately"
        );
        assertTrue(
            MinecraftObservationProvider.decideSemanticRefresh(
                true, true, 0, SEMANTIC_INTERVAL, MAX_STALENESS),
            "a coarse body invalidator refreshes regardless of the dynamic signal"
        );
    }

    @Test
    void dynamicChangeRefreshesOnlyOnFourHertzCadence() {
        // Something changed but fewer than 5 ticks elapsed: hold the line.
        assertFalse(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, true, SEMANTIC_INTERVAL - 1, SEMANTIC_INTERVAL, MAX_STALENESS),
            "a real change still honours the 4 Hz debounce floor"
        );
        assertTrue(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, true, SEMANTIC_INTERVAL, SEMANTIC_INTERVAL, MAX_STALENESS),
            "a real change refreshes once the 4 Hz floor is reached"
        );
        assertTrue(
            MinecraftObservationProvider.decideSemanticRefresh(
                false, true, MAX_STALENESS, SEMANTIC_INTERVAL, MAX_STALENESS),
            "a real change refreshes past the 4 Hz floor"
        );
    }

    // --- dynamicSignalsChanged: the cheap inventory/menu comparison ---

    @Test
    void unchangedMenuAndInventoryAreStill() {
        assertFalse(
            MinecraftObservationProvider.dynamicSignalsChanged(false, 100, false, 100),
            "identical menu + inventory signature must read as unchanged"
        );
    }

    @Test
    void menuOpenDriftIsDetected() {
        assertTrue(
            MinecraftObservationProvider.dynamicSignalsChanged(false, 100, true, 100),
            "opening a menu must be detected"
        );
        assertTrue(
            MinecraftObservationProvider.dynamicSignalsChanged(true, 100, false, 100),
            "closing a menu must be detected"
        );
    }

    @Test
    void inventorySignatureDriftIsDetected() {
        assertTrue(
            MinecraftObservationProvider.dynamicSignalsChanged(false, 100, false, 101),
            "a changed rolling inventory hash must be detected"
        );
        assertFalse(
            MinecraftObservationProvider.dynamicSignalsChanged(false, 101, false, 101),
            "a stable rolling inventory hash must read as unchanged"
        );
    }
}
