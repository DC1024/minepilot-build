package dev.mcai.companion.skills.progress;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores a bounded model-authored task note in world SavedData. Notes carry no
 * action authority and are exposed back to the planner as non-authoritative
 * memory, which allows long jobs to survive restarts without growing the full
 * model transcript.
 */
public final class RecordProgressSkill
        implements Skill<ProgressNoteParameters> {
    private final GoalProgressSink sink;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;

    public RecordProgressSkill(final GoalProgressSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public SkillParameterParser<ProgressNoteParameters> parameters() {
        return RecordProgressSkill::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        return Optional.empty();
    }

    @Override
    public void start(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        phase = Phase.READY;
        failure = null;
    }

    @Override
    public SkillTickResult tick(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        if (phase != Phase.READY) {
            return SkillTickResult.failed(
                "record_progress.invalid_state"
            );
        }
        try {
            sink.append(context.goalRevision(), parameters.note());
        } catch (RuntimeException exception) {
            failure = SkillFailure.of("record_progress.store_rejected");
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        return new SkillCheckpoint(
            1,
            "{\"phase\":\""
                + phase.name()
                + "\",\"noteCodePoints\":"
                + parameters.note().codePointCount(
                    0,
                    parameters.note().length()
                )
                + "}"
        );
    }

    @Override
    public void cancel(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
        final SkillContext context,
        final ProgressNoteParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                SkillFailure.of("record_progress.invalid_state")
            );
        };
    }

    private static SkillParameterResult<ProgressNoteParameters> parse(
        final List<SkillArgument> arguments
    ) {
        if (arguments == null
                || arguments.size() != 1
                || arguments.getFirst() == null
                || !"note".equals(arguments.getFirst().name())) {
            return SkillParameterResult.invalid(
                "record_progress.invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(
                new ProgressNoteParameters(
                    arguments.getFirst().value()
                )
            );
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                "record_progress.invalid_arguments"
            );
        }
    }

    private enum Phase {
        IDLE,
        READY,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
