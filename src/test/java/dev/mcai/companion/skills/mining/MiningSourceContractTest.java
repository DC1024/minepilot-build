package dev.mcai.companion.skills.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MiningSourceContractTest {
    @Test
    void productionSkillHasNoWorldLookupOrDirectMutationEscapeHatch()
            throws Exception {
        final Path source = Path.of(
                "src/main/java/dev/mcai/companion/skills/mining/"
                    + "ExcavateSafeTunnelSkill.java"
        );
        assertTrue(Files.isRegularFile(source));
        final String text = Files.readString(source);

        for (String forbidden : List.of(
                "import net.minecraft",
                "ServerLevel",
                "ClientLevel",
                "LevelReader",
                ".getBlockState(",
                ".getBlockEntity(",
                ".getChunk(",
                ".setBlock(",
                ".destroyBlock(",
                ".setItem(",
                ".teleport",
                "StructureManager",
                "ChunkPos"
        )) {
            assertFalse(
                    text.contains(forbidden),
                    () -> "Forbidden mining escape hatch: " + forbidden
            );
        }
        assertTrue(text.contains("CoreSkillFrameSource"));
        assertTrue(text.contains("InteractionSkillFrameSource"));
        assertTrue(text.contains("beginMining("));
        assertTrue(text.contains("useOnBlock("));
        assertTrue(text.contains("coreActuator.move("));
    }

    @Test
    void productionRuntimeRegistersAndPublishesTheMiningSkill()
            throws Exception {
        final String runtime = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                    + "CompanionRuntime.java"
        ));
        assertTrue(runtime.contains("MiningSkills.registerAll("));
        assertTrue(runtime.contains("+ MiningSkills.plannerGuide()"));
        assertTrue(runtime.indexOf("MiningSkills.registerAll(")
                < runtime.indexOf("new SkillSupervisor("));
    }
}
