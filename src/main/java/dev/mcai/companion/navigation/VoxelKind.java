package dev.mcai.companion.navigation;

public enum VoxelKind {
    AIR(true, false, false, false, 0.0),
    SOLID(false, true, false, false, 0.0),
    WATER(true, false, true, false, 0.01),
    LAVA(true, false, true, false, 1.0),
    CLIMBABLE(true, false, false, true, 0.0),
    OPEN_DOOR(true, false, false, false, 0.0),
    CLOSED_DOOR(false, false, false, false, 0.0);

    private final boolean passable;
    private final boolean supportsWeight;
    private final boolean liquid;
    private final boolean climbable;
    private final double intrinsicDanger;

    VoxelKind(
        boolean passable,
        boolean supportsWeight,
        boolean liquid,
        boolean climbable,
        double intrinsicDanger
    ) {
        this.passable = passable;
        this.supportsWeight = supportsWeight;
        this.liquid = liquid;
        this.climbable = climbable;
        this.intrinsicDanger = intrinsicDanger;
    }

    public boolean isPassable() {
        return passable;
    }

    public boolean supportsWeight() {
        return supportsWeight;
    }

    public boolean isLiquid() {
        return liquid;
    }

    public boolean isClimbable() {
        return climbable;
    }

    public double intrinsicDanger() {
        return intrinsicDanger;
    }
}
