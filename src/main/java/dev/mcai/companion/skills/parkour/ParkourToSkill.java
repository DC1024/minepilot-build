package dev.mcai.companion.skills.parkour;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Conservative, incremental sprint-jump traversal over only first-person
 * observed platform tops and clearance. It supports cardinal turns and
 * one-block elevation changes, but never reads a level or changes a position
 * directly.
 */
public final class ParkourToSkill
        implements Skill<ParkourToParameters> {
    public static final String NAME = "parkour_to";

    private static final double NORMAL_MAXIMUM_DANGER = 0.12;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.04;
    private static final double NORMAL_MINIMUM_HEALTH_RATIO = 0.75;
    private static final double HARDCORE_MINIMUM_HEALTH_RATIO = 0.90;
    private static final int NORMAL_MINIMUM_FOOD = 7;
    private static final int HARDCORE_MINIMUM_FOOD = 10;
    private static final double MOVEMENT_ALIGNMENT_DEGREES = 9.0;
    private static final double TAKEOFF_PROGRESS = 0.58;
    private static final double RECENTER_MINIMUM_PROGRESS = 0.42;
    private static final double RECENTER_MAXIMUM_PROGRESS = 0.58;
    private static final double RECENTER_CAPTURE_MINIMUM_PROGRESS = 0.46;
    private static final double RECENTER_CAPTURE_MAXIMUM_PROGRESS = 0.54;
    private static final double RECENTER_APPROACH_INPUT = 0.30;
    private static final double HARDCORE_RUN_UP_START_PROGRESS = 0.34;
    /*
     * Vanilla sneak edge protection stops a 0.6-block-wide player with its
     * centre at roughly 0.70 inside the takeoff block. Input smoothing and
     * collision correction can settle slightly before that boundary, so
     * requiring 0.68 made a fair sneaking body oscillate forever instead of
     * beginning its deliberate landing/recovery scan.
     */
    private static final double HARDCORE_SCAN_EDGE_PROGRESS = 0.60;
    private static final double HARDCORE_SCAN_APPROACH_SPEED = 1.0;
    private static final int MAXIMUM_EDGE_APPROACH_TICKS = 60;
    private static final int MAXIMUM_SCAN_TICKS = 60;
    private static final int MAXIMUM_SCAN_EVIDENCE_AGE_REVISIONS = 4;
    private static final int SCAN_LOOK_HOLD_TICKS = 6;
    /*
     * Keep the recovery probe inside the gap but far enough forward that a
     * steep first-person ray clears the top of the takeoff block after the
     * stopped sneaking body settles a few centimetres back from the edge.
     */
    private static final double RECOVERY_SCAN_FORWARD_BIAS = 0.48;
    private static final double RECOVERY_SCAN_SUPPORT_PROBE_DEPTH = 0.20;
    private static final int MAXIMUM_JUMP_TICKS = 40;
    private static final int MAXIMUM_JUMP_REQUESTS = 4;
    private static final int HARDCORE_MAXIMUM_RECOVERY_DROP = 3;
    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double MINIMUM_LANDING_BRAKE_OVERLAP = 0.10;
    /*
     * A sprint jump over two empty blocks carries substantially more momentum
     * than a one-gap jump. Start releasing/reversing ordinary input before the
     * collision box reaches the landing edge, otherwise the body touches down
     * near the far edge and spends dozens of ticks sneaking back to centre.
     */
    private static final double LONG_GAP_BRAKE_LEAD = 0.65;
    private static final double AIRBORNE_BRAKE_INPUT = -1.0;
    private static final double STABLE_MOTION_EPSILON = 0.01;
    private static final int RECENTER_STABLE_TICKS = 3;
    private static final int BASE_TIMEOUT_TICKS = 300;
    private static final int TIMEOUT_TICKS_PER_JUMP = 100;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private long jumpStartedAtTick = -1;
    private long landingSafetyRevision = -1;
    private int edgeApproachTicks;
    private int scanTicks;
    private int jumpsCompleted;
    private int jumpRequests;
    private int recenterStableTicks;
    private RecenterPhase recenterPhase;
    private boolean airborneObserved;
    private boolean airborneBrakePrimed;
    private boolean hardcoreRunUpRequired;
    private boolean hardcoreScanEdgeAcquired;
    private boolean confirmedGapScanMode;
    private GridPos hardcoreScanTakeoff;
    private Direction hardcoreScanDirection;
    private long hardcoreScanStartedRevision = -1;
    private GridPos takeoff;
    private GridPos landing;
    private int directionX;
    private int directionZ;
    private boolean sprintJump;
    private double launchY;
    private PerceptionVec3 lastPosition;
    private float startingHealth;
    private GridPos groundedDirectionFeet;
    private Direction groundedDirection;
    private int groundedDirectionLockTicks;

    public ParkourToSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public SkillParameterParser<ParkourToParameters> parameters() {
        return ParkourSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_ground_required"
            ));
        }
        if (Math.abs(parameters.y() - frame.position().y())
                > parameters.maxJumps() + 0.75) {
            return Optional.of(SkillFailure.of(
                    NAME + ".vertical_budget_exceeded"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        phase = Phase.READY;
        failure = null;
        startedAtTick = context.gameTick();
        lastObservationRevision = -1;
        edgeApproachTicks = 0;
        scanTicks = 0;
        jumpsCompleted = 0;
        lastPosition = null;
        startingHealth = validateFrame(parameters)
                .frame()
                .orElseThrow()
                .health();
        clearJump();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,"
                            + "\"jumpsCompleted\":%d,\"maxJumps\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        jumpsCompleted,
                        parameters.maxJumps()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearJump();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final ParkourToParameters parameters
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
            final SkillContext context,
            final ParkourToParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                > BASE_TIMEOUT_TICKS
                    + (long) parameters.maxJumps()
                    * TIMEOUT_TICKS_PER_JUMP) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final boolean freshObservation =
                frame.observationRevision() > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (frame.health() + 1.0E-4F < startingHealth) {
            return fail(NAME + ".damage_taken");
        }
        final PerceptionVec3 previousPosition = lastPosition;
        final boolean moved = previousPosition == null
                || frame.position().subtract(lastPosition)
                    .lengthSquared() >= 0.0025;
        lastPosition = frame.position();

        if (phase == Phase.JUMPING) {
            return continueJump(
                    context,
                    frame,
                    moved,
                    previousPosition
            );
        }
        if (phase == Phase.RECENTERING) {
            return continueRecentering(
                    frame,
                    moved,
                    previousPosition
            );
        }
        /*
         * A coordinate can be inside the final arrival radius on the same tick
         * that an airborne body first touches down. Finish the active jump and
         * recenter on its declared landing before accepting goal arrival.
         */
        if (arrived(parameters, frame)) {
            if (!quiesce()) {
                return fail(NAME + ".actuator_rejected");
            }
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return continueGrounded(
                        context,
                        parameters,
                        frame,
                        freshObservation,
                        moved
                );
    }

    private SkillTickResult continueGrounded(
            final SkillContext context,
            final ParkourToParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation,
            final boolean moved
    ) {
        if (!frame.onGround() || frame.inWater()) {
            return fail(NAME + ".unexpected_fall");
        }
        final GridPos feet = frame.feet();
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        final Direction direction = selectGroundedDirection(
                frame,
                parameters,
                maximumDanger,
                context.hardcore()
        );
        if (direction == null) {
            return fail(NAME + ".unsupported_corridor");
        }
        final GridPos adjacent = feet.offset(
                direction.x(),
                0,
                direction.z()
        );
        if (!feet.equals(hardcoreScanTakeoff)
                || !direction.equals(hardcoreScanDirection)) {
            resetGroundScan(feet, direction);
        }
        if (standable(frame, adjacent, maximumDanger)
                || adjacentPlatformWalkable(
                    frame,
                    adjacent,
                    maximumDanger
                )) {
            edgeApproachTicks = 0;
            scanTicks = 0;
            confirmedGapScanMode = false;
            hardcoreRunUpRequired = false;
            hardcoreScanEdgeAcquired = false;
            return drive(
                    frame,
                    direction,
                    moved || freshObservation,
                    true
            );
        }

        final Optional<GridPos> observedLanding = findLanding(
                frame,
                feet,
                direction,
                parameters.maxGap(),
                parameters.y(),
                maximumDanger,
                context.hardcore()
        );
        if (observedLanding.isEmpty()) {
            if (context.hardcore()
                    && shouldApproachHardcoreScanEdge(
                        frame.position(),
                        direction,
                        hardcoreScanEdgeAcquired
                    )) {
                edgeApproachTicks++;
                scanTicks = 0;
                confirmedGapScanMode = false;
                if (edgeApproachTicks > MAXIMUM_EDGE_APPROACH_TICKS) {
                    return fail(
                            NAME + ".observation_edge_unreachable"
                    );
                }
                final PerceptionVec3 target = scanTarget(
                        context,
                        parameters,
                        feet,
                        direction,
                        1,
                        false
                );
                if (!actuator.look(lookAt(
                            frame.eyePosition(),
                            target
                        )).accepted()
                        || !actuator.move(new MovementIntent(
                            HARDCORE_SCAN_APPROACH_SPEED,
                            0.0,
                            false,
                            true
                        )).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                return SkillTickResult.running(
                        moved || freshObservation,
                        true
                );
            }
            if (context.hardcore() && !hardcoreScanEdgeAcquired) {
                hardcoreScanEdgeAcquired = true;
                hardcoreScanStartedRevision =
                    frame.navigation().revision();
            }
            edgeApproachTicks = 0;
            final boolean currentGapConfirmed =
                    confirmedCurrentGap(
                        frame,
                        feet,
                        direction,
                        2,
                        maximumDanger,
                        evidenceWindow(
                            frame,
                            context.hardcore()
                        )
                    );
            if (currentGapConfirmed != confirmedGapScanMode) {
                scanTicks = 0;
                confirmedGapScanMode = currentGapConfirmed;
            }
            scanTicks++;
            hardcoreRunUpRequired = context.hardcore();
            if (scanTicks > MAXIMUM_SCAN_TICKS) {
                return fail(NAME + ".observed_landing_unavailable");
            }
            final PerceptionVec3 requestedScanTarget =
                    scanTarget(
                        context,
                        parameters,
                        feet,
                        direction,
                        scanTicks,
                        confirmedGapScanMode
                    );
            if (!actuator.stop().accepted()
                    || !actuator.look(lookAt(
                        frame.eyePosition(),
                        requestedScanTarget
                    )).accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(
                    freshObservation,
                    true
            );
        }
        final GridPos landingCandidate =
                observedLanding.orElseThrow();
        confirmedGapScanMode = false;
        if (jumpsCompleted >= parameters.maxJumps()) {
            return fail(NAME + ".jump_budget_exhausted");
        }
        final boolean candidateSprintJump =
                requiresSprintJump(feet, landingCandidate);
        if (context.hardcore() && hardcoreRunUpRequired) {
            scanTicks = 0;
            if (directionalProgress(
                    frame.position(),
                    direction
            ) > HARDCORE_RUN_UP_START_PROGRESS) {
                final PerceptionVec3 target =
                        landingLookTarget(
                            frame.position(),
                            landingCandidate,
                            direction
                        );
                if (!actuator.look(lookAt(
                            frame.eyePosition(),
                            target
                        )).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                if (horizontalAngularError(
                        frame.lookDirection(),
                        direction
                ) > MOVEMENT_ALIGNMENT_DEGREES) {
                    if (!actuator.stop().accepted()) {
                        return fail(NAME + ".actuator_rejected");
                    }
                } else if (!actuator.move(new MovementIntent(
                        -1.0,
                        0.0,
                        false,
                        true
                )).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                return SkillTickResult.running(
                        moved || freshObservation,
                        true
                );
            }
            hardcoreRunUpRequired = false;
            edgeApproachTicks = 0;
            scanTicks = 0;
            if (!actuator.stop().accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(
                    moved || freshObservation,
                    true
            );
        }

        edgeApproachTicks = 0;
        scanTicks = 0;
        final SkillTickResult driving = driveWatchingLanding(
                frame,
                direction,
                landingCandidate,
                candidateSprintJump,
                moved || freshObservation,
                true
        );
        if (driving.status() != SkillTickResult.Status.RUNNING) {
            return driving;
        }
        if (!takeoffReady(frame, direction)) {
            return driving;
        }
        if (!actuator.jump().accepted()) {
            return fail(NAME + ".jump_rejected");
        }
        phase = Phase.JUMPING;
        takeoff = feet;
        landing = landingCandidate;
        directionX = direction.x();
        directionZ = direction.z();
        sprintJump = candidateSprintJump;
        launchY = frame.position().y();
        jumpStartedAtTick = context.gameTick();
        landingSafetyRevision = frame.navigation().revision();
        jumpRequests = 1;
        recenterStableTicks = 0;
        airborneObserved = false;
        airborneBrakePrimed = false;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult continueJump(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean moved,
            final PerceptionVec3 previousPosition
    ) {
        if (frame.inWater()
                || frame.position().y() < launchY - 1.20) {
            return fail(NAME + ".missed_landing");
        }
        if (context.gameTick() - jumpStartedAtTick
                > MAXIMUM_JUMP_TICKS) {
            return fail(NAME + ".jump_timed_out");
        }
        final Direction direction =
                new Direction(directionX, directionZ);
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (!committedLandingStillSafe(
                frame,
                landing,
                landingSafetyRevision,
                maximumDanger
        )) {
            return fail(NAME + ".landing_became_unsafe");
        }
        if (frame.onGround() && airborneObserved) {
            if (!touchingLanding(frame, landing)) {
                return fail(NAME + ".missed_landing");
            }
            jumpsCompleted++;
            phase = Phase.RECENTERING;
            recenterStableTicks = 0;
            recenterPhase = RecenterPhase.BRAKE_LANDING;
            return continueRecentering(
                    frame,
                    moved,
                    previousPosition
            );
        }
        if (!frame.onGround()
                && shouldStartLandingBrake(
                    frame.position(),
                    takeoff,
                    landing,
                    direction.x(),
                    direction.z()
                )) {
            /*
             * Key release preserves vanilla inertia. On a narrow platform a
             * sprint jump can therefore land at the far edge and coast across
             * the following gap before the next plan. Ordinary reverse input
             * actively cancels that momentum without changing velocity or
             * position directly.
             */
            final double forwardMotion = directionalDisplacement(
                    previousPosition,
                    frame.position(),
                    direction
            );
            if (!airborneBrakePrimed) {
                /*
                 * Release the sprint/forward keys before pressing backward.
                 * Otherwise LocalInputController fairly ramps from +1 to -1
                 * over eight ticks while vanilla inertia carries the body
                 * across the narrow landing and the following gap.
                 */
                if (!actuator.stop().accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                airborneBrakePrimed = true;
            }
            if (forwardMotion > STABLE_MOTION_EPSILON) {
                if (!actuator.move(new MovementIntent(
                            AIRBORNE_BRAKE_INPUT,
                            0.0,
                            false,
                            false
                        )).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
            } else if (!actuator.stop().accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            airborneObserved = true;
            return SkillTickResult.running(true, false);
        }
        final SkillTickResult driving = driveLookingAt(
                frame,
                direction,
                new PerceptionVec3(
                    frame.eyePosition().x() + direction.x() * 4.0,
                    frame.eyePosition().y(),
                    frame.eyePosition().z() + direction.z() * 4.0
                ),
                sprintJump,
                moved,
                false
        );
        if (driving.status() != SkillTickResult.Status.RUNNING) {
            return driving;
        }

        if (!frame.onGround()) {
            airborneObserved = true;
            return SkillTickResult.running(true, false);
        }
        if (!frame.feet().equals(takeoff)) {
            return fail(NAME + ".left_takeoff_without_jump");
        }
        if (jumpRequests >= MAXIMUM_JUMP_REQUESTS) {
            return fail(NAME + ".jump_did_not_start");
        }
        if (!actuator.jump().accepted()) {
            return fail(NAME + ".jump_rejected");
        }
        jumpRequests++;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult continueRecentering(
            final CoreSkillFrame frame,
            final boolean moved,
            final PerceptionVec3 previousPosition
    ) {
        if (!frame.onGround()
                || frame.inWater()
                || landing == null) {
            return fail(NAME + ".recenter_lost_support");
        }
        final Direction direction =
                new Direction(directionX, directionZ);
        if (!touchingLanding(frame, landing)) {
            return fail(NAME + ".recenter_left_landing");
        }
        final double progress = landingProgress(
                frame.position(),
                landing,
                direction
        );
        final double forwardMotion = directionalDisplacement(
                previousPosition,
                frame.position(),
                direction
        );
        final PerceptionVec3 target = new PerceptionVec3(
                frame.eyePosition().x() + direction.x() * 4.0,
                frame.eyePosition().y(),
                frame.eyePosition().z() + direction.z() * 4.0
        );
        if (!actuator.look(
                lookAt(frame.eyePosition(), target)
        ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (horizontalAngularError(
                frame.lookDirection(),
                direction
        ) > MOVEMENT_ALIGNMENT_DEGREES) {
            recenterStableTicks = 0;
            if (!stopAndSneak()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(moved, false);
        }

        if (recenterPhase == null) {
            return fail(NAME + ".invalid_recenter_state");
        }

        if (recenterPhase == RecenterPhase.BRAKE_LANDING) {
            recenterStableTicks = 0;
            if (forwardMotion > STABLE_MOTION_EPSILON) {
                if (!actuator.move(new MovementIntent(
                            AIRBORNE_BRAKE_INPUT,
                            0.0,
                            false,
                            true
                        )).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                return SkillTickResult.running(moved, false);
            }
            if (!stopAndSneak()) {
                return fail(NAME + ".actuator_rejected");
            }
            recenterPhase = insideCaptureBand(progress)
                    ? RecenterPhase.COAST
                    : RecenterPhase.APPROACH;
            if (recenterPhase == RecenterPhase.COAST
                    && Math.abs(forwardMotion)
                        <= STABLE_MOTION_EPSILON) {
                recenterStableTicks = 1;
            }
            return SkillTickResult.running(moved, false);
        }

        if (recenterPhase == RecenterPhase.APPROACH) {
            recenterStableTicks = 0;
            if (insideCaptureBand(progress)) {
                recenterPhase = RecenterPhase.COAST;
                if (!stopAndSneak()) {
                    return fail(NAME + ".actuator_rejected");
                }
                return SkillTickResult.running(moved, false);
            }
            final double forward = progress
                    > RECENTER_CAPTURE_MAXIMUM_PROGRESS
                    ? -RECENTER_APPROACH_INPUT
                    : RECENTER_APPROACH_INPUT;
            if (!actuator.move(new MovementIntent(
                    forward,
                    0.0,
                    false,
                    true
            )).accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(moved, false);
        }

        if (!stopAndSneak()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (Math.abs(forwardMotion) > STABLE_MOTION_EPSILON) {
            recenterStableTicks = 0;
            return SkillTickResult.running(moved, false);
        }
        if (progress < RECENTER_MINIMUM_PROGRESS
                || progress > RECENTER_MAXIMUM_PROGRESS) {
            recenterStableTicks = 0;
            recenterPhase = RecenterPhase.APPROACH;
            return SkillTickResult.running(moved, false);
        }
        recenterStableTicks++;
        if (recenterStableTicks < RECENTER_STABLE_TICKS) {
            return SkillTickResult.running(moved, false);
        }
        phase = Phase.READY;
        clearJump();
        return SkillTickResult.running(true, true);
    }

    private static boolean insideCaptureBand(final double progress) {
        return progress >= RECENTER_CAPTURE_MINIMUM_PROGRESS
                && progress <= RECENTER_CAPTURE_MAXIMUM_PROGRESS;
    }

    private boolean stopAndSneak() {
        return actuator.stop().accepted()
                && actuator.move(new MovementIntent(
                    0.0,
                    0.0,
                    false,
                    true
                )).accepted();
    }

    private static double directionalDisplacement(
            final PerceptionVec3 previous,
            final PerceptionVec3 current,
            final Direction direction
    ) {
        if (previous == null) {
            return 0.0;
        }
        return (current.x() - previous.x()) * direction.x()
                + (current.z() - previous.z()) * direction.z();
    }

    /**
     * Vanilla collision may put the player's centre just outside the landing
     * block while the 0.6-block-wide player box is already supported by its
     * top face. Requiring the centre's floored cell to equal the landing cell
     * misclassifies that ordinary edge landing as a miss.
     */
    private static boolean touchingLanding(
            final CoreSkillFrame frame,
            final GridPos landingCell
    ) {
        final double epsilon = 1.0E-4;
        return frame.onGround()
                && Math.abs(
                    frame.position().y() - landingCell.y()
                ) <= 0.20
                && frame.position().x()
                    >= landingCell.x() - PLAYER_HALF_WIDTH - epsilon
                && frame.position().x()
                    <= landingCell.x() + 1.0
                        + PLAYER_HALF_WIDTH + epsilon
                && frame.position().z()
                    >= landingCell.z() - PLAYER_HALF_WIDTH - epsilon
                && frame.position().z()
                    <= landingCell.z() + 1.0
                        + PLAYER_HALF_WIDTH + epsilon;
    }

    /**
     * Releases airborne movement only after the leading edge of the player's
     * collision box has materially overlapped the observed landing. Merely
     * touching the mathematical block boundary is not stable vanilla support:
     * floating-point rounding can leave the box infinitesimally outside and
     * make a zero-input player fall straight down.
     */
    static boolean shouldStartLandingBrake(
            final PerceptionVec3 position,
            final GridPos takeoffCell,
            final GridPos landingCell,
            final int directionX,
            final int directionZ
    ) {
        if (Math.abs(directionX) + Math.abs(directionZ) != 1) {
            throw new IllegalArgumentException(
                    "Landing direction must be cardinal"
            );
        }
        final int distance =
                Math.abs(landingCell.x() - takeoffCell.x())
                    + Math.abs(landingCell.z() - takeoffCell.z());
        if (distance < 3) {
            return hasMinimumForwardLandingOverlap(
                    position,
                    landingCell,
                    directionX,
                    directionZ
            );
        }
        return hasForwardLandingReach(
                position,
                landingCell,
                directionX,
                directionZ,
                LONG_GAP_BRAKE_LEAD
        );
    }

    static boolean hasMinimumForwardLandingOverlap(
            final PerceptionVec3 position,
            final GridPos landingCell,
            final int directionX,
            final int directionZ
    ) {
        if (Math.abs(directionX) + Math.abs(directionZ) != 1) {
            throw new IllegalArgumentException(
                    "Landing direction must be cardinal"
            );
        }
        final double centerReach =
                PLAYER_HALF_WIDTH - MINIMUM_LANDING_BRAKE_OVERLAP;
        return hasForwardLandingReach(
                position,
                landingCell,
                directionX,
                directionZ,
                centerReach
        );
    }

    private static boolean hasForwardLandingReach(
            final PerceptionVec3 position,
            final GridPos landingCell,
            final int directionX,
            final int directionZ,
            final double centerReach
    ) {
        if (directionX > 0) {
            return position.x()
                    >= landingCell.x() - centerReach;
        }
        if (directionX < 0) {
            return position.x()
                    <= landingCell.x() + 1.0 + centerReach;
        }
        if (directionZ > 0) {
            return position.z()
                    >= landingCell.z() - centerReach;
        }
        return position.z()
                <= landingCell.z() + 1.0 + centerReach;
    }

    private static double landingProgress(
            final PerceptionVec3 position,
            final GridPos landingCell,
            final Direction direction
    ) {
        if (direction.x() > 0) {
            return position.x() - landingCell.x();
        }
        if (direction.x() < 0) {
            return landingCell.x() + 1.0 - position.x();
        }
        if (direction.z() > 0) {
            return position.z() - landingCell.z();
        }
        return landingCell.z() + 1.0 - position.z();
    }

    private SkillTickResult drive(
            final CoreSkillFrame frame,
            final Direction direction,
            final boolean progress,
            final boolean safeCheckpoint
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                frame.eyePosition().x() + direction.x() * 4.0,
                frame.eyePosition().y(),
                frame.eyePosition().z() + direction.z() * 4.0
        );
        return driveLookingAt(
                frame,
                direction,
                target,
                true,
                progress,
                safeCheckpoint
        );
    }

    private SkillTickResult driveWatchingLanding(
            final CoreSkillFrame frame,
            final Direction direction,
            final GridPos landingCell,
            final boolean sprint,
            final boolean progress,
            final boolean safeCheckpoint
    ) {
        return driveLookingAt(
                frame,
                direction,
                landingLookTarget(
                    frame.position(),
                    landingCell,
                    direction
                ),
                sprint,
                progress,
                safeCheckpoint
        );
    }

    private SkillTickResult driveLookingAt(
            final CoreSkillFrame frame,
            final Direction direction,
            final PerceptionVec3 target,
            final boolean sprint,
            final boolean progress,
            final boolean safeCheckpoint
    ) {
        if (!actuator.look(
                lookAt(frame.eyePosition(), target)
        ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (horizontalAngularError(
                frame.lookDirection(),
                direction
        ) > MOVEMENT_ALIGNMENT_DEGREES) {
            if (!actuator.stop().accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(progress, safeCheckpoint);
        }
        final ActionOutcome movement = actuator.move(
                new MovementIntent(1.0, 0.0, sprint, false)
        );
        if (!movement.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(progress, safeCheckpoint);
    }

    static boolean requiresSprintJump(
            final GridPos takeoffCell,
            final GridPos landingCell
    ) {
        final int horizontalDistance =
                Math.abs(landingCell.x() - takeoffCell.x())
                    + Math.abs(landingCell.z() - takeoffCell.z());
        /*
         * Distance two already contains one full air block. A walk jump can
         * mathematically brush the landing edge after a short recenter, but
         * cannot establish reliable collision overlap. Use ordinary vanilla
         * sprint input for every actual gap; adjacent, gap-free steps remain
         * walkable.
         */
        return horizontalDistance >= 2
                || landingCell.y() > takeoffCell.y();
    }

    private static PerceptionVec3 landingLookTarget(
            final PerceptionVec3 position,
            final GridPos landingCell,
            final Direction direction
    ) {
        return new PerceptionVec3(
                direction.x() == 0
                        ? clampToBlockInterior(
                            position.x(),
                            landingCell.x()
                        )
                        : landingCell.x() + 0.5,
                landingCell.y() + 0.02,
                direction.z() == 0
                        ? clampToBlockInterior(
                            position.z(),
                            landingCell.z()
                        )
                        : landingCell.z() + 0.5
        );
    }

    private static double clampToBlockInterior(
            final double coordinate,
            final int blockCoordinate
    ) {
        return Math.max(
                blockCoordinate + 0.05,
                Math.min(blockCoordinate + 0.95, coordinate)
        );
    }

    /**
     * A diagonal goal does not imply that both cardinal legs are currently
     * traversable. Select the next leg from first-person evidence before
     * falling back to geometric distance. This prevents centimetre-scale
     * movement inside one block from flipping an L-shaped route to an
     * unobserved empty corridor.
     */
    Direction selectGroundedDirection(
            final CoreSkillFrame frame,
            final ParkourToParameters parameters,
            final double maximumDanger,
            final boolean hardcore
    ) {
        final GridPos feet = frame.feet();
        if (retainHardcoreScanDirection(
                hardcore,
                edgeApproachTicks,
                hardcoreScanEdgeAcquired
            )
                && hardcoreScanDirection != null
                && feet.equals(hardcoreScanTakeoff)
                && targetReducing(
                    frame.position(),
                    parameters.target(),
                    hardcoreScanDirection
                )) {
            return hardcoreScanDirection;
        }
        if (groundedDirection != null
                && feet.equals(groundedDirectionFeet)
                && groundedDirectionLockTicks > 0
                && targetReducing(
                    frame.position(),
                    parameters.target(),
                    groundedDirection
                )) {
            groundedDirectionLockTicks--;
            return groundedDirection;
        }

        final Direction xDirection = axisDirection(
                parameters.x() - frame.position().x(),
                true
        );
        final Direction zDirection = axisDirection(
                parameters.z() - frame.position().z(),
                false
        );
        final Direction selected;
        if (xDirection == null) {
            selected = zDirection;
        } else if (zDirection == null) {
            selected = xDirection;
        } else {
            final DirectionEvidence xEvidence = directionEvidence(
                    frame,
                    feet,
                    xDirection,
                    parameters,
                    maximumDanger,
                    hardcore
            );
            final DirectionEvidence zEvidence = directionEvidence(
                    frame,
                    feet,
                    zDirection,
                    parameters,
                    maximumDanger,
                    hardcore
            );
            if (xEvidence.rank() > zEvidence.rank()) {
                selected = xDirection;
            } else if (zEvidence.rank() > xEvidence.rank()) {
                selected = zDirection;
            } else {
                final double xDistance = Math.abs(
                        parameters.x() - frame.position().x()
                );
                final double zDistance = Math.abs(
                        parameters.z() - frame.position().z()
                );
                /*
                 * Exact ties deliberately choose X. That deterministic
                 * fallback is independent of map iteration order and keeps
                 * identical observations reproducible.
                 */
                selected = xDistance >= zDistance
                        ? xDirection
                        : zDirection;
            }
        }

        if (selected == null) {
            groundedDirectionFeet = null;
            groundedDirection = null;
            groundedDirectionLockTicks = 0;
            return null;
        }
        if (!feet.equals(groundedDirectionFeet)
                || !selected.equals(groundedDirection)) {
            groundedDirectionFeet = feet;
            groundedDirection = selected;
            groundedDirectionLockTicks = SCAN_LOOK_HOLD_TICKS - 1;
        }
        return selected;
    }

    static boolean retainHardcoreScanDirection(
            final boolean hardcore,
            final int edgeApproachTicks,
            final boolean edgeAcquired
    ) {
        return hardcore
                && (edgeApproachTicks > 0 || edgeAcquired);
    }

    static boolean withinHardcoreScanEvidenceWindow(
            final long minimumRevision,
            final long currentRevision,
            final long evidenceRevision
    ) {
        return evidenceRevision >= minimumRevision
                && evidenceRevision <= currentRevision
                && currentRevision - evidenceRevision
                    <= MAXIMUM_SCAN_EVIDENCE_AGE_REVISIONS;
    }

    private DirectionEvidence directionEvidence(
            final CoreSkillFrame frame,
            final GridPos feet,
            final Direction direction,
            final ParkourToParameters parameters,
            final double maximumDanger,
            final boolean hardcore
    ) {
        final GridPos adjacent = feet.offset(
                direction.x(),
                0,
                direction.z()
        );
        if (standable(frame, adjacent, maximumDanger)
                || adjacentPlatformWalkable(
                    frame,
                    adjacent,
                    maximumDanger
                )) {
            return DirectionEvidence.CURRENT_ADJACENT;
        }
        if (findLanding(
                frame,
                feet,
                direction,
                parameters.maxGap(),
                parameters.y(),
                maximumDanger,
                hardcore
        ).isPresent()) {
            return DirectionEvidence.STRICT_LANDING;
        }
        if (hasVisibleCandidateTop(
                frame,
                feet,
                direction,
                parameters.maxGap(),
                parameters.y()
        )) {
            return DirectionEvidence.VISIBLE_CANDIDATE_TOP;
        }
        return DirectionEvidence.UNKNOWN;
    }

    private static boolean hasVisibleCandidateTop(
            final CoreSkillFrame frame,
            final GridPos feet,
            final Direction direction,
            final int maximumGap,
            final double targetY
    ) {
        final int[] verticalOffsets = preferredVerticalOffsets(
                targetY,
                feet.y()
        );
        for (int distance = 1;
                distance <= maximumGap + 1;
                distance++) {
            for (int verticalOffset : verticalOffsets) {
                final GridPos candidate = feet.offset(
                        direction.x() * distance,
                        verticalOffset,
                        direction.z() * distance
                );
                if (visibleLanding(
                        frame,
                        candidate,
                        EvidenceWindow.current(
                            frame.navigation().revision()
                        )
                )) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Direction axisDirection(
            final double delta,
            final boolean xAxis
    ) {
        if (Math.abs(delta) <= 0.05) {
            return null;
        }
        final int sign = delta >= 0.0 ? 1 : -1;
        return xAxis
                ? new Direction(sign, 0)
                : new Direction(0, sign);
    }

    private static boolean targetReducing(
            final PerceptionVec3 position,
            final PerceptionVec3 target,
            final Direction direction
    ) {
        final double delta = direction.x() != 0
                ? target.x() - position.x()
                : target.z() - position.z();
        final int sign = direction.x() + direction.z();
        return Math.abs(delta) > 0.05
                && (delta > 0.0 ? 1 : -1) == sign;
    }

    private Optional<GridPos> findLanding(
            final CoreSkillFrame frame,
            final GridPos takeoffCell,
            final Direction direction,
            final int maximumGap,
            final double targetY,
            final double maximumDanger,
            final boolean hardcore
    ) {
        final EvidenceWindow evidence = evidenceWindow(
            frame,
            hardcore
        );
        final int[] verticalOffsets =
                preferredVerticalOffsets(
                        targetY,
                        takeoffCell.y()
                );
        for (int distance = 1;
                distance <= maximumGap + 1;
                distance++) {
            for (int verticalOffset : verticalOffsets) {
                final GridPos candidate = takeoffCell.offset(
                        direction.x() * distance,
                        verticalOffset,
                        direction.z() * distance
                );
                if (standable(
                            frame,
                            candidate,
                            maximumDanger,
                            evidence
                        )
                        && visibleLanding(
                            frame,
                            candidate,
                            evidence
                        )
                        && confirmedCurrentGap(
                            frame,
                            takeoffCell,
                            direction,
                            distance,
                            maximumDanger,
                            evidence
                        )
                        && corridorClear(
                            frame,
                            takeoffCell,
                            direction,
                            distance,
                            verticalOffset,
                            evidence
                        )
                        && (!hardcore
                            || recoverableMissCorridor(
                                frame,
                                takeoffCell,
                                direction,
                                distance,
                                maximumDanger,
                                evidence
                            ))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A failed standability check is unknown, not proof of a gap. Every
     * support cell that a jump would cross must instead have current,
     * sufficiently strong first-person AIR evidence.
     */
    private static boolean confirmedCurrentGap(
            final CoreSkillFrame frame,
            final GridPos start,
            final Direction direction,
            final int landingDistance,
            final double maximumDanger,
            final EvidenceWindow evidence
    ) {
        for (int step = 1; step < landingDistance; step++) {
            final GridPos possibleSupport = start.offset(
                    direction.x() * step,
                    -1,
                    direction.z() * step
            );
            final Optional<ObservedVoxel> observed =
                    frame.navigation().voxelAt(possibleSupport);
            if (observed.isEmpty()) {
                return false;
            }
            final ObservedVoxel voxel = observed.orElseThrow();
            if (voxel.kind() != VoxelKind.AIR
                    || !hasTraversalClearance(voxel, evidence)
                    || voxel.effectiveDanger() > maximumDanger) {
                return false;
            }
        }
        return true;
    }

    /**
     * A Hardcore jump cannot depend on hidden recovery terrain. While stopped
     * at the edge, deliberately scan each possible miss column before looking
     * back at the candidate landing. The ordinary semantic sampler then
     * supplies the evidence; this method does not read level state.
     */
    static PerceptionVec3 scanTarget(
            final SkillContext context,
            final ParkourToParameters parameters,
            final GridPos start,
            final Direction direction,
            final int scanTicks,
            final boolean adjacentGapConfirmed
    ) {
        final ScanPlan plan = scanPlan(
                context.hardcore(),
                adjacentGapConfirmed,
                parameters.maxGap(),
                scanTicks
        );
        if (!plan.recovery()) {
            final int distance = plan.distance();
            final int landingOffset = preferredVerticalOffsets(
                    parameters.y(),
                    start.y()
            )[0];
            final GridPos candidate = start.offset(
                    direction.x() * distance,
                    landingOffset,
                    direction.z() * distance
            );
            return new PerceptionVec3(
                    candidate.x() + 0.5,
                    candidate.y(),
                    candidate.z() + 0.5
            );
        }

        final int step = plan.distance();
        final GridPos gap = start.offset(
                direction.x() * step,
                0,
                direction.z() * step
        );
        return new PerceptionVec3(
                gap.x() + 0.5
                    + direction.x() * RECOVERY_SCAN_FORWARD_BIAS,
                start.y() - HARDCORE_MAXIMUM_RECOVERY_DROP
                    - RECOVERY_SCAN_SUPPORT_PROBE_DEPTH,
                gap.z() + 0.5
                    + direction.z() * RECOVERY_SCAN_FORWARD_BIAS
        );
    }

    static int scanPhase(
            final int scanTick,
            final int phaseCount
    ) {
        if (scanTick <= 0 || phaseCount <= 0) {
            throw new IllegalArgumentException(
                    "Scan tick and phase count must be positive"
            );
        }
        return Math.floorMod(
                (scanTick - 1) / SCAN_LOOK_HOLD_TICKS,
                phaseCount
        );
    }

    static ScanPlan scanPlan(
            final boolean hardcore,
            final boolean adjacentGapConfirmed,
            final int maximumGap,
            final int scanTick
    ) {
        if (maximumGap < 1 || maximumGap > 2 || scanTick <= 0) {
            throw new IllegalArgumentException(
                    "Invalid evidence-driven scan inputs"
            );
        }
        if (!adjacentGapConfirmed) {
            return new ScanPlan(false, 1);
        }
        if (!hardcore) {
            return new ScanPlan(false, maximumGap + 1);
        }
        final int phase = scanPhase(
                scanTick,
                maximumGap + 1
        );
        return phase == 0
                ? new ScanPlan(false, maximumGap + 1)
                : new ScanPlan(true, phase);
    }

    /**
     * Hardcore jumps are permitted only when a miss has a fully observed,
     * non-hazardous recovery floor within a small non-lethal drop. Unknown
     * space, lava, and deep/void gaps are therefore not parkour candidates.
     */
    private static boolean recoverableMissCorridor(
            final CoreSkillFrame frame,
            final GridPos start,
            final Direction direction,
            final int landingDistance,
            final double maximumDanger,
            final EvidenceWindow evidence
    ) {
        for (int step = 1; step < landingDistance; step++) {
            final GridPos gapFeet = start.offset(
                    direction.x() * step,
                    0,
                    direction.z() * step
            );
            boolean recoverable = false;
            for (int drop = 1;
                    drop <= HARDCORE_MAXIMUM_RECOVERY_DROP;
                    drop++) {
                final GridPos recoveryFeet =
                        gapFeet.offset(0, -drop, 0);
                if (standable(
                        frame,
                        recoveryFeet,
                        maximumDanger,
                        evidence
                )) {
                    recoverable = true;
                    break;
                }
            }
            if (!recoverable) {
                return false;
            }
        }
        return true;
    }

    private static int[] preferredVerticalOffsets(
            final double targetY,
            final int currentFeetY
    ) {
        if (targetY > currentFeetY + 0.35) {
            return new int[]{1, 0, -1};
        }
        if (targetY < currentFeetY - 0.35) {
            return new int[]{-1, 0, 1};
        }
        return new int[]{0, 1, -1};
    }

    private static boolean corridorClear(
            final CoreSkillFrame frame,
            final GridPos start,
            final Direction direction,
            final int distance,
            final int verticalOffset,
            final EvidenceWindow evidence
    ) {
        for (int step = 1; step < distance; step++) {
            final GridPos feet = start.offset(
                    direction.x() * step,
                    0,
                    direction.z() * step
            );
            if (!airClearance(frame, feet, evidence)
                    || !airClearance(
                        frame,
                        feet.above(),
                        evidence
                    )
                    || verticalOffset > 0
                        && !airClearance(
                            frame,
                            feet.above().above(),
                            evidence
                        )) {
                return false;
            }
        }
        return true;
    }

    private static boolean visibleLanding(
            final CoreSkillFrame frame,
            final GridPos landingCell,
            final EvidenceWindow evidence
    ) {
        final GridPos support = landingCell.below();
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            if (face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && face.face().equals("up")) {
                return true;
            }
        }
        if (!evidence.surveyed()) {
            return false;
        }
        return frame.navigation().voxelAt(support)
                .filter(voxel ->
                    isStandingSupport(voxel, evidence)
                )
                .isPresent();
    }

    static boolean standable(
            final CoreSkillFrame frame,
            final GridPos feet,
            final double maximumDanger
    ) {
        return standable(
            frame,
            feet,
            maximumDanger,
            EvidenceWindow.current(frame.navigation().revision())
        );
    }

    private static boolean standable(
            final CoreSkillFrame frame,
            final GridPos feet,
            final double maximumDanger,
            final EvidenceWindow evidence
    ) {
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(feet.below());
        return support.isPresent()
                && isStandingSupport(
                    support.orElseThrow(),
                    evidence
                )
                && airClearance(frame, feet, evidence)
                && airClearance(frame, feet.above(), evidence)
                && effectiveDanger(frame, feet) <= maximumDanger;
    }

    /**
     * A directly observed full top on the immediately adjacent, same-height
     * cell is enough to approach using ordinary grounded movement when there
     * is no current obstruction or hazard contradiction. Requiring three
     * converging air rays in both body cells can deadlock at the seam between
     * two continuous platform blocks while a steep first-person scan is
     * centered on the top face. This relaxation is never used for a jump
     * landing: vanilla collision simply stops the grounded approach if an
     * unseen obstacle exists.
     */
    static boolean adjacentPlatformWalkable(
            final CoreSkillFrame frame,
            final GridPos feet,
            final double maximumDanger
    ) {
        final long currentRevision =
                frame.navigation().revision();
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(feet.below());
        return support
                .filter(voxel ->
                    NavigationEvidence.isFreshStandingSupport(
                        voxel,
                        currentRevision
                    )
                )
                .filter(voxel ->
                    voxel.effectiveDanger() <= maximumDanger
                )
                .isPresent()
                && !currentObstructionOrDanger(
                    frame,
                    feet,
                    maximumDanger
                )
                && !currentObstructionOrDanger(
                    frame,
                    feet.above(),
                    maximumDanger
                );
    }

    private static boolean currentObstructionOrDanger(
            final CoreSkillFrame frame,
            final GridPos position,
            final double maximumDanger
    ) {
        final long currentRevision =
                frame.navigation().revision();
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                    voxel.observationRevision() == currentRevision
                )
                .filter(voxel ->
                    !voxel.kind().isPassable()
                        || voxel.kind().isLiquid()
                        || voxel.effectiveDanger() > maximumDanger
                )
                .isPresent();
    }

    /**
     * Once a vanilla jump has started, lack of a new ray to the committed
     * landing cannot revoke the strict safety evidence that authorized the
     * jump. The body cannot stop in mid-air, so only same-snapshot positive
     * evidence of a changed support, obstruction, liquid, or hazard aborts
     * the bounded jump. The certificate is cleared with the jump state.
     */
    static boolean committedLandingStillSafe(
            final CoreSkillFrame frame,
            final GridPos feet,
            final long certificateRevision,
            final double maximumDanger
    ) {
        final long currentRevision = frame.navigation().revision();
        if (certificateRevision < 0
                || certificateRevision > currentRevision) {
            return false;
        }
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(feet.below());
        if (support.filter(voxel ->
                    voxel.observationRevision() == currentRevision
                ).filter(voxel ->
                    !voxel.kind().supportsWeight()
                        || voxel.topSupportAffordance()
                            == TopSupportAffordance
                                .NON_STURDY_OR_PARTIAL
                        || voxel.effectiveDanger() > maximumDanger
                ).isPresent()) {
            return false;
        }
        return !currentClearanceContradicts(
                    frame,
                    feet,
                    maximumDanger
                )
                && !currentClearanceContradicts(
                    frame,
                    feet.above(),
                    maximumDanger
                );
    }

    private static boolean currentClearanceContradicts(
            final CoreSkillFrame frame,
            final GridPos position,
            final double maximumDanger
    ) {
        final long currentRevision = frame.navigation().revision();
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                    voxel.observationRevision() == currentRevision
                )
                .filter(voxel ->
                    voxel.kind() != VoxelKind.AIR
                        || voxel.effectiveDanger() > maximumDanger
                )
                .isPresent();
    }

    private EvidenceWindow evidenceWindow(
            final CoreSkillFrame frame,
            final boolean hardcore
    ) {
        if (hardcore
                && hardcoreScanEdgeAcquired
                && hardcoreScanStartedRevision >= 0) {
            return EvidenceWindow.surveyed(
                hardcoreScanStartedRevision,
                frame.navigation().revision()
            );
        }
        return EvidenceWindow.current(
            frame.navigation().revision()
        );
    }

    private static boolean airClearance(
            final CoreSkillFrame frame,
            final GridPos position,
            final EvidenceWindow evidence
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel -> voxel.kind() == VoxelKind.AIR)
                .filter(voxel ->
                    hasTraversalClearance(voxel, evidence)
                )
                .isPresent();
    }

    private static boolean hasTraversalClearance(
            final ObservedVoxel voxel,
            final EvidenceWindow evidence
    ) {
        if (!evidence.surveyed()) {
            return NavigationEvidence.hasFreshTraversalClearance(
                voxel,
                evidence.currentRevision()
            );
        }
        return evidence.contains(voxel.observationRevision())
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    private static boolean isStandingSupport(
            final ObservedVoxel voxel,
            final EvidenceWindow evidence
    ) {
        if (!evidence.surveyed()) {
            return NavigationEvidence.isFreshStandingSupport(
                voxel,
                evidence.currentRevision()
            );
        }
        return evidence.contains(voxel.observationRevision())
                && voxel.kind().supportsWeight()
                && voxel.topSupportAffordance()
                    == TopSupportAffordance.STURDY_FULL_TOP
                && (voxel.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT);
    }

    private static double effectiveDanger(
            final CoreSkillFrame frame,
            final GridPos feet
    ) {
        return Math.max(
                frame.navigation().voxelAt(feet)
                    .map(ObservedVoxel::effectiveDanger)
                    .orElse(1.0),
                frame.navigation().voxelAt(feet.below())
                    .map(ObservedVoxel::effectiveDanger)
                    .orElse(1.0)
        );
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        final double frameDanger =
                expectedJumpDescentOnly(frame)
                        ? 0.0
                        : frame.danger();
        if (context.riskScore() > maximumDanger
                || frameDanger > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH_RATIO
                : NORMAL_MINIMUM_HEALTH_RATIO;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_too_low"
            ));
        }
        final int minimumFood = context.hardcore()
                ? HARDCORE_MINIMUM_FOOD
                : NORMAL_MINIMUM_FOOD;
        if (frame.foodLevel() < minimumFood) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_too_low"
            ));
        }
        return Optional.empty();
    }

    private boolean expectedJumpDescentOnly(
            final CoreSkillFrame frame
    ) {
        return phase == Phase.JUMPING
                && !frame.dangerSignals().isEmpty()
                && frame.dangerSignals().stream().allMatch(
                    signal -> signal.kind()
                            == DangerKind.FALLING
                );
    }

    private FrameValidation validateFrame(
            final ParkourToParameters parameters
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
                    NAME + ".body_mismatch"
            );
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(
                    NAME + ".wrong_dimension"
            );
        }
        return FrameValidation.available(frame);
    }

    private static boolean arrived(
            final ParkourToParameters parameters,
            final CoreSkillFrame frame
    ) {
        return frame.onGround()
                && frame.position().subtract(parameters.target())
                    .length() <= parameters.arrivalRadius();
    }

    private static boolean takeoffReady(
            final CoreSkillFrame frame,
            final Direction direction
    ) {
        return directionalProgress(
                frame.position(),
                direction
        ) >= TAKEOFF_PROGRESS;
    }

    private static double directionalProgress(
            final PerceptionVec3 position,
            final Direction direction
    ) {
        final double coordinate = direction.x() != 0
                ? position.x()
                : position.z();
        final double local = coordinate - Math.floor(coordinate);
        return direction.x() + direction.z() > 0
                ? local
                : 1.0 - local;
    }

    static boolean shouldApproachHardcoreScanEdge(
            final PerceptionVec3 position,
            final Direction direction,
            final boolean edgeAcquired
    ) {
        return !edgeAcquired
                && directionalProgress(position, direction)
                < HARDCORE_SCAN_EDGE_PROGRESS;
    }

    private void resetGroundScan(
            final GridPos feet,
            final Direction direction
    ) {
        edgeApproachTicks = 0;
        scanTicks = 0;
        confirmedGapScanMode = false;
        hardcoreRunUpRequired = false;
        hardcoreScanEdgeAcquired = false;
        hardcoreScanStartedRevision = -1;
        hardcoreScanTakeoff = feet;
        hardcoreScanDirection = direction;
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        final float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static double horizontalAngularError(
            final PerceptionVec3 current,
            final Direction direction
    ) {
        final double currentLength = Math.hypot(
                current.x(),
                current.z()
        );
        if (currentLength <= 1.0E-9) {
            return 180.0;
        }
        final double dot = (
                current.x() * direction.x()
                    + current.z() * direction.z()
        ) / currentLength;
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private boolean quiesce() {
        final Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return false;
        }
        final CoreSkillFrame frame = current.orElseThrow();
        return actuator.stop().accepted()
                && actuator.look(lookAt(
                    new PerceptionVec3(0.0, 0.0, 0.0),
                    frame.lookDirection()
                )).accepted();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        clearJump();
        return SkillTickResult.failed(reason);
    }

    private void clearJump() {
        jumpStartedAtTick = -1;
        landingSafetyRevision = -1;
        jumpRequests = 0;
        recenterStableTicks = 0;
        recenterPhase = null;
        airborneObserved = false;
        airborneBrakePrimed = false;
        hardcoreRunUpRequired = false;
        hardcoreScanEdgeAcquired = false;
        hardcoreScanStartedRevision = -1;
        confirmedGapScanMode = false;
        hardcoreScanTakeoff = null;
        hardcoreScanDirection = null;
        takeoff = null;
        landing = null;
        directionX = 0;
        directionZ = 0;
        sprintJump = false;
        launchY = 0.0;
        groundedDirectionFeet = null;
        groundedDirection = null;
        groundedDirectionLockTicks = 0;
    }

    private record EvidenceWindow(
        boolean surveyed,
        long minimumRevision,
        long currentRevision
    ) {
        private static EvidenceWindow current(
                final long revision
        ) {
            return new EvidenceWindow(false, revision, revision);
        }

        private static EvidenceWindow surveyed(
                final long minimumRevision,
                final long currentRevision
        ) {
            return new EvidenceWindow(
                true,
                minimumRevision,
                currentRevision
            );
        }

        private boolean contains(final long revision) {
            return withinHardcoreScanEvidenceWindow(
                minimumRevision,
                currentRevision,
                revision
            );
        }
    }

    private enum Phase {
        IDLE,
        READY,
        JUMPING,
        RECENTERING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == READY
                    || this == JUMPING
                    || this == RECENTERING;
        }
    }

    private enum RecenterPhase {
        BRAKE_LANDING,
        APPROACH,
        COAST
    }

    record Direction(int x, int z) {
        Direction {
            if (Math.abs(x) + Math.abs(z) != 1) {
                throw new IllegalArgumentException(
                        "Direction must be cardinal"
                );
            }
        }
    }

    private enum DirectionEvidence {
        UNKNOWN(0),
        VISIBLE_CANDIDATE_TOP(1),
        STRICT_LANDING(2),
        CURRENT_ADJACENT(3);

        private final int rank;

        DirectionEvidence(final int rank) {
            this.rank = rank;
        }

        private int rank() {
            return rank;
        }
    }

    record ScanPlan(boolean recovery, int distance) {
        ScanPlan {
            if (distance <= 0) {
                throw new IllegalArgumentException(
                        "Scan distance must be positive"
                );
            }
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

        private static FrameValidation failed(
                final String code
        ) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
