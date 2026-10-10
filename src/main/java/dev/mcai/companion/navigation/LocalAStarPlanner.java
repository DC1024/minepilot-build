package dev.mcai.companion.navigation;

import dev.mcai.companion.perception.PerceptionVec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Bounded 3D A* over only the voxels present in LocalNavSnapshot.
 */
public final class LocalAStarPlanner {
    private static final double EPSILON = 1.0e-9;
    private static final int[][] CARDINAL_DIRECTIONS = {
        {-1, 0},
        {0, -1},
        {0, 1},
        {1, 0}
    };

    private static final Comparator<SearchNode> NODE_ORDER =
        Comparator.comparingDouble(SearchNode::estimatedTotalCost)
            .thenComparingDouble(SearchNode::heuristic)
            .thenComparing(SearchNode::position)
            .thenComparingLong(SearchNode::sequence);

    private final LongSupplier nanoTime;

    public LocalAStarPlanner() {
        this(System::nanoTime);
    }

    LocalAStarPlanner(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public LocalRoute plan(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos goal,
        LocalPlannerOptions options
    ) {
        return plan(snapshot, start, goal, options, false);
    }

    /**
     * Plans from a current player cell whose authoritative body state says it
     * is on the ground.  The current support may be outside the latest ray
     * fan (a common case at a ledge or portal threshold); this exception is
     * scoped to the current cell and never authorizes a future support.
     */
    public LocalRoute plan(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos goal,
        LocalPlannerOptions options,
        boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");

        if (!isValidStart(snapshot, start, currentBodyOnGround)
                || !snapshot.isObserved(goal)) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                goal,
                0,
                snapshot.revision()
            );
        }
        return search(
            snapshot,
            start,
            goal,
            Set.of(goal),
            options,
            currentBodyOnGround
        );
    }

    /**
     * Plans to any member of one caller-supplied, fairly observed goal set.
     * One search owns the complete node and wall-clock budget, so trying
     * several candidate landfalls cannot multiply work inside a server tick.
     */
    public LocalRoute planToAny(
        final LocalNavSnapshot snapshot,
        final GridPos start,
        final Set<GridPos> requestedGoals,
        final LocalPlannerOptions options,
        final boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(requestedGoals, "requestedGoals");
        Objects.requireNonNull(options, "options");
        final GridPos diagnosticGoal = requestedGoals.stream()
            .min(GridPos::compareTo)
            .orElse(start);
        if (!isValidStart(snapshot, start, currentBodyOnGround)) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                diagnosticGoal,
                0,
                snapshot.revision()
            );
        }
        final Set<GridPos> validGoals = requestedGoals.stream()
            .filter(goal -> isValidGoal(
                snapshot,
                start,
                goal,
                currentBodyOnGround
            ))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (validGoals.isEmpty()) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                diagnosticGoal,
                0,
                snapshot.revision()
            );
        }
        return search(
            snapshot,
            start,
            diagnosticGoal,
            validGoals,
            options,
            currentBodyOnGround
        );
    }

    /**
     * Plans to any fairly observed, currently occupiable feet cell whose
     * center is inside the caller's arrival region.
     *
     * <p>This is materially different from planning to the exact target
     * cell and checking the radius only after movement. A moving entity
     * occupies its own target cell, and ordinary waypoints often need the
     * actor to stop near rather than on top of the target. The candidate set
     * is derived exclusively from {@link LocalNavSnapshot}; unknown cells
     * never become route endpoints.</p>
     */
    public LocalRoute planWithinRadius(
        LocalNavSnapshot snapshot,
        GridPos start,
        PerceptionVec3 target,
        double arrivalRadius,
        LocalPlannerOptions options
    ) {
        return planWithinRadius(
            snapshot,
            start,
            target,
            arrivalRadius,
            options,
            false
        );
    }

    /** See the current-body support exception on {@link #plan}. */
    public LocalRoute planWithinRadius(
        LocalNavSnapshot snapshot,
        GridPos start,
        PerceptionVec3 target,
        double arrivalRadius,
        LocalPlannerOptions options,
        boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (!Double.isFinite(arrivalRadius) || arrivalRadius < 0.0) {
            throw new IllegalArgumentException(
                "arrivalRadius must be finite and non-negative"
            );
        }

        final GridPos requestedGoal = new GridPos(
            floorCoordinate(target.x()),
            floorCoordinate(target.y()),
            floorCoordinate(target.z())
        );
        if (!isValidStart(snapshot, start, currentBodyOnGround)) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                requestedGoal,
                0,
                snapshot.revision()
            );
        }

        final Set<GridPos> goals = new HashSet<>();
        for (GridPos candidate : arrivalCandidates(
                snapshot,
                target,
                arrivalRadius
        )) {
            if (insideArrivalRegion(candidate, target, arrivalRadius)
                && isValidGoal(
                    snapshot,
                    start,
                    candidate,
                    currentBodyOnGround
                )) {
                goals.add(candidate);
            }
        }
        if (goals.isEmpty()) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                requestedGoal,
                0,
                snapshot.revision()
            );
        }
        return search(
            snapshot,
            start,
            requestedGoal,
            Set.copyOf(goals),
            options,
            currentBodyOnGround
        );
    }

    /**
     * Enumerates the smaller of the exact arrival cube and the observed map.
     * A normal follow radius covers tens or hundreds of cells while a fair
     * ray snapshot can contain thousands; scanning the entire map before A*
     * consumed the same 2 ms budget reserved for the search itself.
     */
    private static Iterable<GridPos> arrivalCandidates(
        final LocalNavSnapshot snapshot,
        final PerceptionVec3 target,
        final double arrivalRadius
    ) {
        final long minimumX = boundedCeil(
            target.x() - arrivalRadius - 0.5
        );
        final long maximumX = boundedFloor(
            target.x() + arrivalRadius - 0.5
        );
        final long minimumY = boundedCeil(target.y() - arrivalRadius);
        final long maximumY = boundedFloor(target.y() + arrivalRadius);
        final long minimumZ = boundedCeil(
            target.z() - arrivalRadius - 0.5
        );
        final long maximumZ = boundedFloor(
            target.z() + arrivalRadius - 0.5
        );
        final long volume = boundedVolume(
            minimumX,
            maximumX,
            minimumY,
            maximumY,
            minimumZ,
            maximumZ,
            snapshot.observedVoxels().size()
        );
        if (volume >= snapshot.observedVoxels().size()) {
            return snapshot.observedVoxels().keySet();
        }
        final List<GridPos> candidates = new ArrayList<>((int) volume);
        for (long x = minimumX; x <= maximumX; x++) {
            for (long y = minimumY; y <= maximumY; y++) {
                for (long z = minimumZ; z <= maximumZ; z++) {
                    candidates.add(new GridPos((int) x, (int) y, (int) z));
                }
            }
        }
        return candidates;
    }

    private static long boundedVolume(
        final long minimumX,
        final long maximumX,
        final long minimumY,
        final long maximumY,
        final long minimumZ,
        final long maximumZ,
        final int observedSize
    ) {
        if (minimumX > maximumX
                || minimumY > maximumY
                || minimumZ > maximumZ) {
            return 0L;
        }
        final long limit = Math.max(0L, observedSize);
        long volume = maximumX - minimumX + 1L;
        final long spanY = maximumY - minimumY + 1L;
        final long spanZ = maximumZ - minimumZ + 1L;
        if (volume > limit
                || spanY > 0L && volume > limit / spanY) {
            return limit;
        }
        volume *= spanY;
        if (volume > limit
                || spanZ > 0L && volume > limit / spanZ) {
            return limit;
        }
        return volume * spanZ;
    }

    private static long boundedCeil(final double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (long) Math.ceil(value);
    }

    private static long boundedFloor(final double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (long) Math.floor(value);
    }

    /**
     * Returns one safely observed local step that advances toward a target
     * whose complete corridor is not visible yet.
     *
     * <p>A first-person player normally walks to the visible frontier and
     * gains a new view; requiring the whole route to be proven before taking
     * the first step makes an otherwise fair ray sampler deadlock. This
     * method considers only transitions already accepted by the same
     * fail-closed movement rules as A*. It never invents an unknown endpoint
     * and never chooses a step whose horizontal component points away from
     * the target. A tangential step remains necessary to begin a fair detour
     * around an observed wall; the caller is responsible for bounding repeated
     * frontier cells so this cannot become a navigation loop.</p>
     */
    public LocalRoute planTowardObserved(
        final LocalNavSnapshot snapshot,
        final GridPos start,
        final PerceptionVec3 target,
        final LocalPlannerOptions options
    ) {
        return planTowardObserved(
            snapshot,
            start,
            target,
            options,
            false
        );
    }

    /** See the current-body support exception on {@link #plan}. */
    public LocalRoute planTowardObserved(
        final LocalNavSnapshot snapshot,
        final GridPos start,
        final PerceptionVec3 target,
        final LocalPlannerOptions options,
        final boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        final GridPos requestedGoal = new GridPos(
            floorCoordinate(target.x()),
            floorCoordinate(target.y()),
            floorCoordinate(target.z())
        );
        if (!isValidStart(snapshot, start, currentBodyOnGround)) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                requestedGoal,
                0,
                snapshot.revision()
            );
        }

        final double targetDeltaX =
                target.x() - (start.x() + 0.5);
        final double targetDeltaZ =
                target.z() - (start.z() + 0.5);
        final Optional<Transition> best = transitions(
                snapshot,
                start,
                start,
                options
            ).stream()
            .filter(transition -> {
                final double stepX =
                        transition.destination().x() - start.x();
                final double stepZ =
                        transition.destination().z() - start.z();
                return stepX * targetDeltaX
                        + stepZ * targetDeltaZ
                        >= -EPSILON;
            })
            .min(
                Comparator.comparingDouble(
                        (Transition transition) ->
                            squaredTargetDistance(
                                transition.destination(),
                                target
                            )
                    )
                    .thenComparingDouble(Transition::cost)
                    .thenComparing(Transition::destination)
                    .thenComparing(
                        transition -> transition.primitive().ordinal()
                    )
            );
        if (best.isEmpty()) {
            return LocalRoute.failure(
                LocalRouteStatus.NO_PATH,
                start,
                requestedGoal,
                1,
                snapshot.revision()
            );
        }

        final Transition transition = best.orElseThrow();
        final GridPos reached = transition.destination();
        final LocalStep step = new LocalStep(
            start,
            reached,
            transition.primitive(),
            transition.cost(),
            transition.danger(),
            snapshot.revision(),
            transition.dependencies()
        );
        return new LocalRoute(
            LocalRouteStatus.FOUND,
            start,
            reached,
            reached,
            List.of(step),
            transition.cost(),
            1,
            snapshot.revision()
        );
    }

    private LocalRoute search(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos requestedGoal,
        Set<GridPos> goals,
        LocalPlannerOptions options,
        boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(goals, "goals");
        if (goals.isEmpty()) {
            return LocalRoute.failure(
                LocalRouteStatus.INVALID_START_OR_GOAL,
                start,
                requestedGoal,
                0,
                snapshot.revision()
            );
        }
        if (goals.contains(start)) {
            return new LocalRoute(
                LocalRouteStatus.FOUND,
                start,
                start,
                start,
                List.of(),
                0.0,
                0,
                snapshot.revision()
            );
        }

        final long startedAt = nanoTime.getAsLong();
        final long budgetNanos = options.budget().maximumWallTime().toNanos();
        final PriorityQueue<SearchNode> open = new PriorityQueue<>(NODE_ORDER);
        final Map<GridPos, Double> bestCosts = new HashMap<>();
        final Map<GridPos, PreviousStep> previous = new HashMap<>();
        final GoalBounds goalBounds = GoalBounds.of(goals);
        long sequence = 0L;
        final double initialHeuristic = heuristic(start, goalBounds);
        open.add(new SearchNode(start, 0.0, initialHeuristic, sequence++));
        bestCosts.put(start, 0.0);
        int expanded = 0;

        while (!open.isEmpty()) {
            if (nanoTime.getAsLong() - startedAt >= budgetNanos) {
                return LocalRoute.failure(
                    LocalRouteStatus.TIME_BUDGET_EXCEEDED,
                    start,
                    requestedGoal,
                    expanded,
                    snapshot.revision()
                );
            }
            if (expanded >= options.budget().maximumExpandedNodes()) {
                return LocalRoute.failure(
                    LocalRouteStatus.NODE_BUDGET_EXCEEDED,
                    start,
                    requestedGoal,
                    expanded,
                    snapshot.revision()
                );
            }

            final SearchNode current = open.remove();
            final double knownCost = bestCosts.getOrDefault(
                current.position(),
                Double.POSITIVE_INFINITY
            );
            if (current.costFromStart() > knownCost + EPSILON) {
                continue;
            }
            expanded++;
            if (goals.contains(current.position())) {
                return reconstruct(
                    start,
                    current.position(),
                    current.costFromStart(),
                    expanded,
                    snapshot.revision(),
                    previous
                );
            }

            for (Transition transition : transitions(
                    snapshot,
                    start,
                    current.position(),
                    options
            )) {
                final double candidateCost = current.costFromStart() + transition.cost();
                final double existingCost = bestCosts.getOrDefault(
                    transition.destination(),
                    Double.POSITIVE_INFINITY
                );
                if (candidateCost + EPSILON >= existingCost) {
                    continue;
                }
                bestCosts.put(transition.destination(), candidateCost);
                previous.put(
                    transition.destination(),
                    new PreviousStep(current.position(), transition)
                );
                final double remaining = heuristic(
                    transition.destination(),
                    goalBounds
                );
                open.add(new SearchNode(
                    transition.destination(),
                    candidateCost,
                    remaining,
                    sequence++
                ));
            }
        }

        return LocalRoute.failure(
            LocalRouteStatus.NO_PATH,
            start,
            requestedGoal,
            expanded,
            snapshot.revision()
        );
    }

    private static List<Transition> transitions(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos current,
        LocalPlannerOptions options
    ) {
        final List<Transition> transitions = new ArrayList<>(16);
        final ObservedVoxel currentVoxel = snapshot.voxelAt(current).orElseThrow();
        for (int[] direction : CARDINAL_DIRECTIONS) {
            final int deltaX = direction[0];
            final int deltaZ = direction[1];
            addHorizontalTransition(
                snapshot,
                start,
                current,
                currentVoxel,
                deltaX,
                deltaZ,
                options,
                transitions
            );
            addStepUpTransition(
                snapshot,
                start,
                current,
                deltaX,
                deltaZ,
                options,
                transitions
            );
            addDropTransition(
                snapshot,
                start,
                current,
                deltaX,
                deltaZ,
                options,
                transitions
            );
        }
        addVerticalTransitions(snapshot, current, currentVoxel, options, transitions);
        transitions.sort(
            Comparator.comparing(Transition::destination)
                .thenComparing(transition -> transition.primitive().ordinal())
        );
        return transitions;
    }

    private static void addHorizontalTransition(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos current,
        ObservedVoxel currentVoxel,
        int deltaX,
        int deltaZ,
        LocalPlannerOptions options,
        List<Transition> output
    ) {
        final GridPos destination = current.offset(deltaX, 0, deltaZ);
        final Optional<ObservedVoxel> destinationVoxel = snapshot.voxelAt(destination);
        if (destinationVoxel.isEmpty()) {
            return;
        }
        final ObservedVoxel voxel = destinationVoxel.orElseThrow();
        final GridPos head = destination.above();
        final GridPos support = destination.below();

        if (NavigationEvidence.isFreshClosedDoor(
                voxel,
                snapshot.revision()
            )
            && isPassableObserved(snapshot, head)
            && supportsObserved(snapshot, support, start)) {
            output.add(transition(
                snapshot,
                current,
                destination,
                MovementPrimitive.OPEN_DOOR,
                1.8,
                maxDanger(snapshot, Set.of(destination, head)),
                0,
                options,
                Set.of(destination, head, support)
            ));
            return;
        }

        if (voxel.kind().isLiquid()
            && isPassableObserved(snapshot, destination)
            && isPassableObserved(snapshot, head)) {
            output.add(transition(
                snapshot,
                current,
                destination,
                MovementPrimitive.SWIM,
                1.4,
                maxDanger(snapshot, Set.of(destination, head)),
                0,
                options,
                Set.of(destination, head)
            ));
            return;
        }

        if (!isPassableObserved(snapshot, destination)
                || !isPassableObserved(snapshot, head)) {
            return;
        }
        if (supportsObserved(snapshot, support, start)) {
            final MovementPrimitive primitive = currentVoxel.kind().isLiquid()
                ? MovementPrimitive.SWIM
                : options.allowSprint()
                && voxel.effectiveDanger() == 0.0
                ? MovementPrimitive.SPRINT
                : MovementPrimitive.WALK;
            output.add(transition(
                snapshot,
                current,
                destination,
                primitive,
                primitive == MovementPrimitive.SPRINT ? 0.8 : 1.0,
                maxDanger(snapshot, Set.of(destination, head)),
                0,
                options,
                Set.of(destination, head, support)
            ));
        } else if (options.allowBuilding()
            && isPassableObserved(snapshot, support)) {
            output.add(transition(
                snapshot,
                current,
                destination,
                MovementPrimitive.BRIDGE,
                3.0,
                maxDanger(snapshot, Set.of(destination, head, support)),
                0,
                options,
                Set.of(destination, head, support)
            ));
        }
    }

    private static void addStepUpTransition(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos current,
        int deltaX,
        int deltaZ,
        LocalPlannerOptions options,
        List<Transition> output
    ) {
        final GridPos destination = current.offset(deltaX, 1, deltaZ);
        final GridPos head = destination.above();
        final GridPos support = destination.below();
        final GridPos launchClearance = current.above(2);
        final Set<GridPos> dependencies = Set.of(
            destination,
            head,
            support,
            launchClearance
        );
        if (isPassableObserved(snapshot, destination)
            && isPassableObserved(snapshot, head)
            && isPassableObserved(snapshot, launchClearance)
            && supportsObserved(snapshot, support, start)) {
            output.add(transition(
                snapshot,
                current,
                destination,
                MovementPrimitive.JUMP,
                1.6,
                maxDanger(snapshot, dependencies),
                0,
                options,
                dependencies
            ));
        }
    }

    private static void addDropTransition(
        LocalNavSnapshot snapshot,
        GridPos start,
        GridPos current,
        int deltaX,
        int deltaZ,
        LocalPlannerOptions options,
        List<Transition> output
    ) {
        final Set<GridPos> observedColumn = new HashSet<>();
        for (int drop = 1; drop <= options.maximumDrop(); drop++) {
            final GridPos destination = current.offset(deltaX, -drop, deltaZ);
            final GridPos head = destination.above();
            final GridPos support = destination.below();
            observedColumn.add(destination);
            observedColumn.add(head);
            if (!isPassableObserved(snapshot, destination)
                || !isPassableObserved(snapshot, head)) {
                return;
            }
            if (supportsObserved(snapshot, support, start)) {
                observedColumn.add(support);
                output.add(transition(
                    snapshot,
                    current,
                    destination,
                    MovementPrimitive.JUMP,
                    1.2 + drop * 0.25,
                    maxDanger(snapshot, observedColumn),
                    drop,
                    options,
                    Set.copyOf(observedColumn)
                ));
                return;
            }
            if (!snapshot.isObserved(support)) {
                return;
            }
        }
    }

    private static void addVerticalTransitions(
        LocalNavSnapshot snapshot,
        GridPos current,
        ObservedVoxel currentVoxel,
        LocalPlannerOptions options,
        List<Transition> output
    ) {
        for (int deltaY : new int[]{-1, 1}) {
            final GridPos destination = current.offset(0, deltaY, 0);
            final Optional<ObservedVoxel> destinationVoxel = snapshot.voxelAt(destination);
            if (destinationVoxel.isEmpty()) {
                continue;
            }
            final ObservedVoxel voxel = destinationVoxel.orElseThrow();
            final GridPos upwardHeadClearance = destination.above();
            if (deltaY > 0 && !isPassableObserved(snapshot, upwardHeadClearance)) {
                continue;
            }
            final Set<GridPos> dependencies = deltaY > 0
                ? Set.of(current, destination, upwardHeadClearance)
                : Set.of(current, destination);
            if ((currentVoxel.kind().isClimbable() || voxel.kind().isClimbable())
                && NavigationEvidence.hasFreshTraversalClearance(
                    voxel,
                    snapshot.revision()
                )) {
                output.add(transition(
                    snapshot,
                    current,
                    destination,
                    MovementPrimitive.CLIMB,
                    1.5,
                    maxDanger(snapshot, dependencies),
                    0,
                    options,
                    dependencies
                ));
            } else if (currentVoxel.kind().isLiquid()
                && voxel.kind().isLiquid()
                && NavigationEvidence.hasFreshTraversalClearance(
                    voxel,
                    snapshot.revision()
                )) {
                output.add(transition(
                    snapshot,
                    current,
                    destination,
                    MovementPrimitive.SWIM,
                    1.4,
                    maxDanger(snapshot, dependencies),
                    0,
                    options,
                    dependencies
                ));
            }
        }

        if (options.allowBuilding()) {
            final GridPos destination = current.above();
            final GridPos head = destination.above();
            if (isPassableObserved(snapshot, destination)
                && isPassableObserved(snapshot, head)) {
                output.add(transition(
                    snapshot,
                    current,
                    destination,
                    MovementPrimitive.PILLAR,
                    4.0,
                    maxDanger(snapshot, Set.of(destination, head)),
                    0,
                    options,
                    Set.of(current, destination, head)
                ));
            }
        }
    }

    private static Transition transition(
        LocalNavSnapshot snapshot,
        GridPos from,
        GridPos destination,
        MovementPrimitive primitive,
        double baseCost,
        double danger,
        int drop,
        LocalPlannerOptions options,
        Set<GridPos> dependencies
    ) {
        final double fallRisk = drop <= 2 ? 0.0 : (drop - 2.0) * (drop - 2.0);
        final double cost = baseCost
            + danger * options.riskProfile().voxelDangerWeight()
            + fallRisk * options.riskProfile().fallDangerWeight();
        return new Transition(
            destination,
            primitive,
            cost,
            danger,
            snapshot.revision(),
            dependencies
        );
    }

    private static boolean isValidStart(
        LocalNavSnapshot snapshot,
        GridPos start,
        boolean currentBodyOnGround
    ) {
        final Optional<ObservedVoxel> startVoxel = snapshot.voxelAt(start);
        if (startVoxel.isEmpty()
                || !NavigationEvidence.hasFreshTraversalClearance(
                        startVoxel.orElseThrow(),
                        snapshot.revision()
                )) {
            return false;
        }
        if (!isPassableObserved(snapshot, start.above())) {
            return false;
        }
        if (startVoxel.orElseThrow().kind().isLiquid()
            || startVoxel.orElseThrow().kind().isClimbable()) {
            return true;
        }
        if (currentBodyOnGround
                && startVoxel.orElseThrow().occupancyEvidence()
                    .isFullBodyFact()) {
            return true;
        }
        return snapshot.voxelAt(start.below())
            .map(voxel -> NavigationEvidence.supportsCurrentBody(
                    voxel,
                    snapshot.revision()
            ))
            .orElse(false);
    }

    private static boolean isPassableObserved(
        LocalNavSnapshot snapshot,
        GridPos position
    ) {
        return snapshot.voxelAt(position)
            .map(voxel ->
                NavigationEvidence.hasFreshTraversalClearance(
                    voxel,
                    snapshot.revision()
                )
            )
            .orElse(false);
    }

    private static boolean supportsObserved(
        LocalNavSnapshot snapshot,
        GridPos position,
        GridPos start
    ) {
        return snapshot.voxelAt(position)
            .map(voxel -> position.equals(start.below())
                ? NavigationEvidence.supportsCurrentBody(
                        voxel,
                        snapshot.revision()
                )
                : NavigationEvidence.isFreshStandingSupport(
                        voxel,
                        snapshot.revision()
                ))
            .orElse(false);
    }

    private static double maxDanger(
        LocalNavSnapshot snapshot,
        Set<GridPos> positions
    ) {
        double danger = 0.0;
        for (GridPos position : positions) {
            danger = Math.max(
                danger,
                snapshot.voxelAt(position)
                    .map(ObservedVoxel::effectiveDanger)
                    .orElse(1.0)
            );
        }
        return danger;
    }

    private static double heuristic(GridPos position, GridPos goal) {
        return position.manhattanDistance(goal) * 0.8;
    }

    private static double squaredTargetDistance(
        final GridPos position,
        final PerceptionVec3 target
    ) {
        final double deltaX = position.x() + 0.5 - target.x();
        final double deltaY = position.y() - target.y();
        final double deltaZ = position.z() + 0.5 - target.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private static double heuristic(
        GridPos position,
        GoalBounds goals
    ) {
        return (
            distanceOutside(position.x(), goals.minimumX(), goals.maximumX())
                + distanceOutside(
                    position.y(),
                    goals.minimumY(),
                    goals.maximumY()
                )
                + distanceOutside(
                    position.z(),
                    goals.minimumZ(),
                    goals.maximumZ()
                )
        ) * 0.8;
    }

    private static long distanceOutside(
        final int coordinate,
        final int minimum,
        final int maximum
    ) {
        if (coordinate < minimum) {
            return (long) minimum - coordinate;
        }
        if (coordinate > maximum) {
            return (long) coordinate - maximum;
        }
        return 0L;
    }

    private static boolean insideArrivalRegion(
        GridPos candidate,
        PerceptionVec3 target,
        double arrivalRadius
    ) {
        final double deltaX = candidate.x() + 0.5 - target.x();
        final double deltaY = candidate.y() - target.y();
        final double deltaZ = candidate.z() + 0.5 - target.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
            <= arrivalRadius * arrivalRadius + EPSILON;
    }

    private static boolean isValidGoal(
        LocalNavSnapshot snapshot,
        GridPos actualStart,
        GridPos candidate,
        boolean currentBodyOnGround
    ) {
        if (candidate.equals(actualStart)) {
            return isValidStart(
                snapshot,
                actualStart,
                currentBodyOnGround
            );
        }
        final Optional<ObservedVoxel> feet =
            snapshot.voxelAt(candidate);
        if (feet.isEmpty()
            || !NavigationEvidence.hasFreshTraversalClearance(
                feet.orElseThrow(),
                snapshot.revision()
            )
            || !isPassableObserved(snapshot, candidate.above())) {
            return false;
        }
        if (feet.orElseThrow().kind().isLiquid()
            || feet.orElseThrow().kind().isClimbable()) {
            return true;
        }
        return snapshot.voxelAt(candidate.below())
            .map(voxel -> NavigationEvidence.isFreshStandingSupport(
                voxel,
                snapshot.revision()
            ))
            .orElse(false);
    }

    private static int floorCoordinate(double coordinate) {
        final double floor = Math.floor(coordinate);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Target coordinate is outside grid bounds"
            );
        }
        return (int) floor;
    }

    private static LocalRoute reconstruct(
        GridPos start,
        GridPos goal,
        double totalCost,
        int expanded,
        long revision,
        Map<GridPos, PreviousStep> previous
    ) {
        final Deque<LocalStep> steps = new ArrayDeque<>();
        GridPos cursor = goal;
        while (!cursor.equals(start)) {
            final PreviousStep predecessor = previous.get(cursor);
            if (predecessor == null) {
                throw new IllegalStateException("A* predecessor chain is incomplete");
            }
            final Transition transition = predecessor.transition();
            steps.addFirst(new LocalStep(
                predecessor.position(),
                cursor,
                transition.primitive(),
                transition.cost(),
                transition.danger(),
                revision,
                transition.dependencies()
            ));
            cursor = predecessor.position();
        }
        return new LocalRoute(
            LocalRouteStatus.FOUND,
            start,
            goal,
            goal,
            List.copyOf(steps),
            totalCost,
            expanded,
            revision
        );
    }

    private record SearchNode(
        GridPos position,
        double costFromStart,
        double heuristic,
        long sequence
    ) {
        double estimatedTotalCost() {
            return costFromStart + heuristic;
        }
    }

    private record PreviousStep(GridPos position, Transition transition) {
    }

    /**
     * Constant-time admissible lower bound for a possibly large goal set.
     * The box may contain cells that are not goals, which only makes the
     * heuristic more conservative; it never overestimates route cost.
     */
    private record GoalBounds(
        int minimumX,
        int maximumX,
        int minimumY,
        int maximumY,
        int minimumZ,
        int maximumZ
    ) {
        private static GoalBounds of(final Set<GridPos> goals) {
            if (goals.isEmpty()) {
                throw new IllegalArgumentException(
                    "Goal bounds require at least one goal"
                );
            }
            int minimumX = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            int minimumY = Integer.MAX_VALUE;
            int maximumY = Integer.MIN_VALUE;
            int minimumZ = Integer.MAX_VALUE;
            int maximumZ = Integer.MIN_VALUE;
            for (GridPos goal : goals) {
                minimumX = Math.min(minimumX, goal.x());
                maximumX = Math.max(maximumX, goal.x());
                minimumY = Math.min(minimumY, goal.y());
                maximumY = Math.max(maximumY, goal.y());
                minimumZ = Math.min(minimumZ, goal.z());
                maximumZ = Math.max(maximumZ, goal.z());
            }
            return new GoalBounds(
                minimumX,
                maximumX,
                minimumY,
                maximumY,
                minimumZ,
                maximumZ
            );
        }
    }

    private record Transition(
        GridPos destination,
        MovementPrimitive primitive,
        double cost,
        double danger,
        long revision,
        Set<GridPos> dependencies
    ) {
    }
}
