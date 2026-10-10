package dev.mcai.companion.skin;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record SkinSpec(
    String sha256,
    ArmType armType,
    SkinFallback fallback
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public SkinSpec {
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        armType = Objects.requireNonNull(armType, "armType");
        fallback = Objects.requireNonNull(fallback, "fallback");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 lowercase hexadecimal characters");
        }
    }
}
