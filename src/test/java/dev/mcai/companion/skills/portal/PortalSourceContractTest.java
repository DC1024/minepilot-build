package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PortalSourceContractTest {
    @Test
    void productionFrameAndSkillContainNoPrivilegedMovementOrWorldLookup()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/portal/"
                        + "ServerPortalSkillFrameSource.java"
        )) + Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/portal/"
                        + "EnterObservedPortalSkill.java"
        ));

        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".absSnapTo("));
        assertFalse(source.contains(".teleport"));
        assertFalse(source.contains(".setBlock("));
        assertFalse(source.contains(".destroyBlock("));
        assertFalse(source.contains(".getChunk("));
        assertFalse(source.contains(".findNearestMapStructure("));
    }
}
