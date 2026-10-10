package dev.mcai.companion.skills.building;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.DropItemParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class ShelterTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000789");
    static final long SESSION = 11;

    private ShelterTestFixtures() {
    }

    static ShelterFrame flatFrame(
            long revision,
            PerceptionVec3 look,
            int structuralCount,
            List<VisibleBlockFace> visible
    ) {
        return new ShelterFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                revision,
                SESSION,
                new GridPos(2, 0, 2),
                look.normalized(),
                new HeldItemSummary(
                        "minecraft:cobblestone",
                        Math.min(structuralCount, 64),
                        0,
                        0
                ),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:cobblestone",
                                structuralCount
                        ),
                        new InventoryItemSummary(
                                "minecraft:oak_door",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:torch",
                                4
                        )
                ),
                flatSnapshot(revision, List.of()),
                visible
        );
    }

    static LocalNavSnapshot flatSnapshot(
            long revision,
            Collection<ObservedVoxel> overrides
    ) {
        List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -6; x <= 10; x++) {
            for (int z = -6; z <= 10; z++) {
                voxels.add(voxel(x, -1, z, VoxelKind.SOLID, revision));
                for (int y = 0; y <= 3; y++) {
                    voxels.add(voxel(x, y, z, VoxelKind.AIR, revision));
                }
            }
        }
        for (ObservedVoxel override : overrides) {
            voxels.removeIf(voxel ->
                    voxel.position().equals(override.position()));
            voxels.add(override);
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels
        );
    }

    static ObservedVoxel voxel(
            int x,
            int y,
            int z,
            VoxelKind kind,
            long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                kind,
                0.0,
                revision
        );
    }

    static VisibleBlockFace topFace(GridPos block) {
        return new VisibleBlockFace(
                new BlockCoordinate(
                        block.x(),
                        block.y(),
                        block.z()
                ),
                "minecraft:stone",
                "up",
                new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 1.0,
                        block.z() + 0.5
                ),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    static VisibleBlockFace placedFace(
            GridPos block,
            String blockId
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(
                        block.x(),
                        block.y(),
                        block.z()
                ),
                blockId,
                "up",
                new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 1.0,
                        block.z() + 0.5
                ),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    static ShelterFrame withWorld(
            ShelterFrame frame,
            long revision,
            LocalNavSnapshot snapshot,
            List<VisibleBlockFace> faces
    ) {
        return new ShelterFrame(
                frame.playerId(),
                frame.dimension(),
                revision,
                revision,
                revision,
                frame.sessionGeneration(),
                frame.feet(),
                frame.lookDirection(),
                frame.mainHand(),
                frame.inventory(),
                snapshot,
                faces
        );
    }

    static ShelterFrame withMainHand(
            ShelterFrame frame,
            HeldItemSummary mainHand
    ) {
        return new ShelterFrame(
                frame.playerId(),
                frame.dimension(),
                frame.currentGameTime(),
                frame.observedAtGameTime(),
                frame.observationRevision(),
                frame.sessionGeneration(),
                frame.feet(),
                frame.lookDirection(),
                mainHand,
                frame.inventory(),
                frame.navigation(),
                frame.visibleBlockFaces()
        );
    }

    static final class MutableFrames implements ShelterFrameSource {
        ShelterFrame frame;
        final List<ShelterFrame> retained = new ArrayList<>();

        MutableFrames(ShelterFrame frame) {
            this.frame = frame;
            retained.add(frame);
        }

        @Override
        public Optional<ShelterFrame> current() {
            return Optional.ofNullable(frame);
        }

        @Override
        public Optional<ShelterFrame> atObservation(
                final long observationRevision
        ) {
            if (frame != null
                    && frame.observationRevision()
                    == observationRevision) {
                return Optional.of(frame);
            }
            return retained.stream()
                    .filter(candidate ->
                            candidate.observationRevision()
                            == observationRevision
                    )
                    .findFirst();
        }
    }

    static final class RecordingActuator
            implements InteractionSkillActuator {
        final List<BlockInteractionTarget> uses = new ArrayList<>();
        long session = SESSION;
        int releaseCalls;
        ActionOutcome useOutcome = ActionOutcome.DISPATCHED;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome useOnBlock(
                ActionHand hand,
                BlockInteractionTarget target
        ) {
            uses.add(target);
            return useOutcome;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome continueUsing(ActionHand hand) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            releaseCalls++;
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }

    static final class RecordingInventoryActuator
            implements InventorySkillActuator {
        int equipCalls;

        @Override
        public InventoryOperationResult checkEquip(
                EquipItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult equip(
                EquipItemParameters parameters
        ) {
            equipCalls++;
            return InventoryOperationResult.success(1);
        }

        @Override
        public InventoryOperationResult checkDrop(
                DropItemParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult drop(
                DropItemParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult checkCraft(
                CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult craftOnce(
                CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }
    }
}
