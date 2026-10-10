package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Protects the production-runtime precondition of the long embodiment chain.
 * An idle goal makes the runtime quiesce leased controls; that can erase a
 * one-frame jump after the skill accepted it and produce a false physics
 * failure such as {@code tower_up.jump_did_not_rise}.
 */
final class EmbodimentGameTestSourceContractTest {
    @Test
    void fullSkillChainOwnsARunningGoalForItsWholeLifetime()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        ));
        final int launchStart = source.indexOf(
            "helper.runAtTickTime(310, () -> {"
        );
        final int launchEnd = source.indexOf(
            "private enum LifecycleGateStage",
            launchStart
        );

        assertTrue(launchStart >= 0 && launchEnd > launchStart);
        final String launch = source.substring(launchStart, launchEnd);
        final int goal = launch.indexOf("runtime.goals().setGoal(");
        final int scenario =
            launch.indexOf("new IntegratedSkillScenario(");
        assertTrue(goal >= 0 && scenario > goal);
        assertTrue(launch.contains("GoalSource.RECOVERY"));
        assertTrue(launch.contains("== GoalStatus.RUNNING"));

        assertTrue(source.contains(
            "\"integrated_test_cleanup\""
        ));
        assertTrue(source.contains(
            "\"Integrated production skill scenario requires a \""
        ));
        assertTrue(source.contains("+ \"running goal\""));
    }
}
