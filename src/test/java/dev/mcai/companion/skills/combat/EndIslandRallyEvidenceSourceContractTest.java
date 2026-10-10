package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks the fresh-sky evidence boundary before a body enters dragon combat. */
final class EndIslandRallyEvidenceSourceContractTest {
    @Test
    void rallyHandoffRejectsFreshlyVisibleLowCeiling() throws Exception {
        final Path source = Path.of(
                "src/main/java/dev/mcai/companion/skills/end/"
                        + "EndIslandRallyEvidence.java"
        );
        final String text = Files.readString(
                source,
                StandardCharsets.UTF_8
        );
        assertTrue(
                text.contains("visibleOverheadObstruction(frame)"),
                "dragon handoff must reject a visible low ceiling"
        );
        assertTrue(
                text.contains("block.y() >= feet.y() + 2")
                        && text.contains("block.y() <= feet.y() + 8"),
                "the ceiling check must stay bounded to fresh first-person "
                        + "evidence"
        );
        assertTrue(
                text.contains("hasFreshSkyObservation")
                        && text.contains("frame.lookDirection().y() >= 0.25"),
                "combat handoff must be preceded by an upward sky observation"
        );
    }
}
