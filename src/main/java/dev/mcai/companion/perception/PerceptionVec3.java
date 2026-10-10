package dev.mcai.companion.perception;

/**
 * Loader-independent immutable vector used at the perception boundary.
 */
public record PerceptionVec3(double x, double y, double z) {
    private static final double NORMAL_EPSILON = 1.0E-12;

    public PerceptionVec3 {
        x = PerceptionValidation.finite(x, "x");
        y = PerceptionValidation.finite(y, "y");
        z = PerceptionValidation.finite(z, "z");
    }

    public PerceptionVec3 add(PerceptionVec3 other) {
        if (other == null) {
            throw new IllegalArgumentException("other is required");
        }
        return new PerceptionVec3(x + other.x, y + other.y, z + other.z);
    }

    public PerceptionVec3 subtract(PerceptionVec3 other) {
        if (other == null) {
            throw new IllegalArgumentException("other is required");
        }
        return new PerceptionVec3(x - other.x, y - other.y, z - other.z);
    }

    public PerceptionVec3 scale(double factor) {
        return new PerceptionVec3(x * factor, y * factor, z * factor);
    }

    public double dot(PerceptionVec3 other) {
        if (other == null) {
            throw new IllegalArgumentException("other is required");
        }
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public PerceptionVec3 normalized() {
        double magnitude = length();
        if (magnitude <= NORMAL_EPSILON) {
            throw new IllegalArgumentException("Cannot normalize a zero-length vector");
        }
        return scale(1.0 / magnitude);
    }
}
