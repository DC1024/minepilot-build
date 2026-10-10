package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Prevents the focused physics fixture from manufacturing a client contact
 * bit after teleporting between course segments.
 */
final class FocusedParkourFixtureSourceContractTest {
    @Test
    void everyCourseWaitsForVanillaGroundContactBeforeStarting()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        ));

        assertFalse(source.contains("player.setOnGround(true)"));
        assertTrue(source.contains("PARKOUR_STABLE_TICKS = 3"));
        assertTrue(source.contains("SETTLING_FOR_PARKOUR"));
        assertTrue(source.contains(
            "SETTLING_FOR_PARKOUR_LONG_GAP"
        ));
        assertTrue(source.contains(
            "SETTLING_FOR_PARKOUR_TURNING_UP"
        ));
        assertTrue(source.contains(
            "settledOnParkourSupport("
        ));
        assertTrue(source.contains(
            "\"Parkour semantic frame preceded vanilla landing\""
        ));
    }
}
