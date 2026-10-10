package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
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
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Throws one normally-owned Eye of Ender, then follows that entity only while
 * it appears in successive fair first-person semantic observations.
 */
public final class TraceStrongholdEyeSkill
        implements Skill<TraceStrongholdEyeParameters> {
    public static final String NAME = "trace_stronghold_eye";
    public static final String EYE_ENTITY_ID = "minecraft:eye_of_ender";
    public static final String EYE_ITEM_ID = "minecraft:ender_eye";

    private static final int THROW_CONFIRMATION_TICKS = 20;
    /*
     * A thrown Eye can leave a 110-degree first-person view cone quickly.
     * Preserve the launch view for a short, human-scale reaction window,
     * then perform one continuous panoramic sweep. The fair actuator still
     * limits the physical head turn to 20 degrees per tick.
     */
    private static final int INITIAL_THROW_VIEW_HOLD_TICKS = 4;
    private static final int SEARCH_VIEW_HOLD_TICKS = 4;
    private static final int SEARCH_TIMEOUT_TICKS = 100;
    private static final int TOTAL_TIMEOUT_TICKS = 160;
    private static final int LOST_COMPLETION_TICKS = 12;
    private static final double MAXIMUM_ORIGIN_DRIFT = 0.75;
    private static final double MINIMUM_TRACE_TRAVEL = 1.0;
    private static final double MINIMUM_LAUNCH_DISPLACEMENT = 3.0;
    private static final float SEARCH_PITCH = -25.0F;
    private static final float[] SEARCH_YAW_OFFSETS = {
            0.0F,
            -45.0F,
            -90.0F,
            -135.0F,
            180.0F,
            135.0F,
            90.0F,
            45.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final EyeTraceResultBuffer results;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private PerceptionVec3 origin;
    private float baseYaw;
    private float basePitch;
    private long startedAtTick = -1;
    private long thrownAtTick = -1;
    private long lastSeenAtTick = -1;
    private long lastProcessedRevision = -1;
    private int initialEyeCount;
    private int searchView;
    private long nextSearchViewTick;
    private UUID boundEyeId;
    private final Set<UUID> eyesVisibleBeforeThrow = new HashSet<>();
    private final List<EyeTraceSnapshot.Sample> samples =
            new ArrayList<>();

    public TraceStrongholdEyeSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final EyeTraceResultBuffer results
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public SkillParameterParser<TraceStrongholdEyeParameters>
            parameters() {
        return StrongholdSkillParameters::parseTrace;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!parameters.dimension().equals(DimensionRef.OVERWORLD)) {
            return Optional.of(
                    SkillFailure.of(NAME + ".overworld_required")
            );
        }
        final Optional<CoreSkillFrame> current = current(parameters);
        if (current.isEmpty()) {
            return Optional.of(
                    SkillFailure.of(NAME + ".observation_unavailable")
            );
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (frame.observationRevision()
                != parameters.sampleSequence()) {
            return Optional.of(
                    SkillFailure.of(NAME + ".stale_observation")
            );
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(
                    SkillFailure.of(NAME + ".stable_ground_required")
            );
        }
        if (!held(frame, parameters.hand())
                .itemId()
                .equals(EYE_ITEM_ID)) {
            return Optional.of(
                    SkillFailure.of(NAME + ".ender_eye_not_held")
            );
        }
        return safetyFailure(context, frame);
    }

    @Override
    public void start(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        final CoreSkillFrame frame = current(parameters)
                .orElseThrow(() -> new IllegalStateException(
                        "Eye trace binding changed before start"
                ));
        phase = Phase.READY;
        failure = null;
        origin = frame.position();
        baseYaw = yawOf(frame.lookDirection());
        basePitch = pitchOf(frame.lookDirection());
        startedAtTick = context.gameTick();
        thrownAtTick = -1;
        lastSeenAtTick = -1;
        lastProcessedRevision = frame.observationRevision();
        initialEyeCount = inventoryCount(frame, EYE_ITEM_ID);
        searchView = 0;
        nextSearchViewTick = -1;
        boundEyeId = null;
        eyesVisibleBeforeThrow.clear();
        frame.visibleEntities().stream()
                .filter(TraceStrongholdEyeSkill::isEye)
                .map(VisibleEntity::entityId)
                .forEach(eyesVisibleBeforeThrow::add);
        samples.clear();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        if (phase != Phase.READY
                && phase != Phase.SEARCHING
                && phase != Phase.TRACKING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        final Optional<CoreSkillFrame> current = current(parameters);
        if (current.isEmpty()) {
            return fail(NAME + ".observation_unavailable");
        }
        final CoreSkillFrame frame = current.orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (horizontalDistance(origin, frame.position())
                > MAXIMUM_ORIGIN_DRIFT) {
            return fail(NAME + ".body_moved_during_trace");
        }
        if (context.gameTick() - startedAtTick >= TOTAL_TIMEOUT_TICKS) {
            return finishOrFail(context, parameters);
        }

        if (phase == Phase.READY) {
            final ActionOutcome stopped = actuator.stop();
            final ActionOutcome used = actuator.useItem(parameters.hand());
            if (!stopped.accepted() || !used.accepted()) {
                return fail(NAME + ".item_use_rejected");
            }
            thrownAtTick = context.gameTick();
            nextSearchViewTick = thrownAtTick
                    + INITIAL_THROW_VIEW_HOLD_TICKS
                    + SEARCH_VIEW_HOLD_TICKS;
            phase = Phase.SEARCHING;
            return SkillTickResult.running(true, false);
        }

        final int currentEyeCount = inventoryCount(frame, EYE_ITEM_ID);
        if (currentEyeCount < initialEyeCount - 1) {
            return fail(NAME + ".unexpected_eye_consumption");
        }
        if (currentEyeCount >= initialEyeCount
                && context.gameTick() - thrownAtTick
                    >= THROW_CONFIRMATION_TICKS) {
            return fail(NAME + ".throw_not_confirmed");
        }
        if (frame.observationRevision() <= lastProcessedRevision) {
            holdCurrentLook(frame);
            return SkillTickResult.running(false, false);
        }
        lastProcessedRevision = frame.observationRevision();

        if (phase == Phase.SEARCHING) {
            final Optional<VisibleEntity> eye =
                    newlyVisibleEye(frame);
            if (eye.isPresent()) {
                boundEyeId = eye.orElseThrow().entityId();
                ingest(frame, eye.orElseThrow());
                lookAt(frame, eye.orElseThrow().position());
                lastSeenAtTick = context.gameTick();
                phase = Phase.TRACKING;
                return SkillTickResult.running(true, false);
            }
            if (context.gameTick() - thrownAtTick
                    >= SEARCH_TIMEOUT_TICKS) {
                return fail(NAME + ".thrown_eye_not_observed");
            }
            if (context.gameTick() - thrownAtTick
                    < INITIAL_THROW_VIEW_HOLD_TICKS) {
                final ActionOutcome stopped = actuator.stop();
                final ActionOutcome looking = actuator.look(
                        new LookIntent(baseYaw, basePitch)
                );
                if (!stopped.accepted() || !looking.accepted()) {
                    return fail(NAME + ".camera_hold_rejected");
                }
                return SkillTickResult.running(false, false);
            }
            while (context.gameTick() >= nextSearchViewTick) {
                searchView++;
                nextSearchViewTick += SEARCH_VIEW_HOLD_TICKS;
            }
            final float yawOffset = SEARCH_YAW_OFFSETS[
                    Math.floorMod(
                            searchView,
                            SEARCH_YAW_OFFSETS.length
                    )
            ];
            final ActionOutcome stopped = actuator.stop();
            final ActionOutcome looking = actuator.look(new LookIntent(
                    baseYaw + yawOffset,
                    SEARCH_PITCH
            ));
            if (!stopped.accepted() || !looking.accepted()) {
                return fail(NAME + ".camera_search_rejected");
            }
            return SkillTickResult.running(true, true);
        }

        final Optional<VisibleEntity> eye = frame.visibleEntities()
                .stream()
                .filter(entity ->
                        entity.entityId().equals(boundEyeId)
                                && isEye(entity)
                )
                .findFirst();
        if (eye.isPresent()) {
            ingest(frame, eye.orElseThrow());
            lookAt(frame, eye.orElseThrow().position());
            lastSeenAtTick = context.gameTick();
            if (samples.size() >= 3
                    && directionEstimate().isPresent()) {
                return complete(context, parameters);
            }
            return SkillTickResult.running(true, false);
        }

        lookTowardExtrapolated(frame);
        if (samples.size() >= 2
                && directionEstimate().isPresent()
                && context.gameTick() - lastSeenAtTick
                    >= LOST_COMPLETION_TICKS) {
            return complete(context, parameters);
        }
        return SkillTickResult.running(false, false);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"hand\":\"%s\",\"samples\":%d,"
                                + "\"searchView\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.hand().name(),
                        samples.size(),
                        searchView
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        actuator.stop();
        actuator.releaseUse();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
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

    private SkillTickResult finishOrFail(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        return samples.size() >= 2
                && directionEstimate().isPresent()
                ? complete(context, parameters)
                : fail(NAME + ".insufficient_visible_trajectory");
    }

    private SkillTickResult complete(
            final SkillContext context,
            final TraceStrongholdEyeParameters parameters
    ) {
        final EyeTraceSnapshot.Sample first = samples.getFirst();
        final EyeTraceSnapshot.Sample last = samples.getLast();
        final Optional<DirectionEstimate> estimated =
                directionEstimate();
        if (estimated.isEmpty()) {
            return fail(NAME + ".insufficient_visible_trajectory");
        }
        final DirectionEstimate estimate = estimated.orElseThrow();
        final double directionX = estimate.directionX();
        final double directionZ = estimate.directionZ();
        final double travel = estimate.observedTravel();
        final double bearing = normalizeDegrees(Math.toDegrees(
                Math.atan2(-directionX, directionZ)
        ));
        results.publish(new EyeTraceSnapshot(
                context.goalRevision(),
                parameters.dimension(),
                origin,
                context.gameTick(),
                first.observationRevision(),
                last.observationRevision(),
                samples,
                directionX,
                directionZ,
                bearing,
                travel
        ));
        actuator.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private void ingest(
            final CoreSkillFrame frame,
            final VisibleEntity eye
    ) {
        if (samples.size() >= EyeTraceSnapshot.MAXIMUM_SAMPLES) {
            return;
        }
        if (!samples.isEmpty()
                && samples.getLast().observationRevision()
                    == frame.observationRevision()) {
            return;
        }
        samples.add(new EyeTraceSnapshot.Sample(
                frame.observationRevision(),
                eye.position()
        ));
    }

    private Optional<VisibleEntity> newlyVisibleEye(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream()
                .filter(TraceStrongholdEyeSkill::isEye)
                .filter(entity ->
                        !eyesVisibleBeforeThrow.contains(entity.entityId())
                )
                .min(java.util.Comparator.comparingDouble(
                        VisibleEntity::distance
                ));
    }

    private void lookAt(
            final CoreSkillFrame frame,
            final PerceptionVec3 position
    ) {
        final PerceptionVec3 relative = position
                .subtract(frame.eyePosition());
        if (relative.lengthSquared() <= 1.0E-9) {
            return;
        }
        final double horizontal = Math.hypot(
                relative.x(),
                relative.z()
        );
        actuator.stop();
        actuator.look(new LookIntent(
                (float) Math.toDegrees(
                        Math.atan2(-relative.x(), relative.z())
                ),
                (float) -Math.toDegrees(
                        Math.atan2(relative.y(), horizontal)
                )
        ));
    }

    private void lookTowardExtrapolated(final CoreSkillFrame frame) {
        if (samples.size() < 2) {
            holdCurrentLook(frame);
            return;
        }
        final PerceptionVec3 last =
                samples.getLast().observedPosition();
        final PerceptionVec3 previous =
                samples.get(samples.size() - 2).observedPosition();
        lookAt(frame, last.add(last.subtract(previous).scale(2.0)));
    }

    private void holdCurrentLook(final CoreSkillFrame frame) {
        actuator.stop();
        actuator.look(new LookIntent(
                yawOf(frame.lookDirection()),
                pitchOf(frame.lookDirection())
        ));
    }

    private Optional<CoreSkillFrame> current(
            final TraceStrongholdEyeParameters parameters
    ) {
        return frames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
                        && parameters.dimension().equals(
                                frame.dimension()
                        )
        );
    }

    private static Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double minimumHealth = context.hardcore() ? 0.65 : 0.40;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(
                    SkillFailure.of(NAME + ".health_reserve_low")
            );
        }
        final boolean nonEyeProjectile = frame.visibleEntities().stream()
                .anyMatch(entity ->
                        entity.projectile() && !isEye(entity)
                );
        final double effectiveDanger = frame.dangerSignals().stream()
                .filter(signal ->
                        nonEyeProjectile
                                || signal.kind()
                                    != DangerKind.PROJECTILE_PROXIMITY
                )
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        final double maximumDanger = context.hardcore() ? 0.10 : 0.25;
        if (effectiveDanger > maximumDanger
                || context.riskScore() > maximumDanger
                    && (nonEyeProjectile
                        || effectiveDanger > 0.0)) {
            return Optional.of(
                    SkillFailure.of(NAME + ".danger_detected")
            );
        }
        return Optional.empty();
    }

    private double observedHorizontalTravel() {
        if (samples.size() < 2) {
            return 0.0;
        }
        return horizontalDistance(
                samples.getFirst().observedPosition(),
                samples.getLast().observedPosition()
        );
    }

    /**
     * The Eye's visible displacement from its known throw origin is itself a
     * fair first-person direction measurement. It remains useful when the
     * camera first catches the Eye near its vanilla hover point, where
     * successive samples move less than one block. Prefer that longer,
     * less-noisy baseline; retain successive-sample travel for very close
     * traces.
     */
    private Optional<DirectionEstimate> directionEstimate() {
        if (samples.size() < 2) {
            return Optional.empty();
        }
        final PerceptionVec3 last =
                samples.getLast().observedPosition();
        final double launchDeltaX = last.x() - origin.x();
        final double launchDeltaZ = last.z() - origin.z();
        final double launchDisplacement = Math.hypot(
                launchDeltaX,
                launchDeltaZ
        );
        if (launchDisplacement >= MINIMUM_LAUNCH_DISPLACEMENT) {
            return Optional.of(new DirectionEstimate(
                    launchDeltaX / launchDisplacement,
                    launchDeltaZ / launchDisplacement,
                    launchDisplacement
            ));
        }

        final PerceptionVec3 first =
                samples.getFirst().observedPosition();
        final double traceDeltaX = last.x() - first.x();
        final double traceDeltaZ = last.z() - first.z();
        final double traceTravel = Math.hypot(
                traceDeltaX,
                traceDeltaZ
        );
        if (traceTravel < MINIMUM_TRACE_TRAVEL) {
            return Optional.empty();
        }
        return Optional.of(new DirectionEstimate(
                traceDeltaX / traceTravel,
                traceDeltaZ / traceTravel,
                traceTravel
        ));
    }

    private static HeldItemSummary held(
            final CoreSkillFrame frame,
            final ActionHand hand
    ) {
        return hand == ActionHand.MAIN_HAND
                ? frame.mainHand()
                : frame.offHand();
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(item -> item.count())
                .sum();
    }

    private static boolean isEye(final VisibleEntity entity) {
        return entity.entityTypeId().equals(EYE_ENTITY_ID);
    }

    private record DirectionEstimate(
            double directionX,
            double directionZ,
            double observedTravel
    ) {
    }

    private static double horizontalDistance(
            final PerceptionVec3 left,
            final PerceptionVec3 right
    ) {
        return Math.hypot(
                left.x() - right.x(),
                left.z() - right.z()
        );
    }

    private static float yawOf(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        );
    }

    private static float pitchOf(final PerceptionVec3 direction) {
        return (float) -Math.toDegrees(Math.asin(
                Math.max(-1.0, Math.min(1.0, direction.y()))
        ));
    }

    private static double normalizeDegrees(final double value) {
        double normalized = value % 360.0;
        if (normalized > 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized == 0.0 ? 0.0 : normalized;
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        actuator.stop();
        actuator.releaseUse();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private enum Phase {
        IDLE,
        READY,
        SEARCHING,
        TRACKING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
