package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the ordinary-world body admission recovery boundary.  This is a
 * source contract only; it is not a claim that the formal client/model gate
 * has run.
 */
final class CompanionRuntimeEmbodimentRecoverySourceContractTest {
    @Test
    void retriesOnlyFailedAdmissionWithASeparateBoundedBackoff()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "CompanionRuntime.java"
        ));

        assertTrue(source.contains("bodySpawnRetryAfterTick"));
        assertTrue(source.contains(
                "embodiment.state() == SessionState.FAILED"
        ));
        assertTrue(source.contains(
                "!runtime.worldData().hardcoreDead()"
        ));
        assertTrue(source.contains(
                "AiPlayerManager.requestSpawn(server)"
        ));
        assertTrue(source.contains("+ (respawn.accepted() ? 20L : 200L)"));
        assertTrue(source.contains(
                "Retry FAILED (not ABSENT)"
        ));
    }

    /**
     * The unattended path must mirror ensureSpawnNear(): requestSpawn()
     * refuses a FAILED lifecycle with "failed_remove_first", so a recovery
     * attempt that does not retire the failed session first can never be
     * accepted and the world stays body-less until a human logs in.
     */
    @Test
    void retiresTheFailedSessionBeforeRequestingAReplacement()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "CompanionRuntime.java"
        ));

        final int gate = source.indexOf("Retry FAILED (not ABSENT)");
        final int retire = source.indexOf(
                "AiPlayerManager.requestRemove(server);"
        );
        final int admit = source.indexOf(
                "final AiPlayerManager.OperationResult respawn ="
        );

        assertTrue(gate >= 0, "the FAILED-only gate marker must survive");
        assertTrue(
                retire >= 0,
                "unattended recovery must retire the failed session first"
        );
        assertTrue(admit >= 0);
        assertTrue(
                gate < retire && retire < admit,
                "the failed session must be retired inside the FAILED-only "
                        + "branch and before the replacement is requested"
        );
    }
}
