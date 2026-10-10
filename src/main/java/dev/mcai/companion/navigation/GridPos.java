package dev.mcai.companion.navigation;

public record GridPos(int x, int y, int z) implements Comparable<GridPos> {
    public GridPos offset(int deltaX, int deltaY, int deltaZ) {
        return new GridPos(
            Math.addExact(x, deltaX),
            Math.addExact(y, deltaY),
            Math.addExact(z, deltaZ)
        );
    }

    public GridPos above() {
        return offset(0, 1, 0);
    }

    public GridPos above(int amount) {
        return offset(0, amount, 0);
    }

    public GridPos below() {
        return offset(0, -1, 0);
    }

    public long manhattanDistance(GridPos other) {
        if (other == null) {
            throw new IllegalArgumentException("Other position must not be null");
        }
        return absoluteDifference(x, other.x)
            + absoluteDifference(y, other.y)
            + absoluteDifference(z, other.z);
    }

    public double euclideanDistance(GridPos other) {
        if (other == null) {
            throw new IllegalArgumentException("Other position must not be null");
        }
        final double deltaX = (double) x - other.x;
        final double deltaY = (double) y - other.y;
        final double deltaZ = (double) z - other.z;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }

    @Override
    public int compareTo(GridPos other) {
        int comparison = Integer.compare(x, other.x);
        if (comparison == 0) {
            comparison = Integer.compare(y, other.y);
        }
        if (comparison == 0) {
            comparison = Integer.compare(z, other.z);
        }
        return comparison;
    }

    private static long absoluteDifference(int first, int second) {
        return Math.abs((long) first - second);
    }
}
