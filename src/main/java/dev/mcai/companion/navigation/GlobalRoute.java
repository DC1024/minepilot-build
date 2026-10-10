package dev.mcai.companion.navigation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GlobalRoute(
    UUID startNode,
    UUID goalNode,
    List<TopologyNode> nodes,
    List<TopologyEdge> edges,
    double totalCost,
    GlobalRouteAlgorithm algorithm,
    int expandedNodes
) {
    public GlobalRoute {
        Objects.requireNonNull(startNode, "startNode");
        Objects.requireNonNull(goalNode, "goalNode");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(algorithm, "algorithm");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (nodes.size() != edges.size() + 1
            || nodes.isEmpty()
            || !nodes.getFirst().id().equals(startNode)
            || !nodes.getLast().id().equals(goalNode)) {
            throw new IllegalArgumentException("Global route node/edge chain is invalid");
        }
        if (!Double.isFinite(totalCost) || totalCost < 0.0 || expandedNodes < 0) {
            throw new IllegalArgumentException("Global route metrics are invalid");
        }
        for (int index = 0; index < edges.size(); index++) {
            final TopologyEdge edge = edges.get(index);
            if (!edge.fromNode().equals(nodes.get(index).id())
                || !edge.toNode().equals(nodes.get(index + 1).id())) {
                throw new IllegalArgumentException("Global route contains a discontinuous edge");
            }
        }
    }
}
