package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class FarmingSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000731");
    static final long SESSION = 9;
    static final long INITIAL_SEQUENCE = 21;

    private FarmingSkillTestFixtures() {
    }

    static HarvestAndReplantParameters parameters() {
        return new HarvestAndReplantParameters(
                DimensionRef.OVERWORLD,
                CropKind.WHEAT,
                new ObservedBlockTarget(
                        INITIAL_SEQUENCE,
                        1,
                        64,
                        0,
                        BlockFace.WEST
                )
        );
    }

    static InteractionSkillFrame matureCropFrame(
            int seedCount
    ) {
        return frame(
                INITIAL_SEQUENCE,
                SESSION,
                List.of(cropFace("7")),
                seedCount
        );
    }

    static InteractionSkillFrame substrateFrame(
            int seedCount
    ) {
        return frame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 63, 0),
                        "minecraft:farmland",
                        "up",
                        new PerceptionVec3(1.5, 64.0, 0.5),
                        3.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of("moisture", "7")
                )),
                seedCount
        );
    }

    static InteractionSkillFrame replantedFrame(
            int seedCount
    ) {
        return frame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                List.of(cropFace("0")),
                seedCount,
                1
        );
    }

    static InteractionSkillFrame frame(
            long sequence,
            long session,
            List<VisibleBlockFace> blocks,
            int seedCount
    ) {
        return frame(sequence, session, blocks, seedCount, 0);
    }

    static InteractionSkillFrame frame(
            long sequence,
            long session,
            List<VisibleBlockFace> blocks,
            int seedCount,
            int wheatCount
    ) {
        List<InventoryItemSummary> inventory = new ArrayList<>();
        if (seedCount > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:wheat_seeds",
                    seedCount
            ));
        }
        if (wheatCount > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:wheat",
                    wheatCount
            ));
        }
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + sequence,
                100 + sequence,
                sequence,
                session,
                new HeldItemSummary(
                        "minecraft:stone_hoe",
                        1,
                        0,
                        131
                ),
                HeldItemSummary.empty(),
                List.of(),
                blocks,
                List.copyOf(inventory)
        );
    }

    static VisibleBlockFace cropFace(String age) {
        return new VisibleBlockFace(
                new BlockCoordinate(1, 64, 0),
                "minecraft:wheat",
                "west",
                new PerceptionVec3(1.0, 64.3, 0.5),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of("age", age)
        );
    }

    static CoreSkillFrame coreFrame(
            InteractionSkillFrame interaction
    ) {
        PerceptionVec3 eye = new PerceptionVec3(
                0.5,
                65.62,
                0.5
        );
        PerceptionVec3 look = interaction.visibleBlockFaces().isEmpty()
                ? new PerceptionVec3(1.0, 0.0, 0.0)
                : interaction.visibleBlockFaces()
                        .getFirst()
                        .hitPosition()
                        .subtract(eye)
                        .normalized();
        return coreFrame(interaction, look);
    }

    static CoreSkillFrame coreFrame(
            InteractionSkillFrame interaction,
            PerceptionVec3 look
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                interaction.currentGameTime(),
                interaction.observationRevision(),
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                look.normalized(),
                true,
                false,
                0.0,
                safeNavigation(interaction.observationRevision()),
                interaction.visibleBlockFaces(),
                20.0F,
                20.0F,
                20,
                interaction.inventory(),
                interaction.mainHand(),
                interaction.offHand(),
                interaction.visibleEntities(),
                List.of()
        );
    }

    private static LocalNavSnapshot safeNavigation(
            final long revision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                final GridPos support = new GridPos(x, 63, z);
                voxels.add(new ObservedVoxel(
                        support,
                        VoxelKind.SOLID,
                        0.0,
                        revision,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                voxels.add(new ObservedVoxel(
                        support.above(),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                voxels.add(new ObservedVoxel(
                        support.above(2),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels
        );
    }

    static final class MutableFrames
            implements InteractionSkillFrameSource {
        InteractionSkillFrame frame;
        VisibleBlockFace crosshair;
        private final InteractionSkillFrame initialFrame;

        MutableFrames(InteractionSkillFrame frame) {
            this.frame = frame;
            this.initialFrame = frame;
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return Optional.ofNullable(frame);
        }

        @Override
        public Optional<InteractionSkillFrame> atObservation(
                final long observationRevision
        ) {
            if (frame != null
                    && frame.observationRevision() == observationRevision) {
                return Optional.of(frame);
            }
            return Optional.ofNullable(initialFrame).filter(candidate ->
                    candidate.observationRevision() == observationRevision
            );
        }

        @Override
        public Optional<VisibleBlockFace> currentCrosshairBlock() {
            return Optional.ofNullable(crosshair);
        }
    }

    static final class CoupledCoreFrames
            implements CoreSkillFrameSource {
        private final MutableFrames interactionFrames;
        PerceptionVec3 lookOverride;

        CoupledCoreFrames(MutableFrames interactionFrames) {
            this.interactionFrames = interactionFrames;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return interactionFrames.current().map(interaction ->
                    lookOverride == null
                            ? coreFrame(interaction)
                            : coreFrame(interaction, lookOverride)
            );
        }
    }

    static final class RecordingCoreActuator
            implements CoreSkillActuator {
        final List<LookIntent> looks = new ArrayList<>();
        final List<MovementIntent> moves = new ArrayList<>();
        int stops;
        ActionOutcome outcome = ActionOutcome.QUEUED;

        @Override
        public ActionOutcome move(MovementIntent intent) {
            moves.add(intent);
            return outcome;
        }

        @Override
        public ActionOutcome look(LookIntent intent) {
            looks.add(intent);
            return outcome;
        }

        @Override
        public ActionOutcome jump() {
            return outcome;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return outcome;
        }

        @Override
        public ActionOutcome useMainHandOn(
                BlockInteractionTarget target
        ) {
            return outcome;
        }
    }

    static final class RecordingActuator
            implements InteractionSkillActuator {
        final List<BlockInteractionTarget> mining = new ArrayList<>();
        final List<BlockInteractionTarget> uses = new ArrayList<>();
        final List<ActionHand> useHands = new ArrayList<>();
        final List<ActionHand> itemUses = new ArrayList<>();
        final List<String> equipped = new ArrayList<>();
        long session = SESSION;
        ActionOutcome beginMiningOutcome = ActionOutcome.COMPLETED;
        ActionOutcome continueMiningOutcome = ActionOutcome.IN_PROGRESS;
        ActionOutcome equipOutcome = ActionOutcome.COMPLETED;
        ActionOutcome useOutcome = ActionOutcome.DISPATCHED;
        int abortCalls;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
        ) {
            mining.add(target);
            return beginMiningOutcome;
        }

        @Override
        public ActionOutcome continueMining() {
            return continueMiningOutcome;
        }

        @Override
        public ActionOutcome abortMining() {
            abortCalls++;
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome useOnBlock(
                ActionHand hand,
                BlockInteractionTarget target
        ) {
            useHands.add(hand);
            uses.add(target);
            return useOutcome;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            itemUses.add(hand);
            return useOutcome;
        }

        @Override
        public ActionOutcome continueUsing(ActionHand hand) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome equipMainHand(String itemId) {
            equipped.add(itemId);
            return equipOutcome;
        }
    }
}
