package dev.mcai.companion.memory.transport;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking, movement-aware cache for nearby directly traversed portals.
 *
 * <p>At most one SQLite query is in flight. Server-thread calls only submit
 * work or read an immutable cached result; they never call {@code get},
 * {@code join}, or JDBC. If the body moves while a query is pending, only the
 * newest desired query is retained.</p>
 */
public final class AsyncVerifiedPortalEdgeRecall {
    public static final double DEFAULT_RADIUS_BLOCKS = 512.0;
    public static final int DEFAULT_RESULT_LIMIT = 4;
    public static final long DEFAULT_REFRESH_INTERVAL_TICKS = 100L;
    public static final double DEFAULT_REQUERY_DISTANCE_BLOCKS = 16.0;

    private final UUID worldId;
    private final NearbyLookup lookup;
    private final Clock clock;
    private final double radiusBlocks;
    private final int resultLimit;
    private final long refreshIntervalTicks;
    private final double requeryDistanceSquared;
    private final AtomicReference<QueryResult> latest =
        new AtomicReference<>();
    private final AtomicReference<Throwable> latestFailure =
        new AtomicReference<>();
    private final AtomicLong completedQueries = new AtomicLong();
    private final AtomicLong failedQueries = new AtomicLong();

    private Query inFlight;
    private Query queued;
    private long sequence;

    public AsyncVerifiedPortalEdgeRecall(
        final UUID worldId,
        final VerifiedPortalEdgeRepository repository
    ) {
        this(
            worldId,
            repository::findNearby,
            Clock.systemUTC(),
            DEFAULT_RADIUS_BLOCKS,
            DEFAULT_RESULT_LIMIT,
            DEFAULT_REFRESH_INTERVAL_TICKS,
            DEFAULT_REQUERY_DISTANCE_BLOCKS
        );
    }

