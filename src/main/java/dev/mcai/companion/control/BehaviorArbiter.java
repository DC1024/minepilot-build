package dev.mcai.companion.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Selects exactly one owner for the companion body in a server tick.
 *
 * <p>The model, atomic skills and emergency reflexes do not run at the same
 * latency. Letting each of them write movement, look, item use and vehicle
 * state in an incidental call order produces a body that nods, stalls, or
 * keeps an old input after danger appears. This arbiter gives the complete
 * body to the first priority lane that makes a real claim. A lower lane is
 * never invoked after a higher lane claims or fails.</p>
 *
 * <p>Candidate attempts must remain server-thread, bounded operations. A
 * candidate may inspect current immutable observations and either perform its
 * one-tick behavior or pass. An exception fails closed and suppresses every
 * lower lane. Re-entering the arbiter for the same tick returns the cached
 * resolution without executing a candidate twice.</p>
 */
public final class BehaviorArbiter {
    private long lastResolvedTick = -1L;
    private Resolution lastResolution;

    public synchronized Resolution arbitrate(
            final long serverTick,
            final List<Candidate> candidates
    ) {
        if (serverTick < 0L) {
            throw new IllegalArgumentException(
                    "serverTick must be non-negative"
            );
        }
        Objects.requireNonNull(candidates, "candidates");
        if (serverTick < lastResolvedTick) {
            return Resolution.failedClosed(
                    serverTick,
                    Optional.empty(),
                    List.of(),
                    "non_monotonic_server_tick"
            );
        }
        if (serverTick == lastResolvedTick
                && lastResolution != null) {
            return lastResolution.asReplay();
        }

        final List<Candidate> ordered =
                new ArrayList<>(candidates.size());
        final EnumSet<Lane> seen = EnumSet.noneOf(Lane.class);
        for (Candidate candidate : candidates) {
            final Candidate checked = Objects.requireNonNull(
                    candidate,
                    "candidate"
            );
            if (!seen.add(checked.lane())) {
                final Resolution duplicate =
                        Resolution.failedClosed(
                                serverTick,
                                Optional.of(checked.lane()),
                                List.of(),
                                "duplicate_behavior_lane"
                        );
                remember(serverTick, duplicate);
                return duplicate;
            }
            ordered.add(checked);
        }
        ordered.sort(
                Comparator.comparingInt(
                        (Candidate candidate) ->
                                candidate.lane().priority()
                ).reversed()
        );

        final List<Lane> attempted = new ArrayList<>();
        for (Candidate candidate : ordered) {
            attempted.add(candidate.lane());
            final Attempt attempt;
            try {
                attempt = Objects.requireNonNull(
                        candidate.operation().get(),
                        "candidate attempt"
                );
            } catch (RuntimeException exception) {
                final Resolution failed =
                        Resolution.failedClosed(
                                serverTick,
                                Optional.of(candidate.lane()),
                                attempted,
                                "behavior_candidate_exception"
                        );
                remember(serverTick, failed);
                return failed;
            }
            if (attempt.claimed()) {
                final Resolution claimed =
                        Resolution.claimed(
                                serverTick,
                                candidate.lane(),
                                attempted,
                                attempt.reason()
                        );
                remember(serverTick, claimed);
                return claimed;
            }
        }

        final Resolution unclaimed = Resolution.unclaimed(
                serverTick,
                attempted
        );
        remember(serverTick, unclaimed);
        return unclaimed;
    }

    public synchronized Optional<Resolution> latest() {
        return Optional.ofNullable(lastResolution);
    }

    private void remember(
            final long serverTick,
            final Resolution resolution
    ) {
        lastResolvedTick = serverTick;
        lastResolution = resolution;
    }

    public enum Lane {
        EMERGENCY_SURVIVAL(100),
        ACTIVE_SKILL(50),
        IDLE_EQUIPMENT(10);

        private final int priority;

        Lane(final int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    public enum Status {
        CLAIMED,
        UNCLAIMED,
        FAILED_CLOSED
    }

    public record Candidate(
            Lane lane,
            Supplier<Attempt> operation
    ) {
        public Candidate {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(operation, "operation");
        }
    }

    public record Attempt(
            boolean claimed,
            String reason
    ) {
        public Attempt {
            reason = sanitizeReason(reason);
            if (claimed && reason.isEmpty()) {
                throw new IllegalArgumentException(
                        "A claimed behavior requires a reason"
                );
            }
            if (!claimed && !reason.isEmpty()) {
                throw new IllegalArgumentException(
                        "A passing behavior cannot report a reason"
                );
            }
        }

        public static Attempt claim(final String reason) {
            return new Attempt(true, reason);
        }

        public static Attempt pass() {
            return new Attempt(false, "");
        }
    }

    public record Resolution(
            long serverTick,
            Status status,
            Optional<Lane> lane,
            List<Lane> attempted,
            String reason,
            boolean replayed
    ) {
        public Resolution {
            if (serverTick < 0L) {
                throw new IllegalArgumentException(
                        "serverTick must be non-negative"
                );
            }
            Objects.requireNonNull(status, "status");
            lane = Objects.requireNonNull(lane, "lane");
            attempted = List.copyOf(
                    Objects.requireNonNull(attempted, "attempted")
            );
            reason = sanitizeReason(reason);
            if (status == Status.CLAIMED
                    && (lane.isEmpty() || reason.isEmpty())) {
                throw new IllegalArgumentException(
                        "Claimed resolution requires lane and reason"
                );
            }
            if (status == Status.UNCLAIMED
                    && (lane.isPresent() || !reason.isEmpty())) {
                throw new IllegalArgumentException(
                        "Unclaimed resolution cannot name a winner"
                );
            }
            if (status == Status.FAILED_CLOSED
                    && reason.isEmpty()) {
                throw new IllegalArgumentException(
                        "Failed resolution requires a reason"
                );
            }
        }

        public boolean claimedBy(final Lane expected) {
            return status == Status.CLAIMED
                    && lane.filter(expected::equals).isPresent();
        }

        public boolean failedClosed() {
            return status == Status.FAILED_CLOSED;
        }

        private Resolution asReplay() {
            return new Resolution(
                    serverTick,
                    status,
                    lane,
                    attempted,
                    reason,
                    true
            );
        }

        private static Resolution claimed(
                final long tick,
                final Lane lane,
                final List<Lane> attempted,
                final String reason
        ) {
            return new Resolution(
                    tick,
                    Status.CLAIMED,
                    Optional.of(lane),
                    attempted,
                    reason,
                    false
            );
        }

        private static Resolution unclaimed(
                final long tick,
                final List<Lane> attempted
        ) {
            return new Resolution(
                    tick,
                    Status.UNCLAIMED,
                    Optional.empty(),
                    attempted,
                    "",
                    false
            );
        }

        private static Resolution failedClosed(
                final long tick,
                final Optional<Lane> lane,
                final List<Lane> attempted,
                final String reason
        ) {
            return new Resolution(
                    tick,
                    Status.FAILED_CLOSED,
                    lane,
                    attempted,
                    reason,
                    false
            );
        }
    }

    private static String sanitizeReason(final String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        final StringBuilder sanitized = new StringBuilder(96);
        for (int index = 0;
                index < raw.length() && sanitized.length() < 96;
                index++) {
            final char character =
                    Character.toLowerCase(raw.charAt(index));
            final boolean accepted =
                    character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-'
                    || character == '.';
            sanitized.append(accepted ? character : '_');
        }
        return sanitized.toString();
    }
}
