package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FocusedWaterFixtureSourceContractTest {
    @Test
    void focusedWaterWaitsForConsecutiveVanillaGroundFrames()
            throws IOException {
        final String gameTest = read(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        );
        final String preparation = between(
            gameTest,
            "private void prepareFocusedWaterStart()",
            "private void tickFocusedWaterLedgeSettlement()"
        );
        assertTrue(preparation.contains(
            "prepareDeliberateWaterClutchFixture()"
        ));
        assertTrue(preparation.contains(
            "enter(Stage.SETTLING_FOR_FOCUSED_WATER)"
        ));
        assertFalse(preparation.contains("startSkill("));

        final String settling = between(
            gameTest,
            "private void tickFocusedWaterLedgeSettlement()",
            "private void prepareDeliberateWaterClutchDescent()"
        );
        assertTrue(settling.contains("player.onGround()"));
        assertTrue(settling.contains("!player.isInWater()"));
        assertTrue(settling.contains(
            "focusedWaterStableTicks++"
        ));
        assertTrue(settling.contains(
            "< FOCUSED_WATER_STABLE_TICKS"
        ));
        assertTrue(settling.contains(
            ".get(\"onGround\")"
        ));
        assertTrue(settling.contains(
            "startDeliberateWaterClutchDescent(observation)"
        ));
        assertFalse(settling.contains(".setOnGround("));
    }

    @Test
    void productionStillRequiresAStableDryLedge()
            throws IOException {
        final String production = read(
            "src/main/java/dev/mcai/companion/skills/bridging/"
                + "WaterClutchDescendSkill.java"
        );
        assertTrue(production.contains(
            "if (!frame.onGround() || frame.inWater())"
        ));
        assertTrue(production.contains(
            "NAME + \".stable_dry_ledge_required\""
        ));
    }

    @Test
    void fullChainAlsoSettlesBeforeStartingWaterDescent()
            throws IOException {
        final String gameTest = read(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        );
        final String fullPreparation = between(
            gameTest,
            "private void prepareDeliberateWaterClutchDescent()",
            "private void prepareDeliberateWaterClutchFixture()"
        );
        assertTrue(fullPreparation.contains(
            "prepareDeliberateWaterClutchFixture()"
        ));
        assertTrue(fullPreparation.contains(
            "enter(Stage.SETTLING_FOR_FOCUSED_WATER)"
        ));
        assertFalse(fullPreparation.contains("startSkill("));
        assertFalse(fullPreparation.contains(
            "startDeliberateWaterClutchDescent(freshObservation())"
        ));
    }

    @Test
    void laterWaterFixtureClearsOnlyItsOldBridgeExitWall()
            throws IOException {
        final String gameTest = read(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        );
        final String fixture = between(
            gameTest,
            "private void prepareDeliberateWaterClutchFixture()",
            "private void startDeliberateWaterClutchDescent("
        );
        assertTrue(fixture.contains(
            "for (int y = 0; y <= 1; y++)"
        ));
        assertTrue(fixture.contains(
            "landing.offset(1, y, 0)"
        ));
        assertTrue(fixture.contains(
            "landing.below()"
        ));
        assertFalse(fixture.contains(
            "origin.above(y)"
        ));

        final String production = read(
            "src/main/java/dev/mcai/companion/skills/bridging/"
                + "WaterClutchDescendSkill.java"
        );
        assertTrue(production.contains(
            "landing_exit_corridor_obstructed"
        ));
    }

    private static String read(final String path)
            throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String between(
            final String source,
            final String start,
            final String end
    ) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
