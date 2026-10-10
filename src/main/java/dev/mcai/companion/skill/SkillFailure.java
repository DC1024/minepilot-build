package dev.mcai.companion.skill;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A bounded, log-safe failure identifier.
 *
 * <p>Failures deliberately contain no exception message, world text, player
 * text, provider body, or credential. Skill implementations must use stable
 * identifiers rather than putting dynamic data in a failure code.</p>
 */
public record SkillFailure(String code) {
    public static final int MAX_CODE_CHARACTERS = 64;
    private static final Pattern SAFE_CODE =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final String FALLBACK_CODE = "skill_failure";

    public SkillFailure {
        code = normalize(Objects.requireNonNullElse(code, ""));
    }

    public static SkillFailure of(String code) {
        return new SkillFailure(code);
    }

    private static String normalize(String candidate) {
        if (candidate.length() < 1
                || candidate.length() > MAX_CODE_CHARACTERS
                || !SAFE_CODE.matcher(candidate).matches()) {
            return FALLBACK_CODE;
        }
        return candidate;
    }
}
