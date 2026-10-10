package dev.mcai.companion.memory.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class AsyncVerifiedPortalEdgeRecallTest {
    private static final UUID WORLD =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_WORLD =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final Instant NOW =
        Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void serverSideRefreshNeverWaitsAndPublishesBoundedNearbyEvidence() {
        ControlledLookup lookup = new ControlledLookup();
        AsyncVerifiedPortalEdgeRecall recall = recall(lookup);
        PerceptionVec3 body = new PerceptionVec3(0, 64, 0);

        recall.refresh(DimensionRef.OVERWORLD, body, 10);

        assertEquals(1, lookup.requests.size());
        assertFalse(lookup.requests.getFirst().response().isDone());
        assertTrue(
            recall.snapshot(DimensionRef.OVERWORLD, body).isEmpty()
        );

        lookup.requests.getFirst().response().complete(List.of(
            edge(WORLD, DimensionRef.OVERWORLD, 3, 64, 4, 1),
            edge(WORLD, DimensionRef.OVERWORLD, 30, 64, 40, 2)
        ));

        VerifiedPortalEdgeRecallSnapshot snapshot = recall.snapshot(
            DimensionRef.OVERWORLD,
            body
        ).orElseThrow();
        assertEquals(2, snapshot.matches().size());
        assertEquals(5.0, snapshot.matches().get(0)
            .distanceFromQueryPosition(), 1.0E-9);
        assertEquals(0.8, snapshot.matches().get(0)
            .evidenceConfidence(), 1.0E-9);
        assertEquals(50.0, snapshot.matches().get(1)
            .distanceFromQueryPosition(), 1.0E-9);
        assertEquals(1, recall.completedQueryCount());
        assertEquals(0, recall.failedQueryCount());
    }

    @Test
    void coalescesMovementWhileOneQueryIsInFlightAndThrottlesFreshData() {
        ControlledLookup lookup = new ControlledLookup();
        AsyncVerifiedPortalEdgeRecall recall = recall(lookup);

        recall.refresh(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(0, 64, 0),
            0
        );
        recall.refresh(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(20, 64, 0),
            1
        );
        recall.refresh(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(40, 64, 0),
            2
        );
        assertEquals(1, lookup.requests.size());

        lookup.requests.getFirst().response().complete(List.of());
        assertEquals(2, lookup.requests.size());
        assertEquals(
            new PerceptionVec3(40, 64, 0),
            lookup.requests.get(1).position()
        );
        lookup.requests.get(1).response().complete(List.of());

        recall.refresh(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(45, 64, 0),
            50
        );
        assertEquals(2, lookup.requests.size());
        recall.refresh(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(45, 64, 0),
            102
        );
        assertEquals(3, lookup.requests.size());
    }

    @Test
    void clearsAQueuedTransientDimensionWhenBodyReturnsBeforeCompletion() {
        ControlledLookup lookup = new ControlledLookup();
        AsyncVerifiedPortalEdgeRecall recall = recall(lookup);
        PerceptionVec3 body = new PerceptionVec3(0, 64, 0);

        recall.refresh(DimensionRef.OVERWORLD, body, 0);
        recall.refresh(DimensionRef.NETHER, body, 1);
        recall.refresh(DimensionRef.OVERWORLD, body, 2);
        lookup.requests.getFirst().response().complete(List.of());

        assertEquals(1, lookup.requests.size());
        assertTrue(
            recall.snapshot(DimensionRef.OVERWORLD, body).isPresent()
        );
    }

    @Test
    void neverProjectsCrossDimensionOrOutOfScopeRepositoryResults() {
        ControlledLookup lookup = new ControlledLookup();
        AsyncVerifiedPortalEdgeRecall recall = recall(lookup);
        PerceptionVec3 body = new PerceptionVec3(0, 64, 0);

        recall.refresh(DimensionRef.OVERWORLD, body, 0);
        lookup.requests.getFirst().response().complete(List.of(
            edge(
                OTHER_WORLD,
                DimensionRef.OVERWORLD,
                1,
                64,
                1,
                1
            )
        ));

        assertTrue(
            recall.snapshot(DimensionRef.OVERWORLD, body).isEmpty()
        );
        assertTrue(recall.latestFailure().isPresent());
        assertEquals(1, recall.failedQueryCount());

        recall.refresh(DimensionRef.NETHER, body, 1);
        lookup.requests.get(1).response().complete(List.of(
            edge(WORLD, DimensionRef.NETHER, 1, 64, 1, 1)
        ));
        assertTrue(
            recall.snapshot(DimensionRef.OVERWORLD, body).isEmpty()
        );
        assertTrue(
            recall.snapshot(DimensionRef.NETHER, body).isPresent()
        );
    }

    @Test
    void currentBodyDistanceFiltersPreviouslyNearbyEdges() {
        ControlledLookup lookup = new ControlledLookup();
        AsyncVerifiedPortalEdgeRecall recall = recall(lookup);
        PerceptionVec3 origin = new PerceptionVec3(0, 64, 0);
        recall.refresh(DimensionRef.OVERWORLD, origin, 0);
        lookup.requests.getFirst().response().complete(List.of(
            edge(WORLD, DimensionRef.OVERWORLD, -90, 64, 0, 1),
            edge(WORLD, DimensionRef.OVERWORLD, 90, 64, 0, 1)
        ));

        Optional<VerifiedPortalEdgeRecallSnapshot> moved = recall.snapshot(
            DimensionRef.OVERWORLD,
            new PerceptionVec3(50, 64, 0)
        );
        assertTrue(moved.isPresent());
        assertEquals(1, moved.orElseThrow().matches().size());
        assertEquals(
            40.0,
            moved.orElseThrow().matches().getFirst()
                .distanceFromQueryPosition(),
            1.0E-9
        );
    }

    private static AsyncVerifiedPortalEdgeRecall recall(
        ControlledLookup lookup
    ) {
        return new AsyncVerifiedPortalEdgeRecall(
            WORLD,
            lookup,
            Clock.fixed(NOW, ZoneOffset.UTC),
            100.0,
            2,
            100,
            10.0
        );
    }

    private static VerifiedPortalEdge edge(
        UUID world,
        DimensionRef sourceDimension,
        double x,
        double y,
        double z,
        long successes
    ) {
        DimensionRef destination = sourceDimension.equals(
            DimensionRef.NETHER
        ) ? DimensionRef.OVERWORLD : DimensionRef.NETHER;
        return new VerifiedPortalEdge(
            ("%064x".formatted(
                Math.round(Math.abs(x) + Math.abs(z)) + successes
            )),
            world,
            PortalKind.NETHER_PORTAL,
            sourceDimension,
            new PerceptionVec3(x, y, z),
            new BlockCoordinate(
                (int) Math.floor(x),
                (int) Math.floor(y),
                (int) Math.floor(z)
            ),
            destination,
            new PerceptionVec3(x / 8.0, y, z / 8.0),
            new BlockCoordinate(
                (int) Math.floor(x / 8.0),
                (int) Math.floor(y),
                (int) Math.floor(z / 8.0)
            ),
            NOW.minusSeconds(60),
            NOW,
            successes,
            successes - 1
        );
    }

    private static final class ControlledLookup
            implements AsyncVerifiedPortalEdgeRecall.NearbyLookup {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public CompletionStage<List<VerifiedPortalEdge>> findNearby(
            UUID worldId,
            DimensionRef currentDimension,
            PerceptionVec3 currentPosition,
            double radius,
            int limit
        ) {
            CompletableFuture<List<VerifiedPortalEdge>> response =
                new CompletableFuture<>();
            requests.add(new Request(
                worldId,
                currentDimension,
                currentPosition,
                radius,
                limit,
                response
            ));
            return response;
        }
    }

    private record Request(
        UUID worldId,
        DimensionRef dimension,
        PerceptionVec3 position,
        double radius,
        int limit,
        CompletableFuture<List<VerifiedPortalEdge>> response
    ) {
    }
}
