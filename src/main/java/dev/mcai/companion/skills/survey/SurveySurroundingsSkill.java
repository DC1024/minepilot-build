package dev.mcai.companion.skills.survey;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotates a stationary player through a bounded set of ordinary camera
 * headings and aggregates only semantic samples that arrive after each
 * heading is reached.
 */
public final class SurveySurroundingsSkill
        implements Skill<SurveySurroundingsParameters> {
    public static final String NAME = "survey_surroundings";
    private static final double ALIGNMENT_DEGREES = 3.0;
    private static final double NORMAL_MAXIMUM_DANGER = 0.25;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.10;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final SurveyResultBuffer results;

    private final Map<BlockKey, ObservedBlockSample> blocks =
            new LinkedHashMap<>();
    private final Map<UUID, VisibleEntity> entities =
            new LinkedHashMap<>();
    private final EnumMap<DangerKind, Double> dangers =
            new EnumMap<>(DangerKind.class);

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private float baseYaw;
    private int viewIndex;
    private long awaitingObservationRevision = -1;
    private long awaitingSinceTick = -1;
    private long firstObservationRevision = -1;
    private long lastObservationRevision = -1;
    private PerceptionVec3 origin;

    public SurveySurroundingsSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final SurveyResultBuffer results
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
    public SkillParameterParser<SurveySurroundingsParameters>
            parameters() {
        return SurveySkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> frame = current(parameters);
        if (frame.isEmpty()) {
            return Optional.of(
                    SkillFailure.of(NAME + ".observation_unavailable")
            );
        }
        return safetyFailure(context, frame.orElseThrow());
    }

    @Override
    public void start(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        final CoreSkillFrame frame = current(parameters)
                .orElseThrow(() -> new IllegalStateException(
                        "Survey binding changed before start"
                ));
        phase = Phase.RUNNING;
        failure = null;
        baseYaw = yawOf(frame.lookDirection());
        viewIndex = 0;
        awaitingObservationRevision = -1;
        awaitingSinceTick = -1;
        firstObservationRevision = -1;
        lastObservationRevision = -1;
        origin = frame.position();
        blocks.clear();
        entities.clear();
        dangers.clear();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        if (phase != Phase.RUNNING) {
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
        if (viewIndex >= parameters.totalViews()) {
            return complete(context, parameters);
        }

        final LookIntent target = target(parameters, viewIndex);
        final ActionOutcome stopped = actuator.stop();
        final ActionOutcome looking = actuator.look(target);
        if (!stopped.accepted() || !looking.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final double error = angularError(
                frame.lookDirection(),
                direction(target)
        );
        if (error > ALIGNMENT_DEGREES) {
            awaitingObservationRevision = -1;
            awaitingSinceTick = -1;
            return SkillTickResult.running(false, false);
        }

        if (awaitingObservationRevision < 0) {
            awaitingObservationRevision =
                    frame.observationRevision();
            awaitingSinceTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (frame.observationRevision()
                <= awaitingObservationRevision) {
            if (context.gameTick() - awaitingSinceTick
                    >= parameters.observationWaitTicks()) {
                return fail(NAME + ".semantic_refresh_timeout");
            }
            return SkillTickResult.running(false, false);
        }

        ingest(frame);
        viewIndex++;
        awaitingObservationRevision = -1;
        awaitingSinceTick = -1;
        if (viewIndex >= parameters.totalViews()) {
            return complete(context, parameters);
        }
        return SkillTickResult.running(true, true);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"view\":%d,\"total\":%d,"
                                + "\"blocks\":%d,\"entities\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        viewIndex,
                        parameters.totalViews(),
                        blocks.size(),
                        entities.size()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        actuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
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

    private void ingest(final CoreSkillFrame frame) {
        if (firstObservationRevision < 0) {
            firstObservationRevision = frame.observationRevision();
        }
        lastObservationRevision = frame.observationRevision();
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            final BlockKey key = new BlockKey(
                    face.block().x(),
                    face.block().y(),
                    face.block().z()
            );
            final ObservedBlockSample sample =
                    new ObservedBlockSample(
                            face,
                            frame.observationRevision()
                    );
            if (blocks.containsKey(key)
                    || blocks.size()
                    < SurveyResultSnapshot.MAXIMUM_BLOCKS) {
                blocks.merge(
                        key,
                        sample,
                        (left, right) ->
                                left.face().distance()
                                    <= right.face().distance()
                                        ? left
                                        : right
                );
            }
        }
        for (VisibleEntity entity : frame.visibleEntities()) {
            if (entities.containsKey(entity.entityId())
                    || entities.size()
                    < SurveyResultSnapshot.MAXIMUM_ENTITIES) {
                entities.put(entity.entityId(), entity);
            }
        }
        for (DangerSignal danger : frame.dangerSignals()) {
            dangers.merge(
                    danger.kind(),
                    danger.severity(),
                    Math::max
            );
        }
    }

    private SkillTickResult complete(
            final SkillContext context,
            final SurveySurroundingsParameters parameters
    ) {
        if (firstObservationRevision < 0
                || lastObservationRevision
                < firstObservationRevision) {
            return fail(NAME + ".no_fresh_samples");
        }
        final List<SurveyResultSnapshot.BlockData> blockData =
                blocks.values().stream()
                        .sorted(Comparator
                                .comparingDouble(
                                        (ObservedBlockSample sample) ->
                                            sample.face().distance()
                                )
                                .thenComparingInt(sample ->
                                        sample.face().block().y()
                                )
                                .thenComparingInt(sample ->
                                        sample.face().block().x()
                                )
                                .thenComparingInt(sample ->
                                        sample.face().block().z()
                                ))
                        .map(sample ->
                                new SurveyResultSnapshot.BlockData(
                                        sample.face().blockTypeId(),
                                        sample.face().block().x(),
                                        sample.face().block().y(),
                                        sample.face().block().z(),
                                        sample.observationRevision(),
                                        sample.face().face(),
                                        sample.face().distance()
                                )
                        )
                        .toList();
        final Map<String, EntityAccumulator> grouped =
                new HashMap<>();
        entities.values().forEach(entity ->
                grouped.compute(
                        entity.entityTypeId(),
                        (ignored, existing) ->
                                existing == null
                                        ? EntityAccumulator.first(entity)
                                        : existing.add(entity)
                )
        );
        final List<SurveyResultSnapshot.EntityData> entityData =
                grouped.values().stream()
                        .sorted(Comparator
                                .comparingDouble(
                                        EntityAccumulator::nearestDistance
                                )
                                .thenComparing(
                                        EntityAccumulator::type
                                ))
                        .map(EntityAccumulator::toData)
                        .toList();
        final List<SurveyResultSnapshot.DangerData> dangerData =
                dangers.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry ->
                                new SurveyResultSnapshot.DangerData(
                                        entry.getKey().name(),
                                        entry.getValue()
                                )
                        )
                        .toList();
        results.publish(new SurveyResultSnapshot(
                context.goalRevision(),
                parameters.dimension(),
                origin,
                context.gameTick(),
                viewIndex,
                firstObservationRevision,
                lastObservationRevision,
                blockData,
                entityData,
                dangerData
        ));
        actuator.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private Optional<CoreSkillFrame> current(
            final SurveySurroundingsParameters parameters
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
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (context.riskScore() > maximumDanger
                || frame.danger() > maximumDanger) {
            return Optional.of(
                    SkillFailure.of(NAME + ".danger_detected")
            );
        }
        final double healthRatio = frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? 0.60
                : 0.35;
        return healthRatio < minimumHealth
                ? Optional.of(
                        SkillFailure.of(NAME + ".health_reserve_low")
                )
                : Optional.empty();
    }

    private LookIntent target(
            final SurveySurroundingsParameters parameters,
            final int index
    ) {
        final int bands = parameters.includeVertical() ? 3 : 1;
        final int horizontal = index / bands;
        final int band = index % bands;
        final float pitch = !parameters.includeVertical()
                ? 0.0F
                : switch (band) {
                    case 1 -> -35.0F;
                    case 2 -> 35.0F;
                    default -> 0.0F;
                };
        final float yaw = baseYaw
                + horizontal
                * (360.0F / parameters.horizontalSteps());
        return new LookIntent(yaw, pitch);
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        actuator.stop();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private static float yawOf(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        );
    }

    private static PerceptionVec3 direction(
            final LookIntent look
    ) {
        final double yaw = Math.toRadians(look.yawDegrees());
        final double pitch = Math.toRadians(look.pitchDegrees());
        final double horizontal = Math.cos(pitch);
        return new PerceptionVec3(
                -Math.sin(yaw) * horizontal,
                -Math.sin(pitch),
                Math.cos(yaw) * horizontal
        ).normalized();
    }

    private static double angularError(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        final double dot = current.normalized().dot(target.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private enum Phase {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record ObservedBlockSample(
            VisibleBlockFace face,
            long observationRevision
    ) {
        private ObservedBlockSample {
            Objects.requireNonNull(face, "face");
            if (observationRevision < 0) {
                throw new IllegalArgumentException(
                        "Negative survey observation revision"
                );
            }
        }
    }

    private record EntityAccumulator(
            String type,
            int count,
            PerceptionVec3 nearestPosition,
            double nearestDistance,
            boolean hostile,
            boolean projectile
    ) {
        static EntityAccumulator first(final VisibleEntity entity) {
            return new EntityAccumulator(
                    entity.entityTypeId(),
                    1,
                    entity.position(),
                    entity.distance(),
                    entity.hostile(),
                    entity.projectile()
            );
        }

        EntityAccumulator add(final VisibleEntity entity) {
            final boolean nearer =
                    entity.distance() < nearestDistance;
            return new EntityAccumulator(
                    type,
                    count + 1,
                    nearer ? entity.position() : nearestPosition,
                    nearer ? entity.distance() : nearestDistance,
                    hostile || entity.hostile(),
                    projectile || entity.projectile()
            );
        }

        SurveyResultSnapshot.EntityData toData() {
            return new SurveyResultSnapshot.EntityData(
                    type,
                    count,
                    nearestPosition,
                    nearestDistance,
                    hostile,
                    projectile
            );
        }
    }
}