    AsyncVerifiedPortalEdgeRecall(
        final UUID worldId,
        final NearbyLookup lookup,
        final Clock clock,
        final double radiusBlocks,
        final int resultLimit,
        final long refreshIntervalTicks,
        final double requeryDistanceBlocks
    ) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!Double.isFinite(radiusBlocks)
            || radiusBlocks <= 0.0
            || radiusBlocks
                > VerifiedPortalEdgeRepository.MAXIMUM_QUERY_RADIUS) {
            throw new IllegalArgumentException(
                "Portal recall radius is outside its bound"
            );
        }
        if (resultLimit < 1
            || resultLimit
                > VerifiedPortalEdgeRecallSnapshot.MAXIMUM_MODEL_RESULTS) {
            throw new IllegalArgumentException(
                "Portal recall result limit is outside its bound"
            );
        }
        if (refreshIntervalTicks < 1L) {
            throw new IllegalArgumentException(
                "Portal recall refresh interval must be positive"
            );
        }
        if (!Double.isFinite(requeryDistanceBlocks)
            || requeryDistanceBlocks <= 0.0
            || requeryDistanceBlocks > radiusBlocks) {
            throw new IllegalArgumentException(
                "Portal recall movement threshold is outside its bound"
            );
        }
        this.radiusBlocks = radiusBlocks;
        this.resultLimit = resultLimit;
        this.refreshIntervalTicks = refreshIntervalTicks;
        this.requeryDistanceSquared =
            requeryDistanceBlocks * requeryDistanceBlocks;
    }

    /**
     * Requests a refresh if the cached origin is old, distant, or belongs to
     * another dimension. This method never waits for the returned stage.
     */
    public synchronized void refresh(
        final DimensionRef dimension,
        final PerceptionVec3 position,
        final long gameTick
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        final QueryResult result = latest.get();
        final Query reference = inFlight != null
            ? inFlight
            : result == null ? null : result.query();
        if (!requiresQuery(reference, dimension, position, gameTick)) {
            /*
             * The newest body pose is covered by the active query, so discard
             * any older queued pose (for example a brief dimension change
             * that was reversed before the query completed).
             */
            if (inFlight != null) {
                queued = null;
            }
            return;
        }

        final Query requested = new Query(
            ++sequence,
            dimension,
            position,
            gameTick
        );
        if (inFlight != null) {
            queued = requested;
            return;
        }
        start(requested);
    }

    /**
     * Returns only edges still within the configured radius of the body's
     * current position. A cross-dimension or not-yet-completed result is
     * omitted rather than projected as stale knowledge.
     */
    public Optional<VerifiedPortalEdgeRecallSnapshot> snapshot(
        final DimensionRef currentDimension,
        final PerceptionVec3 currentPosition
    ) {
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(currentPosition, "currentPosition");
        final QueryResult result = latest.get();
        if (result == null
            || !result.query().dimension().equals(currentDimension)) {
            return Optional.empty();
        }
        final List<VerifiedPortalEdgeRecallEntry> matches =
            result.edges().stream()
                .map(edge -> VerifiedPortalEdgeRecallEntry.from(
                    edge,
                    distance(edge.sourcePosition(), currentPosition)
                ))
                .filter(entry ->
                    entry.distanceFromQueryPosition() <= radiusBlocks
                )
                .sorted(Comparator
                    .comparingDouble(
                        VerifiedPortalEdgeRecallEntry
                            ::distanceFromQueryPosition
                    )
                    .thenComparing(entry -> entry.edge().edgeId()))
                .limit(resultLimit)
                .toList();
        return Optional.of(new VerifiedPortalEdgeRecallSnapshot(
            worldId,
            currentDimension,
            currentPosition,
            radiusBlocks,
            resultLimit,
            result.recalledAt(),
            matches
        ));
    }

    public Optional<Throwable> latestFailure() {
        return Optional.ofNullable(latestFailure.get());
    }

    public long completedQueryCount() {
        return completedQueries.get();
    }

    public long failedQueryCount() {
        return failedQueries.get();
    }

    private boolean requiresQuery(
        final Query reference,
        final DimensionRef dimension,
        final PerceptionVec3 position,
        final long gameTick
    ) {
        if (reference == null
            || !reference.dimension().equals(dimension)
            || distanceSquared(reference.position(), position)
                >= requeryDistanceSquared) {
            return true;
        }
        return gameTick < reference.gameTick()
            || gameTick - reference.gameTick() >= refreshIntervalTicks;
    }

    private void start(final Query query) {
        inFlight = query;
        final CompletionStage<List<VerifiedPortalEdge>> operation;
        try {
            operation = Objects.requireNonNull(
                lookup.findNearby(
                    worldId,
                    query.dimension(),
                    query.position(),
                    radiusBlocks,
                    resultLimit
                ),
                "portal edge lookup"
            );
        } catch (RuntimeException exception) {
            complete(query, null, exception);
            return;
        }
        try {
            operation.whenComplete((edges, failure) ->
                complete(query, edges, failure)
            );
        } catch (RuntimeException exception) {
            complete(query, null, exception);
        }
    }

    private synchronized void complete(
        final Query query,
        final List<VerifiedPortalEdge> edges,
        final Throwable failure
    ) {
        if (inFlight == null || inFlight.sequence() != query.sequence()) {
            return;
        }
        inFlight = null;
        if (failure == null) {
            try {
                final List<VerifiedPortalEdge> checked = validate(
                    query,
                    edges
                );
                latest.set(new QueryResult(
                    query,
                    clock.instant(),
                    checked
                ));
                latestFailure.set(null);
                completedQueries.incrementAndGet();
            } catch (RuntimeException invalidResult) {
                latestFailure.set(invalidResult);
                failedQueries.incrementAndGet();
            }
        } else {
            latestFailure.set(failure);
            failedQueries.incrementAndGet();
        }

        final Query next = queued;
        queued = null;
        if (next != null
            && requiresQuery(query, next.dimension(), next.position(),
                next.gameTick())) {
            start(next);
        }
    }

    private List<VerifiedPortalEdge> validate(
        final Query query,
        final List<VerifiedPortalEdge> edges
    ) {
        final List<VerifiedPortalEdge> checked = new ArrayList<>(
            Objects.requireNonNull(edges, "portal edge query result")
        );
        if (checked.size() > resultLimit) {
            throw new IllegalArgumentException(
                "Portal edge query exceeded its requested result limit"
            );
        }
        for (VerifiedPortalEdge edge : checked) {
            Objects.requireNonNull(edge, "portal edge");
            if (!edge.worldId().equals(worldId)
                || !edge.sourceDimension().equals(query.dimension())
                || distanceSquared(
                    edge.sourcePosition(),
                    query.position()
                ) > radiusBlocks * radiusBlocks) {
                throw new IllegalArgumentException(
                    "Portal edge query returned out-of-scope data"
                );
            }
        }
        checked.sort(Comparator
            .comparingDouble((VerifiedPortalEdge edge) ->
                distanceSquared(edge.sourcePosition(), query.position())
            )
            .thenComparing(VerifiedPortalEdge::edgeId));
        return List.copyOf(checked);
    }

    private static double distance(
        final PerceptionVec3 left,
        final PerceptionVec3 right
    ) {
        return Math.sqrt(distanceSquared(left, right));
    }

    private static double distanceSquared(
        final PerceptionVec3 left,
        final PerceptionVec3 right
    ) {
        final double x = left.x() - right.x();
        final double y = left.y() - right.y();
        final double z = left.z() - right.z();
        return x * x + y * y + z * z;
    }

    @FunctionalInterface
    interface NearbyLookup {
        CompletionStage<List<VerifiedPortalEdge>> findNearby(
            UUID worldId,
            DimensionRef currentDimension,
            PerceptionVec3 currentPosition,
            double radius,
            int limit
        );
    }

    private record Query(
        long sequence,
        DimensionRef dimension,
        PerceptionVec3 position,
        long gameTick
    ) {
    }

    private record QueryResult(
        Query query,
        java.time.Instant recalledAt,
        List<VerifiedPortalEdge> edges
    ) {
        private QueryResult {
            edges = List.copyOf(edges);
        }
    }
}
