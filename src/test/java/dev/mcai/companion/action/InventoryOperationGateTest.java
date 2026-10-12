package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The per-tick container-operation budget must charge whole operations, never
 * individual clicks, and must be readable without side effects so a caller can
 * decline a preflight for free.
 */
final class InventoryOperationGateTest {
    @Test
    void allowsExactlyOneOperationPerTick() {
        final InventoryOperationGate gate = new InventoryOperationGate(1);

        assertTrue(gate.mayStart(100L));
        gate.record(100L);

        assertFalse(
                gate.mayStart(100L),
                "the budget for tick 100 is already spent"
        );
        assertTrue(
                gate.mayStart(101L),
                "the next tick restores the budget"
        );
    }

    @Test
    void askingNeverSpendsBudgetSoARejectedPreflightCostsNothing() {
        final InventoryOperationGate gate = new InventoryOperationGate(1);

        for (int probe = 0; probe < 50; probe++) {
            assertTrue(gate.mayStart(7L));
        }

        assertEquals(0, gate.operationsOn(7L), "probing must not charge");
        gate.record(7L);
        assertFalse(gate.mayStart(7L));
    }

    @Test
    void countsOnlyWithinTheSameTick() {
        final InventoryOperationGate gate = new InventoryOperationGate(2);

        gate.record(1L);
        gate.record(1L);

        assertFalse(gate.mayStart(1L));
        assertEquals(2, gate.operationsOn(1L));
        assertEquals(0, gate.operationsOn(2L), "a later tick starts empty");
    }

    @Test
    void anOutOfOrderTickResetsRatherThanFalselyThrottling() {
        final InventoryOperationGate gate = new InventoryOperationGate(1);
        gate.record(500L);

        assertTrue(
                gate.mayStart(3L),
                "a different tick is a different budget, even going backwards"
        );
    }

    @Test
    void refusesABudgetThatWouldForbidEveryOperation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryOperationGate(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryOperationGate(-1)
        );
    }

    @Test
    void theDeclaredCeilingMatchesWhatTheRuntimeAlreadyDoes() {
        assertEquals(
                1,
                ActionLimits.defaults().maximumContainerOperationsPerTick()
        );
        assertEquals(
                ActionLimits.MAX_CONTAINER_OPERATIONS_PER_TICK,
                ActionLimits.defaults().maximumContainerOperationsPerTick()
        );
    }
}
