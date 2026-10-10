package dev.mcai.companion.skills.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecordProgressSkillTest {
    @Test
    void recordsOneBoundedNoteWithoutCopyingItIntoCheckpoint() {
        final List<String> stored = new ArrayList<>();
        final RecordProgressSkill skill = new RecordProgressSkill(
            (revision, note) -> stored.add(revision + ":" + note)
        );
        final var parsed = skill.parameters().parse(List.of(
            new SkillArgument("note", "已经补种小麦，下一步刷两组石头")
        ));
        final var parameters = parsed.value().orElseThrow();
        final SkillContext context = new SkillContext(
            7, 11, 100, false, true, 0.0
        );

        skill.start(context, parameters);
        assertEquals(
            SkillTickResult.Status.COMPLETED,
            skill.tick(context, parameters).status()
        );
        assertEquals(
            List.of("7:已经补种小麦，下一步刷两组石头"),
            stored
        );
        assertEquals(
            SkillResult.Status.COMPLETED,
            skill.result(context, parameters).status()
        );
        assertFalse(
            skill.checkpoint(context, parameters)
                .payload()
                .contains("小麦")
        );
    }

    @Test
    void rejectsExtraArgumentsAndOversizedNotes() {
        final RecordProgressSkill skill = new RecordProgressSkill(
            (revision, note) -> {
            }
        );
        assertTrue(skill.parameters().parse(List.of()).failure().isPresent());
        assertTrue(skill.parameters().parse(List.of(
            new SkillArgument("note", "x".repeat(257))
        )).failure().isPresent());
    }
}
