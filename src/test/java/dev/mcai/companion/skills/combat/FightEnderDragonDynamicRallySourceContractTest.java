package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the fair dynamic-dragon search boundary. This is a source contract;
 * the natural manager-dragon combat gate remains a separate physical test.
 */
final class FightEnderDragonDynamicRallySourceContractTest {
    @Test
    void rallySearchUsesFreshObservedCellsAndNormalTravelValidation()
            throws Exception {
        final Path source = Path.of(
                "src/main/java/dev/mcai/companion/skills/combat/"
                        + "FightEnderDragonSkill.java"
        );
        final String text = Files.readString(
                source,
                StandardCharsets.UTF_8
        );
        assertTrue(
                text.contains("selectObservedRallyPoint"),
                "dynamic dragon search must have an observed rally selector"
        );
        assertTrue(
                text.contains("NavigationEvidence.hasFreshTraversalClearance")
                        && text.contains(
                                "NavigationEvidence.isFreshStandingSupport"
                        ),
                "rally candidates need fresh clearance and support evidence"
        );
        assertTrue(
                text.contains("EndArenaTopology::insideArenaReadyRadius"),
                "rally movement must stay inside the verified End arena radius"
        );
        assertTrue(
                text.contains(
                        "travel.preconditions(context, travelParameters)"
                ),
                "every observed rally candidate must pass normal TravelTo"
        );
        assertTrue(
                text.contains("localRallyPoint = current.position()"),
                "a completed rally leg must become the next bounded rally"
        );
        assertTrue(
                text.contains("FIGHT_REENTRY_RADIUS")
                        && text.contains(
                                "horizontalRadius(frame.position())"
                        ),
                "a fight outside entity perception must re-enter the "
                        + "observed island route before spending another "
                        + "blind scan"
        );
    }
}
