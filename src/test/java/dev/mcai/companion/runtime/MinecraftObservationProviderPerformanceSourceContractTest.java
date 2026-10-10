package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the hot-path allocation boundary for the 20 TPS observation bridge.
 * This is a source contract, not a formal Forge performance gate.
 */
class MinecraftObservationProviderPerformanceSourceContractTest {
    @Test
    void criticalBodyComparisonDoesNotRebuildFullFingerprint() throws Exception {
        final Path source = Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "MinecraftObservationProvider.java"
        );
        final String text = Files.readString(
                source,
                StandardCharsets.UTF_8
        );
        final int method = text.indexOf(
                "boolean criticalBodyChanged(final ServerPlayer player)"
        );
        assertTrue(method >= 0, "hot-path comparison method is missing");
        final int end = text.indexOf(
                "private static int airBand(final ServerPlayer player)",
                method
        );
        assertTrue(end > method, "hot-path comparison boundary is missing");
        final String body = text.substring(method, end);
        assertFalse(
                body.contains("withCurrentBody(player)"),
                "20 TPS invalidation must not rebuild copied lists"
        );
        assertTrue(
                body.contains("player.getFoodData().getFoodLevel()"),
                "food remains a coarse invalidation field"
        );
        assertTrue(
                body.contains("coarseCoordinate(player.getX())")
                        && body.contains("coarseCoordinate(player.getZ())"),
                "one-block movement must not invalidate a long model request"
        );
        assertFalse(
                body.contains("player.blockPosition().getX()")
                        || body.contains("player.blockPosition().getZ()"),
                "hot-path movement invalidation must use the coarse cell"
        );
        assertTrue(
                body.contains("player.isOnFire()")
                        && body.contains("player.isInWater()"),
                "body hazards remain coarse invalidators"
        );
    }

    @Test
    void activeSkillKeepsItsBoundEpochAcrossRouteMilestones()
            throws Exception {
        final Path provider = Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "MinecraftObservationProvider.java"
        );
        final String providerText = Files.readString(
                provider,
                StandardCharsets.UTF_8
        );
        assertTrue(
                providerText.contains(
                        "isActive(skill)\n            ? OptionalLong.of(skill.boundWorldRevision())"
                ),
                "an active atomic skill must retain its bound decision epoch"
        );
        assertTrue(
                providerText.contains("stale_world_revision"),
                "the provider must document the stale-world regression it prevents"
        );

        final Path skill = Path.of(
                "src/main/java/dev/mcai/companion/skill/Skill.java"
        );
        final String skillText = Files.readString(
                skill,
                StandardCharsets.UTF_8
        );
        assertTrue(
                skillText.contains("allowsWorldRevisionTransition"),
                "the transition capability must be explicit on Skill"
        );
    }
}
