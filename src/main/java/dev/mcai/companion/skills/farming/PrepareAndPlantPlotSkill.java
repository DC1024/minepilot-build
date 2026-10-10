package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
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
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Tills one fairly observed bare plot and plants one selected vanilla crop.
 * Every mutation is an ordinary player use action and completion requires a
 * later first-person crop observation or the body's own seed consumption.
 */
public final class PrepareAndPlantPlotSkill
        implements Skill<PrepareAndPlantPlotParameters> {
    public static final String NAME = "prepare_and_plant_plot";
    private static final double AIM_ALIGNMENT_DEGREES = 0.5;
    private static final double LOOK_EPSILON = 1.0E-12;
    private static final Set<String> TILLABLE = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:farmland"
    );
    private static final List<String> HOE_PREFERENCE = List.of(
            "minecraft:netherite_hoe",
            "minecraft:diamond_hoe",
            "minecraft:iron_hoe",
            "minecraft:stone_hoe",
            "minecraft:wooden_hoe",
            "minecraft:golden_hoe"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final FarmingSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;
    private long tillObservationRevision = -1;
    private long tilledAtGameTick = -1;
    private long plantObservationRevision = -1;
    private long plantedAtGameTick = -1;
    private long retryAfterObservationRevision = -1;
    private int seedsBeforePlant = -1;

    public PrepareAndPlantPlotSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator actuator,
            final InteractionSkillFrameSource frames,
            final FarmingSkillPolicy policy
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
    }

    @Override
    public SkillParameterParser<PrepareAndPlantPlotParameters>
            parameters() {
        return FarmingSkillParameters::parsePrepareAndPlant;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final FrameValidation validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final InteractionSkillFrame frame =
                validation.frame().orElseThrow();
        final GroundResolution ground = resolveInitialGround(
                frame,
                parameters
        );
        if (ground.failure().isPresent()) {
            return ground.failure();
        }
        if (seedCount(frame, parameters.crop()) < 1) {
            return Optional.of(failure("seed_unavailable"));
        }
        if (!isFarmland(ground.face().orElseThrow())
                && selectedHoe(frame).isEmpty()) {
            return Optional.of(failure("hoe_unavailable"));
        }
        return validateCoreFrame(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters
    ) {
        final Optional<SkillFailure> invalid =
                preconditions(context, parameters);
        if (invalid.isPresent()) {
            throw new IllegalStateException(
                    "Plot binding changed after precondition validation"
            );
        }
        final InteractionSkillFrame frame = frames.current()
                .orElseThrow();
        phase = Phase.PREPARING_GROUND;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        tillObservationRevision = -1;
        tilledAtGameTick = -1;
        plantObservationRevision = -1;
        plantedAtGameTick = -1;
        retryAfterObservationRevision = -1;
        seedsBeforePlant = -1;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters
    ) {
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (phase == Phase.IDLE
                || phase == Phase.FAILED
                || phase == Phase.CANCELLED) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtGameTick
                >= policy.totalTimeoutTicks()) {
            return fail("timed_out_" + phase.name().toLowerCase(
                    Locale.ROOT
            ));
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final CoreFrameValidation coreValidation =
                validateCoreFrame(parameters);
        if (coreValidation.failure().isPresent()) {
            return fail(coreValidation.failure().orElseThrow());
        }
        final InteractionSkillFrame frame =
                validation.frame().orElseThrow();
        final CoreSkillFrame core = coreValidation.frame().orElseThrow();
        return switch (phase) {
            case PREPARING_GROUND -> prepareGround(
                    context,
                    core,
                    frame,
                    parameters
            );
            case WAITING_FOR_FARMLAND -> confirmFarmland(
                    context,
                    core,
                    frame,
                    parameters
            );
            case WAITING_FOR_CROP -> confirmCrop(
                    context,
                    core,
                    frame,
                    parameters
            );
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"crop\":\"%s\",\"x\":%d,"
                                + "\"y\":%d,\"z\":%d,\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.crop().blockId(),
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
            final PrepareAndPlantPlotParameters parameters
    ) {
        coreActuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters
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

    private SkillTickResult prepareGround(
            final SkillContext context,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final PrepareAndPlantPlotParameters parameters
    ) {
        final Optional<VisibleBlockFace> visible = currentGround(
                frame,
                parameters
        );
        if (visible.isEmpty()) {
            return fail("ground_not_currently_visible");
        }
        final VisibleBlockFace ground = visible.orElseThrow();
        if (isFarmland(ground)) {
            return plant(core, frame, context, parameters, ground);
        }
        final Optional<String> hoe = selectedHoe(frame);
        if (hoe.isEmpty()) {
            return fail("hoe_unavailable");
        }
        final AimResult aim = aimAt(core, ground.hitPosition());
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        if (frame.observationRevision()
                <= retryAfterObservationRevision) {
            return SkillTickResult.running(false, false);
        }
        if (!hoe.orElseThrow().equals(frame.mainHand().itemId())) {
            final ActionOutcome equipped = actuator.equipMainHand(
                    hoe.orElseThrow()
            );
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(actionFailure("equip_hoe", equipped));
            }
        }
        final ActionOutcome tilled = actuator.useOnBlock(
                ActionHand.MAIN_HAND,
                interactionTarget(ground)
        );
        if (!tilled.accepted()) {
            if (tilled == ActionOutcome.TARGET_OCCLUDED) {
                retryAfterObservationRevision =
                        frame.observationRevision();
                return SkillTickResult.running(false, true);
            }
            return fail(actionFailure("till", tilled));
        }
        phase = Phase.WAITING_FOR_FARMLAND;
        tillObservationRevision = frame.observationRevision();
        tilledAtGameTick = context.gameTick();
        retryAfterObservationRevision = -1;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult confirmFarmland(
            final SkillContext context,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final PrepareAndPlantPlotParameters parameters
    ) {
        if (frame.observationRevision() <= tillObservationRevision) {
            return waitForConfirmation(
                    context,
                    tilledAtGameTick,
                    "farmland_not_confirmed"
            );
        }
        final Optional<VisibleBlockFace> farmland = frame
                .visibleBlockFaces()
                .stream()
                .filter(face -> sameGround(face, parameters.ground()))
                .filter(PrepareAndPlantPlotSkill::isUpFace)
                .filter(PrepareAndPlantPlotSkill::isFarmland)
                .filter(face -> face.distance()
                        <= policy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        if (farmland.isEmpty()) {
            final AimResult reveal = aimAt(
                    core,
                    expectedGroundSurface(parameters.ground())
            );
            if (!reveal.accepted()) {
                return fail("look_rejected");
            }
            return waitForConfirmation(
                    context,
                    tilledAtGameTick,
                    "farmland_not_confirmed"
            );
        }
        return plant(
                core,
                frame,
                context,
                parameters,
                farmland.orElseThrow()
        );
    }

    private SkillTickResult plant(
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final SkillContext context,
            final PrepareAndPlantPlotParameters parameters,
            final VisibleBlockFace farmland
    ) {
        final int currentSeeds = seedCount(frame, parameters.crop());
        if (currentSeeds < 1) {
            return fail("seed_unavailable");
        }
        final AimResult aim = aimAt(core, farmland.hitPosition());
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        if (frame.observationRevision()
                <= retryAfterObservationRevision) {
            return SkillTickResult.running(false, false);
        }

        final ActionHand hand;
        if (parameters.crop().seedItemId().equals(
                frame.offHand().itemId()
        )) {
            hand = ActionHand.OFF_HAND;
        } else if (parameters.crop().seedItemId().equals(
                frame.mainHand().itemId()
        )) {
            hand = ActionHand.MAIN_HAND;
        } else {
            final ActionOutcome equipped = actuator.equipMainHand(
                    parameters.crop().seedItemId()
            );
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(actionFailure("equip_seed", equipped));
            }
            hand = ActionHand.MAIN_HAND;
        }
        final ActionOutcome planted = actuator.useOnBlock(
                hand,
                interactionTarget(farmland)
        );
        if (!planted.accepted()) {
            if (planted == ActionOutcome.TARGET_OCCLUDED) {
                retryAfterObservationRevision =
                        frame.observationRevision();
                return SkillTickResult.running(false, true);
            }
            return fail(actionFailure("plant", planted));
        }
        phase = Phase.WAITING_FOR_CROP;
        plantObservationRevision = frame.observationRevision();
        plantedAtGameTick = context.gameTick();
        seedsBeforePlant = currentSeeds;
        retryAfterObservationRevision = -1;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult confirmCrop(
            final SkillContext context,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final PrepareAndPlantPlotParameters parameters
    ) {
        if (frame.observationRevision() > plantObservationRevision) {
            final boolean cropVisible = frame.visibleBlockFaces()
                    .stream()
                    .filter(face -> isCropCell(
                            face,
                            parameters.ground()
                    ))
                    .anyMatch(parameters.crop()::isNewPlant);
            final boolean seedConsumed =
                    seedCount(frame, parameters.crop()) < seedsBeforePlant;
            if (cropVisible || seedConsumed) {
                phase = Phase.COMPLETED;
                coreActuator.stop();
                return SkillTickResult.completed();
            }
        }
        final AimResult reveal = aimAt(
                core,
                expectedCropCentre(parameters.ground())
        );
        if (!reveal.accepted()) {
            return fail("look_rejected");
        }
        return waitForConfirmation(
                context,
                plantedAtGameTick,
                "crop_not_confirmed"
        );
    }

    private SkillTickResult waitForConfirmation(
            final SkillContext context,
            final long dispatchedAtGameTick,
            final String failureCode
    ) {
        if (context.gameTick() - dispatchedAtGameTick
                >= policy.replantConfirmationTicks()) {
            return fail(failureCode);
        }
        return SkillTickResult.running(false, false);
    }

    private GroundResolution resolveInitialGround(
            final InteractionSkillFrame current,
            final PrepareAndPlantPlotParameters parameters
    ) {
        final Optional<InteractionSkillFrame> authored =
                frames.atObservation(
                        parameters.ground().sampleSequence()
                );
        if (authored.isEmpty()) {
            return GroundResolution.failed(failure(
                    "stale_observation_id"
            ));
        }
        final InteractionSkillFrame retained = authored.orElseThrow();
        if (!expectedPlayerId.equals(retained.playerId())
                || !parameters.dimension().equals(retained.dimension())
                || retained.sessionGeneration()
                        != current.sessionGeneration()) {
            return GroundResolution.failed(failure(
                    "observation_binding_changed"
            ));
        }
        final boolean wasObserved = retained.visibleBlockFaces().stream()
                .anyMatch(face ->
                        sameGround(face, parameters.ground())
                                && isUpFace(face)
                                && TILLABLE.contains(face.blockTypeId())
                );
        if (!wasObserved) {
            return GroundResolution.failed(failure(
                    "ground_not_observed_tillable"
            ));
        }
        final Optional<VisibleBlockFace> ground = currentGround(
                current,
                parameters
        );
        return ground.isPresent()
                ? GroundResolution.resolved(ground.orElseThrow())
                : GroundResolution.failed(failure(
                        "ground_not_currently_visible"
                ));
    }

    private Optional<VisibleBlockFace> currentGround(
            final InteractionSkillFrame frame,
            final PrepareAndPlantPlotParameters parameters
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> sameGround(
                        face,
                        parameters.ground()
                ))
                .filter(PrepareAndPlantPlotSkill::isUpFace)
                .filter(face -> TILLABLE.contains(face.blockTypeId()))
                .filter(face -> face.distance()
                        <= policy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private FrameValidation validateFrame(
            final PrepareAndPlantPlotParameters parameters,
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
            return FrameValidation.failed(frame, failure(
                    "player_mismatch"
            ));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(frame, failure(
                    "dimension_mismatch"
            ));
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(frame, failure(
                    "stale_observation"
            ));
        }
        final OptionalLong session = actuator.sessionGeneration();
        if (session.isEmpty()) {
            return FrameValidation.failed(frame, failure(
                    "player_unavailable"
            ));
        }
        final long actualSession = session.orElseThrow();
        if (frame.sessionGeneration() != actualSession
                || expectedSessionGeneration >= 0
                && expectedSessionGeneration != actualSession) {
            return FrameValidation.failed(frame, failure(
                    "session_mismatch"
            ));
        }
        return FrameValidation.valid(frame);
    }

    private CoreFrameValidation validateCoreFrame(
            final PrepareAndPlantPlotParameters parameters
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

    private static Optional<String> selectedHoe(
            final InteractionSkillFrame frame
    ) {
        if (HOE_PREFERENCE.contains(frame.mainHand().itemId())) {
            return Optional.of(frame.mainHand().itemId());
        }
        if (HOE_PREFERENCE.contains(frame.offHand().itemId())) {
            return Optional.of(frame.offHand().itemId());
        }
        return HOE_PREFERENCE.stream().filter(itemId ->
                itemCount(frame.inventory(), itemId) > 0
        ).findFirst();
    }

    private static int seedCount(
            final InteractionSkillFrame frame,
            final CropKind crop
    ) {
        return itemCount(frame.inventory(), crop.seedItemId());
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

    private static boolean sameGround(
            final VisibleBlockFace face,
            final ObservedBlockTarget target
    ) {
        return face.block().x() == target.x()
                && face.block().y() == target.y()
                && face.block().z() == target.z();
    }

    private static boolean isCropCell(
            final VisibleBlockFace face,
            final ObservedBlockTarget ground
    ) {
        return face.block().x() == ground.x()
                && face.block().y() == ground.y() + 1
                && face.block().z() == ground.z();
    }

    private static boolean isUpFace(final VisibleBlockFace face) {
        return "up".equals(face.face());
    }

    private static boolean isFarmland(final VisibleBlockFace face) {
        return "minecraft:farmland".equals(face.blockTypeId());
    }

    private static BlockInteractionTarget interactionTarget(
            final VisibleBlockFace face
    ) {
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.UP,
                new ActionVec3(
                        face.hitPosition().x(),
                        face.hitPosition().y(),
                        face.hitPosition().z()
                )
        );
    }

    private static PerceptionVec3 expectedGroundSurface(
            final ObservedBlockTarget ground
    ) {
        return new PerceptionVec3(
                ground.x() + 0.5,
                ground.y() + 0.875,
                ground.z() + 0.5
        );
    }

    private static PerceptionVec3 expectedCropCentre(
            final ObservedBlockTarget ground
    ) {
        return new PerceptionVec3(
                ground.x() + 0.5,
                ground.y() + 1.3,
                ground.z() + 0.5
        );
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
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

    private SkillTickResult fail(final String suffix) {
        return fail(failure(suffix));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        coreActuator.stop();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
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
        PREPARING_GROUND,
        WAITING_FOR_FARMLAND,
        WAITING_FOR_CROP,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record GroundResolution(
            Optional<VisibleBlockFace> face,
            Optional<SkillFailure> failure
    ) {
        private static GroundResolution resolved(
                final VisibleBlockFace face
        ) {
            return new GroundResolution(
                    Optional.of(face),
                    Optional.empty()
            );
        }

        private static GroundResolution failed(
                final SkillFailure failure
        ) {
            return new GroundResolution(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record FrameValidation(
            Optional<InteractionSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(
                final InteractionSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(
                final SkillFailure failure
        ) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }

        private static FrameValidation failed(
                final InteractionSkillFrame frame,
                final SkillFailure failure
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(failure)
            );
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
