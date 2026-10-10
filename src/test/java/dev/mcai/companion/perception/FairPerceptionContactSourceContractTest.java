package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the contact reacquisition path explicit and fail-closed.  Contact is
 * a bounded collision cue, not permission to scan arbitrary entities or to
 * bypass the final vanilla attack validation.
 */
final class FairPerceptionContactSourceContractTest {
    @Test
    void contactMayBypassViewConeButMustRemainASeparateProvenance()
            throws Exception {
        final String sampler = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "FairPerceptionSampler.java"
        ));
        final String visibleEntity = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "VisibleEntity.java"
        ));

        assertTrue(sampler.contains("final boolean physicalContact"));
        assertTrue(sampler.contains(
                "!physicalContact && !anyVisualPointInView"
        ));
        assertTrue(sampler.contains(
                "PerceptionGeometry.isInsideViewCone"
        ));
        assertTrue(sampler.contains(
                "physicalContact\n                            ? PerceptionProvenance.PHYSICAL_CONTACT"
        ));
        assertTrue(sampler.contains("FairPlayerActuator"));
        assertTrue(sampler.contains("crosshair, reach and obstruction"));
        assertTrue(visibleEntity.contains(
                "provenance != PerceptionProvenance.PHYSICAL_CONTACT"
        ));
    }
}
