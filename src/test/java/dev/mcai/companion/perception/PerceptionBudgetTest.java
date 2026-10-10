package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PerceptionBudgetTest {
    @Test
    void defaultBudgetIsInternallyConsistent() {
        PerceptionBudget budget = PerceptionBudget.defaults();

        assertEquals(35, budget.maxBlockRays());
        new ObservationBudgetUsage(
                64,
                32,
                64,
                35,
                16,
                24,
                8,
                true,
                true,
                true,
                true
        ).validateAgainst(budget);
    }

    @Test
    void rejectsUnboundedOrInconsistentBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionBudget(
                        65.0,
                        110.0,
                        64,
                        32,
                        16,
                        24.0,
                        100.0,
                        70.0,
                        7,
                        5,
                        24,
                        8.0,
                        8
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionBudget(
                        32.0,
                        110.0,
                        8,
                        16,
                        4,
                        24.0,
                        100.0,
                        70.0,
                        7,
                        5,
                        24,
                        8.0,
                        8
                )
        );
    }

    @Test
    void rejectsUsageBeyondDeclaredLimit() {
        PerceptionBudget budget = PerceptionBudget.defaults();
        ObservationBudgetUsage usage = new ObservationBudgetUsage(
                65,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false
        );

        assertThrows(IllegalArgumentException.class, () -> usage.validateAgainst(budget));
    }
}
