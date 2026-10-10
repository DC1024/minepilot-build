package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
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
import dev.mcai.companion.skills.interaction.BreakBlockParameters;
import dev.mcai.companion.skills.interaction.BreakBlockSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.interaction.UseItemParameters;
import dev.mcai.companion.skills.interaction.UseItemSkill;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Excavates one observed tillable surface and fills the resulting one-block
 * hole from an owned water bucket. Mining and placement are delegated to the
 * same ordinary interaction skills available to the model; completion needs
 * a later first-person water observation and the expected bucket transition.
 */
public final class PrepareWaterSourceSkill
        implements Skill<PrepareWaterSourceParameters> {
    public static final String NAME = "prepare_water_source";
    private static final double AIM_ALIGNMENT_DEGREES = 0.5;
    private static final double LOOK_EPSILON = 1.0E-12;
    private static final Set<String> EXCAVATABLE = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:farmland"
    );
    private static final List<String> SHOVEL_PREFERENCE = List.of(
            "minecraft:netherite_shovel",
            "minecraft:diamond_shovel",
            "minecraft:iron_shovel",
            "minecraft:stone_shovel",
            "minecraft:wooden_shovel",
            "minecraft:golden_shovel"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final FarmingSkillPolicy policy;
    private final InteractionSkillPolicy interactionPolicy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;
    private long placedAtGameTick = -1;
    private long placementObservationRevision = -1;
    private int waterBucketsBefore = -1;
    private int emptyBucketsBefore = -1;
    private BreakBlockSkill breakSkill;
    private BreakBlockParameters breakParameters;
    private UseItemSkill useSkill;
    private UseItemParameters useParameters;

    public PrepareWaterSourceSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator actuator,
            final InteractionSkillFrameSource frames,
            final FarmingSkillPolicy policy
    ) {
        this(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                actuator,
                frames,
                policy,
                InteractionSkillPolicy.defaults()
        );
    }

    PrepareWaterSourceSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator actuator,
            final InteractionSkillFrameSource frames,
            final FarmingSkillPolicy policy,
            final InteractionSkillPolicy interactionPolicy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.interactionPolicy = Objects.requireNonNull(
                interactionPolicy,
                "interactionPolicy"
        );
    }

    @Override
    public SkillParameterParser<PrepareWaterSourceParameters>
            parameters() {
        return FarmingSkillParameters::parsePrepareWaterSource;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.ground().face() != BlockFace.UP) {
            return Optional.of(failure("ground_face_not_up"));
        }
        if (parameters.dimension()
                .equals(dev.mcai.companion.waypoint.DimensionRef.NETHER)) {
            return Optional.of(failure("water_invalid_dimension"));
        }
        final FrameValidation validation = validateFrame(
                parameters,
                -1
        );
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final InteractionSkillFrame frame =
                validation.frame().orElseThrow();
        final Optional<VisibleBlockFace> ground = resolveInitialGround(
                frame,
                parameters
        );
        if (ground.isEmpty()) {
            return Optional.of(failure("ground_not_observed_tillable"));
        }
        if (itemCount(frame.inventory(), "minecraft:water_bucket") < 1) {
            return Optional.of(failure("water_bucket_unavailable"));
        }
        final CoreFrameValidation core = validateCoreFrame(parameters);
        if (core.failure().isPresent()) {
            return core.failure();
        }
        if (core.frame().orElseThrow().feet().below().equals(
                new dev.mcai.companion.navigation.GridPos(
                        parameters.ground().x(),
                        parameters.ground().y(),
                        parameters.ground().z()
                )
        )) {
            return Optional.of(failure("current_support_target"));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        final Optional<SkillFailure> invalid = preconditions(
                context,
                parameters
        );
        if (invalid.isPresent()) {
            throw new IllegalStateException(
                    "Water-source binding changed before start"
            );
        }
        final InteractionSkillFrame frame = frames.current()
                .orElseThrow();
        phase = Phase.AIMING_GROUND;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        placedAtGameTick = -1;
        placementObservationRevision = -1;
        waterBucketsBefore = itemCount(
                frame.inventory(),
                "minecraft:water_bucket"
        );
        emptyBucketsBefore = itemCount(
                frame.inventory(),
                "minecraft:bucket"
        );
        breakSkill = null;
        breakParameters = null;
        useSkill = null;
        useParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (phase == Phase.FAILED) {
            return SkillTickResult.failed(Objects.requireNonNull(failure));
        }
        if (phase == Phase.IDLE || phase == Phase.CANCELLED) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtGameTick
                >= policy.totalTimeoutTicks()) {
            return fail(context, parameters, "timed_out_"
                    + phase.name().toLowerCase(Locale.ROOT));
        }
        final FrameValidation frameValidation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (frameValidation.failure().isPresent()) {
            return fail(
                    context,
                    parameters,
                    frameValidation.failure().orElseThrow()
            );
        }
        final CoreFrameValidation coreValidation =
                validateCoreFrame(parameters);
        if (coreValidation.failure().isPresent()) {
            return fail(
                    context,
                    parameters,
                    coreValidation.failure().orElseThrow()
            );
        }
        final InteractionSkillFrame frame =
                frameValidation.frame().orElseThrow();
        final CoreSkillFrame core = coreValidation.frame().orElseThrow();
        return switch (phase) {
            case AIMING_GROUND -> aimAndStartBreak(
                    context,
                    parameters,
                    core,
                    frame
            );
            case BREAKING -> tickBreak(context, parameters);
            case REVEALING_BOTTOM -> revealBottom(
                    context,
                    parameters,
                    core,
                    frame
            );
            case PLACING -> tickPlacement(context, parameters, frame);
            case VERIFYING -> verifyWater(
                    context,
                    parameters,
                    core,
                    frame
            );
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.ground().x(),
                        parameters.ground().y(),
                        parameters.ground().z(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        cancelChildren(context);
        coreActuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(failure("invalid_state"));
        };
    }

    private SkillTickResult aimAndStartBreak(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> visible = currentGround(
                frame,
                parameters
        );
        if (visible.isEmpty()) {
            return fail(
                    context,
                    parameters,
                    "ground_not_currently_visible"
            );
        }
        final VisibleBlockFace ground = visible.orElseThrow();
        final AimResult aim = aimAt(core, ground.hitPosition());
        if (!aim.accepted()) {
            return fail(context, parameters, "look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final Optional<String> shovel = selectedShovel(frame);
        if (shovel.isPresent()
                && !shovel.orElseThrow().equals(
                        frame.mainHand().itemId()
                )) {
            final ActionOutcome equipped = actuator.equipMainHand(
                    shovel.orElseThrow()
            );
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(
                        context,
                        parameters,
                        actionFailure("equip_shovel", equipped)
                );
            }
        }
        breakParameters = new BreakBlockParameters(
                parameters.dimension(),
                observedTarget(frame, ground)
        );
        breakSkill = new BreakBlockSkill(
                expectedPlayerId,
                actuator,
                frames,
                interactionPolicy
        );
        final Optional<SkillFailure> rejected = breakSkill.preconditions(
                context,
                breakParameters
        );
        if (rejected.isPresent()) {
            return fail(context, parameters, rejected.orElseThrow());
        }
        breakSkill.start(context, breakParameters);
        phase = Phase.BREAKING;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult tickBreak(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters
    ) {
        final SkillTickResult result = Objects.requireNonNull(
                breakSkill
        ).tick(context, Objects.requireNonNull(breakParameters));
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    parameters,
                    result.failure().orElseThrow()
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            breakSkill = null;
            breakParameters = null;
            phase = Phase.REVEALING_BOTTOM;
        }
        return SkillTickResult.running(result.madeProgress(), false);
    }

    private SkillTickResult revealBottom(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame
    ) {
        final Optional<VisibleBlockFace> bottom = visibleBottom(
                frame,
                parameters
        );
        final AimResult aim = aimAt(
                core,
                bottom.map(VisibleBlockFace::hitPosition)
                        .orElseGet(() -> expectedBottomSurface(parameters))
        );
        if (!aim.accepted()) {
            return fail(context, parameters, "look_rejected");
        }
        if (bottom.isEmpty()
                || aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        if (!"minecraft:water_bucket".equals(
                frame.mainHand().itemId()
        )) {
            final ActionOutcome equipped = actuator.equipMainHand(
                    "minecraft:water_bucket"
            );
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(
                        context,
                        parameters,
                        actionFailure("equip_water_bucket", equipped)
                );
            }
            /*
             * Inventory/menu swaps are synchronous, but the child skill must
             * bind to a fresh semantic frame that actually reports the water
             * bucket in hand. This prevents a stale non-empty hand from
             * satisfying UseItemSkill's generic held-item precondition.
             */
            return SkillTickResult.running(true, false);
        }
        useParameters = new UseItemParameters(
                parameters.dimension(),
                dev.mcai.companion.action.ActionHand.MAIN_HAND,
                0
        );
        useSkill = new UseItemSkill(
                expectedPlayerId,
                actuator,
                frames,
                interactionPolicy
        );
        final Optional<SkillFailure> rejected = useSkill.preconditions(
                context,
                useParameters
        );
        if (rejected.isPresent()) {
            return fail(context, parameters, rejected.orElseThrow());
        }
        useSkill.start(context, useParameters);
        phase = Phase.PLACING;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult tickPlacement(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final InteractionSkillFrame frame
    ) {
        final SkillTickResult result = Objects.requireNonNull(useSkill)
                .tick(context, Objects.requireNonNull(useParameters));
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    parameters,
                    result.failure().orElseThrow()
            );
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            useSkill = null;
            useParameters = null;
            placementObservationRevision = frame.observationRevision();
            placedAtGameTick = context.gameTick();
            phase = Phase.VERIFYING;
        }
        return SkillTickResult.running(result.madeProgress(), false);
    }

    private SkillTickResult verifyWater(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame
    ) {
        if (frame.observationRevision()
                > placementObservationRevision) {
            final boolean waterVisible = frame.visibleBlockFaces().stream()
                    .anyMatch(face -> sameGround(
                            face,
                            parameters.ground()
                    ) && "minecraft:water".equals(face.blockTypeId()));
            final boolean bucketChanged = itemCount(
                    frame.inventory(),
                    "minecraft:water_bucket"
            ) == waterBucketsBefore - 1
                    && itemCount(frame.inventory(), "minecraft:bucket")
                            == emptyBucketsBefore + 1;
            if (waterVisible && bucketChanged) {
                phase = Phase.COMPLETED;
                coreActuator.stop();
                return SkillTickResult.completed();
            }
        }
        final AimResult reveal = aimAt(
                core,
                expectedWaterSurface(parameters)
        );
        if (!reveal.accepted()) {
            return fail(context, parameters, "look_rejected");
        }
        if (context.gameTick() - placedAtGameTick
                >= policy.replantConfirmationTicks()) {
            return fail(context, parameters, "water_not_confirmed");
        }
        return SkillTickResult.running(true, false);
    }

    private Optional<VisibleBlockFace> resolveInitialGround(
            final InteractionSkillFrame current,
            final PrepareWaterSourceParameters parameters
    ) {
        final Optional<InteractionSkillFrame> retained =
                frames.atObservation(
                        parameters.ground().sampleSequence()
                );
        if (retained.isEmpty()) {
            return Optional.empty();
        }
        final InteractionSkillFrame authored = retained.orElseThrow();
        if (!expectedPlayerId.equals(authored.playerId())
                || !parameters.dimension().equals(authored.dimension())
                || authored.sessionGeneration()
                        != current.sessionGeneration()) {
            return Optional.empty();
        }
        final Optional<VisibleBlockFace> original = authored
                .visibleBlockFaces().stream()
                .filter(face -> sameGround(face, parameters.ground()))
                .filter(PrepareWaterSourceSkill::isUpFace)
                .filter(face -> EXCAVATABLE.contains(
                        face.blockTypeId()
                ))
                .findFirst();
        if (original.isEmpty()) {
            return Optional.empty();
        }
        return currentGround(current, parameters).filter(face ->
                face.blockTypeId().equals(
                        original.orElseThrow().blockTypeId()
                )
        );
    }

    private Optional<VisibleBlockFace> currentGround(
            final InteractionSkillFrame frame,
            final PrepareWaterSourceParameters parameters
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> sameGround(face, parameters.ground()))
                .filter(PrepareWaterSourceSkill::isUpFace)
                .filter(face -> EXCAVATABLE.contains(
                        face.blockTypeId()
                ))
                .filter(face -> face.distance()
                        <= policy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private Optional<VisibleBlockFace> visibleBottom(
            final InteractionSkillFrame frame,
            final PrepareWaterSourceParameters parameters
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.block().x()
                                == parameters.ground().x()
                        && face.block().y()
                                == parameters.ground().y() - 1
                        && face.block().z()
                                == parameters.ground().z())
                .filter(PrepareWaterSourceSkill::isUpFace)
                .filter(face -> face.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP)
                .filter(face -> face.distance()
                        <= policy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private FrameValidation validateFrame(
            final PrepareWaterSourceParameters parameters,
            final long expectedSessionGeneration
    ) {
        final Optional<InteractionSkillFrame> available = frames.current();
        if (available.isEmpty()) {
            return FrameValidation.failed(failure(
                    "observation_unavailable"
            ));
        }
        final InteractionSkillFrame frame = available.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(failure("player_mismatch"));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(failure("dimension_mismatch"));
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(failure("stale_observation"));
        }
        final OptionalLong session = actuator.sessionGeneration();
        if (session.isEmpty()) {
            return FrameValidation.failed(failure("player_unavailable"));
        }
        final long actual = session.orElseThrow();
        if (frame.sessionGeneration() != actual
                || expectedSessionGeneration >= 0
                && expectedSessionGeneration != actual) {
            return FrameValidation.failed(failure("session_mismatch"));
        }
        return FrameValidation.valid(frame);
    }

    private CoreFrameValidation validateCoreFrame(
            final PrepareWaterSourceParameters parameters
    ) {
        final Optional<CoreSkillFrame> available = coreFrames.current();
        if (available.isEmpty()) {
            return CoreFrameValidation.failed(failure(
                    "pose_unavailable"
            ));
        }
        final CoreSkillFrame frame = available.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return CoreFrameValidation.failed(failure(
                    "pose_player_mismatch"
            ));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return CoreFrameValidation.failed(failure(
                    "pose_dimension_mismatch"
            ));
        }
        return CoreFrameValidation.valid(frame);
    }

    private AimResult aimAt(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final ActionOutcome stopped = coreActuator.stop();
        if (!stopped.accepted()) {
            return new AimResult(false, Double.POSITIVE_INFINITY);
        }
        final PerceptionVec3 delta = target.subtract(frame.eyePosition());
        if (delta.lengthSquared() <= LOOK_EPSILON) {
            return new AimResult(true, 0.0);
        }
        final ActionOutcome looked = coreActuator.look(
                lookAt(frame.eyePosition(), target)
        );
        final double dot = frame.lookDirection().normalized()
                .dot(delta.normalized());
        final double error = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
        return new AimResult(looked.accepted(), error);
    }

    private static Optional<String> selectedShovel(
            final InteractionSkillFrame frame
    ) {
        if (SHOVEL_PREFERENCE.contains(frame.mainHand().itemId())) {
            return Optional.of(frame.mainHand().itemId());
        }
        return SHOVEL_PREFERENCE.stream().filter(itemId ->
                itemCount(frame.inventory(), itemId) > 0
        ).findFirst();
    }

    private static int itemCount(
            final List<InventoryItemSummary> inventory,
            final String itemId
    ) {
        return inventory.stream()
                .filter(item -> itemId.equals(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static ObservedBlockTarget observedTarget(
            final InteractionSkillFrame frame,
            final VisibleBlockFace face
    ) {
        return new ObservedBlockTarget(
                frame.observationRevision(),
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(face.face().toUpperCase(Locale.ROOT))
        );
    }

    private static boolean sameGround(
            final VisibleBlockFace face,
            final ObservedBlockTarget ground
    ) {
        return face.block().x() == ground.x()
                && face.block().y() == ground.y()
                && face.block().z() == ground.z();
    }

    private static boolean isUpFace(final VisibleBlockFace face) {
        return "up".equals(face.face());
    }

    private static PerceptionVec3 expectedBottomSurface(
            final PrepareWaterSourceParameters parameters
    ) {
        return new PerceptionVec3(
                parameters.ground().x() + 0.5,
                parameters.ground().y() - 0.125,
                parameters.ground().z() + 0.5
        );
    }

    private static PerceptionVec3 expectedWaterSurface(
            final PrepareWaterSourceParameters parameters
    ) {
        return new PerceptionVec3(
                parameters.ground().x() + 0.5,
                parameters.ground().y() + 0.8,
                parameters.ground().z() + 0.5
        );
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        return new LookIntent(
                (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())),
                (float) Math.toDegrees(Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private SkillTickResult fail(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final String suffix
    ) {
        return fail(context, parameters, failure(suffix));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final PrepareWaterSourceParameters parameters,
            final SkillFailure reason
    ) {
        cancelChildren(context);
        coreActuator.stop();
        failure = nestedFailure(reason);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    /**
     * Preserve the actionable child failure without ever exceeding
     * {@link SkillFailure#MAX_CODE_CHARACTERS}.  Flattening the complete
     * child name used to fit at this layer but overflow once the whole-field
     * executor added its own prefix, turning useful diagnostics into the
     * generic {@code skill_failure} fallback.
     */
    private static SkillFailure nestedFailure(final SkillFailure reason) {
        final String code = reason.code();
        if (code.startsWith(NAME + ".")) {
            return reason;
        }
        final String breakPrefix = "break_block.";
        if (code.startsWith(breakPrefix)) {
            return failure("break_" + code.substring(
                    breakPrefix.length()
            ));
        }
        final String usePrefix = "use_item.";
        if (code.startsWith(usePrefix)) {
            return failure("place_" + code.substring(
                    usePrefix.length()
            ));
        }
        return failure("child_failed");
    }

    private void cancelChildren(final SkillContext context) {
        if (breakSkill != null && breakParameters != null) {
            breakSkill.cancel(context, breakParameters);
            breakSkill = null;
            breakParameters = null;
        }
        if (useSkill != null && useParameters != null) {
            useSkill.cancel(context, useParameters);
            useSkill = null;
            useParameters = null;
        }
    }

    private static SkillFailure actionFailure(
            final String operation,
            final ActionOutcome outcome
    ) {
        return failure(operation + "_"
                + outcome.name().toLowerCase(Locale.ROOT));
    }

    private static SkillFailure failure(final String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        AIMING_GROUND,
        BREAKING,
        REVEALING_BOTTOM,
        PLACING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<InteractionSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(
                final InteractionSkillFrame frame
        ) {
            return new FrameValidation(Optional.of(frame), Optional.empty());
        }

        private static FrameValidation failed(
                final SkillFailure failure
        ) {
            return new FrameValidation(Optional.empty(), Optional.of(failure));
        }
    }

    private record CoreFrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static CoreFrameValidation valid(
                final CoreSkillFrame frame
        ) {
            return new CoreFrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static CoreFrameValidation failed(
                final SkillFailure failure
        ) {
            return new CoreFrameValidation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record AimResult(boolean accepted, double errorDegrees) {
    }
}
