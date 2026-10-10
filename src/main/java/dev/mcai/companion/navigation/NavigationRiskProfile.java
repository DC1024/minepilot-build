package dev.mcai.companion.navigation;

public enum NavigationRiskProfile {
    NORMAL(8.0, 4.0),
    HARDCORE(100.0, 24.0);

    private final double voxelDangerWeight;
    private final double fallDangerWeight;

    NavigationRiskProfile(double voxelDangerWeight, double fallDangerWeight) {
        this.voxelDangerWeight = voxelDangerWeight;
        this.fallDangerWeight = fallDangerWeight;
    }

    public double voxelDangerWeight() {
        return voxelDangerWeight;
    }

    public double fallDangerWeight() {
        return fallDangerWeight;
    }
}
