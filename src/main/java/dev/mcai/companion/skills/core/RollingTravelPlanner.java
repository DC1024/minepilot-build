package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.LocalPlannerOptions;
import dev.mcai.companion.navigation.LocalRoute;
import dev.mcai.companion.navigation.LocalRouteStatus;
import dev.mcai.companion.navigation.LocalStep;
import dev.mcai.companion.navigation.MovementPrimitive;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.NavigationRiskProfile;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.PerceptionVec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selects one short, fully observed A* segment toward a remote destination.
 *
 * <p>It never fabricates a voxel or asks Minecraft for chunks. Unknown cells
 * are absent from {@link LocalNavSnapshot} and therefore cannot become a
 * segment target or route dependency.</p>
 */
public final class RollingTravelPlanner {
    private static final double MINIMUM_PROGRESS = 0.20;
    private static final double FALLBACK_SAFE_RADIUS = 3.0;
    private static final double COURSE_BAND_SEGMENT_MULTIPLIER = 1.5;
    private static final double MINIMUM_COURSE_BAND = 6.0;
    private static final double COURSE_RECOVERY_TRIGGER_RATIO = 0.75;
    private static final double COURSE_SIDE_TOLERANCE = 1.25;
    private static final double MAXIMUM_DETOUR_DISTANCE_INCREASE = 1.0;
    private static final double DIRECTIONAL_PROGRESS_EPSILON = 1.0E-9;

    private final LocalAStarPlanner planner;
    private final CoreSkillPolicy corePolicy;
    private final TravelSkillPolicy travelPolicy;

