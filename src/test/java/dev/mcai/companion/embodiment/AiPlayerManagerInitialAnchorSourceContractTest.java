package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the one-time first-human anchor boundary tied to survival ownership.
 *
 * <p>The actual lifecycle is exercised by the Forge GameTest.  This small
 * source contract protects the ordering from being weakened while the
 * headless player lifecycle is refactored: an emergency-owned body must stay
 * in place, and a deferred login must be retried only through the normal
 * server-tick reconciliation path.</p>
 */
final class AiPlayerManagerInitialAnchorSourceContractTest {
    @Test
    void initialAnchorReplacementCannotPreemptEmergencyOwnership()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/embodiment/AiPlayerManager.java"
        ));

        final int replaceGate = source.indexOf(
                "private boolean canReplaceInitialBody()"
        );
        final int survivalState = source.indexOf(
                ".survival().state()",
                replaceGate
        );
        final int emergencyClaim = source.indexOf(
                "BehaviorArbiter.Lane.EMERGENCY_SURVIVAL",
                survivalState
        );
        final int retry = source.indexOf(
                "private boolean tryDeferredInitialAnchor()"
        );
        final int normalReanchor = source.indexOf(
                "reanchorInitialBodyNear(currentAnchor)",
                retry
        );

        assertTrue(replaceGate >= 0);
        assertTrue(
                survivalState > replaceGate,
                "initial anchor must inspect the real survival state"
        );
        assertTrue(
                emergencyClaim > survivalState,
                "initial anchor must inspect emergency arbiter ownership"
        );
        assertTrue(
                retry >= 0 && normalReanchor > retry,
                "a deferred login must retry through server-tick reanchor"
        );
        assertTrue(
                source.contains("private SafeCompanionSpawnLocator.Anchor deferredInitialAnchor")
                    && source.contains("requestSpawn(Optional.of(anchor))"),
                "the validated anchor must survive a same-tick human "
                    + "disconnect instead of relying only on a live UUID"
        );
        assertTrue(
                source.contains("body.level() != anchor.level()")
                    && source.contains("markBodyAnchored()")
                    && source.contains(
                        "initial_anchor_claimed_current_dimension"
                    ),
                "a first human in another dimension must not move an active "
                    + "body across dimensions"
        );
        final int remove = source.indexOf(
                "final OperationResult removed = requestRemove()"
        );
        final int safePlacementCheck = source.indexOf(
                "SafeCompanionSpawnLocator.locate(validatedAnchor)"
        );
        assertTrue(
                safePlacementCheck >= 0 && safePlacementCheck < remove,
                "the body must be retained when the login anchor has no "
                    + "bounded vanilla-safe placement"
        );
    }
}
