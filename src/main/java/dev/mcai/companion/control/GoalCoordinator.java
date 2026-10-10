package dev.mcai.companion.control;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serializes high-level goal mutations. The 20 TPS skill controller consumes a
 * snapshot and is responsible for reaching cancellation checkpoints.
 */
public final class GoalCoordinator {
    public static final int MAX_GOAL_CHARACTERS = 4096;
    public static final String HARDCORE_INITIAL_GOAL = "通关 Minecraft";
    public static final String HARDCORE_FOUNDATION_GOAL =
        "建立安全据点并生存到第二天";

    private final GoalRevisionStore revisions;
    private final GoalStateStore persistence;
    private final Clock clock;

    private UUID goalId;
    private GoalStatus status = GoalStatus.IDLE;
    private GoalSource source = GoalSource.RECOVERY;
    private String goal = "";
    private String detailCode = "";
    private Instant updatedAt;
    private boolean externalWritesLocked;

    public GoalCoordinator(final GoalRevisionStore revisions) {
        this(revisions, GoalStateStore.none(), Clock.systemUTC());
    }

    public GoalCoordinator(
        final GoalRevisionStore revisions,
        final GoalStateStore persistence
    ) {
        this(revisions, persistence, Clock.systemUTC());
    }

    GoalCoordinator(
        final GoalRevisionStore revisions,
        final GoalStateStore persistence,
        final Clock clock
    ) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.updatedAt = clock.instant();
        restore();
    }

    public synchronized MutationResult setGoal(
            final String requestedGoal,
            final GoalSource requestedSource
    ) {
        return setGoal(
                requestedGoal,
                requestedSource,
                GoalExecutionPlan.none()
        );
    }

    public synchronized MutationResult setGoal(
            final String requestedGoal,
            final GoalSource requestedSource,
            final GoalExecutionPlan executionPlan
    ) {
        Objects.requireNonNull(requestedSource, "requestedSource");
        Objects.requireNonNull(executionPlan, "executionPlan");
        if (externalWritesLocked) {
            return MutationResult.rejected("evaluation_locked", snapshot());
        }
        final String normalized;
        try {
            normalized = normalizeGoal(requestedGoal);
        } catch (IllegalArgumentException exception) {
            return MutationResult.rejected("invalid_goal", snapshot());
        }

        goalId = UUID.randomUUID();
        goal = normalized;
        source = requestedSource;
        status = GoalStatus.RUNNING;
        detailCode = executionPlan.detailCode();
        revisions.advance();
        updatedAt = clock.instant();
        persist();
        return MutationResult.accepted(snapshot());
    }

    /**
     * Atomic evaluation start: installs the sole command, then permanently
     * disables chat/MCP goal writes for this coordinator instance.
     */
    public synchronized MutationResult startHardcoreEvaluation(final String initialGoal) {
        if (externalWritesLocked || status != GoalStatus.IDLE) {
            return MutationResult.rejected("evaluation_already_started", snapshot());
        }
        final String normalized;
        try {
            normalized = normalizeGoal(initialGoal);
        } catch (IllegalArgumentException exception) {
            return MutationResult.rejected("invalid_goal", snapshot());
        }
        if (!normalized.equals(HARDCORE_INITIAL_GOAL)
                && !normalized.equals(HARDCORE_FOUNDATION_GOAL)) {
            return MutationResult.rejected(
                "invalid_evaluation_goal",
                snapshot()
            );
        }

        goalId = UUID.randomUUID();
        goal = normalized;
        source = GoalSource.HARDCORE_EVALUATION;
        status = GoalStatus.RUNNING;
        detailCode = normalized.equals(HARDCORE_INITIAL_GOAL)
                ? GoalExecutionPlan.completion(
                        GoalExecutionPlan.Target.RETURNED_FROM_END
                ).detailCode()
                : GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target.FIRST_NIGHT_SURVIVED
                ).detailCode();
        externalWritesLocked = true;
        revisions.advance();
        updatedAt = clock.instant();
        persist();
        return MutationResult.accepted(snapshot());
    }

    public synchronized MutationResult requestCancel(final GoalSource requestedSource) {
        Objects.requireNonNull(requestedSource, "requestedSource");
        if (externalWritesLocked) {
            return MutationResult.rejected("evaluation_locked", snapshot());
        }
        if (status != GoalStatus.RUNNING) {
            return MutationResult.rejected("no_running_goal", snapshot());
        }
        status = GoalStatus.CANCEL_PENDING;
        detailCode = "cancel_requested";
        revisions.advance();
        updatedAt = clock.instant();
        persist();
        return MutationResult.accepted(snapshot());
    }

    public synchronized void markTerminal(final GoalStatus terminalStatus, final String code) {
        if (terminalStatus != GoalStatus.COMPLETED
            && terminalStatus != GoalStatus.FAILED
            && terminalStatus != GoalStatus.SAFE_IDLE) {
            throw new IllegalArgumentException("Not a terminal goal status: " + terminalStatus);
        }
        if (status != GoalStatus.RUNNING && status != GoalStatus.CANCEL_PENDING) {
            throw new IllegalStateException("No active goal can be completed");
        }
        status = terminalStatus;
        detailCode = sanitizeCode(code);
        revisions.advance();
        updatedAt = clock.instant();
        persist();
    }

    public synchronized GoalSnapshot snapshot() {
        return new GoalSnapshot(
            Optional.ofNullable(goalId),
            revisions.current(),
            status,
            source,
            goal,
            detailCode,
            updatedAt,
            externalWritesLocked
        );
    }

    private void restore() {
        final Optional<PersistedGoalState> loaded = persistence.load();
        if (loaded.isEmpty()) {
            return;
        }
        final PersistedGoalState state = loaded.orElseThrow();
        if (state.revision() != revisions.current()) {
            throw new IllegalStateException(
                "Persisted goal revision does not match the world revision"
            );
        }
        goalId = state.goalId().orElse(null);
        status = state.status();
        source = state.source();
        goal = state.goal();
        detailCode = state.detailCode();
        updatedAt = state.updatedAt();
        externalWritesLocked = state.externalWritesLocked();
    }

    private void persist() {
        persistence.save(new PersistedGoalState(
            revisions.current(),
            Optional.ofNullable(goalId),
            status,
            source,
            goal,
            detailCode,
            updatedAt,
            externalWritesLocked
        ));
    }

    private static String normalizeGoal(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("goal is required");
        }
        final String normalized = raw.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_GOAL_CHARACTERS) {
            throw new IllegalArgumentException("goal length is invalid");
        }
        for (int index = 0; index < normalized.length(); index++) {
            final char character = normalized.charAt(index);
            if (Character.isISOControl(character)
                && character != '\n'
                && character != '\t') {
                throw new IllegalArgumentException("goal contains control characters");
            }
        }
        return normalized;
    }

    private static String sanitizeCode(final String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        final StringBuilder result = new StringBuilder(Math.min(raw.length(), 64));
        for (int index = 0; index < raw.length() && result.length() < 64; index++) {
            final char character = Character.toLowerCase(raw.charAt(index));
            final boolean safe = character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_'
                || character == '-'
                || character == '.';
            result.append(safe ? character : '_');
        }
        return result.toString();
    }

    public record MutationResult(boolean accepted, String code, GoalSnapshot snapshot) {
        private static MutationResult accepted(final GoalSnapshot snapshot) {
            return new MutationResult(true, "accepted", snapshot);
        }

        private static MutationResult rejected(final String code, final GoalSnapshot snapshot) {
            return new MutationResult(false, code, snapshot);
        }
    }
}
