package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the single deliberate bypass of the play-packet listener visible.
 *
 * <p>Every other player action the companion takes is expressed as a play
 * packet. {@code useOnBlock} is the lone exception: an empty main hand writes
 * through {@code ServerPlayerGameMode#useItemOn} directly. That premise could
 * not be verified against decompiled vanilla in this environment, so rather
 * than guess at a fix the exception is pinned instead. A second bypass, or a
 * silent removal of the only one, fails here.</p>
 */
final class FairActuatorExceptionContractTest {
    @Test
    void theEmptyHandBypassRemainsExactlyOnePlace() throws IOException {
        final List<Path> sources = productionSources();
        final List<Path> writes = new ArrayList<>();
        for (final Path source : sources) {
            if (Files.readString(source).contains(".gameMode.useItemOn(")) {
                writes.add(source);
            }
        }

        assertEquals(
                1,
                writes.size(),
                "an empty-hand use is the only action that may skip the play-"
                        + "packet listener; any additional bypass must be "
                        + "argued for, not added quietly. Found: " + writes
        );
        assertTrue(
                writes.get(0).getFileName().toString()
                        .equals("FairPlayerActuator.java"),
                "the bypass belongs to the tick-local actuator, not elsewhere"
        );
    }

    @Test
    void theBypassStatesThatItsPremiseIsUnverified() throws IOException {
        final String source = Files.readString(
                Path.of(
                        "src/main/java/dev/mcai/companion/action/"
                                + "FairPlayerActuator.java"
                )
        );

        assertTrue(
                source.contains("Known deviation"),
                "the class must declare the deviation where a reader looks first"
        );
        assertTrue(
                source.contains("NOT been verified"),
                "the unverified premise must not be restated as a fact"
        );
    }

    private static List<Path> productionSources() throws IOException {
        final Path root = Path.of("src/main/java");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
