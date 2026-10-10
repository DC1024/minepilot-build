package dev.mcai.companion.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Sparse multimodal graph. Every node position stays in its dimension's native
 * coordinate frame; no overworld-equivalent transform is performed.
 */
public final class TopologyGraph {
    public static final int MAXIMUM_EXPANDED_NODES = 1_000_000;
    private static final double EPSILON = 1.0e-9;

    private static final Comparator<FrontierNode> FRONTIER_ORDER =
        Comparator.comparingDouble(FrontierNode::estimatedTotal)
            .thenComparingDouble(FrontierNode::costFromStart)
            .thenComparing(node -> node.nodeId().toString())
            .thenComparingLong(FrontierNode::sequence);

    private final Map<UUID, TopologyNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, TopologyEdge> edges = new LinkedHashMap<>();
    private final Map<UUID, List<TopologyEdge>> outgoing = new HashMap<>();

    public synchronized void addNode(TopologyNode node) {
        Objects.requireNonNull(node, "node");
        final TopologyNode existing = nodes.putIfAbsent(node.id(), node);
        if (existing != null && !existing.equals(node)) {
            throw new IllegalArgumentException("Conflicting topology node id");
        }
    }

    public synchronized void addEdge(TopologyEdge edge) {
        Objects.requireNonNull(edge, "edge");
        final TopologyNode from = nodes.get(edge.fromNode());
        final TopologyNode to = nodes.get(edge.toNode());
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both topology edge endpoints must exist");
        }
        if (!from.dimension().equals(to.dimension())
            && edge.mode() != TransportMode.PORTAL) {
            throw new IllegalArgumentException(
                "Only a portal edge may cross native dimension frames"
            );
        }
        final TopologyEdge existing = edges.putIfAbsent(edge.id(), edge);
        if (existing != null) {
            if (!existing.equals(edge)) {
                throw new IllegalArgumentException("Conflicting topology edge id");
            }
            return;
        }
        outgoing.computeIfAbsent(edge.fromNode(), ignored -> new ArrayList<>()).add(edge);
    }

    public synchronized Optional<GlobalRoute> findRoute(
        UUID startNode,
        UUID goalNode,
        GlobalRouteAlgorithm algorithm,
        NavigationRiskProfile riskProfile,
        int maximumExpandedNodes
    ) {
        Objects.requireNonNull(startNode, "startNode");
        Objects.requireNonNull(goalNode, "goalNode");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(riskProfile, "riskProfile");
        if (maximumExpandedNodes < 1 || maximumExpandedNodes > MAXIMUM_EXPANDED_NODES) {
            throw new IllegalArgumentException("Global expanded-node budget is out of range");
        }
        if (!nodes.containsKey(startNode) || !nodes.containsKey(goalNode)) {
            return Optional.empty();
        }
        if (startNode.equals(goalNode)) {
            return Optional.of(new GlobalRoute(
                startNode,
                goalNode,
                List.of(nodes.get(startNode)),
                List.of(),
                0.0,
                algorithm,
                0
            ));
        }

        final double minimumCostPerBlock = algorithm == GlobalRouteAlgorithm.A_STAR
            ? admissibleCostPerBlock()
            : 0.0;
        final PriorityQueue<FrontierNode> frontier = new PriorityQueue<>(FRONTIER_ORDER);
        final Map<UUID, Double> costs = new HashMap<>();
        final Map<UUID, TopologyEdge> previous = new HashMap<>();
        long sequence = 0L;
        frontier.add(new FrontierNode(
            startNode,
            0.0,
            heuristic(startNode, goalNode, minimumCostPerBlock),
            sequence++
        ));
        costs.put(startNode, 0.0);
        int expanded = 0;

        while (!frontier.isEmpty() && expanded < maximumExpandedNodes) {
            final FrontierNode current = frontier.remove();
            final double knownCost = costs.getOrDefault(
                current.nodeId(),
                Double.POSITIVE_INFINITY
            );
            if (current.costFromStart() > knownCost + EPSILON) {
                continue;
            }
            expanded++;
            if (current.nodeId().equals(goalNode)) {
                return Optional.of(reconstruct(
                    startNode,
                    goalNode,
                    knownCost,
                    algorithm,
                    expanded,
                    previous
                ));
            }

            final List<TopologyEdge> candidates = new ArrayList<>(
                outgoing.getOrDefault(current.nodeId(), List.of())
            );
            candidates.sort(
                Comparator.<TopologyEdge>comparingDouble(
                    edge -> edge.effectiveCost(riskProfile)
                )
                    .thenComparing(edge -> edge.mode().ordinal())
                    .thenComparing(edge -> edge.toNode().toString())
                    .thenComparing(edge -> edge.id().toString())
            );
            for (TopologyEdge edge : candidates) {
                if (!edge.isUsable()) {
                    continue;
                }
                final double candidateCost =
                    knownCost + edge.effectiveCost(riskProfile);
                final double existingCost = costs.getOrDefault(
                    edge.toNode(),
                    Double.POSITIVE_INFINITY
                );
                if (candidateCost + EPSILON >= existingCost) {
                    continue;
                }
                costs.put(edge.toNode(), candidateCost);
                previous.put(edge.toNode(), edge);
                frontier.add(new FrontierNode(
                    edge.toNode(),
                    candidateCost,
                    heuristic(edge.toNode(), goalNode, minimumCostPerBlock),
                    sequence++
                ));
            }
        }
        return Optional.empty();
    }

    public Optional<GlobalRoute> findRoute(
        UUID startNode,
        UUID goalNode,
        GlobalRouteAlgorithm algorithm,
        NavigationRiskProfile riskProfile
    ) {
        return findRoute(
            startNode,
            goalNode,
            algorithm,
            riskProfile,
            100_000
        );
    }

    private GlobalRoute reconstruct(
        UUID startNode,
        UUID goalNode,
        double totalCost,
        GlobalRouteAlgorithm algorithm,
        int expanded,
        Map<UUID, TopologyEdge> previous
    ) {
        final Deque<TopologyEdge> edgeChain = new ArrayDeque<>();
        UUID cursor = goalNode;
        while (!cursor.equals(startNode)) {
            final TopologyEdge edge = previous.get(cursor);
            if (edge == null) {
                throw new IllegalStateException("Topology predecessor chain is incomplete");
            }
            edgeChain.addFirst(edge);
            cursor = edge.fromNode();
        }
        final List<TopologyEdge> routeEdges = List.copyOf(edgeChain);
        final List<TopologyNode> routeNodes = new ArrayList<>(routeEdges.size() + 1);
        routeNodes.add(nodes.get(startNode));
        routeEdges.forEach(edge -> routeNodes.add(nodes.get(edge.toNode())));
        return new GlobalRoute(
            startNode,
            goalNode,
            routeNodes,
            routeEdges,
            totalCost,
            algorithm,
            expanded
        );
    }

    private double admissibleCostPerBlock() {
        if (edges.values().stream().anyMatch(
            edge -> edge.mode() == TransportMode.PORTAL && edge.isUsable()
        )) {
            return 0.0;
        }
        double minimum = Double.POSITIVE_INFINITY;
        for (TopologyEdge edge : edges.values()) {
            if (!edge.isUsable()) {
                continue;
            }
            final TopologyNode from = nodes.get(edge.fromNode());
            final TopologyNode to = nodes.get(edge.toNode());
            if (!from.dimension().equals(to.dimension())) {
                continue;
            }
            final double distance = from.nativePosition().euclideanDistance(to.nativePosition());
            if (distance > 0.0) {
                minimum = Math.min(minimum, edge.expectedCost() / distance);
            }
        }
        return Double.isFinite(minimum) ? minimum : 0.0;
    }

    private double heuristic(UUID nodeId, UUID goalId, double costPerBlock) {
        if (costPerBlock <= 0.0) {
            return 0.0;
        }
        final TopologyNode node = nodes.get(nodeId);
        final TopologyNode goal = nodes.get(goalId);
        if (!node.dimension().equals(goal.dimension())) {
            return 0.0;
        }
        return node.nativePosition().euclideanDistance(goal.nativePosition())
            * costPerBlock;
    }

    private record FrontierNode(
        UUID nodeId,
        double costFromStart,
        double heuristic,
        long sequence
    ) {
        double estimatedTotal() {
            return costFromStart + heuristic;
        }
    }
}
