package dev.mcai.companion.action;

/**
 * Per-tick budget for atomic container operations.
 *
 * <p>Movement and rotation are paced by {@link ActionLimits} one tick at a
 * time, but a container transaction is a single indivisible burst of vanilla
 * {@code AbstractContainerMenu#clicked} calls. Each transaction is already
 * bounded in click count by its own parameter record; what nothing bounded is
 * how many whole <em>transactions</em> one server tick may start. This gate
 * supplies that missing ceiling.</p>
 *
 * <p>The budget is charged per operation, never per click. An operation is a
 * complete transaction including any compensating rollback, so splitting one
 * across a tick boundary would leave the cursor holding a stack and a slot
 * half emptied. This gate therefore answers only one question: may a whole
 * operation start on this tick? {@link #mayStart(long)} must be consulted
 * before the first click, and {@link #record(long)} called only once the
 * operation is committed to running.</p>
 *
 * <p>The default budget of one matches what an ordinary player can do: the
 * runtime arbitrates a single behaviour lane per tick, so production already
 * never exceeds it. The gate is a safety rail against a future caller that
 * would, not a pacing device.</p>
 */
public final class InventoryOperationGate {
    private final int maximumOperationsPerTick;
    private long recordedTick = Long.MIN_VALUE;
    private int operationsThisTick;

    public InventoryOperationGate(final int maximumOperationsPerTick) {
        if (maximumOperationsPerTick < 1) {
            throw new IllegalArgumentException(
                    "maximumOperationsPerTick must be at least 1"
            );
        }
        this.maximumOperationsPerTick = maximumOperationsPerTick;
    }

    /**
     * True when one more operation may start on the supplied server tick.
     *
     * <p>This is deliberately free of side effects so a caller can decline a
     * preflight without spending budget.</p>
     */
    public boolean mayStart(final long serverTick) {
        if (recordedTick != serverTick) {
            return true;
        }
        return operationsThisTick < maximumOperationsPerTick;
    }

    /** Charges one operation to the supplied server tick. */
    public void record(final long serverTick) {
        if (recordedTick != serverTick) {
            recordedTick = serverTick;
            operationsThisTick = 0;
        }
        operationsThisTick++;
    }

    /** Operations already charged to the supplied server tick. */
    public int operationsOn(final long serverTick) {
        return recordedTick == serverTick ? operationsThisTick : 0;
    }
}
