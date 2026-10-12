package dev.mcai.companion.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The single table of every reason this companion stops acting.
 *
 * <p>Before this table, "why did she stop?" was answered in three different
 * places — {@code BrainOrchestrator.terminal}, {@code GoalCoordinator
 * .markTerminal} and the runtime tick — and each caller chose its own status,
 * its own code spelling and its own side effects. A new failure path could be
 * added without anyone noticing that it also needed to halt the body, clear a
 * superseded instruction or release the long-term goal. Nothing forced the
 * author to decide.</p>
 *
 * <p>This enum makes that decision non-optional. Every externally visible stop
 * is one constant here, and every constant must answer the same four
 * questions:</p>
 *
 * <ol>
 *   <li><b>halt body</b> — does the physical actuator stop immediately, or
 *       does an in-flight skill keep its lease until its own safe
 *       checkpoint? {@code false} only for stops that are the skill's own
 *       normal exit.</li>
 *   <li><b>clear superseded instructions</b> — does this stop invalidate
 *       local intent (a pending cancel, a queued instruction) that must not
 *       survive into the next goal?</li>
 *   <li><b>end long-term goal</b> — does the goal itself reach a terminal
 *       state, or is only this attempt abandoned while the goal stays
 *       alive?</li>
 *   <li><b>hold</b> — which {@link Hold} does the companion rest in, and
 *       therefore what is allowed to wake her again?</li>
 * </ol>
 *
 * <p>Two guards keep the table honest, and both are enforced by
 * {@code HaltReasonTableTest}:</p>
 * <ul>
 *   <li>every {@code code} is unique, so one spelling never means two
 *       things;</li>
 *   <li>every termination code that appears in production source is a
 *       member of this table, so a new failure cannot hide in a catch
 *       block.</li>
 * </ul>
 *
 * <p>The table is deliberately data, not behaviour. Callers still own the
 * transitions; this type only guarantees that the answer exists and is the
 * same everywhere it is asked.</p>
 */
public enum HaltReason {
    /*
     * ---- COMPLETED: the goal succeeded. The hold is EXTERNAL because a
     * finished goal releases the body to whoever speaks next; nothing here
     * is blocked or failed.
     */
    SERVER_VERIFIED_COMPLETE(
            "server_verified_complete",
            GoalStatus.COMPLETED,
            true, true, true, Hold.EXTERNAL
    ),
    SERVER_VERIFIED_NEARBY_WOOD_COMPLETE(
            "server_verified_nearby_wood_complete",
            GoalStatus.COMPLETED,
            true, true, true, Hold.EXTERNAL
    ),
    FOUNDATION_ROUTE_VERIFIED(
            "foundation_route_verified",
            GoalStatus.COMPLETED,
            true, true, true, Hold.EXTERNAL
    ),
    DRAGON_KILLED_AND_RETURNED(
            "dragon_killed_and_returned",
            GoalStatus.COMPLETED,
            true, true, true, Hold.EXTERNAL
    ),

    /*
     * ---- CANCEL: the owner, or the machine's own control plane, withdrew
     * the goal. The body halts and superseded instructions are cleared, but
     * the companion is not failed — she is simply unowned again.
     */
    GOAL_CANCELLED(
            "goal_cancelled",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.OWNER_STOP
    ),
    SKILL_SAFE_IDLE(
            "skill_safe_idle",
            GoalStatus.SAFE_IDLE,
            /*
             * The skill already reached its own safe idle; halting the body
             * again would interrupt the very checkpoint this code reports.
             */
            false, true, true, Hold.OWNER_STOP
    ),
    /*
     * The model itself chose SAFE_IDLE, i.e. it declared there is nothing
     * safe or useful left to do for this goal. Unlike SKILL_SAFE_IDLE this
     * is not a physical checkpoint, but the body had already been left idle
     * by the decision, so re-halting it would be redundant.
     */
    MODEL_SAFE_IDLE(
            "model_safe_idle",
            GoalStatus.SAFE_IDLE,
            false, true, true, Hold.OWNER_STOP
    ),
    EVALUATION_REQUIRES_INPUT(
            "evaluation_requires_input",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.OWNER_STOP
    ),
    INVALID_ASK_PLAYER(
            "invalid_ask_player",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.OWNER_STOP
    ),

    /*
     * ---- DEAD: the embodiment is gone. Nothing may reanimate it except a
     * respawn; Hold.DEAD is the only hold that another agent cannot clear.
     */
    BODY_ABSENT(
            "body_absent",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.DEAD
    ),
    EMBODIMENT_FAILED(
            "embodiment_failed",
            GoalStatus.FAILED,
            true, true, true, Hold.DEAD
    ),
    HARDCORE_DEATH(
            "hardcore_death",
            GoalStatus.FAILED,
            true, true, true, Hold.DEAD
    ),

    /*
     * ---- BLOCKED: no usable model endpoint. The goal is not judged
     * invalid; the companion simply cannot plan until the endpoint returns
     * or the owner rebinds it.
     */
    MODEL_GATEWAY_CLOSED(
            "model_gateway_closed",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.BLOCKED
    ),
    MODEL_UNAVAILABLE(
            "model_unavailable",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.BLOCKED
    ),
    MODEL_BOOTSTRAP_INVALID_CONFIGURATION(
            "model_bootstrap_invalid_configuration",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.BLOCKED
    ),

