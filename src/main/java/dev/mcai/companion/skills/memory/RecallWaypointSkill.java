package dev.mcai.companion.skills.memory;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Non-blocking lookup of explicitly stored waypoint memory.
 */
public final class RecallWaypointSkill
    implements Skill<RecallWaypointParameters> {
    private static final Set<String> ARGUMENTS = Set.of(
        "dimension",
        "query"
    );

    private final WaypointLookup lookup;
    private final WaypointRecallBuffer buffer;
    private CompletionStage<List<Waypoint>> pending;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;

    public RecallWaypointSkill(
        final WaypointLookup lookup,
        final WaypointRecallBuffer buffer
    ) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @Override
    public SkillParameterParser<RecallWaypointParameters> parameters() {
        return RecallWaypointSkill::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        return Optional.empty();
    }

    @Override
    public void start(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        pending = Objects.requireNonNull(
            lookup.search(
                parameters.dimension(),
                parameters.query(),
                WaypointRecallSnapshot.MAXIMUM_MATCHES
            ),
            "waypoint lookup"
        );
        phase = Phase.WAITING;
        failure = null;
    }

    @Override
    public SkillTickResult tick(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        if (phase != Phase.WAITING || pending == null) {
            return SkillTickResult.failed(
                "recall_waypoint.invalid_state"
            );
        }
        final var future = pending.toCompletableFuture();
        if (!future.isDone()) {
            return SkillTickResult.running(false, true);
        }
        final List<Waypoint> results;
        try {
            results = List.copyOf(future.join());
        } catch (RuntimeException exception) {
            failure = SkillFailure.of(
                "recall_waypoint.query_failed"
            );
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        final boolean invalid = results.stream().anyMatch(waypoint ->
            !waypoint.dimension().equals(parameters.dimension())
                || !waypoint.isSearchableAt(java.time.Instant.now())
        );
        if (invalid) {
            failure = SkillFailure.of(
                "recall_waypoint.invalid_result"
            );
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        buffer.publish(
            context.goalRevision(),
            parameters.query(),
            results
        );
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        return new SkillCheckpoint(
            1,
            "{\"phase\":\"%s\",\"queryLength\":%d}".formatted(
                phase.name(),
                parameters.query().codePointCount(
                    0,
                    parameters.query().length()
                )
            )
        );
    }

    @Override
    public void cancel(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        if (pending != null) {
            pending.toCompletableFuture().cancel(true);
        }
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
        final SkillContext context,
        final RecallWaypointParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                SkillFailure.of("recall_waypoint.invalid_state")
            );
        };
    }

    static SkillParameterResult<RecallWaypointParameters> parse(
        final List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != ARGUMENTS.size()) {
            return invalid();
        }
        final Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                || !ARGUMENTS.contains(argument.name())
                || values.putIfAbsent(
                    argument.name(),
                    argument.value()
                ) != null) {
                return invalid();
            }
        }
        try {
            return SkillParameterResult.valid(
                new RecallWaypointParameters(
                    DimensionRef.parse(values.get("dimension")),
                    values.get("query")
                )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static SkillParameterResult<RecallWaypointParameters> invalid() {
        return SkillParameterResult.invalid(
            "recall_waypoint.invalid_arguments"
        );
    }

    private enum Phase {
        IDLE,
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
