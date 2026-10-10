package dev.mcai.companion.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.mcai.companion.action.AcceptedLowLevelAction;
import dev.mcai.companion.brain.BrainEvent;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.memory.MemoryEvent;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Binds the first accepted low-level body command to the exact model decision
 * that started its skill.
 *
 * <p>Only one event is retained per model-started skill. This is enough to
 * prove the chat-to-action causal chain without writing a 20 Hz unbounded
 * movement log into long-lived worlds. Independent test observers still own
 * dense position/action sampling.</p>
 */
final class RuntimeActionTrace {
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private final MemoryDatabase memory;
    private final Supplier<GoalSnapshot> currentGoal;
    private final Clock clock;

    private ActiveTrace active;

    RuntimeActionTrace(
        final MemoryDatabase memory,
        final Supplier<GoalSnapshot> currentGoal
    ) {
        this(memory, currentGoal, Clock.systemUTC());
    }

    RuntimeActionTrace(
        final MemoryDatabase memory,
        final Supplier<GoalSnapshot> currentGoal,
        final Clock clock
    ) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.currentGoal = Objects.requireNonNull(
            currentGoal,
            "currentGoal"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void observe(final BrainEvent.ModelAudit audit) {
        Objects.requireNonNull(audit, "audit");
        if (audit.stage()
                != BrainEvent.ModelAuditStage.SKILL_STARTED) {
            return;
        }
        active = new ActiveTrace(
            audit.goalRevision(),
            audit.observedWorldRevision(),
            audit.requestId(),
            audit.skillName(),
            false
        );
    }

    void record(
        final ServerOwnedCoreSkillActuator.AcceptedAction action
    ) {
        Objects.requireNonNull(action, "action");
        record(new AcceptedLowLevelAction(
            action.action(),
            action.serverTick(),
            action.outcome().name()
        ));
    }

    void record(final AcceptedLowLevelAction action) {
        Objects.requireNonNull(action, "action");
        final ActiveTrace candidate = active;
        if (candidate == null || candidate.actionRecorded()) {
            return;
        }
        final GoalSnapshot goal = Objects.requireNonNull(
            currentGoal.get(),
            "current goal"
        );
        if (goal.revision() != candidate.goalRevision()) {
            active = null;
            return;
        }

        active = candidate.withActionRecorded();
        final JsonObject payload = new JsonObject();
        payload.addProperty("requestId", candidate.requestId());
        payload.addProperty("skillName", candidate.skillName());
        payload.addProperty("action", action.action());
        payload.addProperty("serverTick", action.serverTick());
        payload.addProperty("outcome", action.outcome());
        memory.appendEvent(new MemoryEvent(
            clock.instant(),
            "low_level_actions_issued",
            "action",
            GSON.toJson(payload),
            candidate.observedWorldRevision(),
            candidate.goalRevision()
        ));
    }

    private record ActiveTrace(
        long goalRevision,
        long observedWorldRevision,
        String requestId,
        String skillName,
        boolean actionRecorded
    ) {
        private ActiveTrace withActionRecorded() {
            return new ActiveTrace(
                goalRevision,
                observedWorldRevision,
                requestId,
                skillName,
                true
            );
        }
    }
}
