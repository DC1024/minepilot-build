package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the live combat fixture aligned with the production login boundary.
 *
 * <p>The fixture must create its human through a safe position beside the
 * companion. A default vanilla origin is not a valid substitute for a real
 * client spawn and can cause the initial-anchor lifecycle to remove the body
 * before the chat packet reaches the model.</p>
 */
final class LiveModelCombatFixtureSourceContractTest {
    @Test
    void combatChatUsesNearbySafeLoginPosition() throws Exception {
        final String source = Files.readString(
                Path.of(
                        "src/main/java/dev/mcai/companion/communication/"
                                + "LiveModelChatGameTests.java"
                ),
                StandardCharsets.UTF_8
        );
        final int combat = source.indexOf(
                "private void waitForThreatVisibility()"
        );
        final int nextStage = source.indexOf(
                "private void waitForGoal()",
                combat
        );
        assertTrue(combat >= 0, "combat fixture stage must exist");
        assertTrue(nextStage > combat, "combat fixture stages must be ordered");
        final String stage = source.substring(combat, nextStage);
        assertTrue(
                stage.contains("body.position().add(2.0D, 0.0D, 0.0D)"),
                "combat human must join beside the companion"
        );
        assertFalse(
                stage.contains("PlacedHuman.create(helper, runtime)"),
                "combat fixture must not use the unanchored origin"
        );
    }
}
