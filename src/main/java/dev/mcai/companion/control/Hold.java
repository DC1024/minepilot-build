package dev.mcai.companion.control;

import java.util.Objects;

/**
 * Why the companion is resting, and what may wake her.
 *
 * <p>A {@link HaltReason} says what happened. A {@code Hold} says where that
 * leaves her. Collapsing the two is what produced the field bug this table
 * exists to prevent: a terminal goal is not the same as an absent body, and
 * neither is the same as an owner who pressed stop. Only one of those three
 * is ever cleared by a chat message.</p>
 *
 * <p>Each hold answers two independent questions a caller must not
 * guess:</p>
 * <ol>
 *   <li><b>self-clearing</b> — can the companion leave this hold on her own,
 *       with no external event at all? Only {@link #EXTERNAL} qualifies: a
 *       finished or never-owned goal is immediately eligible for a new
 *       objective.</li>
 *   <li><b>curable by owner</b> — does the authorized player's next
 *       instruction release this hold? {@link #DEAD} is never released this
 *       way, and {@link #EXTERNAL} does not need to be.</li>
 * </ol>
 *
 * <p>Both columns are written out explicitly rather than derived, so the
 * table can be read and reviewed as data. Their one invariant — nothing is
 * simultaneously self-clearing and owner-curable, because those are two
 * different exits — is checked in the constructor.</p>
 *
 * <p>The enum is intentionally closed. A new hold is a new architectural
 * claim about who owns the body, and it should require a human decision
 * rather than fit inside an existing constant.</p>
 */
public enum Hold {
    /**
     * The embodiment is gone. No model, message or timer may act until a
     * fresh body exists; a respawn is the only exit. Nothing else in the
     * system is permitted to clear this.
     */
    DEAD(false, false),

    /**
     * Nothing is wrong and nothing is pending: the goal finished, or no
     * owner ever claimed one. The companion may self-propose a new objective
     * immediately, and the ordinary autonomy path is the way out.
     */
    EXTERNAL(true, false),

    /**
     * The owner stopped her, or the control plane withdrew the goal on the
     * owner's behalf. She stays put until that owner speaks again; autonomy
     * must not quietly restart work the owner just cancelled.
     */
    OWNER_STOP(false, true),

    /**
     * No usable model endpoint, or an equivalent external dependency is
     * absent. The goal is still valid — only the ability to plan is
     * missing. Recovery is either the dependency returning or the owner
     * rebinding it.
     */
    BLOCKED(false, true),

    /**
     * This attempt is judged unrecoverable within the current goal. Her own
     * retry loop has already been exhausted, so recovery requires an owner
     * instruction. Distinct from {@link #BLOCKED}: there the dependency is
     * missing, here the plan itself did not work.
     */
    FAILED(false, true);

    private final boolean selfClearing;
    private final boolean curableByOwner;

    Hold(
            final boolean selfClearing,
            final boolean curableByOwner
    ) {
        if (selfClearing && curableByOwner) {
            throw new IllegalStateException(
                    "A hold cannot be both self-clearing and "
                            + "owner-curable: " + name()
            );
        }
        this.selfClearing = selfClearing;
        this.curableByOwner = curableByOwner;
    }

    /**
     * Whether the companion may leave this hold with no external event.
     */
    public boolean selfClearing() {
        return selfClearing;
    }

    /**
     * Whether the authorized owner's next instruction releases this hold.
     */
    public boolean curableByOwner() {
        return curableByOwner;
    }

    /**
     * Whether this hold represents a missing or unusable body rather than a
     * stopped plan. Callers use it to decide whether re-establishing the
     * embodiment is a prerequisite for any other recovery.
     */
    public boolean isBodyRelated() {
        return this == DEAD;
    }

    /**
     * The hold implied by a terminal goal status when no more specific
     * reason is known. Callers holding a {@link HaltReason} must prefer
     * {@link HaltReason#hold()}; this fallback exists so that a bare status
     * never leaves the hold ambiguous.
     */
    public static Hold of(final GoalStatus status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case IDLE, COMPLETED -> EXTERNAL;
            case FAILED -> FAILED;
            case SAFE_IDLE -> OWNER_STOP;
            /*
             * RUNNING and CANCEL_PENDING are not holds: the companion is
             * still working, or still winding down. Reporting a hold here
             * would be a lie, so the caller is told to keep observing.
             */
            case RUNNING, CANCEL_PENDING -> throw new IllegalArgumentException(
                    "A live goal is not in a hold: " + status
            );
        };
    }

    /**
     * Whether a status is a hold at all, without throwing. Prefer this over
     * catching {@link #of(GoalStatus)} for control flow.
     */
    public static boolean isHold(final GoalStatus status) {
        return status != GoalStatus.RUNNING
                && status != GoalStatus.CANCEL_PENDING;
    }
}
