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
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Saves only the companion's current, server-observed position.
 */
public final class RememberWaypointSkill
    implements Skill<RememberWaypointParameters> {
    private static final Set<String> ARGUMENTS = Set.of(
        "name",
        "category"
    );

    private final UUID worldId;
    private final UUID creatorId;
    private final CurrentPositionSource positions;
    private final WaypointWriter writer;
    private final BooleanSupplier writesAllowed;
    private Waypoint pendingWaypoint;
    private CompletionStage<Void> pending;
    private long boundSession = -1;
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;

    public RememberWaypointSkill(
        final UUID worldId,
        final UUID creatorId,
        final CurrentPositionSource positions,
        final WaypointWriter writer,
        final BooleanSupplier writesAllowed
    ) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.creatorId = Objects.requireNonNull(
            creatorId,
            "creatorId"
        );
        this.positions = Objects.requireNonNull(
            positions,
            "positions"
        );
        this.writer = Objects.requireNonNull(writer, "writer");
        this.writesAllowed = Objects.requireNonNull(
            writesAllowed,
            "writesAllowed"
        );
    }

    @Override
    public SkillParameterParser<RememberWaypointParameters> parameters() {
        return RememberWaypointSkill::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        if (!writesAllowed.getAsBoolean()) {
            return Optional.of(SkillFailure.of(
                "remember_waypoint.evaluation_locked"
            ));
        }
        return positions.current().isPresent()
            ? Optional.empty()
            : Optional.of(SkillFailure.of(
                "remember_waypoint.body_unavailable"
            ));
    }

    @Override
    public void start(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        if (!writesAllowed.getAsBoolean()) {
            throw new IllegalStateException(
                "Waypoint writes became locked"
            );
        }
        final ObservedCurrentPosition position = positions.current()
            .orElseThrow(() -> new IllegalStateException(
                "Companion position became unavailable"
            ));
        boundSession = position.sessionGeneration();
        final Instant now = Instant.now();
        final Waypoint waypoint = new Waypoint(
            UUID.randomUUID(),
            worldId,
            position.dimension(),
            new WaypointPoint(
                position.x(),
                position.y(),
                position.z()
            ),
            parameters.name(),
            Set.of(),
            parameters.category(),
            creatorId,
            "ai_current_position",
            WaypointProvenance.AI_DIRECT_OBSERVATION,
            1.0,
            0,
            WaypointStatus.ACTIVE,
            now,
            now,
            Optional.of(now),
            Optional.empty()
        );
        pendingWaypoint = waypoint;
        pending = null;
        phase = Phase.READY;
        failure = null;
    }

    @Override
    public SkillTickResult tick(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        if (phase != Phase.READY && phase != Phase.WAITING) {
            return SkillTickResult.failed(
                "remember_waypoint.invalid_state"
            );
        }
        if (phase == Phase.READY) {
            final Optional<ObservedCurrentPosition> current =
                positions.current();
            if (current.isEmpty()
                || current.orElseThrow().sessionGeneration()
                    != boundSession) {
                return fail("remember_waypoint.session_changed");
            }
            if (!writesAllowed.getAsBoolean()) {
                return fail("remember_waypoint.evaluation_locked");
            }
            pending = Objects.requireNonNull(
                writer.write(Objects.requireNonNull(pendingWaypoint)),
                "waypoint write"
            );
            phase = Phase.WAITING;
            return SkillTickResult.running(true, false);
        }
        Objects.requireNonNull(pending, "pending");
        final var future = pending.toCompletableFuture();
        if (!future.isDone()) {
            return SkillTickResult.running(false, false);
        }
        try {
            future.join();
        } catch (RuntimeException exception) {
            return fail("remember_waypoint.write_failed");
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        return new SkillCheckpoint(
            1,
            """
            {"phase":"%s","nameLength":%d,"categoryLength":%d,\
            "session":%d}
            """.formatted(
                phase.name(),
                parameters.name().codePointCount(
                    0,
                    parameters.name().length()
                ),
                parameters.category().codePointCount(
                    0,
                    parameters.category().length()
                ),
                boundSession
            ).strip()
        );
    }

    @Override
    public void cancel(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        if (pending != null) {
            pending.toCompletableFuture().cancel(true);
        }
        pendingWaypoint = null;
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
        final SkillContext context,
        final RememberWaypointParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                SkillFailure.of("remember_waypoint.invalid_state")
            );
        };
    }

    static SkillParameterResult<RememberWaypointParameters> parse(
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
                new RememberWaypointParameters(
                    values.get("name"),
                    values.get("category")
                )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private SkillTickResult fail(final String code) {
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static SkillParameterResult<RememberWaypointParameters> invalid() {
        return SkillParameterResult.invalid(
            "remember_waypoint.invalid_arguments"
        );
    }

    private enum Phase {
        IDLE,
        READY,
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
