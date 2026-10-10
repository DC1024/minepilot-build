package dev.mcai.companion.mechanism;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned, declarative mechanism knowledge. It describes causal rules and
 * commissioning evidence; it never contains world coordinates or a saved
 * block-by-block build.
 */
public record MechanismSpec(
        int schemaVersion,
        String id,
        String purpose,
        List<Rule> invariants,
        ComponentGraph componentGraph,
        List<ResourceFlow> inputs,
        List<ResourceFlow> outputs,
        List<MaterialSubstitution> materialSubstitutions,
        List<Rule> siteConstraints,
        List<Rule> chunkAndDimensionConstraints,
        List<SafetyClearance> safetyClearances,
        ExpectedRate expectedRate,
        List<CommissioningProbe> commissioningProbes,
        List<KnownFailureMode> knownFailureModes,
        List<RepairStrategy> repairStrategies
) {
    private static final Pattern TOKEN = Pattern.compile(
            "[a-z0-9_.-]{1,64}"
    );
    private static final Pattern IDENTIFIER = Pattern.compile(
            "#?[a-z0-9_.-]+:[a-z0-9_./-]+"
    );

    public MechanismSpec {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException(
                    "Mechanism schema version must be positive"
            );
        }
        id = identifier(id, "id");
        purpose = token(purpose, "purpose");
        invariants = nonEmpty(invariants, "invariants");
        Objects.requireNonNull(componentGraph, "componentGraph");
        inputs = nonEmpty(inputs, "inputs");
        outputs = nonEmpty(outputs, "outputs");
        materialSubstitutions = nonEmpty(
                materialSubstitutions,
                "materialSubstitutions"
        );
        siteConstraints = nonEmpty(siteConstraints, "siteConstraints");
        chunkAndDimensionConstraints = nonEmpty(
                chunkAndDimensionConstraints,
                "chunkAndDimensionConstraints"
        );
        safetyClearances = nonEmpty(
                safetyClearances,
                "safetyClearances"
        );
        Objects.requireNonNull(expectedRate, "expectedRate");
        commissioningProbes = nonEmpty(
                commissioningProbes,
                "commissioningProbes"
        );
        knownFailureModes = nonEmpty(
                knownFailureModes,
                "knownFailureModes"
        );
        repairStrategies = nonEmpty(
                repairStrategies,
                "repairStrategies"
        );
        final Set<String> repairIds = new HashSet<>();
        repairStrategies.forEach(repair -> {
            if (!repairIds.add(repair.id())) {
                throw new IllegalArgumentException(
                        "Duplicate repair strategy id"
                );
            }
        });
        if (knownFailureModes.stream().anyMatch(failure ->
                !repairIds.contains(failure.repairStrategyId())
        )) {
            throw new IllegalArgumentException(
                    "Failure mode references an unknown repair strategy"
            );
        }
    }

    public record Rule(String id, String statement) {
        public Rule {
            id = token(id, "rule id");
            statement = text(statement, "rule statement");
        }
    }

    public record ComponentGraph(
            List<Component> components,
            List<ComponentEdge> edges
    ) {
        public ComponentGraph {
            components = nonEmpty(components, "components");
            edges = nonEmpty(edges, "component edges");
            final Set<String> ids = new HashSet<>();
            components.forEach(component -> {
                if (!ids.add(component.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate component id"
                    );
                }
            });
            if (edges.stream().anyMatch(edge ->
                    !ids.contains(edge.fromComponent())
                            || !ids.contains(edge.toComponent())
            )) {
                throw new IllegalArgumentException(
                        "Component edge references an unknown component"
                );
            }
        }
    }

    public record Component(
            String id,
            String role,
            int minimumCount,
            int maximumCount
    ) {
        public Component {
            id = token(id, "component id");
            role = token(role, "component role");
            if (minimumCount < 1 || maximumCount < minimumCount) {
                throw new IllegalArgumentException(
                        "Invalid component cardinality"
                );
            }
        }
    }

    public record ComponentEdge(
            String fromComponent,
            String toComponent,
            String relation
    ) {
        public ComponentEdge {
            fromComponent = token(fromComponent, "edge source");
            toComponent = token(toComponent, "edge target");
            relation = token(relation, "edge relation");
        }
    }

    public record ResourceFlow(
            String selector,
            int minimumCount,
            boolean consumed,
            String role
    ) {
        public ResourceFlow {
            selector = identifier(selector, "resource selector");
            if (minimumCount < 1) {
                throw new IllegalArgumentException(
                        "Resource count must be positive"
                );
            }
            role = token(role, "resource role");
        }
    }

    public record MaterialSubstitution(
            String componentRole,
            List<String> alternatives
    ) {
        public MaterialSubstitution {
            componentRole = token(componentRole, "component role");
            alternatives = nonEmpty(alternatives, "alternatives");
            alternatives = alternatives.stream()
                    .map(value -> identifier(value, "alternative"))
                    .distinct()
                    .toList();
        }
    }

    public record SafetyClearance(
            String id,
            int horizontalBlocks,
            int verticalBlocks,
            String reason
    ) {
        public SafetyClearance {
            id = token(id, "clearance id");
            if (horizontalBlocks < 0 || verticalBlocks < 0) {
                throw new IllegalArgumentException(
                        "Safety clearance cannot be negative"
                );
            }
            reason = text(reason, "clearance reason");
        }
    }

    public record ExpectedRate(
            double minimumUnits,
            String unit,
            long observationWindowTicks,
            String basis
    ) {
        public ExpectedRate {
            if (!Double.isFinite(minimumUnits) || minimumUnits <= 0.0) {
                throw new IllegalArgumentException(
                        "Expected rate must be finite and positive"
                );
            }
            unit = token(unit, "rate unit");
            if (observationWindowTicks < 1) {
                throw new IllegalArgumentException(
                        "Rate observation window must be positive"
                );
            }
            basis = text(basis, "rate basis");
        }
    }

    public record CommissioningProbe(
            String id,
            String predicate,
            long observationWindowTicks
    ) {
        public CommissioningProbe {
            id = token(id, "probe id");
            predicate = token(predicate, "probe predicate");
            if (observationWindowTicks < 1) {
                throw new IllegalArgumentException(
                        "Probe observation window must be positive"
                );
            }
        }
    }

    public record KnownFailureMode(
            String id,
            String observedSignal,
            String repairStrategyId
    ) {
        public KnownFailureMode {
            id = token(id, "failure id");
            observedSignal = token(observedSignal, "failure signal");
            repairStrategyId = token(
                    repairStrategyId,
                    "failure repair id"
            );
        }
    }

    public record RepairStrategy(String id, List<String> actions) {
        public RepairStrategy {
            id = token(id, "repair id");
            actions = nonEmpty(actions, "repair actions").stream()
                    .map(value -> token(value, "repair action"))
                    .toList();
        }
    }

    private static String token(final String value, final String label) {
        final String checked = Objects.requireNonNull(value, label);
        if (!TOKEN.matcher(checked).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return checked;
    }

    private static String identifier(
            final String value,
            final String label
    ) {
        final String checked = Objects.requireNonNull(value, label);
        if (!IDENTIFIER.matcher(checked).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return checked;
    }

    private static String text(final String value, final String label) {
        final String checked = Objects.requireNonNull(value, label).strip();
        if (checked.isEmpty() || checked.length() > 512) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return checked;
    }

    private static <T> List<T> nonEmpty(
            final List<T> values,
            final String label
    ) {
        final List<T> checked = List.copyOf(
                Objects.requireNonNull(values, label)
        );
        if (checked.isEmpty() || checked.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
