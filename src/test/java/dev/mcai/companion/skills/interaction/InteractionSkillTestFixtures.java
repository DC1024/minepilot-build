package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class InteractionSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123");
    static final UUID ENTITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000456");
    static final long SESSION = 7;
    static final long SEQUENCE = 12;
    static final ObservedBlockTarget OBSERVED_BLOCK =
            new ObservedBlockTarget(
                    SEQUENCE,
                    1,
                    64,
                    0,
                    BlockFace.WEST
            );

    private InteractionSkillTestFixtures() {
    }

    static InteractionSkillFrame frame() {
        return frame(SEQUENCE, 100, 100, SESSION, true, true);
    }

    static InteractionSkillFrame frame(
            long sequence,
            long currentTime,
            long observedAt,
            long session,
            boolean blockVisible,
            boolean entityVisible
    ) {
        List<VisibleBlockFace> blocks = blockVisible
                ? List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 64, 0),
                        "minecraft:stone",
                        "west",
                        new PerceptionVec3(1.0, 64.5, 0.5),
                        3.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                ))
                : List.of();
        List<VisibleEntity> entities = entityVisible
                ? List.of(new VisibleEntity(
                        ENTITY_ID,
                        "minecraft:zombie",
                        new PerceptionVec3(2.0, 64.0, 0.0),
                        new PerceptionVec3(2.0, 0.0, 0.0),
                        2.0,
                        true,
                        false,
                        PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
                ))
                : List.of();
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                currentTime,
                observedAt,
                sequence,
                session,
                new HeldItemSummary("minecraft:stone", 1, 0, 0),
                new HeldItemSummary("minecraft:shield", 1, 0, 336),
                entities,
                blocks
        );
    }

    static final class MutableFrames
            implements InteractionSkillFrameSource {
        InteractionSkillFrame frame;
        final Map<Long, InteractionSkillFrame> history =
                new HashMap<>();

        MutableFrames(InteractionSkillFrame frame) {
            this.frame = frame;
            remember(frame);
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return Optional.ofNullable(frame);
        }

        @Override
        public Optional<InteractionSkillFrame> atObservation(
                final long observationRevision
        ) {
            return Optional.ofNullable(
                    history.get(observationRevision)
            );
        }

        void publish(final InteractionSkillFrame next) {
            frame = next;
            remember(next);
        }

        private void remember(final InteractionSkillFrame value) {
            if (value != null) {
                history.put(value.observationRevision(), value);
            }
        }
    }

    static final class RecordingActuator
            implements InteractionSkillActuator {
        final List<BlockInteractionTarget> miningTargets =
                new ArrayList<>();
        final List<BlockInteractionTarget> blockTargets =
                new ArrayList<>();
        final List<UUID> attacks = new ArrayList<>();
        final List<UUID> interactions = new ArrayList<>();
        final List<ActionHand> interactionHands = new ArrayList<>();
        final List<ActionHand> itemUses = new ArrayList<>();
        final List<String> equippedMainHandItems =
                new ArrayList<>();
        long session = SESSION;
        boolean available = true;
        int continueMiningCalls;
        int abortMiningCalls;
        int continueUsingCalls;
        int releaseUseCalls;
        ActionOutcome beginMiningOutcome = ActionOutcome.IN_PROGRESS;
        ActionOutcome continueMiningOutcome = ActionOutcome.IN_PROGRESS;
        ActionOutcome useBlockOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome attackOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome interactOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome useItemOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome continueUsingOutcome = ActionOutcome.IN_PROGRESS;
        ActionOutcome abortMiningOutcome = ActionOutcome.COMPLETED;
        ActionOutcome releaseUseOutcome = ActionOutcome.NO_ACTIVE_ACTION;
        ActionOutcome equipMainHandOutcome = ActionOutcome.COMPLETED;

        @Override
        public OptionalLong sessionGeneration() {
            return available
                    ? OptionalLong.of(session)
                    : OptionalLong.empty();
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
        ) {
            miningTargets.add(target);
            return beginMiningOutcome;
        }

        @Override
        public ActionOutcome continueMining() {
            continueMiningCalls++;
            return continueMiningOutcome;
        }

        @Override
        public ActionOutcome abortMining() {
            abortMiningCalls++;
            return abortMiningOutcome;
        }

        @Override
        public ActionOutcome useOnBlock(
                ActionHand hand,
                BlockInteractionTarget target
        ) {
            blockTargets.add(target);
            return useBlockOutcome;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            attacks.add(entityId);
            return attackOutcome;
        }

        @Override
        public ActionOutcome interactEntity(
                final UUID entityId,
                final ActionHand hand
        ) {
            interactions.add(entityId);
            interactionHands.add(hand);
            return interactOutcome;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            itemUses.add(hand);
            return useItemOutcome;
        }

        @Override
        public ActionOutcome continueUsing(ActionHand hand) {
            continueUsingCalls++;
            return continueUsingOutcome;
        }

        @Override
        public ActionOutcome releaseUse() {
            releaseUseCalls++;
            return releaseUseOutcome;
        }

        @Override
        public ActionOutcome equipMainHand(final String itemId) {
            equippedMainHandItems.add(itemId);
            return equipMainHandOutcome;
        }
    }
}
