package dev.mcai.companion.adapter;

import java.util.List;
import java.util.Objects;

public record AdapterCompatibility(
    boolean compatible,
    String code,
    List<String> verifiedContracts
) {
    public AdapterCompatibility {
        code = boundedCode(code);
        verifiedContracts = List.copyOf(
            Objects.requireNonNull(verifiedContracts, "verifiedContracts")
        );
        if (compatible && verifiedContracts.isEmpty()) {
            throw new IllegalArgumentException(
                "A compatible adapter must name at least one verified contract"
            );
        }
    }

    public static AdapterCompatibility incompatible(final String code) {
        return new AdapterCompatibility(false, code, List.of());
    }

    private static String boundedCode(final String value) {
        if (value == null || value.isBlank() || value.length() > 64
            || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid compatibility code");
        }
        return value;
    }
}
