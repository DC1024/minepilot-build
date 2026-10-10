package dev.mcai.companion.skills.progress;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;

public final class ProgressSkills {
    public static final String RECORD_PROGRESS = "record_progress";

    private ProgressSkills() {
    }

    public static SkillRegistry registerAll(
        final SkillRegistry registry,
        final GoalProgressSink sink
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(sink, "sink");
        return registry.register(
            RECORD_PROGRESS,
            new RecordProgressSkill(sink)
        );
    }

    public static String plannerGuide() {
        return """
            record_progress requires exactly note (at most 256 Unicode code
            points). Use it after a meaningful subtask or irreversible choice
            so a long job can resume after restart. Notes are model-authored
            memory only: they do not prove world state and never authorize an
            action. Keep at most one concise factual note per subtask.
            """;
    }
}
