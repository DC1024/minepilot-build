package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Uses exactly one freshly observed vanilla bed and verifies a real sleep
 * cycle owned by the bound player.
 *
 * <p>The only mutation is an ordinary main-hand block-use action. Completion
 * requires the real player to enter the expected bed, acquire the matching
 * respawn point, and later wake after the world's ordinary clock reaches
 * daylight. The skill never controls time, weather, spawn, position, or wake
 * state.</p>
 */
public final class SleepInObservedBedSkill
        implements Skill<SleepInObservedBedParameters> {
    private static final String NAME = "sleep_in_observed_bed";
    private static final Set<String> VANILLA_BEDS = Set.of(
            "minecraft:white_bed",
            "minecraft:orange_bed",
            "minecraft:magenta_bed",
            "minecraft:light_blue_bed",
            "minecraft:yellow_bed",
            "minecraft:lime_bed",
            "minecraft:pink_bed",
            "minecraft:gray_bed",
            "minecraft:light_gray_bed",
            "minecraft:cyan_bed",
            "minecraft:purple_bed",
            "minecraft:blue_bed",
            "minecraft:brown_bed",
            "minecraft:green_bed",
            "minecraft:red_bed",
            "minecraft:black_bed"
    );

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final SleepSkillFrameSource frames;
    private final SleepSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long dispatchedAtTick = -1;
    private long sleepClockAtEntry = -1;
    private BlockCoordinate expectedBedHead;

    public SleepInObservedBedSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            SleepSkillFrameSource frames,
            SleepSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<SleepInObservedBedParameters> parameters() {
        return SleepSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            SleepInObservedBedParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        FrameValidation validation = validateFrame(
                parameters,
                -1,
                true
        );
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        SleepSkillFrame frame = validation.frame().orElseThrow();
        Optional<SkillFailure> safety = validateBeforeUse(
                context,
                frame
        );
        if (safety.isPresent()) {
            return safety;
        }
        return resolveBed(frame, parameters).failure();
    }

    @Override
    public void start(
            SkillContext context,
            SleepInObservedBedParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        FrameValidation validation = validateFrame(
                parameters,
                -1,
                true
        );
        if (validation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Sleep observation changed before start"
            );
        }
        SleepSkillFrame frame = validation.frame().orElseThrow();
        BedResolution bed = resolveBed(frame, parameters);
        if (validateBeforeUse(context, frame).isPresent()
                || bed.failure().isPresent()) {
            throw new IllegalStateException(
                    "Sleep preconditions changed before start"
            );
        }

        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
        dispatchedAtTick = -1;
        sleepClockAtEntry = -1;
        expectedBedHead = bed.head().orElseThrow();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            SleepInObservedBedParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.READY
                && phase != Phase.WAITING_FOR_SLEEP
                && phase != Phase.SLEEPING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtTick
                >= policy.maximumTotalTicks()) {
            return fail(timeoutFailure());
        }

        boolean requireFresh = phase == Phase.READY;
        FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                requireFresh
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        SleepSkillFrame frame = validation.frame().orElseThrow();
        return switch (phase) {
            case READY -> useObservedBed(context, parameters, frame);
            case WAITING_FOR_SLEEP ->
                    verifySleepStarted(context, parameters, frame);
            case SLEEPING -> verifyNaturalWake(frame);
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            SleepInObservedBedParameters parameters
    ) {
        BlockCoordinate head = expectedBedHead == null
                ? new BlockCoordinate(
                        parameters.target().x(),
                        parameters.target().y(),
                        parameters.target().z()
                )
                : expectedBedHead;
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"bedHead\":{\"x\":%d,\"y\":%d,"
                                + "\"z\":%d},\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        head.x(),
                        head.y(),
                        head.z(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            SleepInObservedBedParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            SleepInObservedBedParameters parameters
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

    private SkillTickResult useObservedBed(
            SkillContext context,
            SleepInObservedBedParameters parameters,
            SleepSkillFrame frame
    ) {
        Optional<SkillFailure> safety = validateBeforeUse(
                context,
                frame
        );
        if (safety.isPresent()) {
            return fail(safety.orElseThrow());
        }
        BedResolution resolution = resolveBed(frame, parameters);
        if (resolution.failure().isPresent()) {
            return fail(resolution.failure().orElseThrow());
        }
        if (!expectedBedHead.equals(
                resolution.head().orElseThrow()
        )) {
            return fail("bed_state_changed");
        }

        ActionOutcome outcome = actuator.useOnBlock(
                ActionHand.MAIN_HAND,
                resolution.target().orElseThrow()
        );
        if (!outcome.accepted()) {
            return fail(
                    "use_" + outcome.name().toLowerCase(Locale.ROOT)
            );
        }
        phase = Phase.WAITING_FOR_SLEEP;
        dispatchedAtTick = context.gameTick();
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifySleepStarted(
            SkillContext context,
            SleepInObservedBedParameters parameters,
            SleepSkillFrame frame
    ) {
        if (frame.sleeping()) {
            Optional<SkillFailure> binding = verifySleepBinding(frame);
            if (binding.isPresent()) {
                return fail(binding.orElseThrow());
            }
            phase = Phase.SLEEPING;
            sleepClockAtEntry = frame.clockTime();
            return SkillTickResult.running(true, false);
        }

        Optional<SkillFailure> danger = dangerFailure(
                context.hardcore(),
                frame
        );
        if (danger.isPresent()) {
            return fail(danger.orElseThrow());
        }
        if (!frame.darkOutside()) {
            return fail("daytime_before_sleep");
        }
        if (!frame.projectedSleepThresholdMet()) {
            return fail("insufficient_sleepers");
        }
        BedResolution currentBed = resolveBedIfCurrent(
                frame,
                parameters
        );
        if (currentBed.failure().map(value ->
                value.code().endsWith(".bed_occupied")).orElse(false)) {
            return fail("bed_occupied");
        }
        if (context.gameTick() - dispatchedAtTick
                >= policy.sleepStartConfirmationTicks()) {
            return fail("sleep_not_started");
        }
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyNaturalWake(
            SleepSkillFrame frame
    ) {
        if (frame.sleeping()) {
            Optional<SkillFailure> binding = verifySleepBinding(frame);
            if (binding.isPresent()) {
                return fail(binding.orElseThrow());
            }
            return SkillTickResult.running(true, false);
        }
        if (!frame.respawnMatches(
                frame.currentDimension(),
                expectedBedHead
        )) {
            return fail("respawn_changed");
        }
        if (!frame.darkOutside()
                && frame.clockTime() > sleepClockAtEntry) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return fail("woke_before_dawn");
    }

    private Optional<SkillFailure> verifySleepBinding(
            SleepSkillFrame frame
    ) {
        if (frame.sleepingPosition().isEmpty()
                || !frame.sleepingPosition()
                        .orElseThrow()
                        .equals(expectedBedHead)) {
            return Optional.of(failure("wrong_sleeping_position"));
        }
        if (!frame.respawnMatches(
                frame.currentDimension(),
                expectedBedHead
        )) {
            return Optional.of(failure("respawn_not_set"));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> validateBeforeUse(
            SkillContext context,
            SleepSkillFrame frame
    ) {
        if (!DimensionRef.OVERWORLD.equals(frame.currentDimension())) {
            return Optional.of(failure(
                    "explosive_or_unsupported_dimension"
            ));
        }
        if (frame.sleeping()) {
            return Optional.of(failure("already_sleeping"));
        }
        if (!frame.darkOutside()) {
            return Optional.of(failure("daytime"));
        }
        if (frame.healthFraction()
                < policy.minimumHealthFraction(context.hardcore())) {
            return Optional.of(failure("health_too_low"));
        }
        Optional<SkillFailure> danger = dangerFailure(
                context.hardcore(),
                frame
        );
        if (danger.isPresent()) {
            return danger;
        }
        if (context.riskScore()
                > policy.maximumDanger(context.hardcore())) {
            return Optional.of(failure("risk_too_high"));
        }
        if (!frame.projectedSleepThresholdMet()) {
            return Optional.of(failure("insufficient_sleepers"));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> dangerFailure(
            boolean hardcore,
            SleepSkillFrame frame
    ) {
        double maximum = policy.maximumDanger(hardcore);
        for (DangerSignal signal : frame.dangers()) {
            if (signal.kind() == DangerKind.ON_FIRE
                    || signal.kind() == DangerKind.LOW_AIR
                    || signal.kind() == DangerKind.FALLING
                    || signal.kind() == DangerKind.THREAT_CONTACT) {
                return Optional.of(failure("unsafe_body_state"));
            }
            if ((signal.kind() == DangerKind.HOSTILE_PROXIMITY
                    || signal.kind()
                            == DangerKind.PROJECTILE_PROXIMITY)
                    && signal.severity() > maximum) {
                return Optional.of(failure("hostile_nearby"));
            }
            if (signal.severity() > maximum) {
                return Optional.of(failure("danger_too_high"));
            }
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            SleepInObservedBedParameters parameters,
            long expectedSessionGeneration,
            boolean requireFreshObservation
    ) {
        Optional<SleepSkillFrame> maybeFrame = frames.current();
        if (maybeFrame.isEmpty()) {
            return FrameValidation.failed(failure(
                    "observation_unavailable"
            ));
        }
        SleepSkillFrame frame = maybeFrame.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(frame, failure(
                    "player_mismatch"
            ));
        }
        if (!parameters.dimension().equals(
                frame.observedDimension()
        ) || !parameters.dimension().equals(
                frame.currentDimension()
        )) {
            return FrameValidation.failed(frame, failure(
                    "dimension_mismatch"
            ));
        }
        if (!frame.alive() || frame.spectator()) {
            return FrameValidation.failed(frame, failure(
                    "player_incapacitated"
            ));
        }
        if (requireFreshObservation
                && frame.observationAgeTicks()
                        > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(frame, failure(
                    "stale_observation"
            ));
        }
        OptionalLong actuatorSession = actuator.sessionGeneration();
        if (actuatorSession.isEmpty()) {
            return FrameValidation.failed(frame, failure(
                    "player_unavailable"
            ));
        }
        long currentSession = actuatorSession.orElseThrow();
        if (currentSession != frame.sessionGeneration()
                || expectedSessionGeneration >= 0
                && currentSession != expectedSessionGeneration) {
            return FrameValidation.failed(frame, failure(
                    "session_mismatch"
            ));
        }
        return FrameValidation.valid(frame);
    }

    private BedResolution resolveBed(
            SleepSkillFrame frame,
            SleepInObservedBedParameters parameters
    ) {
        if (frame.observationRevision()
                != parameters.target().sampleSequence()) {
            return BedResolution.failed(failure(
                    "observation_expired"
            ));
        }
        return resolveBedIfCurrent(frame, parameters);
    }

    private BedResolution resolveBedIfCurrent(
            SleepSkillFrame frame,
            SleepInObservedBedParameters parameters
    ) {
        ObservedBlockTarget target = parameters.target();
        Optional<VisibleBlockFace> candidate =
                frame.visibleBlockFaces().stream()
                        .filter(face -> sameBlockAndFace(face, target))
                        .findFirst();
        if (candidate.isEmpty()) {
            return BedResolution.failed(failure(
                    "target_not_visible"
            ));
        }
        VisibleBlockFace visible = candidate.orElseThrow();
        if (!VANILLA_BEDS.contains(visible.blockTypeId())) {
            return BedResolution.failed(failure(
                    "target_not_vanilla_bed"
            ));
        }
        if (visible.distance() > policy.maximumBedDistance()) {
            return BedResolution.failed(failure("too_far"));
        }
        String occupied = visible.stateProperties().get("occupied");
        String part = visible.stateProperties().get("part");
        String facing = visible.stateProperties().get("facing");
        if (occupied == null || part == null || facing == null) {
            return BedResolution.failed(failure(
                    "bed_state_unavailable"
            ));
        }
        if ("true".equals(occupied)) {
            return BedResolution.failed(failure("bed_occupied"));
        }
        if (!"false".equals(occupied)) {
            return BedResolution.failed(failure(
                    "bed_state_unavailable"
            ));
        }
        Optional<BlockCoordinate> head = bedHead(
                visible.block(),
                part,
                facing
        );
        if (head.isEmpty()) {
            return BedResolution.failed(failure(
                    "bed_state_unavailable"
            ));
        }
        return BedResolution.resolved(
                interactionTarget(visible),
                head.orElseThrow()
        );
    }

    private SkillFailure timeoutFailure() {
        Optional<SleepSkillFrame> frame = frames.current();
        if (frame.isPresent()
                && frame.orElseThrow().sleeping()
                && !frame.orElseThrow()
                        .projectedSleepThresholdMet()) {
            return failure("insufficient_sleepers_timeout");
        }
        return failure("timed_out");
    }

    private SkillTickResult fail(String suffix) {
        return fail(failure(suffix));
    }

    private SkillTickResult fail(SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private static Optional<BlockCoordinate> bedHead(
            BlockCoordinate visible,
            String part,
            String facing
    ) {
        if ("head".equals(part)) {
            return Optional.of(visible);
        }
        if (!"foot".equals(part)) {
            return Optional.empty();
        }
        return switch (facing) {
            case "north" -> Optional.of(
                    new BlockCoordinate(
                            visible.x(),
                            visible.y(),
                            visible.z() - 1
                    )
            );
            case "south" -> Optional.of(
                    new BlockCoordinate(
                            visible.x(),
                            visible.y(),
                            visible.z() + 1
                    )
            );
            case "west" -> Optional.of(
                    new BlockCoordinate(
                            visible.x() - 1,
                            visible.y(),
                            visible.z()
                    )
            );
            case "east" -> Optional.of(
                    new BlockCoordinate(
                            visible.x() + 1,
                            visible.y(),
                            visible.z()
                    )
            );
            default -> Optional.empty();
        };
    }

    private static boolean sameBlockAndFace(
            VisibleBlockFace visible,
            ObservedBlockTarget target
    ) {
        return visible.block().x() == target.x()
                && visible.block().y() == target.y()
                && visible.block().z() == target.z()
                && visible.face().equals(
                        target.face().name()
                                .toLowerCase(Locale.ROOT)
                );
    }

    private static BlockInteractionTarget interactionTarget(
            VisibleBlockFace visible
    ) {
        return new BlockInteractionTarget(
                visible.block().x(),
                visible.block().y(),
                visible.block().z(),
                BlockFace.valueOf(
                        visible.face().toUpperCase(Locale.ROOT)
                ),
                new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                )
        );
    }

    private static SkillFailure failure(String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        READY,
        WAITING_FOR_SLEEP,
        SLEEPING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<SleepSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private FrameValidation {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(failure, "failure");
        }

        private static FrameValidation valid(SleepSkillFrame frame) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(SkillFailure failure) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }

        private static FrameValidation failed(
                SleepSkillFrame frame,
                SkillFailure failure
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(failure)
            );
        }
    }

    private record BedResolution(
            Optional<BlockInteractionTarget> target,
            Optional<BlockCoordinate> head,
            Optional<SkillFailure> failure
    ) {
        private BedResolution {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(failure, "failure");
        }

        private static BedResolution resolved(
                BlockInteractionTarget target,
                BlockCoordinate head
        ) {
            return new BedResolution(
                    Optional.of(target),
                    Optional.of(head),
                    Optional.empty()
            );
        }

        private static BedResolution failed(SkillFailure failure) {
            return new BedResolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }
}
