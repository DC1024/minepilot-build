package dev.mcai.companion.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class GoalExecutionPlanTest {
    @Test
    void normalizesTypedFoundationTerminalWithMissingRoute() {
        assertEquals(
                GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target.STONE_TOOL_OBTAINED
                ),
                GoalExecutionPlan.fromModelValues(
                        "NONE",
                        "STONE_TOOL_OBTAINED"
                )
        );
        assertEquals(
                GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target
                                .CONTAINER_WOOD_DOOR_PLACED
                ),
                GoalExecutionPlan.fromModelValues(
                        "NONE",
                        "CONTAINER_WOOD_DOOR_PLACED"
                )
        );
    }

    @Test
    void normalizesCompletionOnlyTerminalWithMissingRoute() {
        assertEquals(
                GoalExecutionPlan.completion(
                        GoalExecutionPlan.Target.RETURNED_FROM_END
                ),
                GoalExecutionPlan.fromModelValues(
                        "NONE",
                        "RETURNED_FROM_END"
                )
        );
    }

    @Test
    void retainsStrictRouteTargetCompatibility() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GoalExecutionPlan.fromModelValues(
                        "FOUNDATION",
                        "RETURNED_FROM_END"
                )
        );
    }
}
