package dev.mcai.companion.skills.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExploreForObservedTargetSkillSourceContractTest {
    @Test
    void segmentEndpointsUseBoundedFirstPersonCameraScan()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/skills/exploration/"
                + "ExploreForObservedTargetSkill.java"
        ));

        assertTrue(source.contains(
            "private static final float[] SCAN_YAW_OFFSETS"
        ));
        assertTrue(source.contains(
            "private static final float[] SCAN_PITCHES"
        ));
        assertTrue(source.contains(
            "scanBaseYaw = yawOf(frame.lookDirection())"
        ));
        assertTrue(source.contains(
            "actuator.look(new LookIntent("
        ));
        assertTrue(source.contains(
            "ExploreForObservedTargetSkill::targetVisible"
        ));
        assertTrue(source.contains(
            "if (targetVisibility.test(frame, parameters))"
        ));
        assertFalse(source.contains("findNearestMapStructure"));
        assertFalse(source.contains("getChunk("));
        assertFalse(source.contains("setPos("));
        assertFalse(source.contains("teleport"));
    }
}
