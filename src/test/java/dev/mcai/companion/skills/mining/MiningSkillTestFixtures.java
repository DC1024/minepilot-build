package dev.mcai.companion.skills.mining;

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
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.gathering.ResourceInventoryState;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

final class MiningSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000981");
    static final long SESSION = 37;
    static final String PICKAXE = "minecraft:iron_pickaxe";
    static final String TORCH = "minecraft:torch";
    static final String TARGET = "minecraft:diamond_ore";
    static final GridPos ORIGIN = new GridPos(0, 64, 0);

    private MiningSkillTestFixtures() {
    }

    static ExcavateSafeTunnelParameters parameters(
            final TunnelMode mode
    ) {
        return new ExcavateSafeTunnelParameters(
                DimensionRef.OVERWORLD,
                10,
                TunnelDirection.EAST,
                mode,
                1,
                4,
                PICKAXE,
                List.of(TARGET)
        );
    }

    static MiningSkillPolicy fastPolicy() {
        return new MiningSkillPolicy(
                10,
                6,
                2,
                20,
                3,
                10,
                3,
                4,
                10,
                14,
                0.65,
                0.85,
                0.15,
                0.06
        );
    }

    static MutableFrames initial(final TunnelMode mode) {
        final MutableFrames frames = new MutableFrames();
        frames.putVoxel(ORIGIN.below(), VoxelKind.SOLID, 0.0);
        frames.putVoxel(ORIGIN, VoxelKind.AIR, 0.0);
        frames.putVoxel(ORIGIN.above(), VoxelKind.AIR, 0.0);
        frames.addFace(ORIGIN.below(), "minecraft:stone", BlockFace.UP);
        final GridPos destination = destination(mode);
        if (mode == TunnelMode.DESCENDING) {
            frames.putVoxel(
                    destination.above().above(),
                    VoxelKind.AIR,
                    0.0
            );
        } else if (mode == TunnelMode.ASCENDING) {
            frames.putVoxel(
                    ORIGIN.above().above(),
                    VoxelKind.AIR,
                    0.0
            );
        }
        frames.putVoxel(destination, VoxelKind.SOLID, 0.0);
        frames.putVoxel(destination.above(), VoxelKind.SOLID, 0.0);
        frames.addFace(
                destination,
                "minecraft:stone",
                BlockFace.WEST
        );
        frames.addFace(
                destination.above(),
                "minecraft:stone",
                BlockFace.WEST
        );
        return frames;
    }

    static GridPos destination(final TunnelMode mode) {
        return switch (mode) {
            case HORIZONTAL -> new GridPos(1, 64, 0);
            case DESCENDING -> new GridPos(1, 63, 0);
            case ASCENDING -> new GridPos(1, 65, 0);
        };
    }

    static final class MutableFrames {
        long revision = 10;
        long gameTime = 100;
        long observationAge;
        long session = SESSION;
        PerceptionVec3 position = new PerceptionVec3(0.5, 64.0, 0.5);
        PerceptionVec3 look = new PerceptionVec3(1.0, 0.0, 0.0);
        boolean onGround = true;
        boolean inWater;
        double danger;
        float health = 20.0F;
        int food = 20;
        HeldItemSummary main =
                new HeldItemSummary(PICKAXE, 1, 0, 250);
        HeldItemSummary off = HeldItemSummary.empty();
        int pickaxeCount = 1;
        int torchCount = 8;
        int emptySlots = 8;
        boolean publishPlacedTorch = true;
        boolean publishCrosshair = true;
        final Map<GridPos, VoxelRecord> voxels = new HashMap<>();
        final Map<GridPos, Integer> voxelRevisionLags =
                new HashMap<>();
        final List<FaceRecord> faces = new ArrayList<>();
        final List<VisibleEntity> entities = new ArrayList<>();

        Optional<CoreSkillFrame> coreCurrent() {
            final PerceptionVec3 eye = new PerceptionVec3(
                    position.x(),
                    position.y() + 1.62,
                    position.z()
            );
            return Optional.of(new CoreSkillFrame(
                    PLAYER_ID,
                    DimensionRef.OVERWORLD,
                    gameTime,
                    revision,
                    position,
                    eye,
                    look,
                    onGround,
                    inWater,
                    danger,
                    navigation(),
                    visibleFaces(eye),
                    health,
                    20.0F,
                    food,
                    inventoryItems(),
                    main,
                    off,
                    entities,
                    List.of()
            ));
        }

        Optional<InteractionSkillFrame> interactionCurrent() {
            final PerceptionVec3 eye = new PerceptionVec3(
                    position.x(),
                    position.y() + 1.62,
                    position.z()
            );
            return Optional.of(new InteractionSkillFrame(
                    PLAYER_ID,
                    DimensionRef.OVERWORLD,
                    gameTime,
                    gameTime - observationAge,
                    revision,
                    session,
                    main,
                    off,
                    entities,
                    visibleFaces(eye),
                    inventoryItems()
            ));
        }

        InteractionSkillFrameSource interactionFrameSource() {
            return new InteractionSkillFrameSource() {
                @Override
                public Optional<InteractionSkillFrame> current() {
                    return interactionCurrent();
                }

                @Override
                public Optional<VisibleBlockFace>
                        currentCrosshairBlock() {
                    if (!publishCrosshair) {
                        return Optional.empty();
                    }
                    final PerceptionVec3 eye = new PerceptionVec3(
                            position.x(),
                            position.y() + 1.62,
                            position.z()
                    );
                    return visibleFaces(eye).stream()
                            .filter(face -> {
                                final PerceptionVec3 direction =
                                        face.hitPosition()
                                            .subtract(eye)
                                            .normalized();
                                return direction.dot(look) >= 0.999;
                            })
                            .max(java.util.Comparator.comparingDouble(
                                    face -> face.hitPosition()
                                        .subtract(eye)
                                        .normalized()
                                        .dot(look)
                            ));
                }
            };
        }

        Optional<ResourceInventoryState> inventoryCurrent() {
            return Optional.of(new ResourceInventoryState(
                    session,
                    emptySlots
            ));
        }

        void advance() {
            revision++;
            gameTime++;
            observationAge = 0;
        }

        void putVoxel(
                final GridPos position,
                final VoxelKind kind,
                final double danger
        ) {
            final OccupancyEvidence evidence =
                    kind == VoxelKind.AIR
                            ? OccupancyEvidence.MULTI_RAY_CLEAR
                            : OccupancyEvidence.SURFACE_HIT;
            putVoxel(position, kind, danger, evidence);
        }

        void putVoxel(
                final GridPos position,
                final VoxelKind kind,
                final double danger,
                final OccupancyEvidence evidence
        ) {
            voxels.put(
                    position,
                    new VoxelRecord(kind, danger, evidence)
            );
        }

        void removeVoxel(final GridPos position) {
            voxels.remove(position);
            voxelRevisionLags.remove(position);
        }

        void setVoxelRevisionLag(
                final GridPos position,
                final int lag
        ) {
            if (lag < 0) {
                throw new IllegalArgumentException(
                        "Voxel revision lag must be non-negative"
                );
            }
            voxelRevisionLags.put(position, lag);
        }

        void addFace(
                final GridPos position,
                final String blockId,
                final BlockFace face
        ) {
            final TopSupportAffordance support =
                    face == BlockFace.UP
                            && "minecraft:stone".equals(blockId)
                            ? TopSupportAffordance.STURDY_FULL_TOP
                            : face == BlockFace.UP
                                ? TopSupportAffordance
                                    .NON_STURDY_OR_PARTIAL
                                : TopSupportAffordance.UNKNOWN;
            addFace(position, blockId, face, support);
        }

        void addFace(
                final GridPos position,
                final String blockId,
                final BlockFace face,
                final TopSupportAffordance support
        ) {
            removeFace(position);
            faces.add(new FaceRecord(
                    position,
                    blockId,
                    face,
                    support
            ));
        }

        void removeFace(final GridPos position) {
            faces.removeIf(face -> face.position().equals(position));
        }

        void clearBlock(final GridPos position) {
            removeFace(position);
            /*
             * A single clear ray is sufficient only in the skill's narrow
             * post-mining verifier, where the exact visible face was already
             * bound to an accepted ordinary break action. It is deliberately
             * insufficient for an arbitrary future corridor cell.
             */
            putVoxel(
                    position,
                    VoxelKind.AIR,
                    0.0,
                    OccupancyEvidence.SINGLE_RAY_CLEAR
            );
        }

        void exposeSupport(final GridPos destination) {
            final GridPos support = destination.below();
            putVoxel(support, VoxelKind.SOLID, 0.0);
            addFace(support, "minecraft:stone", BlockFace.UP);
        }

        void setMain(final String itemId) {
            main = switch (itemId) {
                case PICKAXE -> new HeldItemSummary(
                        PICKAXE,
                        1,
                        0,
                        250
                );
                case TORCH -> new HeldItemSummary(
                        TORCH,
                        Math.max(1, torchCount),
                        0,
                        0
                );
                default -> HeldItemSummary.empty();
            };
        }

        void consumeTorch() {
            torchCount = Math.max(0, torchCount - 1);
            setMain(torchCount == 0 ? "minecraft:air" : TORCH);
        }

        void moveTo(final GridPos target) {
            position = new PerceptionVec3(
                    target.x() + 0.5,
                    target.y(),
                    target.z() + 0.5
            );
            onGround = true;
        }

        private LocalNavSnapshot navigation() {
            final List<ObservedVoxel> observed = voxels.entrySet()
                    .stream()
                    .map(entry -> new ObservedVoxel(
                            entry.getKey(),
                            entry.getValue().kind(),
                            entry.getValue().danger(),
                            Math.max(
                                    0,
                                    revision - voxelRevisionLags
                                        .getOrDefault(entry.getKey(), 0)
                            ),
                            entry.getValue().occupancyEvidence(),
                            faces.stream()
                                .filter(face ->
                                    face.position().equals(entry.getKey())
                                        && face.face() == BlockFace.UP
                                )
                                .map(FaceRecord::topSupportAffordance)
                                .findFirst()
                                .orElse(
                                    TopSupportAffordance.UNKNOWN
                                )
                    ))
                    .toList();
            return new LocalNavSnapshot(
                    DimensionRef.OVERWORLD,
                    revision,
                    observed
            );
        }

        private List<InventoryItemSummary> inventoryItems() {
            final List<InventoryItemSummary> result =
                    new ArrayList<>();
            if (pickaxeCount > 0) {
                result.add(new InventoryItemSummary(
                        PICKAXE,
                        pickaxeCount
                ));
            }
            if (torchCount > 0) {
                result.add(new InventoryItemSummary(
                        TORCH,
                        torchCount
                ));
            }
            return List.copyOf(result);
        }

        private List<VisibleBlockFace> visibleFaces(
                final PerceptionVec3 eye
        ) {
            return faces.stream()
                    .map(face -> visibleFace(face, eye))
                    .toList();
        }

        private static VisibleBlockFace visibleFace(
                final FaceRecord face,
                final PerceptionVec3 eye
        ) {
            final GridPos block = face.position();
            final PerceptionVec3 hit = switch (face.face()) {
                case UP -> new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 1.0,
                        block.z() + 0.5
                );
                case DOWN -> new PerceptionVec3(
                        block.x() + 0.5,
                        block.y(),
                        block.z() + 0.5
                );
                case WEST -> new PerceptionVec3(
                        block.x(),
                        block.y() + 0.5,
                        block.z() + 0.5
                );
                case EAST -> new PerceptionVec3(
                        block.x() + 1.0,
                        block.y() + 0.5,
                        block.z() + 0.5
                );
                case NORTH -> new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 0.5,
                        block.z()
                );
                case SOUTH -> new PerceptionVec3(
                        block.x() + 0.5,
                        block.y() + 0.5,
                        block.z() + 1.0
                );
            };
            return new VisibleBlockFace(
                    new BlockCoordinate(
                            block.x(),
                            block.y(),
                            block.z()
                    ),
                    face.blockId(),
                    face.face().name().toLowerCase(),
                    hit,
                    hit.subtract(eye).length(),
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    face.topSupportAffordance()
            );
        }
    }

    static final class RecordingCoreActuator
            implements CoreSkillActuator {
        final MutableFrames frames;
        final List<MovementIntent> moves = new ArrayList<>();
        final List<LookIntent> looks = new ArrayList<>();
        int stops;
        int jumps;

        RecordingCoreActuator(final MutableFrames frames) {
            this.frames = frames;
        }

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            moves.add(intent);
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks.add(intent);
            final double yaw = Math.toRadians(intent.yawDegrees());
            final double pitch = Math.toRadians(intent.pitchDegrees());
            frames.look = new PerceptionVec3(
                    -Math.sin(yaw) * Math.cos(pitch),
                    -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch)
            ).normalized();
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
    }

    static final class RecordingInteractionActuator
            implements InteractionSkillActuator {
        final MutableFrames frames;
        final List<BlockInteractionTarget> mines = new ArrayList<>();
        final List<BlockInteractionTarget> placements =
                new ArrayList<>();
        final List<String> equips = new ArrayList<>();
        ActionOutcome beginMiningOutcome = ActionOutcome.COMPLETED;
        ActionOutcome continueMiningOutcome = ActionOutcome.IN_PROGRESS;
        int aborts;

        RecordingInteractionActuator(final MutableFrames frames) {
            this.frames = frames;
        }

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(frames.session);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            mines.add(target);
            return beginMiningOutcome;
        }

        @Override
        public ActionOutcome continueMining() {
            return continueMiningOutcome;
        }

        @Override
        public ActionOutcome abortMining() {
            aborts++;
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            placements.add(target);
            if (frames.publishPlacedTorch) {
                frames.addFace(
                        new GridPos(
                                target.x(),
                                target.y() + 1,
                                target.z()
                        ),
                        TORCH,
                        BlockFace.WEST,
                        TopSupportAffordance.UNKNOWN
                );
            }
            frames.consumeTorch();
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.empty();
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome continueUsing(final ActionHand hand) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome equipMainHand(final String itemId) {
            equips.add(itemId);
            if (itemId.equals(PICKAXE)
                    && frames.pickaxeCount > 0) {
                frames.setMain(PICKAXE);
                return ActionOutcome.COMPLETED;
            }
            if (itemId.equals(TORCH)
                    && frames.torchCount > 0) {
                frames.setMain(TORCH);
                return ActionOutcome.COMPLETED;
            }
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
    }

    record VoxelRecord(
            VoxelKind kind,
            double danger,
            OccupancyEvidence occupancyEvidence
    ) {
    }

    private record FaceRecord(
            GridPos position,
            String blockId,
            BlockFace face,
            TopSupportAffordance topSupportAffordance
    ) {
    }
}
