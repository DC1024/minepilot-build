package dev.mcai.companion.skills.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
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
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.DropItemParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CastObservedNetherPortalSkillTest {
    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000778");

    @Test
    void productionRegistrationExposesTheStagedCastingSkill() {
        FakeWorld world = FakeWorld.safeCastSite();
        FakeCore core = new FakeCore(world);
        SkillRegistry registry = PortalBuildSkills.registerAll(
                new SkillRegistry(),
                PLAYER,
                core,
                () -> Optional.of(world.coreFrame()),
                new FakeInteractions(world),
                () -> Optional.of(world.interactionFrame()),
                new FakeInventory(world),
                () -> 1
        );

        assertTrue(registry.contains(
                PortalBuildSkills.CAST_OBSERVED_NETHER_PORTAL
        ));
    }

    @Test
    void castsOneBlockThroughFourObservedBucketTransactions() {
        FakeWorld world = FakeWorld.safeCastSite();
        FakeCore core = new FakeCore(world);
        FakeInteractions interactions = new FakeInteractions(world);
        FakeInventory inventory = new FakeInventory(world);
        var skill = new CastObservedNetherPortalSkill(
                PLAYER,
                core,
                () -> Optional.of(world.coreFrame()),
                interactions,
                () -> Optional.of(world.interactionFrame()),
                inventory
        );
        var parameters = world.castParameters();

        assertTrue(skill.preconditions(context(0), parameters).isEmpty());
        skill.start(context(0), parameters);

        SkillTickResult result = null;
        for (int tick = 0; tick < 160; tick++) {
            world.gameTime = tick;
            result = skill.tick(context(tick), parameters);
            world.publishPending();
            if (result.status() != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        final String terminalFailure = result.failure()
                .map(failure -> failure.code())
                .orElse("no terminal failure");
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                result.status(),
                terminalFailure
        );
        assertEquals("minecraft:obsidian", world.blocks.get(world.target));
        assertEquals("minecraft:air", world.blocks.get(world.water));
        assertEquals(1, world.inventory.get("minecraft:bucket"));
        assertEquals(1, world.inventory.get("minecraft:water_bucket"));
        assertEquals(
                List.of(
                        "collect_lava",
                        "place_lava",
                        "place_water",
                        "collect_water"
                ),
                interactions.operations
        );
        assertEquals(0, interactions.directMiningStarts);
    }

    @Test
    void rejectsNetherCastingAndNonSourceLavaBeforeMutation() {
        FakeWorld world = FakeWorld.safeCastSite();
        FakeInteractions interactions = new FakeInteractions(world);
        var skill = new CastObservedNetherPortalSkill(
                PLAYER,
                new FakeCore(world),
                () -> Optional.of(world.coreFrame()),
                interactions,
                () -> Optional.of(world.interactionFrame()),
                new FakeInventory(world)
        );
        var nether = new CastObservedNetherPortalParameters(
                DimensionRef.NETHER,
                world.revision,
                world.anchor,
                PortalBuildAxis.X,
                PortalCastOperation.CAST_NEXT,
                OptionalInt.of(0),
                Optional.of(world.lava)
        );
        world.dimension = DimensionRef.NETHER;
        assertEquals(
                "cast_observed_nether_portal.casting_requires_overworld",
                skill.preconditions(context(0), nether)
                        .orElseThrow()
                        .code()
        );

        world.dimension = DimensionRef.OVERWORLD;
        world.fluidLevels.put(world.lava, "3");
        assertEquals(
                "cast_observed_nether_portal.visible_lava_source_required",
                skill.preconditions(context(0), world.castParameters())
                        .orElseThrow()
                        .code()
        );
        assertTrue(interactions.operations.isEmpty());
    }

    @Test
    void anAlreadyObservedObsidianStepIsIdempotent() {
        FakeWorld world = FakeWorld.safeCastSite();
        world.blocks.put(world.target, "minecraft:obsidian");
        FakeInteractions interactions = new FakeInteractions(world);
        var skill = new CastObservedNetherPortalSkill(
                PLAYER,
                new FakeCore(world),
                () -> Optional.of(world.coreFrame()),
                interactions,
                () -> Optional.of(world.interactionFrame()),
                new FakeInventory(world)
        );
        var parameters = world.castParameters();

        assertTrue(skill.preconditions(context(0), parameters).isEmpty());
        skill.start(context(0), parameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(0), parameters).status()
        );
        assertTrue(interactions.operations.isEmpty());
    }

    @Test
    void startReservesForUnknownFormworkBeforeTurningToInspectIt() {
        FakeWorld world = FakeWorld.safeCastSite();
        for (GridPos formwork : FakeWorld.formwork(world.target)) {
            world.blocks.put(formwork, "minecraft:air");
            world.observed.remove(formwork);
        }
        world.inventory.put("minecraft:dirt", 9);
        var skill = new CastObservedNetherPortalSkill(
                PLAYER,
                new FakeCore(world),
                () -> Optional.of(world.coreFrame()),
                new FakeInteractions(world),
                () -> Optional.of(world.interactionFrame()),
                new FakeInventory(world)
        );

        assertTrue(
                skill.preconditions(
                    context(0),
                    world.castParameters()
                ).isEmpty()
        );

        world.inventory.put("minecraft:dirt", 8);
        assertEquals(
                "cast_observed_nether_portal.nonflammable_formwork_required",
                skill.preconditions(
                    context(0),
                    world.castParameters()
                ).orElseThrow().code()
        );
    }

    @Test
    void completedInstanceCanBindANewerBodySession() {
        FakeWorld world = FakeWorld.safeCastSite();
        world.blocks.put(world.target, "minecraft:obsidian");
        FakeInteractions interactions = new FakeInteractions(world);
        var skill = new CastObservedNetherPortalSkill(
                PLAYER,
                new FakeCore(world),
                () -> Optional.of(world.coreFrame()),
                interactions,
                () -> Optional.of(world.interactionFrame()),
                new FakeInventory(world)
        );
        var first = world.castParameters();

        assertTrue(skill.preconditions(context(0), first).isEmpty());
        skill.start(context(0), first);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(0), first).status()
        );

        world.sessionGeneration = 2;
        world.revision++;
        var rebound = world.castParameters();
        assertTrue(skill.preconditions(context(1), rebound).isEmpty());
        skill.start(context(1), rebound);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), rebound).status()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }

    private static final class FakeWorld {
        final GridPos anchor = new GridPos(0, 64, 0);
        final GridPos target = new GridPos(1, 64, 0);
        final GridPos water = target.above();
        final GridPos lava = new GridPos(3, 64, 0);
        final Map<GridPos, String> blocks = new HashMap<>();
        final Map<GridPos, String> fluidLevels = new HashMap<>();
        final Map<String, Integer> inventory = new HashMap<>();
        final Set<GridPos> observed = new LinkedHashSet<>();
        final PerceptionVec3 position =
                new PerceptionVec3(1.5, 64.0, -2.5);
        final PerceptionVec3 eye =
                new PerceptionVec3(1.5, 65.62, -2.5);

        DimensionRef dimension = DimensionRef.OVERWORLD;
        PerceptionVec3 look = new PerceptionVec3(0.0, 0.0, 1.0);
        String mainHand = "minecraft:air";
        long revision = 42;
        long gameTime;
        long sessionGeneration = 1;
        Runnable pending;

        static FakeWorld safeCastSite() {
            FakeWorld world = new FakeWorld();
            world.blocks.put(world.target, "minecraft:air");
            world.blocks.put(world.water, "minecraft:air");
            world.blocks.put(world.lava, "minecraft:lava");
            world.fluidLevels.put(world.lava, "0");
            for (GridPos formwork : formwork(world.target)) {
                world.blocks.put(formwork, "minecraft:stone");
            }
            world.observed.addAll(world.blocks.keySet());
            world.inventory.put("minecraft:bucket", 1);
            world.inventory.put("minecraft:water_bucket", 1);
            return world;
        }

        CastObservedNetherPortalParameters castParameters() {
            return new CastObservedNetherPortalParameters(
                    DimensionRef.OVERWORLD,
                    revision,
                    anchor,
                    PortalBuildAxis.X,
                    PortalCastOperation.CAST_NEXT,
                    OptionalInt.of(0),
                    Optional.of(lava)
            );
        }

        CoreSkillFrame coreFrame() {
            List<ObservedVoxel> voxels = observed.stream()
                    .map(pos -> new ObservedVoxel(
                            pos,
                            kind(blocks.getOrDefault(
                                    pos,
                                    "minecraft:air"
                            )),
                            0.0,
                            revision
                    ))
                    .toList();
            return new CoreSkillFrame(
                    PLAYER,
                    dimension,
                    gameTime,
                    revision,
                    position,
                    eye,
                    look,
                    true,
                    false,
                    0.0,
                    new LocalNavSnapshot(
                            dimension,
                            revision,
                            voxels
                    ),
                    faces(),
                    20.0F,
                    20.0F,
                    20,
                    inventorySummary(),
                    held(),
                    HeldItemSummary.empty(),
                    List.of(),
                    List.of()
            );
        }

        InteractionSkillFrame interactionFrame() {
            return new InteractionSkillFrame(
                    PLAYER,
                    dimension,
                    gameTime,
                    gameTime,
                    revision,
                    sessionGeneration,
                    held(),
                    HeldItemSummary.empty(),
                    List.of(),
                    faces(),
                    inventorySummary()
            );
        }

        List<VisibleBlockFace> faces() {
            List<VisibleBlockFace> result = new ArrayList<>();
            for (Map.Entry<GridPos, String> entry : blocks.entrySet()) {
                if ("minecraft:air".equals(entry.getValue())) {
                    continue;
                }
                GridPos pos = entry.getKey();
                PerceptionVec3 hit = new PerceptionVec3(
                        pos.x() + 0.5,
                        pos.y() + 1.0,
                        pos.z() + 0.5
                );
                Map<String, String> state =
                        entry.getValue().equals("minecraft:lava")
                                || entry.getValue().equals("minecraft:water")
                                ? Map.of(
                                    "level",
                                    fluidLevels.getOrDefault(pos, "0")
                                )
                                : Map.of();
                result.add(new VisibleBlockFace(
                        new BlockCoordinate(
                                pos.x(),
                                pos.y(),
                                pos.z()
                        ),
                        entry.getValue(),
                        "up",
                        hit,
                        hit.subtract(eye).length(),
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        state
                ));
                if (!entry.getValue().equals("minecraft:lava")
                        && !entry.getValue().equals("minecraft:water")) {
                    final PerceptionVec3 westHit =
                            new PerceptionVec3(
                                    pos.x(),
                                    pos.y() + 0.5,
                                    pos.z() + 0.5
                            );
                    result.add(new VisibleBlockFace(
                            new BlockCoordinate(
                                    pos.x(),
                                    pos.y(),
                                    pos.z()
                            ),
                            entry.getValue(),
                            "west",
                            westHit,
                            westHit.subtract(eye).length(),
                            PerceptionProvenance
                                    .BLOCK_SURFACE_RAY_CLIP,
                            state
                    ));
                }
            }
            return List.copyOf(result);
        }

        HeldItemSummary held() {
            if ("minecraft:air".equals(mainHand)) {
                return HeldItemSummary.empty();
            }
            return new HeldItemSummary(
                    mainHand,
                    Math.max(1, inventory.getOrDefault(mainHand, 1)),
                    0,
                    0
            );
        }

        List<InventoryItemSummary> inventorySummary() {
            return inventory.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> new InventoryItemSummary(
                            entry.getKey(),
                            entry.getValue()
                    ))
                    .toList();
        }

        void publishPending() {
            if (pending != null) {
                Runnable operation = pending;
                pending = null;
                operation.run();
                revision++;
            }
        }

        void changeCount(final String item, final int delta) {
            inventory.compute(item, (ignored, count) ->
                    Math.max(0, (count == null ? 0 : count) + delta)
            );
        }

        private static VoxelKind kind(final String id) {
            return switch (id) {
                case "minecraft:air" -> VoxelKind.AIR;
                case "minecraft:water" -> VoxelKind.WATER;
                case "minecraft:lava" -> VoxelKind.LAVA;
                default -> VoxelKind.SOLID;
            };
        }

        private static Set<GridPos> formwork(final GridPos target) {
            Set<GridPos> result = new LinkedHashSet<>();
            result.add(target.below());
            for (GridPos center : List.of(target, target.above())) {
                result.add(center.offset(1, 0, 0));
                result.add(center.offset(-1, 0, 0));
                result.add(center.offset(0, 0, 1));
                result.add(center.offset(0, 0, -1));
            }
            return result;
        }
    }

    private static final class FakeCore implements CoreSkillActuator {
        private final FakeWorld world;

        private FakeCore(final FakeWorld world) {
            this.world = world;
        }

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            double yaw = Math.toRadians(intent.yawDegrees());
            double pitch = Math.toRadians(intent.pitchDegrees());
            double horizontal = Math.cos(pitch);
            world.look = new PerceptionVec3(
                    -Math.sin(yaw) * horizontal,
                    -Math.sin(pitch),
                    Math.cos(yaw) * horizontal
            ).normalized();
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }

    private static final class FakeInteractions
            implements InteractionSkillActuator {
        private final FakeWorld world;
        private final List<String> operations = new ArrayList<>();
        private int directMiningStarts;

        private FakeInteractions(final FakeWorld world) {
            this.world = world;
        }

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(world.sessionGeneration);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            directMiningStarts++;
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            GridPos clicked = new GridPos(
                    target.x(),
                    target.y(),
                    target.z()
            );
            if ("minecraft:bucket".equals(world.mainHand)
                    && "minecraft:lava".equals(world.blocks.get(clicked))) {
                operations.add("collect_lava");
                world.pending = () -> {
                    world.blocks.put(clicked, "minecraft:air");
                    world.fluidLevels.remove(clicked);
                    world.changeCount("minecraft:bucket", -1);
                    world.changeCount("minecraft:lava_bucket", 1);
                    world.mainHand = "minecraft:lava_bucket";
                };
            } else if ("minecraft:lava_bucket".equals(world.mainHand)) {
                operations.add("place_lava");
                world.pending = () -> {
                    world.blocks.put(world.target, "minecraft:lava");
                    world.fluidLevels.put(world.target, "0");
                    world.changeCount("minecraft:lava_bucket", -1);
                    world.changeCount("minecraft:bucket", 1);
                    world.mainHand = "minecraft:bucket";
                };
            } else if ("minecraft:water_bucket".equals(world.mainHand)) {
                operations.add("place_water");
                world.pending = () -> {
                    world.blocks.put(world.target, "minecraft:obsidian");
                    world.fluidLevels.remove(world.target);
                    world.blocks.put(world.water, "minecraft:water");
                    world.fluidLevels.put(world.water, "0");
                    world.changeCount("minecraft:water_bucket", -1);
                    world.changeCount("minecraft:bucket", 1);
                    world.mainHand = "minecraft:bucket";
                };
            } else if ("minecraft:bucket".equals(world.mainHand)
                    && "minecraft:water".equals(world.blocks.get(clicked))) {
                operations.add("collect_water");
                world.pending = () -> {
                    world.blocks.put(clicked, "minecraft:air");
                    world.fluidLevels.remove(clicked);
                    world.changeCount("minecraft:bucket", -1);
                    world.changeCount("minecraft:water_bucket", 1);
                    world.mainHand = "minecraft:water_bucket";
                };
            } else {
                return ActionOutcome.ITEM_UNAVAILABLE;
            }
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            if ("minecraft:lava_bucket".equals(world.mainHand)) {
                operations.add("place_lava");
                world.pending = () -> {
                    world.blocks.put(world.target, "minecraft:lava");
                    world.fluidLevels.put(world.target, "0");
                    world.changeCount("minecraft:lava_bucket", -1);
                    world.changeCount("minecraft:bucket", 1);
                    world.mainHand = "minecraft:bucket";
                };
                return ActionOutcome.DISPATCHED;
            }
            if ("minecraft:water_bucket".equals(world.mainHand)) {
                operations.add("place_water");
                world.pending = () -> {
                    world.blocks.put(world.target, "minecraft:obsidian");
                    world.fluidLevels.remove(world.target);
                    world.blocks.put(world.water, "minecraft:water");
                    world.fluidLevels.put(world.water, "0");
                    world.changeCount("minecraft:water_bucket", -1);
                    world.changeCount("minecraft:bucket", 1);
                    world.mainHand = "minecraft:bucket";
                };
                return ActionOutcome.DISPATCHED;
            }
            if ("minecraft:bucket".equals(world.mainHand)
                    && "minecraft:lava".equals(
                        world.blocks.get(world.lava)
                    )) {
                operations.add("collect_lava");
                world.pending = () -> {
                    world.blocks.put(world.lava, "minecraft:air");
                    world.fluidLevels.remove(world.lava);
                    world.changeCount("minecraft:bucket", -1);
                    world.changeCount("minecraft:lava_bucket", 1);
                    world.mainHand = "minecraft:lava_bucket";
                };
                return ActionOutcome.DISPATCHED;
            }
            if ("minecraft:bucket".equals(world.mainHand)
                    && "minecraft:water".equals(
                        world.blocks.get(world.water)
                    )) {
                operations.add("collect_water");
                world.pending = () -> {
                    world.blocks.put(world.water, "minecraft:air");
                    world.fluidLevels.remove(world.water);
                    world.changeCount("minecraft:bucket", -1);
                    world.changeCount("minecraft:water_bucket", 1);
                    world.mainHand = "minecraft:water_bucket";
                };
                return ActionOutcome.DISPATCHED;
            }
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
    }

    private static final class FakeInventory
            implements InventorySkillActuator {
        private final FakeWorld world;

        private FakeInventory(final FakeWorld world) {
            this.world = world;
        }

        @Override
        public InventoryOperationResult checkEquip(
                final EquipItemParameters parameters
        ) {
            return world.inventory.getOrDefault(
                    parameters.itemId(),
                    0
            ) > 0
                    ? InventoryOperationResult.success()
                    : InventoryOperationResult.rejected("missing");
        }

        @Override
        public InventoryOperationResult equip(
                final EquipItemParameters parameters
        ) {
            InventoryOperationResult checked = checkEquip(parameters);
            if (checked.succeeded()) {
                world.mainHand = parameters.itemId();
            }
            return checked;
        }

        @Override
        public InventoryOperationResult checkDrop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult drop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult checkCraft(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }

        @Override
        public InventoryOperationResult craftOnce(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.rejected("unused");
        }
    }
}