    public RollingTravelPlanner(
            LocalAStarPlanner planner,
            CoreSkillPolicy corePolicy,
            TravelSkillPolicy travelPolicy
    ) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.corePolicy = Objects.requireNonNull(corePolicy, "corePolicy");
        this.travelPolicy = Objects.requireNonNull(
                travelPolicy,
                "travelPolicy"
        );
    }

    public SegmentSelection select(
            LocalNavSnapshot snapshot,
            GridPos start,
            TravelToParameters target,
            boolean hardcore,
            Set<GridPos> rejected
    ) {
        return select(
                snapshot,
                start,
                target,
                hardcore,
                rejected,
                new PerceptionVec3(
                        start.x() + 0.5,
                        start.y(),
                        start.z() + 0.5
                ),
                false,
                0,
                false
        );
    }

    SegmentSelection select(
            LocalNavSnapshot snapshot,
            GridPos start,
            TravelToParameters target,
            boolean hardcore,
            Set<GridPos> rejected,
            PerceptionVec3 courseOrigin,
            boolean recoveringCourse,
            int rejectedCourseSide
    ) {
        return select(
                snapshot,
                start,
                target,
                hardcore,
                rejected,
                courseOrigin,
                recoveringCourse,
                rejectedCourseSide,
                false
        );
    }

    SegmentSelection select(
            LocalNavSnapshot snapshot,
            GridPos start,
            TravelToParameters target,
            boolean hardcore,
            Set<GridPos> rejected,
            PerceptionVec3 courseOrigin,
            boolean recoveringCourse,
            int rejectedCourseSide,
            boolean currentBodyOnGround
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(courseOrigin, "courseOrigin");
        if (rejectedCourseSide < -1 || rejectedCourseSide > 1) {
            throw new IllegalArgumentException(
                    "rejectedCourseSide must be -1, 0, or 1"
            );
        }
        if (!snapshot.dimension().equals(target.dimension())) {
            return SegmentSelection.blocked(
                    SelectionStatus.DIMENSION_MISMATCH,
                    false
            );
        }

        final double dangerLimit = dangerLimit(hardcore);
        final boolean targetObserved = targetVicinityObserved(
                snapshot,
                target.gridGoal()
        );
        final List<Candidate> advancingCandidates = new ArrayList<>();
        final List<Candidate> recoveryCandidates = new ArrayList<>();
        final List<Candidate> detourCandidates = new ArrayList<>();
        final List<Candidate> reverseFrontierCandidates = new ArrayList<>();
        boolean dangerSeen = false;
        final double startDistance = distanceToTarget(start, target);
        final double targetDeltaX =
                target.x() - (start.x() + 0.5);
        final double targetDeltaZ =
                target.z() - (start.z() + 0.5);
        final double signedStartCourseDeviation =
                signedCourseDeviation(
                        courseOrigin,
                        target,
                        start
                );
        final double startCourseDeviation = Math.abs(
                signedStartCourseDeviation
        );
        final int startCourseSide =
                signedStartCourseDeviation > COURSE_SIDE_TOLERANCE
                        ? 1
                        : signedStartCourseDeviation
                            < -COURSE_SIDE_TOLERANCE
                                ? -1
                                : 0;
        final double courseBand = courseBand();
        final double minimumDetourDeviation = Math.max(
                COURSE_SIDE_TOLERANCE,
                startCourseDeviation + MINIMUM_PROGRESS
        );

        for (ObservedVoxel voxel : snapshot.observedVoxels().values()) {
            final GridPos position = voxel.position();
            if (position.equals(start)
                    || rejected.contains(position)
                    || start.euclideanDistance(position)
                    > travelPolicy.maximumSegmentDistance()) {
                continue;
            }
            Standability standability = standability(
                    snapshot,
                    position,
                    dangerLimit,
                    false
            );
            if (standability == Standability.DANGEROUS) {
                if (distanceToTarget(position, target)
                        + MINIMUM_PROGRESS < startDistance) {
                    dangerSeen = true;
                }
                continue;
            }
            if (standability != Standability.SAFE
                    || !dependenciesRecent(snapshot, Set.of(
                            position,
                            position.above(),
                            position.below()
                    ))) {
                continue;
            }
            final double targetDistance = distanceToTarget(position, target);
            final boolean arrival = targetObserved
                    && targetDistance <= Math.max(
                            target.arrivalRadius(),
                            FALLBACK_SAFE_RADIUS
                    );
            final double targetProjection =
                    (position.x() - start.x()) * targetDeltaX
                            + (position.z() - start.z()) * targetDeltaZ;
            final double signedCourseDeviation = signedCourseDeviation(
                    courseOrigin,
                    target,
                    position
            );
            final double candidateCourseDeviation =
                    Math.abs(signedCourseDeviation);
            final boolean advancesTarget = arrival
                    || targetDistance + MINIMUM_PROGRESS < startDistance;
            final boolean rejectedSide = rejectedCourseSide != 0
                    && Math.signum(signedCourseDeviation)
                        == rejectedCourseSide
                    && candidateCourseDeviation > COURSE_SIDE_TOLERANCE;
            if (advancesTarget
                    && targetProjection >= -DIRECTIONAL_PROGRESS_EPSILON
                    && candidateCourseDeviation <= courseBand
                    && !rejectedSide) {
                advancingCandidates.add(new Candidate(
                        position,
                        targetDistance,
                        candidateCourseDeviation,
                        arrival
                ));
            }
            if (candidateCourseDeviation + MINIMUM_PROGRESS
                    < startCourseDeviation) {
                recoveryCandidates.add(new Candidate(
                        position,
                        targetDistance,
                        candidateCourseDeviation,
                        false
                ));
            }
            /*
             * A fair local map can end at a junction where every observed
             * cell that closes the Euclidean distance has already been
             * rejected as a dead end. In that state the only honest way to
             * discover the branch is one bounded, adjacent step away from
             * the waypoint. Keep this separate from ordinary detours so a
             * course-recovery preference cannot repeatedly pull the body
             * back onto the same dead-end corridor.
             */
            final boolean boundedReverseFrontier = !advancesTarget
                    && candidateCourseDeviation <= courseBand
                    && start.euclideanDistance(position) <= 2.0
                    && targetDistance
                        <= startDistance + MAXIMUM_DETOUR_DISTANCE_INCREASE
                    && (position.x() - start.x()) * targetDeltaX
                            + (position.z() - start.z()) * targetDeltaZ
                        < -DIRECTIONAL_PROGRESS_EPSILON
                    && !rejectedSide;
            if (boundedReverseFrontier) {
                reverseFrontierCandidates.add(new Candidate(
                        position,
                        targetDistance,
                        candidateCourseDeviation,
                        false
                ));
            }
            final int candidateSide =
                    signedCourseDeviation > COURSE_SIDE_TOLERANCE
                            ? 1
                            : signedCourseDeviation
                                < -COURSE_SIDE_TOLERANCE
                                    ? -1
                                    : 0;
            final boolean continuesCurrentDetour =
                    startCourseSide == 0
                        || startCourseSide == rejectedCourseSide
                        || candidateSide == startCourseSide;
            if (!advancesTarget
                    && candidateCourseDeviation <= courseBand
                    && candidateCourseDeviation
                        >= minimumDetourDeviation
                    && candidateSide != 0
                    /*
                     * A detour may be tangential or mildly farther from a
                     * waypoint while it goes around an observed wall, but it
                     * must not point materially backwards.  Without this
                     * projection guard a safe side cell could be selected
                     * merely because it was visible, causing a body to walk
                     * away from the requested destination for hundreds of
                     * locally successful segments.
                     */
                    && (position.x() - start.x()) * targetDeltaX
                            + (position.z() - start.z()) * targetDeltaZ
                        >= -DIRECTIONAL_PROGRESS_EPSILON
                    && targetDistance
                            <= startDistance
                                + MAXIMUM_DETOUR_DISTANCE_INCREASE
                    && !rejectedSide
                    && continuesCurrentDetour) {
                detourCandidates.add(new Candidate(
                        position,
                        targetDistance,
                        candidateCourseDeviation,
                        false
                ));
            }
        }

        advancingCandidates.sort(
                Comparator.comparing(Candidate::arrival).reversed()
                        .thenComparingDouble(Candidate::targetDistance)
                        .thenComparingDouble(
                                Candidate::courseDeviation
                        )
                        .thenComparing(
                                Comparator.comparingDouble(
                                        (Candidate candidate) ->
                                                start.euclideanDistance(
                                                candidate.position()
                                        )
                                ).reversed()
                        )
                        .thenComparing(Candidate::position)
        );
        recoveryCandidates.sort(
                Comparator.comparingDouble(Candidate::courseDeviation)
                        .thenComparingDouble(Candidate::targetDistance)
                        .thenComparing(
                                Comparator.comparingDouble(
                                        (Candidate candidate) ->
                                                start.euclideanDistance(
                                                candidate.position()
                                        )
                                ).reversed()
                        )
                        .thenComparing(Candidate::position)
        );
        detourCandidates.sort(
                Comparator.comparingDouble(
                        Candidate::courseDeviation
                ).reversed()
                        .thenComparingDouble(Candidate::targetDistance)
                        .thenComparing(
                                Comparator.comparingDouble(
                                        (Candidate candidate) ->
                                                start.euclideanDistance(
                                                candidate.position()
                                        )
                                ).reversed()
                        )
                        .thenComparing(Candidate::position)
        );
        reverseFrontierCandidates.sort(
                Comparator.comparingDouble(
                        (Candidate candidate) ->
                                start.euclideanDistance(candidate.position())
                ).thenComparingDouble(Candidate::targetDistance)
                        .thenComparing(Candidate::position)
        );

        final boolean strandedOnRejectedSide =
                rejectedCourseSide != 0
                    && startCourseSide == rejectedCourseSide;
        final boolean shouldRecoverCourse = recoveringCourse
                || advancingCandidates.isEmpty()
                    && (strandedOnRejectedSide
                        || startCourseDeviation
                            >= courseBand
                                * COURSE_RECOVERY_TRIGGER_RATIO)
                    && !recoveryCandidates.isEmpty();
        final boolean shouldUseReverseFrontier =
                advancingCandidates.isEmpty()
                    && !reverseFrontierCandidates.isEmpty();
        final List<Candidate> candidates = shouldUseReverseFrontier
                ? reverseFrontierCandidates
                : shouldRecoverCourse
                    ? recoveryCandidates
                    : !advancingCandidates.isEmpty()
                        ? advancingCandidates
                        : detourCandidates;
        if (candidates.isEmpty()) {
            /*
             * A remote waypoint is commonly outside the current first-person
             * corridor.  In that state a complete A* segment cannot exist
             * yet, but a single already-observed frontier step can still move
             * the body to the next honest camera vantage point.  MoveTo uses
             * the same bounded primitive; TravelTo must not wait for a full
             * corridor and thereby spin forever during ordinary exploration.
             */
            if (!dangerSeen) {
                final LocalPlannerOptions frontierOptions =
                        new LocalPlannerOptions(
                                hardcore
                                        ? NavigationRiskProfile.HARDCORE
                                        : NavigationRiskProfile.NORMAL,
                                corePolicy.planningBudget(),
                                hardcore ? 1 : 3,
                                true,
                                false
                        );
                final LocalRoute frontier = planner.planTowardObserved(
                        snapshot,
                        start,
                        new PerceptionVec3(
                                target.x(),
                                target.y(),
                                target.z()
                        ),
                        frontierOptions,
                        currentBodyOnGround
                );
                if (frontier.found()
                        && !frontier.steps().isEmpty()
                        && !rejected.contains(frontier.reached())
                        && routeIsExecutable(
                                snapshot,
                                frontier,
                                dangerLimit
                        )) {
                    final GridPos endpoint = frontier.reached();
                    return SegmentSelection.found(
                            endpoint,
                            false,
                            snapshot.revision(),
                            frontier.steps().size(),
                            false
                    );
                }
            }
            return SegmentSelection.blocked(
                    dangerSeen
                            ? SelectionStatus.DANGER_BLOCKED
                            : SelectionStatus.NEEDS_OBSERVATION,
                    targetObserved
            );
        }

        final Candidate selected = candidates.getFirst();
        final LocalPlannerOptions options = new LocalPlannerOptions(
                hardcore
                        ? NavigationRiskProfile.HARDCORE
                        : NavigationRiskProfile.NORMAL,
                corePolicy.planningBudget(),
                hardcore ? 1 : 3,
                true,
                false
        );
        final LocalRoute route = planner.plan(
                snapshot,
                start,
                selected.position(),
                options,
                currentBodyOnGround
        );
        if (!route.found()) {
            final SelectionStatus status = switch (route.status()) {
                case NODE_BUDGET_EXCEEDED ->
                        SelectionStatus.PLANNING_NODE_BUDGET_EXCEEDED;
                case TIME_BUDGET_EXCEEDED ->
                        SelectionStatus.PLANNING_TIME_BUDGET_EXCEEDED;
                default -> SelectionStatus.CANDIDATE_UNREACHABLE;
            };
            if (status == SelectionStatus.CANDIDATE_UNREACHABLE) {
                /*
                 * Preserve the selected candidate as an explicit bounded
                 * retry.  A disconnected observed cell must be reported as
                 * such so the caller can reject it and replan from the same
                 * fair snapshot; silently switching to a different frontier
                 * here made route diagnostics and reject memory lie.
                 */
                return SegmentSelection.unreachableCandidate(
                        selected.position(),
                        targetObserved,
                        snapshot.revision(),
                        shouldRecoverCourse
                );
            }
            return SegmentSelection.blocked(status, targetObserved);
        }
        if (!routeIsExecutable(snapshot, route, dangerLimit)) {
            return SegmentSelection.blocked(
                    SelectionStatus.DANGER_BLOCKED,
                    targetObserved
            );
        }

        GridPos endpoint = selected.position();
        boolean arrival = selected.arrival();
        if (route.steps().size() > travelPolicy.maximumSegmentSteps()) {
            endpoint = route.steps()
                    .get(travelPolicy.maximumSegmentSteps() - 1)
                    .to();
            arrival = false;
        }
        return SegmentSelection.found(
                endpoint,
                arrival,
                snapshot.revision(),
                route.steps().size(),
                shouldRecoverCourse
        );
    }

    double courseBand() {
        return Math.max(
                MINIMUM_COURSE_BAND,
                travelPolicy.maximumSegmentDistance()
                        * COURSE_BAND_SEGMENT_MULTIPLIER
        );
    }

    double courseRecoveryExitDeviation() {
        return COURSE_SIDE_TOLERANCE * 0.5;
    }

    static double signedCourseDeviation(
            PerceptionVec3 courseOrigin,
            TravelToParameters target,
            GridPos position
    ) {
        Objects.requireNonNull(courseOrigin, "courseOrigin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(position, "position");
        final double courseX = target.x() - courseOrigin.x();
        final double courseZ = target.z() - courseOrigin.z();
        final double courseLength = Math.hypot(courseX, courseZ);
        if (courseLength <= 1.0E-9) {
            return 0.0;
        }
        final double positionX =
                position.x() + 0.5 - courseOrigin.x();
        final double positionZ =
                position.z() + 0.5 - courseOrigin.z();
        return (positionX * courseZ - positionZ * courseX)
                / courseLength;
    }

    static double courseDeviation(
            PerceptionVec3 courseOrigin,
            TravelToParameters target,
            GridPos position
    ) {
        return Math.abs(signedCourseDeviation(
                courseOrigin,
                target,
                position
        ));
    }

    public boolean isSafeArrival(
            LocalNavSnapshot snapshot,
            GridPos position,
            boolean hardcore
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(position, "position");
        return standability(
                snapshot,
                position,
                dangerLimit(hardcore),
                true
        ) == Standability.SAFE
                && dependenciesRecent(snapshot, Set.of(
                        position,
                        position.above(),
                        position.below()
                ));
    }

    public double closestRecentSafeDistance(
            LocalNavSnapshot snapshot,
            TravelToParameters target,
            boolean hardcore
    ) {
        double closest = Double.POSITIVE_INFINITY;
        for (ObservedVoxel voxel : snapshot.observedVoxels().values()) {
            GridPos position = voxel.position();
            if (isSafeArrival(snapshot, position, hardcore)) {
                closest = Math.min(
                        closest,
                        distanceToTarget(position, target)
                );
            }
        }
        return closest;
    }

    private boolean routeIsExecutable(
            LocalNavSnapshot snapshot,
            LocalRoute route,
            double dangerLimit
    ) {
        for (LocalStep step : route.steps()) {
            if (step.danger() > dangerLimit
                    || step.primitive() == MovementPrimitive.BRIDGE
                    || step.primitive() == MovementPrimitive.PILLAR
                    || step.primitive() == MovementPrimitive.CLIMB
                    || (step.primitive() == MovementPrimitive.SWIM
                    && step.from().y() != step.to().y())
                    || !dependenciesRecent(
                            snapshot,
                            step.observedDependencies()
                    )) {
                return false;
            }
            for (GridPos dependency : step.observedDependencies()) {
                Optional<ObservedVoxel> voxel = snapshot.voxelAt(dependency);
                if (voxel.isEmpty()
                        || voxel.orElseThrow().effectiveDanger()
                        > dangerLimit) {
                    return false;
                }
            }
        }
        return true;
    }

    private Standability standability(
            LocalNavSnapshot snapshot,
            GridPos position,
            double dangerLimit,
            boolean allowCurrentBodySupport
    ) {
        Optional<ObservedVoxel> feet = snapshot.voxelAt(position);
        Optional<ObservedVoxel> head = snapshot.voxelAt(position.above());
        Optional<ObservedVoxel> support = snapshot.voxelAt(position.below());
        if (feet.isEmpty() || head.isEmpty() || support.isEmpty()) {
            return Standability.UNKNOWN_OR_BLOCKED;
        }
        ObservedVoxel feetVoxel = feet.orElseThrow();
        ObservedVoxel headVoxel = head.orElseThrow();
        ObservedVoxel supportVoxel = support.orElseThrow();
        final boolean currentBodySupport =
                allowCurrentBodySupport
                    && feetVoxel.occupancyEvidence()
                        .isFullBodyFact()
                    && headVoxel.occupancyEvidence()
                        .isFullBodyFact()
                    && feetVoxel.observationRevision()
                        == snapshot.revision()
                    && headVoxel.observationRevision()
                        == snapshot.revision()
                    && NavigationEvidence.supportsCurrentBody(
                        supportVoxel,
                        snapshot.revision()
                    );
        if (!NavigationEvidence.hasFreshTraversalClearance(
                    feetVoxel,
                    snapshot.revision()
                )
                || feetVoxel.kind().isLiquid()
                || feetVoxel.kind().isClimbable()
                || !NavigationEvidence.hasFreshTraversalClearance(
                    headVoxel,
                    snapshot.revision()
                )
                || headVoxel.kind().isLiquid()
                || !currentBodySupport
                    && !NavigationEvidence.isFreshStandingSupport(
                            supportVoxel,
                            snapshot.revision()
                    )) {
            return Standability.UNKNOWN_OR_BLOCKED;
        }
        /*
         * Classify danger only after proving this is otherwise a real
         * standing candidate. A visible lava column, fire surface, or other
         * intrinsically impassable voxel may be closer to the target than the
         * body, but it is not evidence that an observed walking route is
         * danger-blocked. Treating it as such poisons fail-closed scans near
         * ordinary Nether terrain and reports the wrong terminal reason.
         */
        double danger = Math.max(
                feetVoxel.effectiveDanger(),
                Math.max(
                        headVoxel.effectiveDanger(),
                        supportVoxel.effectiveDanger()
                )
        );
        if (danger > dangerLimit) {
            return Standability.DANGEROUS;
        }
        return Standability.SAFE;
    }

    private boolean dependenciesRecent(
            LocalNavSnapshot snapshot,
            Set<GridPos> dependencies
    ) {
        final long oldest = Math.max(
                0,
                snapshot.revision()
                        - travelPolicy.maximumVoxelAgeRevisions()
        );
        for (GridPos dependency : dependencies) {
            Optional<ObservedVoxel> voxel = snapshot.voxelAt(dependency);
            if (voxel.isEmpty()
                    || voxel.orElseThrow().observationRevision() < oldest) {
                return false;
            }
        }
        return true;
    }

    private static boolean targetVicinityObserved(
            LocalNavSnapshot snapshot,
            GridPos target
    ) {
        if (snapshot.isObserved(target)
                || snapshot.isObserved(target.above())
                || snapshot.isObserved(target.below())) {
            return true;
        }
        for (GridPos position : snapshot.observedVoxels().keySet()) {
            if (position.euclideanDistance(target) <= 1.5) {
                return true;
            }
        }
        return false;
    }

    private double dangerLimit(boolean hardcore) {
        return hardcore
                ? travelPolicy.hardcoreMaximumDanger()
                : travelPolicy.normalMaximumDanger();
    }

    private static double distanceToTarget(
            GridPos position,
            TravelToParameters target
    ) {
        double dx = position.x() + 0.5 - target.x();
        double dy = position.y() - target.y();
        double dz = position.z() + 0.5 - target.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private enum Standability {
        SAFE,
        DANGEROUS,
        UNKNOWN_OR_BLOCKED
    }

    private record Candidate(
            GridPos position,
            double targetDistance,
            double courseDeviation,
            boolean arrival
    ) {
    }

    public enum SelectionStatus {
        FOUND,
        CANDIDATE_UNREACHABLE,
        NEEDS_OBSERVATION,
        DANGER_BLOCKED,
        PLANNING_NODE_BUDGET_EXCEEDED,
        PLANNING_TIME_BUDGET_EXCEEDED,
        DIMENSION_MISMATCH
    }

    public record SegmentSelection(
            SelectionStatus status,
            Optional<GridPos> endpoint,
            boolean arrival,
            boolean targetVicinityObserved,
            long snapshotRevision,
            int plannedSteps,
            boolean courseRecovery
    ) {
        public SegmentSelection {
            Objects.requireNonNull(status, "status");
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            if (status == SelectionStatus.FOUND
                    && (endpoint.isEmpty()
                    || snapshotRevision < 0
                    || plannedSteps < 1)) {
                throw new IllegalArgumentException(
                        "A found segment needs a route endpoint"
                );
            }
            if (status == SelectionStatus.CANDIDATE_UNREACHABLE
                    && (endpoint.isEmpty()
                    || arrival
                    || snapshotRevision < 0
                    || plannedSteps != 0)) {
                throw new IllegalArgumentException(
                        "An unreachable selection needs its rejected endpoint"
                );
            }
            if (status != SelectionStatus.FOUND
                    && status != SelectionStatus.CANDIDATE_UNREACHABLE
                    && (endpoint.isPresent()
                    || arrival
                    || snapshotRevision != -1
                    || plannedSteps != 0
                    || courseRecovery)) {
                throw new IllegalArgumentException(
                        "A blocked selection cannot expose a route"
                );
            }
        }

        static SegmentSelection found(
                GridPos endpoint,
                boolean arrival,
                long revision,
                int steps,
                boolean courseRecovery
        ) {
            return new SegmentSelection(
                    SelectionStatus.FOUND,
                    Optional.of(endpoint),
                    arrival,
                    true,
                    revision,
                    steps,
                    courseRecovery
            );
        }

        static SegmentSelection unreachableCandidate(
                GridPos endpoint,
                boolean targetObserved,
                long revision,
                boolean courseRecovery
        ) {
            return new SegmentSelection(
                    SelectionStatus.CANDIDATE_UNREACHABLE,
                    Optional.of(endpoint),
                    false,
                    targetObserved,
                    revision,
                    0,
                    courseRecovery
            );
        }

        static SegmentSelection blocked(
                SelectionStatus status,
                boolean targetObserved
        ) {
            return new SegmentSelection(
                    status,
                    Optional.empty(),
                    false,
                    targetObserved,
                    -1,
                    0,
                    false
            );
        }
    }
}
