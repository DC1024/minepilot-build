package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcai.companion.waypoint.DimensionRef;

final class TopologyGraphTest {
    @Test
    void crossDimensionRouteRequiresVerifiedPortal() {
        final TopologyNode overworld = node(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            new GridPos(800, 64, 400),
            "Overworld portal"
        );
        final TopologyNode nether = node(
            "00000000-0000-0000-0000-000000000002",
            DimensionRef.NETHER,
            new GridPos(100, 64, 50),
            "Nether portal"
        );
        final TopologyGraph graph = new TopologyGraph();
        graph.addNode(overworld);
        graph.addNode(nether);
        graph.addEdge(edge(
            "10000000-0000-0000-0000-000000000001",
            overworld,
            nether,
            TransportMode.PORTAL,
            4.0,
            false
        ));

        assertTrue(graph.findRoute(
            overworld.id(),
            nether.id(),
            GlobalRouteAlgorithm.DIJKSTRA,
            NavigationRiskProfile.HARDCORE
        ).isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> graph.addEdge(edge(
                "10000000-0000-0000-0000-000000000099",
                overworld,
                nether,
                TransportMode.WALK,
                1.0,
                true
            ))
        );

        graph.addEdge(edge(
            "10000000-0000-0000-0000-000000000002",
            overworld,
            nether,
            TransportMode.PORTAL,
            5.0,
            true
        ));
        final GlobalRoute route = graph.findRoute(
            overworld.id(),
            nether.id(),
            GlobalRouteAlgorithm.A_STAR,
            NavigationRiskProfile.HARDCORE
        ).orElseThrow();
        assertEquals(TransportMode.PORTAL, route.edges().getFirst().mode());
        assertTrue(route.edges().getFirst().verified());
        assertEquals(new GridPos(800, 64, 400), route.nodes().getFirst().nativePosition());
        assertEquals(new GridPos(100, 64, 50), route.nodes().getLast().nativePosition());
    }

    @Test
    void choosesLowestEffectiveTransportCost() {
        final TopologyNode start = node(
            "00000000-0000-0000-0000-000000000010",
            DimensionRef.OVERWORLD,
            new GridPos(0, 64, 0),
            "Start"
        );
        final TopologyNode goal = node(
            "00000000-0000-0000-0000-000000000020",
            DimensionRef.OVERWORLD,
            new GridPos(1_000, 64, 0),
            "Goal"
        );
        final TopologyGraph graph = new TopologyGraph();
        graph.addNode(start);
        graph.addNode(goal);
        graph.addEdge(edge(
            "10000000-0000-0000-0000-000000000010",
            start,
            goal,
            TransportMode.WALK,
            20.0,
            true
        ));
        graph.addEdge(edge(
            "10000000-0000-0000-0000-000000000011",
            start,
            goal,
            TransportMode.BOAT,
            9.0,
            true
        ));
        graph.addEdge(edge(
            "10000000-0000-0000-0000-000000000012",
            start,
            goal,
            TransportMode.RAIL,
            4.0,
            true
        ));

        for (GlobalRouteAlgorithm algorithm : GlobalRouteAlgorithm.values()) {
            final GlobalRoute route = graph.findRoute(
                start.id(),
                goal.id(),
                algorithm,
                NavigationRiskProfile.NORMAL
            ).orElseThrow();
            assertEquals(TransportMode.RAIL, route.edges().getFirst().mode());
            assertEquals(4.0, route.totalCost());
        }
    }

    @Test
    void equalCostRoutesHaveStableOrderingIndependentOfInsertion() {
        final TopologyNode start = node(
            "00000000-0000-0000-0000-000000000010",
            DimensionRef.OVERWORLD,
            new GridPos(0, 64, 0),
            "Start"
        );
        final TopologyNode preferred = node(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            new GridPos(10, 64, -1),
            "Preferred tie"
        );
        final TopologyNode alternate = node(
            "00000000-0000-0000-0000-000000000002",
            DimensionRef.OVERWORLD,
            new GridPos(10, 64, 1),
            "Alternate tie"
        );
        final TopologyNode goal = node(
            "00000000-0000-0000-0000-000000000020",
            DimensionRef.OVERWORLD,
            new GridPos(20, 64, 0),
            "Goal"
        );
        final List<TopologyEdge> edges = List.of(
            edge("10000000-0000-0000-0000-000000000001", start, preferred, TransportMode.WALK, 5, true),
            edge("10000000-0000-0000-0000-000000000002", preferred, goal, TransportMode.WALK, 5, true),
            edge("10000000-0000-0000-0000-000000000003", start, alternate, TransportMode.WALK, 5, true),
            edge("10000000-0000-0000-0000-000000000004", alternate, goal, TransportMode.WALK, 5, true)
        );

        final TopologyGraph forward = graph(List.of(start, preferred, alternate, goal), edges);
        final TopologyGraph reverse = graph(
            List.of(goal, alternate, preferred, start),
            edges.reversed()
        );
        final List<UUID> forwardNodes = routeNodeIds(forward, start, goal);
        final List<UUID> reverseNodes = routeNodeIds(reverse, start, goal);

        assertEquals(forwardNodes, reverseNodes);
        assertEquals(List.of(start.id(), preferred.id(), goal.id()), forwardNodes);
    }

    private static List<UUID> routeNodeIds(
        TopologyGraph graph,
        TopologyNode start,
        TopologyNode goal
    ) {
        return graph.findRoute(
            start.id(),
            goal.id(),
            GlobalRouteAlgorithm.DIJKSTRA,
            NavigationRiskProfile.NORMAL
        ).orElseThrow().nodes().stream().map(TopologyNode::id).toList();
    }

    private static TopologyGraph graph(
        List<TopologyNode> nodes,
        List<TopologyEdge> edges
    ) {
        final TopologyGraph graph = new TopologyGraph();
        nodes.forEach(graph::addNode);
        edges.forEach(graph::addEdge);
        return graph;
    }

    private static TopologyNode node(
        String id,
        DimensionRef dimension,
        GridPos position,
        String label
    ) {
        return new TopologyNode(UUID.fromString(id), dimension, position, label);
    }

    private static TopologyEdge edge(
        String id,
        TopologyNode from,
        TopologyNode to,
        TransportMode mode,
        double cost,
        boolean verified
    ) {
        return new TopologyEdge(
            UUID.fromString(id),
            from.id(),
            to.id(),
            mode,
            cost,
            0.0,
            verified,
            1
        );
    }
}
