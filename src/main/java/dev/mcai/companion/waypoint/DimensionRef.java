package dev.mcai.companion.waypoint;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loader-independent identity for a Minecraft dimension.
 */
public record DimensionRef(String namespace, String path) implements Comparable<DimensionRef> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public static final DimensionRef OVERWORLD = new DimensionRef("minecraft", "overworld");
    public static final DimensionRef NETHER = new DimensionRef("minecraft", "the_nether");
    public static final DimensionRef END = new DimensionRef("minecraft", "the_end");

    public DimensionRef {
        namespace = validatePart(namespace, "namespace", NAMESPACE, 64);
        path = validatePart(path, "path", PATH, 192);
    }

    public static DimensionRef parse(String value) {
        Objects.requireNonNull(value, "value");
        final int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("Dimension must be a namespaced identifier");
        }
        return new DimensionRef(value.substring(0, separator), value.substring(separator + 1));
    }

    public String id() {
        return namespace + ":" + path;
    }

    @Override
    public int compareTo(DimensionRef other) {
        Objects.requireNonNull(other, "other");
        return id().compareTo(other.id());
    }

    @Override
    public String toString() {
        return id();
    }

    private static String validatePart(String value, String label, Pattern pattern, int maximumLength) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty() || value.length() > maximumLength || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid dimension " + label);
        }
        return value;
    }
}
