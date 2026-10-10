package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
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
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

final class GatheringSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000841");
    static final long SESSION = 13;
    static final long SEQUENCE = 40;
    static final String BLOCK_ID = "minecraft:oak_log";
    static final String TOOL_ID = "minecraft:iron_axe";

    private GatheringSkillTestFixtures() {
    }

    static GatherVisibleBlockClusterParameters parameters(int maximum) {
        return new GatherVisibleBlockClusterParameters(
                DimensionRef.OVERWORLD,
                new ObservedBlockTarget(
                        SEQUENCE,
                        1,
                        64,
                        0,
                        BlockFace.WEST
                ),
                BLOCK_ID,
                maximum,
                16.0,
                TOOL_ID
        );
    }

    static SnapshotFrames frames(
            long sequence,
            List<VisibleBlockFace> blocks
    ) {
        PerceptionVec3 eye = new PerceptionVec3(
                0.5,
                65.62,
                0.5
        );
        PerceptionVec3 look = blocks.isEmpty()
                ? new PerceptionVec3(1.0, 0.0, 0.0)
                : blocks.getFirst()
                        .hitPosition()
                        .subtract(eye)
                        .normalized();
        CoreSkillFrame core = new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + sequence,
                sequence,
                new PerceptionVec3(0.5, 64.0, 0.5),
                eye,
                look,
                true,
                false,
                0.0,
                observedFloor(sequence),
                blocks,
                20.0F,
                20.0F,
                20,
                axe(0),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
        InteractionSkillFrame interaction =
                new InteractionSkillFrame(
                        PLAYER_ID,
                        DimensionRef.OVERWORLD,
                        100 + sequence,
                        100 + sequence,
                        sequence,
                        SESSION,
                        axe(0),
                        HeldItemSummary.empty(),
                        List.of(),
                        blocks,
                        List.of(new InventoryItemSummary(TOOL_ID, 1))
                );
        return new SnapshotFrames(core, interaction);
    }

    static SnapshotFrames withDanger(
            SnapshotFrames frames,
            double danger
    ) {
        CoreSkillFrame source = frames.core;
        frames.core = new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                danger,
                source.navigation(),
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
        return frames;
    }

    static SnapshotFrames withToolDamage(
            SnapshotFrames frames,
            int damage
    ) {
        HeldItemSummary damaged = axe(damage);
        CoreSkillFrame source = frames.core;
        frames.core = new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                source.navigation(),
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                damaged,
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
        InteractionSkillFrame interaction = frames.interaction;
        frames.interaction = new InteractionSkillFrame(
                interaction.playerId(),
                interaction.dimension(),
                interaction.currentGameTime(),
                interaction.observedAtGameTime(),
                interaction.observationRevision(),
                interaction.sessionGeneration(),
                damaged,
                interaction.offHand(),
                interaction.visibleEntities(),
                interaction.visibleBlockFaces(),
                interaction.inventory()
        );
        return frames;
    }

    static VisibleBlockFace log(int x, long ignoredSequence) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, 64, 0),
                BLOCK_ID,
                "west",
                new PerceptionVec3(x, 64.5, 0.5),
                Math.hypot(x - 0.5, 1.12),
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    static GatheringSkillPolicy immediateCollectionPolicy() {
        return new GatheringSkillPolicy(
                10,
                1_200,
                0,
                4,
                36,
                10,
                30,
                3,
                3,
                4.25,
                12.0,
                0.20,
                0.10,
                0.35,
                0.60
        );
    }

    static GatheringSkillPolicy collectionPolicy(
            final int collectionTicks
    ) {
        return new GatheringSkillPolicy(
                10,
                1_200,
                collectionTicks,
                4,
                36,
                10,
                30,
                3,
                3,
                4.25,
                12.0,
                0.20,
                0.10,
                0.35,
                0.60
        );
    }

    static SnapshotFrames withInventory(
            final SnapshotFrames frames,
            final List<InventoryItemSummary> inventory
    ) {
        final InteractionSkillFrame interaction =
            frames.interaction;
        frames.interaction = new InteractionSkillFrame(
            interaction.playerId(),
            interaction.dimension(),
            interaction.currentGameTime(),
            interaction.observedAtGameTime(),
            interaction.observationRevision(),
            interaction.sessionGeneration(),
            interaction.mainHand(),
            interaction.offHand(),
            interaction.visibleEntities(),
            interaction.visibleBlockFaces(),
            inventory
        );
        return frames;
    }

    static SnapshotFrames withLiveCoreInventory(
            final SnapshotFrames frames,
            final List<InventoryItemSummary> inventory
    ) {
        final CoreSkillFrame core = frames.core;
        frames.core = new CoreSkillFrame(
                core.playerId(),
                core.dimension(),
                core.gameTime(),
                core.observationRevision(),
                core.position(),
                core.eyePosition(),
                core.lookDirection(),
                core.onGround(),
                core.inWater(),
                core.danger(),
                core.navigation(),
                core.visibleBlockFaces(),
                core.health(),
                core.maxHealth(),
                core.foodLevel(),
                inventory,
                core.mainHand(),
                core.offHand(),
                core.visibleEntities(),
                core.dangerSignals()
        );
        return frames;
    }

    static SnapshotFrames withVisibleEntities(
            final SnapshotFrames frames,
            final List<VisibleEntity> entities
    ) {
        final CoreSkillFrame core = frames.core;
        frames.core = new CoreSkillFrame(
                core.playerId(),
                core.dimension(),
                core.gameTime(),
                core.observationRevision(),
                core.position(),
                core.eyePosition(),
                core.lookDirection(),
                core.onGround(),
                core.inWater(),
                core.danger(),
                core.navigation(),
                core.visibleBlockFaces(),
                core.health(),
                core.maxHealth(),
                core.foodLevel(),
                core.mainHand(),
                core.offHand(),
                entities,
                core.dangerSignals()
        );
        final InteractionSkillFrame interaction = frames.interaction;
        frames.interaction = new InteractionSkillFrame(
                interaction.playerId(),
                interaction.dimension(),
                interaction.currentGameTime(),
                interaction.observedAtGameTime(),
                interaction.observationRevision(),
                interaction.sessionGeneration(),
                interaction.mainHand(),
                interaction.offHand(),
                entities,
                interaction.visibleBlockFaces(),
                interaction.inventory()
        );
        return frames;
    }

    static void setInteractionBlocks(
            final SnapshotFrames frames,
            final List<VisibleBlockFace> blocks
    ) {
        final InteractionSkillFrame interaction = frames.interaction;
        frames.interaction = new InteractionSkillFrame(
                interaction.playerId(),
                interaction.dimension(),
                interaction.currentGameTime(),
                interaction.observedAtGameTime(),
                interaction.observationRevision(),
                interaction.sessionGeneration(),
                interaction.mainHand(),
                interaction.offHand(),
                interaction.visibleEntities(),
                blocks,
                interaction.inventory()
        );
    }

    static VisibleEntity droppedLog(
            final double x,
            final double y,
            final double z
    ) {
        final PerceptionVec3 position =
                new PerceptionVec3(x, y, z);
        final PerceptionVec3 playerPosition =
                new PerceptionVec3(0.5, 64.0, 0.5);
        return new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000842"
                ),
                "minecraft:item",
                position,
                position.subtract(playerPosition),
                position.subtract(playerPosition).length(),
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("itemId", BLOCK_ID)
        );
    }

    private static HeldItemSummary axe(int damage) {
        return new HeldItemSummary(TOOL_ID, 1, damage, 250);
    }

    static SnapshotFrames withNavigation(
            final SnapshotFrames frames,
            final LocalNavSnapshot navigation
    ) {
        final CoreSkillFrame source = frames.core;
        frames.core = new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                navigation,
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
        return frames;
    }

    static SnapshotFrames withLook(
            final SnapshotFrames frames,
            final PerceptionVec3 look
    ) {
        final CoreSkillFrame source = frames.core;
        frames.core = new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                look.normalized(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                source.navigation(),
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
        return frames;
    }

    static LocalNavSnapshot observedFloor(long revision) {
        List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -2; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 63, z),
                        VoxelKind.SOLID,
                        0.0,
                        revision,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 64, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 65, z),
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

    static final class SnapshotFrames
            implements CoreSkillFrameSource {
        CoreSkillFrame core;
        InteractionSkillFrame interaction;

        SnapshotFrames(
                CoreSkillFrame core,
                InteractionSkillFrame interaction
        ) {
            this.core = core;
            this.interaction = interaction;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(core);
        }

        Optional<InteractionSkillFrame> interactionCurrent() {
            return Optional.ofNullable(interaction);
        }
    }

    static final class InteractionFrames
            implements InteractionSkillFrameSource {
        private final SnapshotFrames snapshots;
        private VisibleBlockFace crosshair;

        InteractionFrames(SnapshotFrames snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return snapshots.interactionCurrent();
        }

        @Override
        public Optional<VisibleBlockFace> currentCrosshairBlock() {
            return Optional.ofNullable(crosshair);
        }

        void setCrosshair(final VisibleBlockFace visible) {
            crosshair = visible;
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

    static final class RecordingInteractionActuator
            implements InteractionSkillActuator {
        final List<BlockInteractionTarget> mining = new ArrayList<>();
        final List<String> equipped = new ArrayList<>();
        long session = SESSION;
        ActionOutcome beginOutcome = ActionOutcome.COMPLETED;
        ActionOutcome continueOutcome = ActionOutcome.IN_PROGRESS;
        int aborts;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
        ) {
            mining.add(target);
            return beginOutcome;
        }

        @Override
        public ActionOutcome continueMining() {
            return continueOutcome;
        }

        @Override
        public ActionOutcome abortMining() {
            aborts++;
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome useOnBlock(
                ActionHand hand,
                BlockInteractionTarget target
        ) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.empty();
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
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
            return ActionOutcome.COMPLETED;
        }
    }

    static final class MutableInventory
            implements ResourceInventorySource {
        int emptySlots = 5;
        long session = SESSION;

        @Override
        public Optional<ResourceInventoryState> current() {
            return Optional.of(new ResourceInventoryState(
                    session,
                    emptySlots
            ));
        }
    }
}
