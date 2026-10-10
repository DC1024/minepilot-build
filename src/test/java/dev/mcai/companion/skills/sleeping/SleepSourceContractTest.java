package dev.mcai.companion.skills.sleeping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class SleepSourceContractTest {
    @Test
    void implementationContainsOnlyOrdinaryBedUseAndNoCheatMutation()
            throws IOException {
        Path directory = Path.of(
                "src/main/java/dev/mcai/companion/skills/sleeping"
        );
        String source;
        try (var paths = Files.list(directory)) {
            source = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(SleepSourceContractTest::read)
                    .collect(Collectors.joining("\n"));
        }

        assertTrue(source.contains("actuator.useOnBlock("));
        assertTrue(source.contains("player.isSleeping()"));
        assertTrue(source.contains("player.getSleepingPos()"));
        assertTrue(source.contains("player.getRespawnConfig()"));
        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".teleport("));
        assertFalse(source.contains(".setRespawnPosition("));
        assertFalse(source.contains(".setDayTime("));
        assertFalse(source.contains(".setGameTime("));
        assertFalse(source.contains(".setWeather"));
        assertFalse(source.contains(".startSleeping("));
        assertFalse(source.contains(".stopSleeping("));
        assertFalse(source.contains(".setBlock("));
        assertFalse(source.contains(".removeBlock("));
        assertFalse(source.contains(".explode("));
        assertFalse(source.contains("ServerPlayerGameMode"));
        assertFalse(source.contains("performCommand"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
