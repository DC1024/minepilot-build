package dev.mcai.companion.skills.core;

import dev.mcai.companion.perception.PerceptionVec3;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A short-lived, player-authored warning interpreted only as a broad
 * directional cue. It does not identify, locate, or expose an unseen entity.
 */
record PlayerThreatWarningCue(
        Optional<PerceptionVec3> threatDirection,
        double severity
) {
    PlayerThreatWarningCue {
        threatDirection = Objects.requireNonNull(
                threatDirection,
                "threatDirection"
        ).map(PerceptionVec3::normalized);
        if (!Double.isFinite(severity)
                || severity < 0.0
                || severity > 1.0) {
            throw new IllegalArgumentException(
                    "severity must be in [0, 1]"
            );
        }
    }

    static Optional<PlayerThreatWarningCue> parse(
            final String message,
            final PerceptionVec3 lookDirection
    ) {
        final String original =
                Objects.requireNonNullElse(message, "");
        final String lower = original.toLowerCase(Locale.ROOT);
        if (!looksLikeThreatWarning(original, lower)) {
            return Optional.empty();
        }
        final PerceptionVec3 horizontal = new PerceptionVec3(
                lookDirection.x(),
                0.0,
                lookDirection.z()
        );
        final PerceptionVec3 forward =
                horizontal.lengthSquared() <= 1.0E-12
                        ? new PerceptionVec3(0.0, 0.0, 1.0)
                        : horizontal.normalized();
        final Optional<PerceptionVec3> direction;
        if (containsAny(
                original,
                lower,
                "后面",
                "身后",
                "背后",
                "behind you",
                "behind",
                "at your back"
        )) {
            direction = Optional.of(forward.scale(-1.0));
        } else if (containsAny(
                original,
                lower,
                "左边",
                "左侧",
                "左面",
                "to your left",
                "on your left"
        )) {
            direction = Optional.of(new PerceptionVec3(
                    forward.z(),
                    0.0,
                    -forward.x()
            ));
        } else if (containsAny(
                original,
                lower,
                "右边",
                "右侧",
                "右面",
                "to your right",
                "on your right"
        )) {
            direction = Optional.of(new PerceptionVec3(
                    -forward.z(),
                    0.0,
                    forward.x()
            ));
        } else if (containsAny(
                original,
                lower,
                "前面",
                "正前方",
                "ahead",
                "in front"
        )) {
            direction = Optional.of(forward);
        } else {
            direction = Optional.empty();
        }
        return Optional.of(new PlayerThreatWarningCue(
                direction,
                0.85
        ));
    }

    private static boolean looksLikeThreatWarning(
            final String original,
            final String lower
    ) {
        return original.contains("小心")
                || original.contains("危险")
                || original.contains("僵尸")
                || original.contains("骷髅")
                || original.contains("苦力怕")
                || original.contains("怪物")
                || lower.contains("watch out")
                || lower.contains("danger")
                || lower.contains("zombie")
                || lower.contains("skeleton")
                || lower.contains("creeper")
                || lower.contains("hostile");
    }

    private static boolean containsAny(
            final String original,
            final String lower,
            final String... needles
    ) {
        for (String needle : needles) {
            if (original.contains(needle)
                    || lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
