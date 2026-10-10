package dev.mcai.companion.perception;

/**
 * Hard limits for one semantic sample. Constructor caps prevent a caller from
 * accidentally turning perception into an unbounded world scan.
 */
public record PerceptionBudget(
        double entityRange,
        double entityFieldOfViewDegrees,
        int maxEntityCandidates,
        int maxEntityLosChecks,
        int maxVisibleEntities,
        double blockRange,
        double blockHorizontalFieldOfViewDegrees,
        double blockVerticalFieldOfViewDegrees,
        int blockRayColumns,
        int blockRayRows,
        int maxVisibleBlockFaces,
        double dangerRange,
        int maxDangerSignals
) {
    public static final double MAX_ENTITY_RANGE = 64.0;
    public static final double MAX_BLOCK_RANGE = 48.0;
    public static final int MAX_ENTITY_CANDIDATES = 256;
    public static final int MAX_ENTITY_LOS_CHECKS = 128;
    public static final int MAX_BLOCK_RAYS = 128;
    public static final int MAX_RESULTS = 64;

    public PerceptionBudget {
        entityRange = positiveBounded(entityRange, MAX_ENTITY_RANGE, "entityRange");
        blockRange = positiveBounded(blockRange, MAX_BLOCK_RANGE, "blockRange");
        dangerRange = positiveBounded(dangerRange, 16.0, "dangerRange");
        angle(entityFieldOfViewDegrees, "entityFieldOfViewDegrees");
        angle(blockHorizontalFieldOfViewDegrees, "blockHorizontalFieldOfViewDegrees");
        angle(blockVerticalFieldOfViewDegrees, "blockVerticalFieldOfViewDegrees");
        boundedCount(maxEntityCandidates, MAX_ENTITY_CANDIDATES, "maxEntityCandidates");
        boundedCount(maxEntityLosChecks, MAX_ENTITY_LOS_CHECKS, "maxEntityLosChecks");
        boundedCount(maxVisibleEntities, MAX_RESULTS, "maxVisibleEntities");
        boundedCount(blockRayColumns, MAX_BLOCK_RAYS, "blockRayColumns");
        boundedCount(blockRayRows, MAX_BLOCK_RAYS, "blockRayRows");
        boundedCount(maxVisibleBlockFaces, MAX_RESULTS, "maxVisibleBlockFaces");
        boundedCount(maxDangerSignals, 32, "maxDangerSignals");

        int blockRays;
        try {
            blockRays = Math.multiplyExact(blockRayColumns, blockRayRows);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Block ray budget overflow", exception);
        }
        if (maxEntityLosChecks > maxEntityCandidates
                || maxVisibleEntities > maxEntityLosChecks
                || blockRays > MAX_BLOCK_RAYS
                || maxVisibleBlockFaces > blockRays
                || dangerRange > entityRange) {
            throw new IllegalArgumentException("Inconsistent perception budget");
        }
    }

    public static PerceptionBudget defaults() {
        return new PerceptionBudget(
                /* A player can visually track the Ender Dragon well beyond
                 * the ordinary hostile-mob lane.  Keep the bounded sampler
                 * within MAX_ENTITY_RANGE so this is still only fair
                 * line-of-sight perception, but do not make a dragon vanish
                 * merely because the body was knocked to the outer island. */
                64.0,
                110.0,
                64,
                32,
                16,
                24.0,
                100.0,
                70.0,
                7,
                5,
                24,
                8.0,
                8
        );
    }

    public int maxBlockRays() {
        return blockRayColumns * blockRayRows;
    }

    private static double positiveBounded(double value, double maximum, String label) {
        value = PerceptionValidation.finite(value, label);
        if (value <= 0.0 || value > maximum) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
        return value;
    }

    private static void angle(double value, String label) {
        value = PerceptionValidation.finite(value, label);
        if (value <= 0.0 || value >= 180.0) {
            throw new IllegalArgumentException(label + " must be in (0, 180)");
        }
    }

    private static void boundedCount(int value, int maximum, String label) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
    }
}
