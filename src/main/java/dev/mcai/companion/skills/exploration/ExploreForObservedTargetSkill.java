package dev.mcai.companion.skills.exploration;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillPolicy;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.core.TravelSkillPolicy;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Bounded square-spiral exploration composed from the fair rolling travel
 * skill. Target detection uses only the current semantic observation.
 */
public final class ExploreForObservedTargetSkill
        implements Skill<ExploreForTargetParameters> {
    public static final String NAME =
            "explore_for_observed_target";

    private static final double NORMAL_MAXIMUM_DANGER = 0.20;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.08;
    private static final int MAXIMUM_SEGMENT_FAILURES = 12;
    private static final int MAXIMUM_TOTAL_TICKS = 6_000;
    private static final float SCAN_ALIGNMENT_TOLERANCE_DEGREES = 2.0F;
    private static final float[] SCAN_YAW_OFFSETS = {
        0.0F,
        45.0F,
        90.0F,
        135.0F,
        180.0F,
        -135.0F,
        -90.0F,
        -45.0F
    };
    private static final float[] SCAN_PITCHES = {
        3.0F,
        -20.0F
    };
    private static final int[][] CLOCKWISE = {
        {0, 1},
        {1, 0},
        {0, -1},
        {-1, 0}
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final LongSupplier sessionGeneration;
    private final BiPredicate<
            CoreSkillFrame,
            ExploreForTargetParameters
    > targetVisibility;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private double originX;
    private double originZ;
    private int gridX;
    private int gridZ;
    private int directionIndex;
    private int legLength;
    private int legProgress;
    private int completedLegsAtLength;
    private int attemptedSegments;
    private int failedSegments;
    private int scanViewIndex;
    private float scanBaseYaw;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;

    public ExploreForObservedTargetSkill(
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
                ExploreForObservedTargetSkill::targetVisible
        );
    }

    /**
     * Internal composition seam for compound skills whose target is a
     * semantic category rather than one registry id. The detector receives
     * only the same bounded first-person frame as the ordinary public skill;
     * it cannot inspect chunks, entities, or registries behind the body.
     */
    public ExploreForObservedTargetSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final LongSupplier sessionGeneration,
            final BiPredicate<
                    CoreSkillFrame,
                    ExploreForTargetParameters
            > targetVisibility
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(
                actuator,
                "actuator"
        );
        this.frames = Objects.requireNonNull(frames, "frames");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.targetVisibility = Objects.requireNonNull(
                targetVisibility,
                "targetVisibility"
        );
    }

    @Override
    public SkillParameterParser<ExploreForTargetParameters> parameters() {
        return ExplorationSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final ExploreForTargetParameters parameters
    ) {
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return safetyFailure(
                context,
                validation.frame().orElseThrow()
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final ExploreForTargetParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame(parameters)
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "Exploration body changed before start"
                ));
        phase = Phase.SEARCHING;
        failure = null;
        startedAtTick = context.gameTick();
        lastObservationRevision = -1;
        originX = frame.position().x();
        originZ = frame.position().z();
        gridX = 0;
        gridZ = 0;
        directionIndex = initialDirection(frame.lookDirection());
        legLength = 1;
        legProgress = 0;
        completedLegsAtLength = 0;
        attemptedSegments = 0;
        failedSegments = 0;
        scanViewIndex = -1;
        scanBaseYaw = 0.0F;
        travel = null;
        travelParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final ExploreForTargetParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final ExploreForTargetParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"targetKind\":\"%s\",\"targetId\":\"%s\","
                            + "\"gridX\":%d,\"gridZ\":%d,"
                            + "\"segments\":%d,\"failures\":%d,"
                            + "\"scanView\":%d,\"travel\":\"%s\"}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.targetKind().name(),
                        parameters.targetId(),
                        gridX,
                        gridZ,
                        attemptedSegments,
                        failedSegments,
                        scanViewIndex,
                        travelCheckpoint(context)
                )
        );
    }

    private String travelCheckpoint(
            final SkillContext context
    ) {
        if (travel == null || travelParameters == null) {
            return "";
        }
        return travel.checkpoint(context, travelParameters)
                .payload()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @Override
    public void cancel(
            final SkillContext context,
            final ExploreForTargetParameters parameters
    ) {
        cancelTravel(context);
        actuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final ExploreForTargetParameters parameters
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
            final ExploreForTargetParameters parameters
    ) {
        if (context.gameTick() - startedAtTick > MAXIMUM_TOTAL_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(parameters);
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
        if (targetVisibility.test(frame, parameters)) {
            cancelTravel(context);
            actuator.move(MovementIntent.STOPPED);
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (travel != null) {
            final SkillTickResult result = travel.tick(
                    context,
                    travelParameters
            );
            if (result.status()
                    == SkillTickResult.Status.COMPLETED) {
                travel = null;
                travelParameters = null;
                scanBaseYaw = yawOf(frame.lookDirection());
                scanViewIndex = 0;
                return SkillTickResult.running(true, true);
            }
            if (result.status()
                    == SkillTickResult.Status.FAILED) {
                failedSegments++;
                travel = null;
                travelParameters = null;
                if (failedSegments > MAXIMUM_SEGMENT_FAILURES) {
                    return fail(
                            context,
                            NAME + ".route_failures_exhausted"
                    );
                }
                return SkillTickResult.running(true, true);
            }
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        if (scanViewIndex >= 0) {
            return tickScan(context, frame, fresh);
        }
        return startNextSegment(
                context,
                parameters,
                frame,
                fresh
        );
    }

    private SkillTickResult startNextSegment(
            final SkillContext context,
            final ExploreForTargetParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final int[] direction = CLOCKWISE[directionIndex];
        gridX += direction[0];
        gridZ += direction[1];
        legProgress++;
        if (legProgress >= legLength) {
            legProgress = 0;
            directionIndex = (directionIndex + 1)
                    % CLOCKWISE.length;
            completedLegsAtLength++;
            if (completedLegsAtLength >= 2) {
                completedLegsAtLength = 0;
                legLength++;
            }
        }
        final double offsetX =
                (double) gridX * parameters.stepDistance();
        final double offsetZ =
                (double) gridZ * parameters.stepDistance();
        if (Math.hypot(offsetX, offsetZ)
                > parameters.maximumDistance()) {
            return fail(context, NAME + ".search_radius_exhausted");
        }
        travelParameters = new TravelToParameters(
                parameters.dimension(),
                originX + offsetX,
                frame.position().y(),
                originZ + offsetZ,
                2.5
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                actuator,
                frames,
                sessionGeneration,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                TravelSkillPolicy.explorationDefaults()
        );
        final Optional<SkillFailure> precondition =
                travel.preconditions(context, travelParameters);
        if (precondition.isPresent()) {
            travel = null;
            travelParameters = null;
            failedSegments++;
            return failedSegments > MAXIMUM_SEGMENT_FAILURES
                    ? fail(
                        context,
                        NAME + ".route_failures_exhausted"
                    )
                    : SkillTickResult.running(true, true);
        }
        travel.start(context, travelParameters);
        attemptedSegments++;
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult tickScan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final int pitchIndex =
                scanViewIndex / SCAN_YAW_OFFSETS.length;
        final int yawIndex =
                scanViewIndex % SCAN_YAW_OFFSETS.length;
        if (pitchIndex >= SCAN_PITCHES.length) {
            scanViewIndex = -1;
            actuator.stop();
            return SkillTickResult.running(true, true);
        }
        final float desiredYaw = normalizeDegrees(
                scanBaseYaw + SCAN_YAW_OFFSETS[yawIndex]
        );
        final float desiredPitch = SCAN_PITCHES[pitchIndex];
        final ActionOutcome stopped = actuator.stop();
        final ActionOutcome looking = actuator.look(new LookIntent(
                desiredYaw,
                desiredPitch
        ));
        if (!stopped.accepted() || !looking.accepted()) {
            return fail(context, NAME + ".camera_scan_rejected");
        }
        final float currentYaw = yawOf(frame.lookDirection());
        final float currentPitch = pitchOf(frame.lookDirection());
        if (fresh
                && Math.abs(normalizeDegrees(
                    currentYaw - desiredYaw
                )) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES
                && Math.abs(currentPitch - desiredPitch)
                    <= SCAN_ALIGNMENT_TOLERANCE_DEGREES) {
            scanViewIndex++;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(false, false);
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
                ? 0.85
                : 0.50;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 10 : 5)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            final ExploreForTargetParameters parameters
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

    private static boolean targetVisible(
            final CoreSkillFrame frame,
            final ExploreForTargetParameters parameters
    ) {
        return switch (parameters.targetKind()) {
            case BLOCK -> frame.visibleBlockFaces().stream()
                    .anyMatch(face ->
                            face.blockTypeId().equals(
                                parameters.targetId()
                            )
                    );
            case ENTITY -> frame.visibleEntities().stream()
                    .anyMatch(entity ->
                            entity.entityTypeId().equals(
                                parameters.targetId()
                            )
                    );
        };
    }

    private void cancelTravel(final SkillContext context) {
        if (travel == null || travelParameters == null) {
            return;
        }
        try {
            travel.cancel(context, travelParameters);
        } catch (RuntimeException ignored) {
            actuator.stop();
        }
        travel = null;
        travelParameters = null;
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
        cancelTravel(context);
        actuator.stop();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private static int initialDirection(
            final PerceptionVec3 look
    ) {
        if (Math.abs(look.z()) >= Math.abs(look.x())) {
            return look.z() >= 0.0 ? 0 : 2;
        }
        return look.x() >= 0.0 ? 1 : 3;
    }

    private static float yawOf(final PerceptionVec3 look) {
        return normalizeDegrees((float) Math.toDegrees(
                Math.atan2(-look.x(), look.z())
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

    private enum Phase {
        IDLE,
        SEARCHING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == SEARCHING;
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
