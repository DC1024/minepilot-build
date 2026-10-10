package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class InteractionSkillValidation {
    private InteractionSkillValidation() {
    }

    static FrameValidation frame(
            String skillName,
            UUID expectedPlayerId,
            DimensionRef expectedDimension,
            long boundSessionGeneration,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            InteractionSkillPolicy policy
    ) {
        Optional<InteractionSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    skillName + ".observation_unavailable"
            );
        }
        InteractionSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(
                    frame,
                    skillName + ".player_mismatch"
            );
        }
        if (!frame.dimension().equals(expectedDimension)) {
            return FrameValidation.failed(
                    frame,
                    skillName + ".dimension_mismatch"
            );
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(
                    frame,
                    skillName + ".stale_observation"
            );
        }
        OptionalLong actuatorSession = actuator.sessionGeneration();
        if (actuatorSession.isEmpty()) {
            return FrameValidation.failed(
                    frame,
                    skillName + ".player_unavailable"
            );
        }
        long currentSession = actuatorSession.orElseThrow();
        if (frame.sessionGeneration() != currentSession
                || boundSessionGeneration >= 0
                && boundSessionGeneration != currentSession) {
            return FrameValidation.failed(
                    frame,
                    skillName + ".session_mismatch"
            );
        }
        return FrameValidation.valid(frame);
    }

    static BlockResolution resolveVisibleBlock(
            String skillName,
            InteractionSkillFrame frame,
            ObservedBlockTarget target,
            InteractionSkillPolicy policy
    ) {
        if (frame.observationRevision() != target.sampleSequence()) {
            return BlockResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        Optional<VisibleBlockFace> visible = frame.visibleBlockFaces().stream()
                .filter(face -> sameBlockAndFace(face, target))
                .findFirst();
        if (visible.isEmpty()) {
            return BlockResolution.failed(
                    skillName + ".target_not_visible"
            );
        }
        VisibleBlockFace face = visible.orElseThrow();
        if (face.distance() > policy.maximumCandidateDistance()) {
            return BlockResolution.failed(
                    skillName + ".target_out_of_range"
            );
        }
        PerceptionVec3 actual = face.hitPosition();
        try {
            return BlockResolution.resolved(new BlockInteractionTarget(
                    target.x(),
                    target.y(),
                    target.z(),
                    target.face(),
                    new ActionVec3(actual.x(), actual.y(), actual.z())
            ));
        } catch (RuntimeException exception) {
            return BlockResolution.failed(
                    skillName + ".target_hit_invalid"
            );
        }
    }

    /**
     * Resolves the exact block the model selected from its retained fair
     * sample, then independently requires the same block type and face to be
     * visible in the current first-person frame.
     *
     * <p>A model request normally takes longer than the semantic sampler's
     * 2-5 Hz interval. Requiring the authored sample sequence to still be the
     * latest frame made every otherwise-valid action expire during ordinary
     * provider latency. Using the old frame only for identity and the current
     * frame only for execution preserves both fairness and liveness.</p>
     */
    static BlockResolution resolveRetainedVisibleBlock(
            final String skillName,
            final InteractionSkillFrameSource frames,
            final InteractionSkillFrame current,
            final ObservedBlockTarget target,
            final InteractionSkillPolicy policy
    ) {
        final Optional<InteractionSkillFrame> retained =
                frames.atObservation(target.sampleSequence());
        if (retained.isEmpty()) {
            return BlockResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        final InteractionSkillFrame authored = retained.orElseThrow();
        if (!sameObservationSession(current, authored)) {
            return BlockResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        final BlockResolution authoredResolution = resolveVisibleBlock(
                skillName,
                authored,
                target,
                policy
        );
        if (authoredResolution.failure().isPresent()) {
            return authoredResolution;
        }
        final Optional<VisibleBlockFace> authoredFace =
                authored.visibleBlockFaces().stream()
                        .filter(face -> sameBlockAndFace(face, target))
                        .findFirst();
        if (authoredFace.isEmpty()) {
            return BlockResolution.failed(
                    skillName + ".target_not_visible"
            );
        }
        final VisibleBlockFace original = authoredFace.orElseThrow();
        final Optional<VisibleBlockFace> visible =
                current.visibleBlockFaces().stream()
                        .filter(face -> sameBlockAndFace(face, target))
                        .filter(face -> face.blockTypeId().equals(
                                original.blockTypeId()
                        ))
                        .findFirst();
        if (visible.isEmpty()) {
            final boolean sameSurfaceChanged =
                    current.visibleBlockFaces().stream()
                            .anyMatch(face ->
                                    sameBlockAndFace(face, target)
                            );
            return BlockResolution.failed(
                    skillName
                            + (sameSurfaceChanged
                                ? ".target_changed"
                                : ".target_not_visible")
            );
        }
        /* The semantic frame supplies the retained identity, while the
         * live first-person sampler supplies the exact hit point used by the
         * vanilla actuator.  Peripheral fan hits can be a few tenths of a
         * block away from the current outline clip; replaying those stale
         * coordinates causes a legitimate mining action to be rejected as
         * TARGET_OCCLUDED. */
        final VisibleBlockFace currentFace = frames.currentCrosshairBlock()
                .filter(face -> sameBlockAndFace(face, target))
                .filter(face -> face.blockTypeId().equals(
                        original.blockTypeId()
                ))
                .orElse(visible.orElseThrow());
        if (currentFace.distance()
                > policy.maximumCandidateDistance()) {
            return BlockResolution.failed(
                    skillName + ".target_out_of_range"
            );
        }
        final PerceptionVec3 actual = currentFace.hitPosition();
        try {
            return BlockResolution.resolved(
                    new BlockInteractionTarget(
                            target.x(),
                            target.y(),
                            target.z(),
                            target.face(),
                            new ActionVec3(
                                    actual.x(),
                                    actual.y(),
                                    actual.z()
                            )
                    )
            );
        } catch (RuntimeException exception) {
            return BlockResolution.failed(
                    skillName + ".target_hit_invalid"
            );
        }
    }

    static EntityResolution resolveVisibleEntity(
            String skillName,
            InteractionSkillFrame frame,
            AttackEntityParameters target,
            InteractionSkillPolicy policy
    ) {
        if (frame.observationRevision() != target.sampleSequence()) {
            return EntityResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        int index = target.observationIndex();
        if (index < 0 || index >= frame.visibleEntities().size()) {
            return EntityResolution.failed(
                    skillName + ".target_not_visible"
            );
        }
        VisibleEntity visible = frame.visibleEntities().get(index);
        if (visible.distance() > policy.maximumCandidateDistance()) {
            return EntityResolution.failed(
                    skillName + ".target_out_of_range"
            );
        }
        return EntityResolution.resolved(visible.entityId());
    }

    /**
     * Maps the model's old opaque observation index to one retained UUID, then
     * requires that exact entity to remain visible in the current frame.
     */
    static EntityResolution resolveRetainedVisibleEntity(
            final String skillName,
            final InteractionSkillFrameSource frames,
            final InteractionSkillFrame current,
            final AttackEntityParameters target,
            final InteractionSkillPolicy policy
    ) {
        final Optional<InteractionSkillFrame> retained =
                frames.atObservation(target.sampleSequence());
        if (retained.isEmpty()) {
            return EntityResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        final InteractionSkillFrame authored = retained.orElseThrow();
        if (!sameObservationSession(current, authored)) {
            return EntityResolution.failed(
                    skillName + ".observation_expired"
            );
        }
        final EntityResolution authoredResolution =
                resolveVisibleEntity(
                        skillName,
                        authored,
                        target,
                        policy
                );
        if (authoredResolution.failure().isPresent()) {
            return authoredResolution;
        }
        final UUID selected =
                authoredResolution.entityId().orElseThrow();
        final Optional<VisibleEntity> visible =
                current.visibleEntities().stream()
                        .filter(entity ->
                                entity.entityId().equals(selected)
                        )
                        .findFirst();
        if (visible.isEmpty()) {
            return EntityResolution.failed(
                    skillName + ".target_not_visible"
            );
        }
        if (visible.orElseThrow().distance()
                > policy.maximumCandidateDistance()) {
            return EntityResolution.failed(
                    skillName + ".target_out_of_range"
            );
        }
        return EntityResolution.resolved(selected);
    }

    static Optional<SkillFailure> heldItem(
            String skillName,
            InteractionSkillFrame frame,
            ActionHand hand
    ) {
        HeldItemSummary item = hand == ActionHand.MAIN_HAND
                ? frame.mainHand()
                : frame.offHand();
        return item.emptyHand()
                ? Optional.of(
                        SkillFailure.of(skillName + ".item_unavailable")
                )
                : Optional.empty();
    }

    static SkillFailure actionFailure(
            String skillName,
            ActionOutcome outcome
    ) {
        Objects.requireNonNull(outcome, "outcome");
        return SkillFailure.of(
                skillName
                        + ".action_"
                        + outcome.name().toLowerCase(Locale.ROOT)
        );
    }

    static boolean releaseSucceeded(ActionOutcome outcome) {
        return outcome.accepted()
                || outcome == ActionOutcome.NO_ACTIVE_ACTION;
    }

    private static boolean sameBlockAndFace(
            VisibleBlockFace visible,
            ObservedBlockTarget target
    ) {
        return visible.block().x() == target.x()
                && visible.block().y() == target.y()
                && visible.block().z() == target.z()
                && visible.face().equals(
                        target.face().name().toLowerCase(Locale.ROOT)
                );
    }

    private static boolean sameObservationSession(
            final InteractionSkillFrame current,
            final InteractionSkillFrame retained
    ) {
        return current.playerId().equals(retained.playerId())
                && current.dimension().equals(retained.dimension())
                && current.sessionGeneration()
                    == retained.sessionGeneration();
    }

    record BlockResolution(
            Optional<BlockInteractionTarget> target,
            Optional<SkillFailure> failure
    ) {
        BlockResolution {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(failure, "failure");
            if (target.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Block resolution requires exactly one outcome"
                );
            }
        }

        static BlockResolution resolved(BlockInteractionTarget target) {
            return new BlockResolution(
                    Optional.of(target),
                    Optional.empty()
            );
        }

        static BlockResolution failed(String code) {
            return new BlockResolution(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    record EntityResolution(
            Optional<UUID> entityId,
            Optional<SkillFailure> failure
    ) {
        EntityResolution {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(failure, "failure");
            if (entityId.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Entity resolution requires exactly one outcome"
                );
            }
        }

        static EntityResolution resolved(UUID entityId) {
            return new EntityResolution(
                    Optional.of(entityId),
                    Optional.empty()
            );
        }

        static EntityResolution failed(String code) {
            return new EntityResolution(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    record FrameValidation(
            Optional<InteractionSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        FrameValidation {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(failure, "failure");
            if (failure.isEmpty() && frame.isEmpty()) {
                throw new IllegalArgumentException(
                        "A successful validation requires a frame"
                );
            }
        }

        static FrameValidation valid(InteractionSkillFrame frame) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }

        static FrameValidation failed(
                InteractionSkillFrame frame,
                String code
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
