package dev.mcai.companion.modelsetup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EvaluationModelLockTest {
    @Test
    void persistentWorldLockSurvivesLossOfRuntimeReservation() {
        assertFalse(EvaluationModelLock.isLocked(false, false));
        assertTrue(EvaluationModelLock.isLocked(false, true));
        assertTrue(EvaluationModelLock.isLocked(true, false));
        assertTrue(EvaluationModelLock.isLocked(true, true));
    }

    @Test
    void everyExternalModelMutationEntryUsesPersistentLock()
            throws IOException {
        final String setup = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/modelsetup/"
                + "ModelSetupModule.java"
        ));
        final String commands = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/runtime/"
                + "ModelCommands.java"
        ));
        final String runtime = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/runtime/"
                + "CompanionRuntime.java"
        ));
        final String worldData = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/world/"
                + "CompanionWorldData.java"
        ));
        final String modelRuntime = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/runtime/"
                + "ModelRuntime.java"
        ));
        final String bootstrap = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/runtime/"
                + "ModelBootstrapCoordinator.java"
        ));

        assertTrue(
            setup.indexOf("EvaluationModelLock.isLocked")
                < setup.indexOf("runtime.updateProfile")
        );
        assertTrue(commands.contains("EvaluationModelLock.isLocked"));
        assertTrue(runtime.contains("worldData.evaluationModelBaseUrl()"));
        assertTrue(runtime.contains("worldData.evaluationModelName()"));
        assertTrue(
            runtime.indexOf("worldData.evaluationModelBaseUrl()")
                < runtime.indexOf("new ModelRuntime(")
        );
        assertEquals(
            0,
            occurrences(runtime, "model.probeExplicitly()")
        );
        assertEquals(1, occurrences(
            bootstrap,
            "return model.prepareConfiguredProfile();"
        ));
        assertTrue(bootstrap.contains(
            "GoalSource.HARDCORE_EVALUATION"
        ));
        assertTrue(bootstrap.contains(
            "freezeRestoredEvaluationModel"
        ));
        assertTrue(
            modelRuntime.indexOf("!apiKeys.unlockPersisted()")
                < modelRuntime.indexOf("probeFactory.create(")
        );
        assertFalse(worldData.contains("api_key"));
        assertFalse(worldData.contains("apiKey"));
    }

    private static int occurrences(
            final String source,
            final String needle
    ) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
