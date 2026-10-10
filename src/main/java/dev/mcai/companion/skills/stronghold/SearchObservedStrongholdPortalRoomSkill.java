package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalPlannerOptions;
import dev.mcai.companion.navigation.LocalRoute;
import dev.mcai.companion.navigation.MovementPrimitive;
import dev.mcai.companion.navigation.NavigationRiskProfile;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.CoreSkillPolicy;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.portal.ObservedEndPortalGeometry;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded depth-first exploration of a stronghold interior using only the
 * headless player's current first-person semantic frame.
 *
 * <p>Every forward edge is a one-step route accepted by the ordinary local
 * A* rules. A station retains only its scan cursor and an actually traversed
 * parent edge; dead ends therefore backtrack like a player instead of
 * guessing a world-space spiral through walls. Unknown cells never become
 * candidates and the portal room is complete only while the current
 * first-person observation contains enough portal-frame geometry to prove
 * one unique ring center.</p>
 */
public final class SearchObservedStrongholdPortalRoomSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "search_stronghold_portal_room";

    private static final String END_PORTAL_FRAME =
            "minecraft:end_portal_frame";
    private static final Set<String> STRONGHOLD_BLOCKS = Set.of(
            "minecraft:stone_bricks",
            "minecraft:mossy_stone_bricks",
            "minecraft:cracked_stone_bricks",
            "minecraft:infested_stone_bricks",
            "minecraft:iron_bars",
            END_PORTAL_FRAME
    );
    private static final double NORMAL_MAXIMUM_DANGER = 0.16;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.06;
    private static final double MAXIMUM_INTERACTION_DISTANCE = 4.70;
    private static final double INTERACTION_ALIGNMENT_DEGREES = 3.0;
    private static final double ARRIVAL_RADIUS = 0.38;
    private static final int MAXIMUM_TOTAL_TICKS = 24_000;
    private static final int MAXIMUM_VISITED_STATIONS = 4_096;
    private static final int MAXIMUM_REJECTED_STATIONS = 256;
    private static final int MAXIMUM_HORIZONTAL_RADIUS = 192;
    private static final int MAXIMUM_VERTICAL_RADIUS = 48;
    private static final int MAXIMUM_MOVE_FAILURES = 24;
    /**
     * A first-person ray can show a stronghold wall without hitting the
     * floor. Keep a bounded amount of that inherited interior confidence so
     * a corridor is not rejected merely because the camera is looking level;
     * never allow the same ambiguity to become an unbounded overworld walk.
     */
    private static final int MAXIMUM_UNVERIFIED_STATION_STEPS = 48;
    private static final float SCAN_ALIGNMENT_TOLERANCE_DEGREES = 2.0F;
    private static final float[] SCAN_YAW_OFFSETS = {
        0.0F,
        90.0F,
        180.0F,
        -90.0F
    };
    private static final float SUPPORT_SCAN_PITCH = 20.0F;
    private static final float DEEP_SUPPORT_SCAN_PITCH = 45.0F;
    private static final float[] SCAN_PITCHES = {
        SUPPORT_SCAN_PITCH,
        DEEP_SUPPORT_SCAN_PITCH
    };
    private static final int[][] HORIZONTAL_DIRECTIONS = {
        {0, 1},
        {1, 0},
        {0, -1},
        {-1, 0}
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final LongSupplier sessionGeneration;
    private final LocalAStarPlanner localPlanner;
    private final CoreSkillPolicy corePolicy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private long requiredObservationRevision = -1;
    private GridPos origin;
    private GridPos station;
    private float stationBaseYaw;
    private int scanViewIndex;
    private String lastVisibleStrongholdFaces = "";
    private String lastObservedFrontiers = "";
    private int moveFailures;
    private boolean backtracking;
    private MoveToSkill movement;
    private MoveToParameters movementParameters;
    private GridPos movementTarget;
    private GridPos backtrackProbeTarget;
    private BlockInteractionTarget interactionTarget;
    private GridPos interactionBlock;
    private PerceptionVec3 interactionAim;
    private final Set<GridPos> visited = new HashSet<>();
    private final Set<GridPos> exhausted = new HashSet<>();
    private final Set<GridPos> rejected = new HashSet<>();
    private final Set<GridPos> operatedObstacles = new HashSet<>();
    private final Map<GridPos, GridPos> parent = new HashMap<>();
    private final Map<GridPos, Integer> scanCursor = new HashMap<>();
    private final Map<GridPos, Float> scanBaseYaws = new HashMap<>();
    private final Map<GridPos, Integer> unverifiedStationSteps =
            new HashMap<>();
    private final Map<GridPos, Boolean> stationStrongholdEvidence =
            new HashMap<>();

    public SearchObservedStrongholdPortalRoomSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final LongSupplier sessionGeneration
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                sessionGeneration,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
    }

    SearchObservedStrongholdPortalRoomSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final LongSupplier sessionGeneration,
            final LocalAStarPlanner localPlanner,
            final CoreSkillPolicy corePolicy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.localPlanner = Objects.requireNonNull(
                localPlanner,
                "localPlanner"
        );
        this.corePolicy = Objects.requireNonNull(
                corePolicy,
                "corePolicy"
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final FrameValidation validation = validateFrame(-1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".overworld_required"
            ));
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_ground_required"
            ));
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!portalVisible(frame)
                && !strongholdEvidenceVisible(frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stronghold_evidence_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame(-1)
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "Stronghold search body unavailable before start"
                ));
        boundSessionGeneration = sessionGeneration.getAsLong();
        if (boundSessionGeneration < 0) {
            throw new IllegalStateException(
                    "Stronghold search session unavailable"
            );
        }
        phase = Phase.SCANNING;
        failure = null;
        startedAtTick = context.gameTick();
        lastObservationRevision = -1;
        requiredObservationRevision =
                frame.observationRevision() + 1;
        origin = frame.feet();
        station = frame.feet();
        stationBaseYaw = yawOf(frame.lookDirection());
        scanViewIndex = 0;
        lastVisibleStrongholdFaces = "";
        lastObservedFrontiers = "";
        moveFailures = 0;
        backtracking = false;
        movement = null;
        movementParameters = null;
        movementTarget = null;
        backtrackProbeTarget = null;
        interactionTarget = null;
        interactionBlock = null;
        interactionAim = null;
        visited.clear();
        exhausted.clear();
        rejected.clear();
        operatedObstacles.clear();
        parent.clear();
        scanCursor.clear();
        scanBaseYaws.clear();
        unverifiedStationSteps.clear();
        stationStrongholdEvidence.clear();
        visited.add(station);
        scanCursor.put(station, 0);
        scanBaseYaws.put(station, stationBaseYaw);
        // Preconditions already proved stronghold material in the current
        // first-person frame; the next station inherits only a bounded step.
        unverifiedStationSteps.put(station, 0);
        stationStrongholdEvidence.put(
                station,
                strongholdEvidenceVisible(frame)
        );
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"origin\":\"%s\","
                            + "\"station\":\"%s\",\"scanView\":%d,"
                            + "\"visited\":%d,\"exhausted\":%d,"
                            + "\"rejected\":%d,\"moveFailures\":%d,"
                            + "\"backtracking\":%s,"
                            + "\"visibleStrongholdFaces\":\"%s\","
                            + "\"frontiers\":\"%s\","
                            + "\"stationYaw\":%.1f}",
                        phase.name(),
                        origin == null ? "" : origin,
                        station == null ? "" : station,
                        scanViewIndex,
                        visited.size(),
                        exhausted.size(),
                        rejected.size(),
                        moveFailures,
                        backtracking,
                        escapeJson(lastVisibleStrongholdFaces),
                        escapeJson(lastObservedFrontiers),
                        stationBaseYaw
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelMovement(context);
        backtrackProbeTarget = null;
        actuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(
            final SkillContext context
    ) {
        if (context.gameTick() - startedAtTick
                > MAXIMUM_TOTAL_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(
                    context,
                    validation.failure().orElseThrow()
            );
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(context, NAME + ".stale_observation");
        }
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow());
        }
        if (portalVisible(frame)) {
            cancelMovement(context);
            actuator.move(MovementIntent.STOPPED);
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return switch (phase) {
            case SCANNING -> tickScan(context, frame, fresh);
            case BACKTRACK_PROBING -> tickBacktrackProbe(
                    context,
                    frame,
                    fresh
            );
            case MOVING -> tickMovement(context, frame);
            case INTERACTING -> tickInteraction(
                    context,
                    frame,
                    fresh
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    private SkillTickResult tickScan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (!frame.feet().equals(station)) {
            station = frame.feet();
            visited.add(station);
            scanViewIndex = scanCursor.getOrDefault(
                    station,
                    0
            );
            stationBaseYaw = scanBaseYaws.computeIfAbsent(
                    station,
                    ignored -> yawOf(frame.lookDirection())
            );
            requiredObservationRevision =
                    frame.observationRevision() + 1;
        }
        if (visited.size() > MAXIMUM_VISITED_STATIONS) {
            return fail(context, NAME + ".station_budget_exhausted");
        }
        if (!insideSearchEnvelope(station)) {
            rejected.add(station);
            return beginBacktrack(context, frame);
        }
        if (scanViewIndex >= scanViewCount()) {
            exhausted.add(station);
            scanCursor.put(station, scanViewIndex);
            return beginBacktrack(context, frame);
        }

        /*
         * Two deliberately bounded downward views refresh both head-height
         * clearance and the immediately adjacent floor top. Four 100-degree
         * horizontal fans cover the full circle with overlap. The deeper
         * view matters at a threshold where a previous ray sample saw the
         * corridor air but left its support at an older revision; without
         * it the fail-closed planner would reject a physically legal first
         * step. This remains an eight-view sweep, rather than a robotic
         * all-angle scan.
         */
        final int yawIndex =
                scanViewIndex / SCAN_PITCHES.length;
        final int pitchIndex =
                scanViewIndex % SCAN_PITCHES.length;
        final float desiredYaw = normalizeDegrees(
                stationBaseYaw + SCAN_YAW_OFFSETS[yawIndex]
        );
        final float desiredPitch = SCAN_PITCHES[pitchIndex];
        if (!actuator.stop().accepted()
                || !actuator.look(new LookIntent(
                        desiredYaw,
                        desiredPitch
                )).accepted()) {
            return fail(context, NAME + ".camera_scan_rejected");
        }
        final boolean aligned =
                Math.abs(normalizeDegrees(
                        yawOf(frame.lookDirection()) - desiredYaw
                )) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES
                && Math.abs(
                        pitchOf(frame.lookDirection())
                            - desiredPitch
                ) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES;
        if (!fresh
                || frame.observationRevision()
                    < requiredObservationRevision
                || !aligned) {
            return SkillTickResult.running(false, true);
        }

        final Optional<VisibleBlockFace> obstacle =
                visibleOperableObstacle(frame);
        lastVisibleStrongholdFaces = describeVisibleStrongholdFaces(frame);
        if (obstacle.isPresent()) {
            final VisibleBlockFace face = obstacle.orElseThrow();
            final Optional<BlockInteractionTarget> target =
                    interactionTarget(face);
            if (target.isPresent()) {
                interactionTarget = target.orElseThrow();
                interactionBlock = new GridPos(
                        face.block().x(),
                        face.block().y(),
                        face.block().z()
                );
                interactionAim = face.hitPosition();
                phase = Phase.INTERACTING;
                return SkillTickResult.running(true, true);
            }
        }

        /*
         * A stronghold search must not turn an initially observed corridor
         * into an unbounded walk through ordinary open terrain.  The local
         * navigation map quite correctly records clear rays outside a
         * structure, but those cells are not evidence that the player is
         * still inside the stronghold.  Finish this camera sweep in place
         * and backtrack through the last physically traversed edge whenever
         * the current first-person view contains no stronghold block.  This
         * keeps exploration fair while preventing the real-model route from
         * drifting hundreds of blocks into an unknown overworld.
         */
        final boolean interiorEvidence =
                strongholdInteriorEvidenceVisible(frame);
        final boolean corridorEvidence =
                strongholdCorridorEvidenceVisible(frame);
        if (strongholdEvidenceVisible(frame)) {
            stationStrongholdEvidence.put(station, true);
        }
        if (interiorEvidence || corridorEvidence) {
            unverifiedStationSteps.put(station, 0);
        }
        final int inheritedUnverifiedSteps =
                unverifiedStationSteps.getOrDefault(
                        station,
                        MAXIMUM_UNVERIFIED_STATION_STEPS + 1
                );
        final boolean hasStationEvidence =
                stationStrongholdEvidence.getOrDefault(
                        station,
                        false
                );
        if (!interiorEvidence
                && !corridorEvidence
                && (!hasStationEvidence
                    || inheritedUnverifiedSteps
                        > MAXIMUM_UNVERIFIED_STATION_STEPS)) {
            scanViewIndex++;
            scanCursor.put(station, scanViewIndex);
            requiredObservationRevision =
                    frame.observationRevision() + 1;
            return SkillTickResult.running(true, true);
        }

        final List<GridPos> observedFrontiers = observedAdjacentFrontiers(
                frame,
                origin,
                visited,
                rejected,
                context.hardcore(),
                localPlanner,
                corePolicy
        );
        lastObservedFrontiers = observedFrontiers.stream()
                .limit(12)
                .map(GridPos::toString)
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        final Optional<GridPos> next = observedFrontiers.stream()
                .min(Comparator
                        .comparingDouble((GridPos candidate) ->
                                lookAlignmentCost(
                                        frame,
                                        candidate,
                                        stationBaseYaw
                                ))
                        .thenComparing(Comparator.naturalOrder()));
        scanViewIndex++;
        scanCursor.put(station, scanViewIndex);
        requiredObservationRevision =
                frame.observationRevision() + 1;
        if (next.isPresent()) {
            final GridPos destination = next.orElseThrow();
            parent.putIfAbsent(destination, station);
            unverifiedStationSteps.putIfAbsent(
                    destination,
                    Math.min(
                            MAXIMUM_UNVERIFIED_STATION_STEPS + 1,
                            inheritedUnverifiedSteps + 1
                    )
            );
            stationStrongholdEvidence.putIfAbsent(
                    destination,
                    stationStrongholdEvidence.getOrDefault(
                            station,
                            false
                    )
            );
            return startMovement(
                    context,
                    frame,
                    destination,
                    false
            );
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginBacktrack(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final GridPos destination = parent.get(station);
        if (destination == null) {
            return fail(context, NAME + ".search_exhausted");
        }
        /*
         * The parent edge was physically traversed earlier, but its support
         * cannot be reused after a full dead-end scan. Turn back and obtain a
         * new first-person body-and-floor observation before asking the
         * ordinary movement skill to consume that edge.
         */
        backtrackProbeTarget = destination;
        requiredObservationRevision =
                frame.observationRevision() + 1;
        phase = Phase.BACKTRACK_PROBING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickBacktrackProbe(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (backtrackProbeTarget == null) {
            return fail(
                    context,
                    NAME + ".backtrack_probe_state_missing"
            );
        }
        if (!frame.feet().equals(station)) {
            return fail(
                    context,
                    NAME + ".backtrack_station_changed"
            );
        }
        final PerceptionVec3 target = new PerceptionVec3(
                backtrackProbeTarget.x() + 0.5,
                frame.eyePosition().y(),
                backtrackProbeTarget.z() + 0.5
        );
        final float desiredYaw = yawOf(
                target.subtract(frame.eyePosition())
        );
        final float desiredPitch = SUPPORT_SCAN_PITCH;
        if (!actuator.stop().accepted()
                || !actuator.look(new LookIntent(
                        desiredYaw,
                        desiredPitch
                )).accepted()) {
            return fail(
                    context,
                    NAME + ".backtrack_camera_rejected"
            );
        }
        final boolean aligned =
                Math.abs(normalizeDegrees(
                        yawOf(frame.lookDirection()) - desiredYaw
                )) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES
                && Math.abs(
                        pitchOf(frame.lookDirection())
                            - desiredPitch
                ) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES;
        if (!fresh
                || frame.observationRevision()
                    < requiredObservationRevision
                || !aligned) {
            return SkillTickResult.running(false, true);
        }
        final GridPos destination = backtrackProbeTarget;
        backtrackProbeTarget = null;
        return startMovement(
                context,
                frame,
                destination,
                true
        );
    }

    private SkillTickResult startMovement(
            final SkillContext context,
            final CoreSkillFrame frame,
            final GridPos destination,
            final boolean isBacktracking
    ) {
        if (rejected.size() > MAXIMUM_REJECTED_STATIONS) {
            return fail(context, NAME + ".route_budget_exhausted");
        }
        final MoveToParameters target = new MoveToParameters(
                frame.dimension(),
                destination.x() + 0.5,
                destination.y(),
                destination.z() + 0.5,
                ARRIVAL_RADIUS
        );
        final MoveToSkill local = new MoveToSkill(
                expectedPlayerId,
                actuator,
                frames,
                localPlanner,
                corePolicy
        );
        final Optional<SkillFailure> precondition =
                local.preconditions(context, target);
        if (precondition.isPresent()) {
            rejected.add(destination);
            return isBacktracking
                    ? fail(
                            context,
                            NAME + ".backtrack_precondition"
                    )
                    : SkillTickResult.running(true, true);
        }
        local.start(context, target);
        movement = local;
        movementParameters = target;
        movementTarget = destination;
        backtracking = isBacktracking;
        phase = Phase.MOVING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickMovement(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (movement == null
                || movementParameters == null
                || movementTarget == null) {
            return fail(context, NAME + ".movement_state_missing");
        }
        final SkillTickResult result = movement.tick(
                context,
                movementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String childCode =
                    result.failure().orElseThrow().code();
            final GridPos failedTarget = movementTarget;
            clearMovement();
            if (childCode.contains("danger")
                    || childCode.contains("stale")
                    || childCode.contains("player_mismatch")
                    || childCode.contains("dimension_mismatch")) {
                return fail(
                        context,
                        NAME + ".movement_" + suffix(childCode)
                );
            }
            moveFailures++;
            rejected.add(failedTarget);
            if (moveFailures > MAXIMUM_MOVE_FAILURES) {
                return fail(
                        context,
                        NAME + ".move_failures_exhausted"
                );
            }
            phase = Phase.SCANNING;
            station = frame.feet();
            visited.add(station);
            stationBaseYaw = scanBaseYaws.computeIfAbsent(
                    station,
                    ignored -> yawOf(frame.lookDirection())
            );
            scanViewIndex = scanCursor.getOrDefault(
                    station,
                    0
            );
            requiredObservationRevision =
                    frame.observationRevision() + 1;
            return SkillTickResult.running(true, true);
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }

        final GridPos reached = movementTarget;
        final boolean completedBacktrack = backtracking;
        clearMovement();
        final GridPos previousStation = station;
        final float travelYaw = yawOf(new PerceptionVec3(
                reached.x() - previousStation.x(),
                0.0,
                reached.z() - previousStation.z()
        ));
        station = reached;
        visited.add(reached);
        unverifiedStationSteps.putIfAbsent(
                station,
                Math.min(
                        MAXIMUM_UNVERIFIED_STATION_STEPS + 1,
                        unverifiedStationSteps.getOrDefault(
                                station,
                                MAXIMUM_UNVERIFIED_STATION_STEPS
                        )
                )
        );
        stationBaseYaw = scanBaseYaws.computeIfAbsent(
                station,
                ignored -> travelYaw
        );
        scanViewIndex = completedBacktrack
                ? scanCursor.getOrDefault(reached, 0)
                : 0;
        scanCursor.putIfAbsent(reached, scanViewIndex);
        requiredObservationRevision =
                frame.observationRevision() + 1;
        phase = Phase.SCANNING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickInteraction(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (interactionTarget == null
                || interactionBlock == null
                || interactionAim == null) {
            return fail(context, NAME + ".interaction_state_missing");
        }
        final ActionOutcome stopped = actuator.stop();
        final ActionOutcome looked = actuator.look(
                lookAt(
                        frame.eyePosition(),
                        interactionAim
                )
        );
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, NAME + ".interaction_rejected");
        }
        if (!fresh
                || angularErrorDegrees(
                        frame.lookDirection(),
                        interactionAim.subtract(frame.eyePosition())
                ) > INTERACTION_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(false, true);
        }
        final boolean stillVisible =
                frame.visibleBlockFaces().stream().anyMatch(face ->
                        face.block().x() == interactionBlock.x()
                            && face.block().y() == interactionBlock.y()
                            && face.block().z() == interactionBlock.z()
                );
        if (!stillVisible) {
            clearInteraction();
            phase = Phase.SCANNING;
            requiredObservationRevision =
                    frame.observationRevision() + 1;
            return SkillTickResult.running(true, true);
        }
        final ActionOutcome used =
                actuator.useMainHandOn(interactionTarget);
        if (!used.accepted()) {
            return fail(context, NAME + ".interaction_rejected");
        }
        operatedObstacles.add(interactionBlock);
        clearInteraction();
        phase = Phase.SCANNING;
        requiredObservationRevision =
                frame.observationRevision() + 1;
        return SkillTickResult.running(true, true);
    }

    static Optional<GridPos> nextObservedAdjacentFrontier(
            final CoreSkillFrame frame,
            final GridPos origin,
            final Set<GridPos> visited,
            final Set<GridPos> rejected,
            final boolean hardcore,
            final LocalAStarPlanner planner,
            final CoreSkillPolicy policy
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(visited, "visited");
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(policy, "policy");
        return observedAdjacentFrontiers(
                frame,
                origin,
                visited,
                rejected,
                hardcore,
                planner,
                policy
        ).stream()
                .min(Comparator
                        .comparingDouble((GridPos candidate) ->
                                lookAlignmentCost(
                                        frame,
                                        candidate,
                                        yawOf(frame.lookDirection())
                                ))
                        .thenComparing(
                                Comparator.naturalOrder()
                        ));
    }

    private static Optional<GridPos> nextObservedAdjacentFrontier(
            final CoreSkillFrame frame,
            final GridPos origin,
            final Set<GridPos> visited,
            final Set<GridPos> rejected,
            final boolean hardcore,
            final LocalAStarPlanner planner,
            final CoreSkillPolicy policy,
            final float preferredYaw
    ) {
        return observedAdjacentFrontiers(
                frame,
                origin,
                visited,
                rejected,
                hardcore,
                planner,
                policy
        ).stream()
                .min(Comparator
                        .comparingDouble((GridPos candidate) ->
                                lookAlignmentCost(
                                        frame,
                                        candidate,
                                        preferredYaw
                                ))
                        .thenComparing(Comparator.naturalOrder()));
    }

    private static List<GridPos> observedAdjacentFrontiers(
            final CoreSkillFrame frame,
            final GridPos origin,
            final Set<GridPos> visited,
            final Set<GridPos> rejected,
            final boolean hardcore,
            final LocalAStarPlanner planner,
            final CoreSkillPolicy policy
    ) {
        final GridPos start = frame.feet();
        final double dangerLimit = hardcore
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        final LocalPlannerOptions options = new LocalPlannerOptions(
                hardcore
                        ? NavigationRiskProfile.HARDCORE
                        : NavigationRiskProfile.NORMAL,
                policy.planningBudget(),
                1,
                false,
                false
        );
        final List<GridPos> candidates = new ArrayList<>();
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            for (int deltaY : new int[]{0, 1, -1}) {
                final GridPos candidate = start.offset(
                        direction[0],
                        deltaY,
                        direction[1]
                );
                if (visited.contains(candidate)
                        || rejected.contains(candidate)
                        || !insideEnvelope(origin, candidate)) {
                    continue;
                }
                final LocalRoute route = planner.plan(
                        frame.navigation(),
                        start,
                        candidate,
                        options
                );
                if (!route.found()
                        || route.steps().size() != 1
                        || route.steps().getFirst().danger()
                            > dangerLimit
                        || !allowedInteriorPrimitive(
                                route.steps().getFirst().primitive()
                        )) {
                    continue;
                }
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Proves that the current body pose can hand off into this search without
     * inventing an unseen cell. This is used by the preceding stronghold
     * reach compound so crossing a masonry plane is not mistaken for entering
     * a traversable room.
     */
    public static boolean hasObservedAdjacentFrontier(
            final CoreSkillFrame frame,
            final boolean hardcore
    ) {
        Objects.requireNonNull(frame, "frame");
        return nextObservedAdjacentFrontier(
                frame,
                frame.feet(),
                Set.of(frame.feet()),
                Set.of(),
                hardcore,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        ).isPresent();
    }

    /**
     * Directional form of the handoff proof. A freshly observed retreat cell
     * behind a mined wall is not evidence that the body has entered the room
     * on the far side.
     */
    public static boolean hasObservedAdjacentFrontier(
            final CoreSkillFrame frame,
            final boolean hardcore,
            final int stepX,
            final int stepZ
    ) {
        Objects.requireNonNull(frame, "frame");
        if (Math.abs(stepX) + Math.abs(stepZ) != 1) {
            throw new IllegalArgumentException(
                    "Frontier direction must be cardinal"
            );
        }
        final GridPos start = frame.feet();
        return observedAdjacentFrontiers(
                frame,
                start,
                Set.of(start),
                Set.of(),
                hardcore,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        ).stream().anyMatch(candidate ->
                candidate.x() == start.x() + stepX
                    && candidate.z() == start.z() + stepZ
        );
    }

    private Optional<VisibleBlockFace> visibleOperableObstacle(
            final CoreSkillFrame frame
    ) {
        final boolean visibleIronDoor =
                frame.visibleBlockFaces().stream().anyMatch(face ->
                        "minecraft:iron_door".equals(
                                face.blockTypeId()
                        )
                            && !"true".equals(
                                face.stateProperties().get("open")
                            )
                );
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        face.distance()
                            <= MAXIMUM_INTERACTION_DISTANCE)
                .filter(face -> {
                    final GridPos block = new GridPos(
                            face.block().x(),
                            face.block().y(),
                            face.block().z()
                    );
                    return !operatedObstacles.contains(block)
                            && insideSearchEnvelope(block);
                })
                .filter(face ->
                        face.blockTypeId().endsWith("_door")
                            && !"minecraft:iron_door".equals(
                                face.blockTypeId()
                            )
                            && !"true".equals(
                                face.stateProperties().get("open")
                            )
                        || visibleIronDoor
                            && face.blockTypeId().endsWith("_button")
                )
                .min(Comparator
                        .comparingDouble(VisibleBlockFace::distance)
                        .thenComparingInt(face -> face.block().x())
                        .thenComparingInt(face -> face.block().y())
                        .thenComparingInt(face -> face.block().z()));
    }

    private static Optional<BlockInteractionTarget> interactionTarget(
            final VisibleBlockFace visible
    ) {
        try {
            final BlockFace face = BlockFace.valueOf(
                    visible.face().toUpperCase(Locale.ROOT)
            );
            return Optional.of(new BlockInteractionTarget(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z(),
                    face,
                    new ActionVec3(
                            visible.hitPosition().x(),
                            visible.hitPosition().y(),
                            visible.hitPosition().z()
                    )
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (Math.max(context.riskScore(), frame.danger())
                > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double minimumHealth = context.hardcore()
                ? 0.80
                : 0.45;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 8 : 4)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            final long expectedGeneration
    ) {
        final Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    NAME + ".body_unavailable"
            );
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(
                    frame,
                    NAME + ".body_mismatch"
            );
        }
        if (expectedGeneration >= 0) {
            final long currentGeneration =
                    sessionGeneration.getAsLong();
            if (currentGeneration < 0
                    || currentGeneration != expectedGeneration) {
                return FrameValidation.failed(
                        frame,
                        NAME + ".session_changed"
                );
            }
        }
        return FrameValidation.available(frame);
    }

    private boolean insideSearchEnvelope(final GridPos position) {
        return origin != null && insideEnvelope(origin, position);
    }

    private static boolean insideEnvelope(
            final GridPos center,
            final GridPos position
    ) {
        return Math.abs((long) position.x() - center.x())
                    <= MAXIMUM_HORIZONTAL_RADIUS
                && Math.abs((long) position.z() - center.z())
                    <= MAXIMUM_HORIZONTAL_RADIUS
                && Math.abs((long) position.y() - center.y())
                    <= MAXIMUM_VERTICAL_RADIUS;
    }

    private static boolean allowedInteriorPrimitive(
            final MovementPrimitive primitive
    ) {
        return primitive == MovementPrimitive.WALK
                || primitive == MovementPrimitive.JUMP
                || primitive == MovementPrimitive.OPEN_DOOR;
    }

    private static double lookAlignmentCost(
            final CoreSkillFrame frame,
            final GridPos candidate,
            final float preferredYaw
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                candidate.x() + 0.5,
                frame.eyePosition().y(),
                candidate.z() + 0.5
        );
        final float candidateYaw = yawOf(
                target.subtract(frame.eyePosition())
        );
        return Math.abs(normalizeDegrees(candidateYaw - preferredYaw));
    }

    private static boolean portalVisible(
            final CoreSkillFrame frame
    ) {
        return ObservedEndPortalGeometry.uniqueCenter(
                frame.visibleBlockFaces()
        ).isPresent();
    }

    private static boolean strongholdEvidenceVisible(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                STRONGHOLD_BLOCKS.contains(face.blockTypeId())
        );
    }

    private static String describeVisibleStrongholdFaces(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> STRONGHOLD_BLOCKS.contains(
                        face.blockTypeId()
                ))
                .limit(12)
                .map(face -> face.blockTypeId()
                        + "@"
                        + face.block().x() + ","
                        + face.block().y() + ","
                        + face.block().z()
                        + "/" + face.face())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static String escapeJson(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * A wall seen across an exit is not enough to prove that the body is
     * still inside the structure.  The current first-person frame must also
     * show a stronghold-material floor within three blocks of the feet. This
     * deliberately conservative handoff rejects open overworld ground and
     * makes the search backtrack rather than drifting outside, while the
     * normal stone-brick, mossy, cracked, infested and iron-bar floors used
     * by vanilla strongholds remain valid.
     */
    private static boolean strongholdInteriorEvidenceVisible(
            final CoreSkillFrame frame
    ) {
        final int floorY = frame.feet().y() - 1;
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                Math.abs(face.block().x() - frame.feet().x()) <= 3
                    && Math.abs(face.block().z() - frame.feet().z()) <= 3
                    && face.block().y() == floorY
                    && STRONGHOLD_BLOCKS.contains(face.blockTypeId())
        );
    }

    /**
     * Corridor walls are a second fair signal when the downward camera ray
     * is occluded by the body.  Require opposite, already observed solid
     * surfaces within a short span; a single exterior wall (or an outside
     * corner) therefore cannot keep the DFS walking around the structure.
     */
    private static boolean strongholdCorridorEvidenceVisible(
            final CoreSkillFrame frame
    ) {
        if (!strongholdEvidenceVisible(frame)) {
            return false;
        }
        final GridPos feet = frame.feet();
        return (hasObservedSolidWall(frame, feet, 1, 0)
                    && hasObservedSolidWall(frame, feet, -1, 0))
                || (hasObservedSolidWall(frame, feet, 0, 1)
                    && hasObservedSolidWall(frame, feet, 0, -1));
    }

    private static boolean hasObservedSolidWall(
            final CoreSkillFrame frame,
            final GridPos feet,
            final int stepX,
            final int stepZ
    ) {
        for (int distance = 1; distance <= 4; distance++) {
            final GridPos position = feet.offset(
                    stepX * distance,
                    0,
                    stepZ * distance
            );
            if (frame.navigation().voxelAt(position)
                    .map(voxel -> voxel.kind().supportsWeight())
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private void cancelMovement(final SkillContext context) {
        if (movement != null && movementParameters != null) {
            try {
                movement.cancel(context, movementParameters);
            } catch (RuntimeException ignored) {
                actuator.stop();
            }
        }
        clearMovement();
    }

    private void clearMovement() {
        movement = null;
        movementParameters = null;
        movementTarget = null;
        backtracking = false;
    }

    private void clearInteraction() {
        interactionTarget = null;
        interactionBlock = null;
        interactionAim = null;
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        return fail(context, SkillFailure.of(code));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final SkillFailure reason
    ) {
        cancelMovement(context);
        backtrackProbeTarget = null;
        clearInteraction();
        actuator.stop();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private static int scanViewCount() {
        return SCAN_YAW_OFFSETS.length * SCAN_PITCHES.length;
    }

    private static float yawOf(final PerceptionVec3 look) {
        return normalizeDegrees((float) Math.toDegrees(
                Math.atan2(-look.x(), look.z())
        ));
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        if (delta.lengthSquared() <= 1.0E-12) {
            throw new IllegalArgumentException(
                    "Look target coincides with eye"
            );
        }
        return new LookIntent(
                (float) Math.toDegrees(
                        Math.atan2(-delta.x(), delta.z())
                ),
                (float) Math.toDegrees(Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private static double angularErrorDegrees(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        if (target.lengthSquared() <= 1.0E-12) {
            return 0.0;
        }
        final double dot = current.normalized()
                .dot(target.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static float pitchOf(final PerceptionVec3 look) {
        return (float) -Math.toDegrees(Math.atan2(
                look.y(),
                Math.hypot(look.x(), look.z())
        ));
    }

    private static float normalizeDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private static String suffix(final String code) {
        final int separator = code.lastIndexOf('.');
        return separator < 0 ? code : code.substring(separator + 1);
    }

    private enum Phase {
        IDLE,
        SCANNING,
        BACKTRACK_PROBING,
        MOVING,
        INTERACTING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == SCANNING
                    || this == BACKTRACK_PROBING
                    || this == MOVING
                    || this == INTERACTING;
        }
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation available(
                final CoreSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(final String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }

        private static FrameValidation failed(
                final CoreSkillFrame frame,
                final String code
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
