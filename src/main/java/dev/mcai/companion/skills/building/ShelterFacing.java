package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;

public enum ShelterFacing {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(-1, 0),
    EAST(1, 0);

    private final int stepX;
    private final int stepZ;

    ShelterFacing(int stepX, int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    public GridPos outside(GridPos position) {
        return position.offset(stepX, 0, stepZ);
    }

    public double alignment(double x, double z) {
        return x * stepX + z * stepZ;
    }
}
