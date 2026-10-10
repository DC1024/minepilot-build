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
 * Plants one sugar cane on a support block that was actually observed by the
 * companion.  The water requirement is checked from the same first-person
 * semantic frame; no world lookup or structure template is used.
 */
public final class PlantObservedSugarcaneSkill
        implements Skill<PlantObservedSugarcaneParameters> {
    public static final String NAME = "plant_observed_sugarcane";
    private static final double AIM_ALIGNMENT_DEGREES = 0.5;
    private static final double LOOK_EPSILON = 1.0E-12;
    private static final Set<String> SUPPORTS = Set.of(
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:dirt",
            "minecraft:grass_block"
    );
    private static final String SUGAR_CANE = "minecraft:sugar_cane";
    private static final String WATER = "minecraft:water";

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
    private long plantedAtGameTick = -1;
    private long plantObservationRevision = -1;
    private int sugarCaneBefore = -1;
    private String boundSupportBlockType;

    public PlantObservedSugarcaneSkill(
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
        this.coreFrames = Objects.requireNonNull(coreFrames, "coreFrames");
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<PlantObservedSugarcaneParameters>
            parameters() {
        return FarmingSkillParameters::parsePlantObservedSugarcane;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.support().face() != BlockFace.UP) {
            return Optional.of(failure("support_face_not_up"));
        }
        final FrameValidation validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final InteractionSkillFrame frame = validation.frame().orElseThrow();
        final Optional<VisibleBlockFace> support = resolveSupport(
                frame,
                parameters,
                null
        );
        if (support.isEmpty()) {
            return Optional.of(failure("support_not_observed"));
        }
        if (!hasAdjacentWater(frame, parameters.support())) {
            return Optional.of(failure("adjacent_water_not_observed"));
        }
        if (itemCount(frame.inventory(), SUGAR_CANE) < 1) {
            return Optional.of(failure("sugar_cane_unavailable"));
        }
        return validateCoreFrame(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
    ) {
        final Optional<SkillFailure> invalid = preconditions(
                context,
                parameters
        );
        if (invalid.isPresent()) {
            throw new IllegalStateException(
                    "Sugar-cane binding changed before start"
            );
        }
        final InteractionSkillFrame frame = frames.current().orElseThrow();
        boundSupportBlockType = resolveSupport(
                frame,
                parameters,
                null
        ).orElseThrow().blockTypeId();
        phase = Phase.AIMING;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        plantedAtGameTick = -1;
        plantObservationRevision = -1;
        sugarCaneBefore = -1;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
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
        final CoreFrameValidation coreValidation = validateCoreFrame(
                parameters
        );
        if (coreValidation.failure().isPresent()) {
            return fail(coreValidation.failure().orElseThrow());
        }
        final InteractionSkillFrame frame = validation.frame().orElseThrow();
        final CoreSkillFrame core = coreValidation.frame().orElseThrow();
        return phase == Phase.AIMING
                ? aimAndPlant(context, core, frame, parameters)
                : verifyPlant(context, core, frame, parameters);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"failure\":\"%s\","
                                + "\"dimension\":\"%s\","
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"session\":%d}",
                        phase.name(),
                        failure == null ? "" : failure.code(),
                        parameters.dimension().id(),
                        parameters.support().x(),
                        parameters.support().y(),
                        parameters.support().z(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
    ) {
        coreActuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final PlantObservedSugarcaneParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(failure("invalid_state"));
        };
    }

    private SkillTickResult aimAndPlant(
            final SkillContext context,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final PlantObservedSugarcaneParameters parameters
    ) {
        final Optional<VisibleBlockFace> support = resolveSupport(
                frame,
                parameters,
                boundSupportBlockType
        );
        if (support.isEmpty()) {
            return fail("support_not_currently_visible");
        }
        if (!hasAdjacentWater(frame, parameters.support())) {
            return fail("adjacent_water_not_currently_visible");
        }
        final VisibleBlockFace face = support.orElseThrow();
        final PerceptionVec3 target = safeTopHitPoint(
                core,
                parameters.support()
        );
        final AimResult aim = aimAt(core, target);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        if (!SUGAR_CANE.equals(frame.mainHand().itemId())) {
            final ActionOutcome equipped = actuator.equipMainHand(SUGAR_CANE);
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(actionFailure("equip_sugar_cane", equipped));
            }
            return SkillTickResult.running(true, false);
        }
        final int current = itemCount(frame.inventory(), SUGAR_CANE);
        if (current < 1) {
            return fail("sugar_cane_unavailable");
        }
        final ActionOutcome planted = actuator.useOnBlock(
                ActionHand.MAIN_HAND,
                interactionTarget(face, target)
        );
        if (!planted.accepted()) {
            return fail(actionFailure("plant", planted));
        }
        phase = Phase.WAITING_FOR_PLANT;
        plantObservationRevision = frame.observationRevision();
        plantedAtGameTick = context.gameTick();
        sugarCaneBefore = current;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyPlant(
            final SkillContext context,
            final CoreSkillFrame core,
            final InteractionSkillFrame frame,
            final PlantObservedSugarcaneParameters parameters
    ) {
        final Optional<VisibleBlockFace> support = resolveSupport(
                frame,
                parameters,
                boundSupportBlockType
        );
        if (support.isEmpty() || !hasAdjacentWater(
                frame,
                parameters.support()
        )) {
            return fail("plant_support_lost");
        }
        if (frame.observationRevision() > plantObservationRevision) {
            final boolean visible = frame.visibleBlockFaces().stream()
                    .anyMatch(face -> isPlantAt(face, parameters.support()));
            final boolean consumed = itemCount(
                    frame.inventory(),
                    SUGAR_CANE
            ) < sugarCaneBefore;
            if (visible || consumed) {
                phase = Phase.COMPLETED;
                coreActuator.stop();
                return SkillTickResult.completed();
            }
        }
        final AimResult reveal = aimAt(
                core,
                expectedPlantCentre(parameters.support())
        );
        if (!reveal.accepted()) {
            return fail("look_rejected");
        }
        if (context.gameTick() - plantedAtGameTick
                >= policy.replantConfirmationTicks()) {
            return fail("plant_not_confirmed");
        }
        return SkillTickResult.running(false, false);
    }

    private Optional<VisibleBlockFace> resolveSupport(
            final InteractionSkillFrame current,
            final PlantObservedSugarcaneParameters parameters,
            final String expectedType
    ) {
        final Optional<InteractionSkillFrame> retained = frames.atObservation(
                parameters.support().sampleSequence()
        );
        if (retained.isEmpty()) {
            return Optional.empty();
        }
        final InteractionSkillFrame authored = retained.orElseThrow();
        if (!expectedPlayerId.equals(authored.playerId())
                || !parameters.dimension().equals(authored.dimension())
                || authored.sessionGeneration() != current.sessionGeneration()) {
            return Optional.empty();
        }
        final Optional<VisibleBlockFace> original = authored
                .visibleBlockFaces()
                .stream()
                .filter(face -> sameSupport(face, parameters.support()))
                .filter(PlantObservedSugarcaneSkill::isUpFace)
                .filter(face -> SUPPORTS.contains(face.blockTypeId()))
                .findFirst();
        if (original.isEmpty()) {
            return Optional.empty();
        }
        final String required = expectedType == null
                ? original.orElseThrow().blockTypeId()
                : expectedType;
        final Optional<VisibleBlockFace> currentSupport = current
                .visibleBlockFaces().stream()
                .filter(face -> sameSupport(face, parameters.support()))
                .filter(PlantObservedSugarcaneSkill::isUpFace)
                .filter(face -> required.equals(face.blockTypeId()))
                .filter(face -> face.distance()
                        <= policy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(VisibleBlockFace::distance));
        if (currentSupport.isPresent()) {
            return currentSupport;
        }
        /*
         * Semantic rays are deliberately sparse and are allowed to land on a
         * neighbouring face between two 20-TPS frames even when the body has
         * not moved. Keep the exact, bounded first-person face that authored
         * the target as a short reacquisition hint. The normal interaction
         * actuator still performs the server-side target/range/occlusion
         * checks; no block state is read or written here.
         */
        return original.filter(face -> required.equals(face.blockTypeId()));
    }

    private boolean hasAdjacentWater(
            final InteractionSkillFrame frame,
            final ObservedBlockTarget support
    ) {
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                WATER.equals(face.blockTypeId())
                        && face.block().y() == support.y()
                        && Math.abs(face.block().x() - support.x())
                                + Math.abs(face.block().z() - support.z()) == 1
                        && face.distance()
                                <= policy.maximumCandidateDistance()
        );
    }

    private FrameValidation validateFrame(
            final PlantObservedSugarcaneParameters parameters,
            final long expectedSessionGeneration
    ) {
        final Optional<InteractionSkillFrame> available = frames.current();
        if (available.isEmpty()) {
            return FrameValidation.failed(failure("observation_unavailable"));
        }
        final InteractionSkillFrame frame = available.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(failure("player_mismatch"));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(failure("dimension_mismatch"));
        }
        if (frame.observationAgeTicks() > policy.maximumObservationAgeTicks()) {
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
            final PlantObservedSugarcaneParameters parameters
    ) {
        final Optional<CoreSkillFrame> available = coreFrames.current();
        if (available.isEmpty()) {
            return CoreFrameValidation.failed(failure("pose_unavailable"));
        }
        final CoreSkillFrame frame = available.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return CoreFrameValidation.failed(failure("pose_player_mismatch"));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return CoreFrameValidation.failed(failure("pose_dimension_mismatch"));
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

    private static boolean isPlantAt(
            final VisibleBlockFace face,
            final ObservedBlockTarget support
    ) {
        return SUGAR_CANE.equals(face.blockTypeId())
                && face.block().x() == support.x()
                && face.block().y() == support.y() + 1
                && face.block().z() == support.z();
    }

    private static boolean sameSupport(
            final VisibleBlockFace face,
            final ObservedBlockTarget target
    ) {
        return face.block().x() == target.x()
                && face.block().y() == target.y()
                && face.block().z() == target.z();
    }

    private static boolean isUpFace(final VisibleBlockFace face) {
        return "up".equals(face.face());
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

    private static BlockInteractionTarget interactionTarget(
            final VisibleBlockFace face,
            final PerceptionVec3 hitPoint
    ) {
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.UP,
                new ActionVec3(hitPoint.x(), hitPoint.y(), hitPoint.z())
        );
    }

    /**
     * Selects a point well inside the observed top face rather than the exact
     * ray/voxel edge.  The latter can be classified as the neighbouring block
     * by vanilla's outline clip due to floating-point tie-breaking.  The
     * point is derived only from the observed block coordinate and the live
     * player pose; the interaction actuator still performs the authoritative
     * server crosshair and range checks.
     */
    private static PerceptionVec3 safeTopHitPoint(
            final CoreSkillFrame core,
            final ObservedBlockTarget support
    ) {
        final double localX = core.position().x() < support.x() + 0.5
                ? 0.25
                : 0.75;
        final double localZ = core.position().z() < support.z() + 0.5
                ? 0.25
                : 0.75;
        return new PerceptionVec3(
                support.x() + localX,
                support.y() + 1.0,
                support.z() + localZ
        );
    }

    private static PerceptionVec3 expectedPlantCentre(
            final ObservedBlockTarget support
    ) {
        return new PerceptionVec3(
                support.x() + 0.5,
                support.y() + 1.3,
                support.z() + 0.5
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
        return failure(operation + "_" + outcome.name().toLowerCase(
                Locale.ROOT
        ));
    }

    private static SkillFailure failure(final String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        AIMING,
        WAITING_FOR_PLANT,
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
        private static CoreFrameValidation valid(final CoreSkillFrame frame) {
            return new CoreFrameValidation(Optional.of(frame), Optional.empty());
        }

        private static CoreFrameValidation failed(final SkillFailure failure) {
            return new CoreFrameValidation(Optional.empty(), Optional.of(failure));
        }
    }

    private record AimResult(boolean accepted, double errorDegrees) {
    }
}
