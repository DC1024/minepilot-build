package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PortalCastSourceContractTest {
    @Test
    void castingHasNoPrivilegedWorldMutationOrHiddenLookup()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/portal/"
                        + "CastObservedNetherPortalSkill.java"
        ));

        for (String forbidden : new String[] {
            ".setBlock(",
            ".destroyBlock(",
            ".setPos(",
            ".teleport",
            ".getChunk(",
            ".getBlockState(",
            ".getFluidState(",
            ".findNearestMapStructure(",
            "ServerLevel",
            "MinecraftServer",
            "BlockGetter"
        }) {
            assertFalse(
                    source.contains(forbidden),
                    () -> "Found privileged production token " + forbidden
            );
        }
        assertTrue(source.contains("interactions.useOnBlock("));
        assertTrue(source.contains("interactions.beginMining("));
        assertTrue(source.contains("inventory.equip("));
        assertTrue(source.contains("observationRevision()"));
        assertTrue(source.contains("stateProperties().get(\"level\")"));
    }
}
