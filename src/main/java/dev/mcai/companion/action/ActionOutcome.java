package dev.mcai.companion.action;

/**
 * Bounded result vocabulary suitable for audit logs and local skill logic.
 */
public enum ActionOutcome {
    QUEUED(true),
    DISPATCHED(true),
    IN_PROGRESS(true),
    COMPLETED(true),
    NO_ACTIVE_ACTION(false),
    WRONG_THREAD(false),
    PLAYER_UNAVAILABLE(false),
    PLAYER_INCAPACITATED(false),
    INVALID_PLAYER_STATE(false),
    TARGET_NOT_FOUND(false),
    TARGET_UNLOADED(false),
    TARGET_OUT_OF_REACH(false),
    TARGET_OCCLUDED(false),
    TARGET_CHANGED(false),
    WORLD_DENIED(false),
    ITEM_UNAVAILABLE(false),
    ITEM_ON_COOLDOWN(false),
    /**
     * Refused because the per-tick container-operation budget is already
     * spent. The operation did not run and changed nothing, so a caller may
     * retry on a later tick; this is deliberately not reported as an invalid
     * player state, which callers treat as a reason to give up.
     */
    RATE_LIMITED(false),
    TIMED_OUT(false);

    private final boolean accepted;

    ActionOutcome(boolean accepted) {
        this.accepted = accepted;
    }

    public boolean accepted() {
        return accepted;
    }
}
