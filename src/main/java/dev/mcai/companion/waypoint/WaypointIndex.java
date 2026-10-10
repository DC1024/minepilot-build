package dev.mcai.companion.waypoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A deterministic, loader-independent in-memory waypoint index.
 */
public final class WaypointIndex {
    private static final int MAXIMUM_RESULTS = 1_000;

    private static final Comparator<WaypointSearchResult> RESULT_ORDER =
        Comparator.comparingDouble(WaypointSearchResult::score)
            .reversed()
            .thenComparing(
                result -> WaypointNames.normalize(result.waypoint().name())
            )
            .thenComparing(result -> result.waypoint().id().toString());

    private final Map<UUID, Waypoint> waypoints = new HashMap<>();

    public synchronized void upsert(Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        final Waypoint current = waypoints.get(waypoint.id());
        if (current != null) {
            if (!current.worldId().equals(waypoint.worldId())) {
                throw new IllegalArgumentException("A waypoint cannot move between worlds");
            }
            if (waypoint.revision() < current.revision()) {
                throw new IllegalArgumentException("Waypoint revision must not move backwards");
            }
            if (waypoint.revision() == current.revision()) {
                if (waypoint.equals(current)) {
                    return;
                }
                throw new IllegalArgumentException("Conflicting waypoint data has the same revision");
            }
        }
        waypoints.put(waypoint.id(), waypoint);
    }

    public synchronized Optional<Waypoint> get(UUID waypointId) {
        Objects.requireNonNull(waypointId, "waypointId");
        return Optional.ofNullable(waypoints.get(waypointId));
    }

    public synchronized List<WaypointSearchResult> search(
        UUID worldId,
        DimensionRef dimension,
        String query,
        Instant at,
        int limit
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(at, "at");
        if (limit < 1 || limit > MAXIMUM_RESULTS) {
            throw new IllegalArgumentException("Search limit must be between 1 and " + MAXIMUM_RESULTS);
        }

        final String normalizedQuery = WaypointNames.normalize(query);
        final List<WaypointSearchResult> results = new ArrayList<>();
        for (Waypoint waypoint : waypoints.values()) {
            if (!waypoint.worldId().equals(worldId)
                || !waypoint.dimension().equals(dimension)
                || !waypoint.isSearchableAt(at)) {
                continue;
            }
            final double score = score(waypoint, normalizedQuery);
            if (score > 0.0) {
                results.add(new WaypointSearchResult(waypoint, score));
            }
        }
        results.sort(RESULT_ORDER);
        if (results.size() > limit) {
            return List.copyOf(results.subList(0, limit));
        }
        return List.copyOf(results);
    }

    public synchronized int size() {
        return waypoints.size();
    }

    private static double score(Waypoint waypoint, String query) {
        double lexicalScore = termScore(
            WaypointNames.normalize(waypoint.name()),
            query,
            1_000.0,
            700.0,
            500.0,
            250.0
        );
        for (String alias : waypoint.aliases()) {
            lexicalScore = Math.max(
                lexicalScore,
                termScore(
                    WaypointNames.normalize(alias),
                    query,
                    950.0,
                    650.0,
                    450.0,
                    225.0
                )
            );
        }
        lexicalScore = Math.max(
            lexicalScore,
            termScore(
                WaypointNames.normalize(waypoint.category()),
                query,
                300.0,
                225.0,
                175.0,
                100.0
            )
        );
        if (lexicalScore <= 0.0) {
            return 0.0;
        }

        final double statusAdjustment = switch (waypoint.status()) {
            case ACTIVE -> 20.0;
            case DANGEROUS -> -15.0;
            case STALE -> -40.0;
            case REMOVED, ARCHIVED -> -1_000.0;
        };
        return lexicalScore + waypoint.confidence() * 100.0 + statusAdjustment;
    }

    private static double termScore(
        String candidate,
        String query,
        double exact,
        double prefix,
        double contains,
        double tokenOverlap
    ) {
        if (candidate.equals(query)) {
            return exact;
        }
        if (candidate.startsWith(query) || query.startsWith(candidate)) {
            return prefix;
        }
        if (candidate.contains(query) || query.contains(candidate)) {
            return contains;
        }

        final Set<String> queryTokens = new HashSet<>(List.of(query.split(" +")));
        final Set<String> candidateTokens = new HashSet<>(List.of(candidate.split(" +")));
        int common = 0;
        for (String token : queryTokens) {
            if (candidateTokens.contains(token)) {
                common++;
            }
        }
        if (common == 0) {
            return 0.0;
        }
        return tokenOverlap * common / Math.max(queryTokens.size(), candidateTokens.size());
    }
}
