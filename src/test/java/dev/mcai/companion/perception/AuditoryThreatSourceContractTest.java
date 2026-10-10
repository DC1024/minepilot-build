package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level guard for the fair auditory channel.  This test deliberately
 * checks the privacy boundary rather than pretending a headless unit fixture
 * is a real sound/AI gameplay run.
 */
final class AuditoryThreatSourceContractTest {
    @Test
    void hostileSoundCueIsBoundedAndWiredToSemanticRefresh() throws IOException {
        final String frameSource = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/core/"
                        + "ServerCoreSkillFrameSource.java"
        ));
        final String runtime = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/CompanionRuntime.java"
        ));

        assertTrue(frameSource.contains("RECENT_AUDIBLE_SOUND_TICKS = 20L"));
        assertTrue(frameSource.contains("MAX_AUDIBLE_HOSTILE_RANGE = 16.0"));
        assertTrue(frameSource.contains("recordAudibleHostileSound"));
        assertTrue(frameSource.contains(
                "PerceptionProvenance.AUDIBLE_HOSTILE_SOUND"
        ));
        assertTrue(frameSource.contains("source.position()"));
        assertTrue(frameSource.contains("source.isRemoved()"));
        assertTrue(frameSource.contains("delta.lengthSqr() > range * range"));
        assertTrue(runtime.contains("PlayLevelSoundEvent.AtEntity.BUS"));
        assertTrue(runtime.contains("CompanionRuntime::onHostileSound"));
        assertTrue(runtime.contains("event.getEntity().isAlive()"));
        assertTrue(runtime.contains("instanceof net.minecraft.world.entity.monster.Enemy"));
        assertTrue(runtime.contains("audible_hostile_sound"));
    }
}