    /*
     * ---- FAILED: this attempt is judged unrecoverable. The distinction
     * from BLOCKED is that repeating the same goal is expected to fail
     * again unless the owner intervenes.
     */
    MODEL_FAILURES_EXHAUSTED(
            "model_failures_exhausted",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    OBSERVATION_UNAVAILABLE(
            "observation_unavailable",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    OBSERVATION_REVISION_MISMATCH(
            "observation_revision_mismatch",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    PLANNER_INPUT_INVALID(
            "planner_input_invalid",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    PLANNER_NO_ACTION_EXHAUSTED(
            "planner_no_action_exhausted",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    RUNTIME_TICK_FAILURE(
            "runtime_tick_failure",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    SKILL_START_FAILURES(
            "skill_start_failures",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    REPEATED_SKILL_FAILURE_WITHOUT_PROGRESS(
            "repeated_skill_failure_without_progress",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    REPEATED_SKILL_REJECTION_WITHOUT_WORLD_CHANGE(
            "repeated_skill_rejection_without_world_change",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    /*
     * The replacement goal asked the previous skill to stop and the skill
     * refused. The replacement is abandoned, not the companion.
     */
    REPLACEMENT_SKILL_CANCEL_REJECTED(
            "replacement_skill_cancel_rejected",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.FAILED
    ),
    SKILL_CANCEL_REJECTED(
            "skill_cancel_rejected",
            GoalStatus.FAILED,
            true, true, true, Hold.FAILED
    ),
    SKILL_CANCEL_FAILED(
            "skill_cancel_failed",
            GoalStatus.FAILED,
            true, true, true, Hold.FAILED
    ),

    /*
     * ---- Evaluation integrity: a locked Hardcore evaluation is void. These
     * are the only reasons that fault the run rather than the attempt, and
     * the hold is FAILED because the same world can never be re-run.
     */
    EVALUATION_OBSERVER_OR_BODY_STATE_CHANGED(
            "evaluation_observer_or_body_state_changed",
            GoalStatus.FAILED,
            true, true, true, Hold.FAILED
    ),
    EVALUATION_COMMAND_ATTEMPTED(
            "evaluation_command_attempted",
            GoalStatus.FAILED,
            true, true, true, Hold.FAILED
    ),

    /*
     * ---- Dynamic family. A frozen evaluation model restore reports a
     * bounded sub-code, so the exact spelling is only known at runtime.
     * The family prefix is registered here so the table still owns the
     * namespace, and resolve() matches it.
     */
    EVALUATION_MODEL_RESTORE(
            "evaluation_model_restore_",
            GoalStatus.SAFE_IDLE,
            true, true, true, Hold.BLOCKED
    );

    private static final Map<String, HaltReason> BY_CODE;

    static {
        final Map<String, HaltReason> index =
                new LinkedHashMap<>();
        for (HaltReason reason : values()) {
            final HaltReason previous = index.put(reason.code, reason);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate halt code: " + reason.code
                );
            }
        }
        BY_CODE = Collections.unmodifiableMap(index);
    }

    private final String code;
    private final GoalStatus status;
    private final boolean haltsBody;
    private final boolean clearsSupersededInstructions;
    private final boolean endsLongTermGoal;
    private final Hold hold;

    HaltReason(
            final String code,
            final GoalStatus status,
            final boolean haltsBody,
            final boolean clearsSupersededInstructions,
            final boolean endsLongTermGoal,
            final Hold hold
    ) {
        this.code = code;
        this.status = Objects.requireNonNull(status, "status");
        this.haltsBody = haltsBody;
        this.clearsSupersededInstructions = clearsSupersededInstructions;
        this.endsLongTermGoal = endsLongTermGoal;
        this.hold = Objects.requireNonNull(hold, "hold");
    }

    public String code() {
        return code;
    }

    public GoalStatus status() {
        return status;
    }

    public boolean haltsBody() {
        return haltsBody;
    }

    public boolean clearsSupersededInstructions() {
        return clearsSupersededInstructions;
    }

    public boolean endsLongTermGoal() {
        return endsLongTermGoal;
    }

    public Hold hold() {
        return hold;
    }

    /**
     * Whether this constant is a prefix family rather than an exact code.
     * Only the dynamic evaluation-restore family currently qualifies.
     */
    public boolean isFamily() {
        return this == EVALUATION_MODEL_RESTORE;
    }

    /**
     * Resolves a production termination code to its table member.
     *
     * <p>Exact codes win; otherwise the longest matching family prefix is
     * used. Returns empty for an unregistered code so callers such as the
     * contract test can fail loudly instead of silently accepting a new
     * undocumented stop.</p>
     */
    public static Optional<HaltReason> resolve(final String rawCode) {
        final String code = Objects.requireNonNullElse(rawCode, "")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        final HaltReason exact = BY_CODE.get(code);
        if (exact != null) {
            return Optional.of(exact);
        }
        HaltReason matched = null;
        for (HaltReason reason : values()) {
            if (!reason.isFamily() || !code.startsWith(reason.code)) {
                continue;
            }
            if (matched == null || reason.code.length() > matched.code.length()) {
                matched = reason;
            }
        }
        return Optional.ofNullable(matched);
    }

    /**
     * Every exact code owned by this table, in declaration order. Family
     * prefixes are excluded because they are not complete codes on their own.
     */
    public static java.util.List<String> exactCodes() {
        final java.util.List<String> codes =
                new java.util.ArrayList<>(values().length);
        for (HaltReason reason : values()) {
            if (!reason.isFamily()) {
                codes.add(reason.code);
            }
        }
        return java.util.List.copyOf(codes);
    }
}
