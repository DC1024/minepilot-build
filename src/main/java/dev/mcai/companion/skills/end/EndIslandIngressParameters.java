package dev.mcai.companion.skills.end;

/**
 * Local-only budgets for reaching the central End island.
 *
 * <p>The model-facing skill is parameterless. In particular, a provider may
 * not choose a destination, bridge length, or hidden landfall coordinate.</p>
 */
public record EndIslandIngressParameters(
        double maximumStartRadius,
        double arenaReadyRadius,
        int maximumBridgeBlocks,
        int maximumTowerBlocks,
        int maximumMinedBlocks,
        double maximumVisibleLandfallDistance,
        int maximumScanTurns,
        int maximumChildFailures,
        int timeoutTicks
) {
    private static final EndIslandIngressParameters DEFAULTS =
            new EndIslandIngressParameters(
                    EndArenaTopology.MAXIMUM_INGRESS_START_RADIUS,
                    EndArenaTopology.ARENA_READY_RADIUS,
                    48,
                    16,
                    128,
                    10.0,
                    160,
                    8,
                    12_000
            );

    public EndIslandIngressParameters {
        if (!Double.isFinite(maximumStartRadius)
                || maximumStartRadius < 100.0
                || maximumStartRadius > 128.0
                || !Double.isFinite(arenaReadyRadius)
                || arenaReadyRadius < 48.0
                || arenaReadyRadius > 64.0
                || arenaReadyRadius >= maximumStartRadius
                || maximumBridgeBlocks < 1
                || maximumBridgeBlocks > 64
                || maximumTowerBlocks < 1
                || maximumTowerBlocks > 32
                || maximumBridgeBlocks + maximumTowerBlocks > 64
                || maximumMinedBlocks < 1
                || maximumMinedBlocks > 128
                || !Double.isFinite(maximumVisibleLandfallDistance)
                || maximumVisibleLandfallDistance < 2.0
                || maximumVisibleLandfallDistance > 12.0
                || maximumScanTurns < 8
                || maximumScanTurns > 256
                || maximumChildFailures < 1
                || maximumChildFailures > 16
                || timeoutTicks < 1_200
                || timeoutTicks > 12_000) {
            throw new IllegalArgumentException(
                    "Invalid End island ingress policy"
            );
        }
    }

    public static EndIslandIngressParameters defaults() {
        return DEFAULTS;
    }

    public static EndIslandIngressParameters localControllerDefaults() {
        return DEFAULTS;
    }
}
