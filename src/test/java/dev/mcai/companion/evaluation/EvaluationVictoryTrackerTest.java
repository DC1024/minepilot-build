package dev.mcai.companion.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EvaluationVictoryTrackerTest {
    @Test
    void lockedEvaluationAllowsOnlyExplicitReadOnlyOperations() {
        assertTrue(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand(
                EvaluationVictoryTracker.normalizeCommand(
                    " /MCAI   GOAL   STATUS "
                )
            ));
        assertTrue(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("save-all flush"));
        assertTrue(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("stop"));

        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("give @a diamond 64"));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand(
                "execute as @a run setblock 0 64 0 diamond_block"
            ));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("mcai goal cancel"));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("seed"));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("stop now"));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("save-all flush extra"));
        assertFalse(EvaluationVictoryTracker
            .isReadOnlyEvaluationCommand("mcai goal status extra"));
    }

    @Test
    void endReturnRequiresVanillaEndConqueredRespawnEvidence()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/evaluation/"
                + "EvaluationVictoryTracker.java"
        ));

        assertTrue(source.contains("event.isEndConquered()"));
        assertFalse(source.contains("PlayerChangedDimensionEvent"));
        assertTrue(source.contains("dragon.getKillCredit()"));
        assertTrue(source.contains("server.isHardcore()"));
        assertTrue(source.contains("Difficulty.HARD"));
        assertTrue(source.contains("GameRules.KEEP_INVENTORY"));
        assertTrue(source.contains("GameRules.RANDOM_TICK_SPEED"));
        assertTrue(source.contains("SessionState.PREPARING"));
        assertTrue(source.contains("data.bodyEverSpawned()"));
    }
}
