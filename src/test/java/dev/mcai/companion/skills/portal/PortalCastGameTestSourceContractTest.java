package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PortalCastGameTestSourceContractTest {
    @Test
    void activeGateUsesFairFramesAndProductionActuatorsOnly()
            throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/skills/portal/"
                + "PortalCastGameTests.java"
        ));
        final int boundary = source.indexOf(
            "private void settle()"
        );
        final int setup = source.indexOf(
            "private void prepareFixture()"
        );
        final String active = source.substring(boundary);

        assertTrue(setup >= 0 && setup < boundary);
        assertTrue(source.contains("new FairPerceptionSampler()"));
        assertTrue(source.contains(
            "new ServerOwnedCoreSkillActuator("
        ));
        assertTrue(source.contains(
            "new ServerOwnedInteractionSkillActuator("
        ));
        assertTrue(source.contains(
            "new ServerInventorySkillActuator("
        ));
        assertTrue(source.contains(
            "new CastObservedNetherPortalSkill("
        ));
        assertTrue(source.contains(
            "Stats.ITEM_USED.get(item)"
        ));
        assertTrue(source.contains(
            "Stats.BLOCK_MINED.get(block)"
        ));
        assertFalse(active.contains(".setBlockAndUpdate("));
        assertFalse(active.contains(".setBlock("));
        assertFalse(active.contains(".destroyBlock("));
        assertFalse(active.contains(".setItem("));
        assertFalse(active.contains("helper.setBlock"));
        assertFalse(active.contains("helper.give"));
    }

    @Test
    void gateIsDataDrivenRegisteredAndReleaseExcluded()
            throws Exception {
        final String instance = Files.readString(Path.of(
            "src/main/resources/data/mcai_companion/test_instance/"
                + "real_portal_cast_and_light.json"
        ));
        final String registrar = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/gametest/"
                + "GameTestRegistrar.java"
        ));
        final String build = Files.readString(Path.of("build.gradle"));

        assertTrue(instance.contains(
            "\"function\": "
                + "\"mcai_companion:real_portal_cast_and_light\""
        ));
        assertTrue(registrar.contains(
            "\"dev.mcai.companion.skills.portal.PortalCastGameTests\""
        ));
        assertTrue(build.contains(
            "exclude 'dev/mcai/companion/skills/portal/"
                + "PortalCastGameTests*.class'"
        ));
    }
}
