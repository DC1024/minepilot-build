package dev.mcai.companion.skills.portal;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.interaction.ServerInteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.skills.inventory.ServerInventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTest;
import net.minecraftforge.gametest.GameTestDontPrefix;
import net.minecraftforge.gametest.GameTestNamespace;

/**
 * Development-only real-Forge gate for staged lava casting.
 *
 * <p>The fixture creates only a bounded, already-visible work site and owned
 * starting materials before {@link Scenario#fixtureFrozen} becomes true.
 * After that boundary, every asserted world or inventory transition is made
 * by {@link CastObservedNetherPortalSkill} through the production server
 * actuators. The test never injects an obsidian result, drains a fluid,
 * removes formwork, activates a portal, or changes an inventory while either
 * skill invocation is active.</p>
 */
@GameTestNamespace(MinecraftAiCompanion.MOD_ID)
@GameTestDontPrefix
public final class PortalCastGameTests {
    private static final String STRUCTURE = "forge:empty48x32x48";
    private static final BlockPos TEST_ORIGIN = new BlockPos(16, 8, 16);
    private static final int MAX_TICKS = 5_000;
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private PortalCastGameTests() {
    }

    /**
     * Proves one genuine source-lava bucket chain, owned temporary formwork
     * cleanup, and ignition of the completed minimal frame on Minecraft 26.2.
     */
    @GameTest(
        name = "real_portal_cast_and_light",
        environment = "exclusive_real_portal_cast",
        structure = STRUCTURE,
        maxTicks = MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realPortalCastAndLight(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<Scenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.cleanup();
            }
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });
        GameTestCompanionSpawn.resetForIsolatedFixture(server);

        final var initial = AiPlayerManager.status(server);
        helper.assertTrue(
                initial.state() == SessionState.ABSENT,
                "Portal-cast gate requires an isolated companion body: "
                    + initial
        );
        final var spawn = GameTestCompanionSpawn.request(
                helper,
                TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Portal-cast companion spawn was rejected: "
                    + spawn.code()
        );

        /*
         * A single callback avoids the method-reference key reuse problem in
         * 26.2's onEachTick implementation.
         */
        helper.onEachTick(() -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Portal-cast companion failed to spawn: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                            <= BODY_START_TIMEOUT_TICKS,
                        "Portal-cast companion did not become active"
                );
                return;
            }
            scenario.set(new Scenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    private static final class Scenario {
        private static final int SEMANTIC_INTERVAL_TICKS = 4;
        private static final int OBSERVATION_START_WINDOW_TICKS = 120;
        private static final int CAST_WINDOW_TICKS = 1_800;
        private static final int LIGHT_WINDOW_TICKS = 240;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos anchor;
        private final BlockPos target;
        private final BlockPos waterCell;
        private final BlockPos lavaSource;
        private final List<BlockPos> temporaryFormwork;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerOwnedCoreSkillActuator core;
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final ServerInventorySkillActuator inventory;

        private Stage stage = Stage.SETTLING;
        private long stageStartedAt;
        private long lastSemanticTick = Long.MIN_VALUE;
        private int stableGroundTicks;
        private SemanticObservation latest;
        private CastObservedNetherPortalSkill activeSkill;
        private CastObservedNetherPortalParameters activeParameters;
        private SkillFailure lastStartFailure;
        private int emptyBucketUsesBefore;
        private int lavaBucketUsesBefore;
        private int waterBucketUsesBefore;
        private int dirtUsesBefore;
        private int dirtMinedBefore;
        private int flintUsesBefore;
        private int flintDamageBefore;
        private boolean fixtureFrozen;
        private boolean cleaned;

        private Scenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos anchor
        ) {
            this.helper = helper;
            this.player = player;
            this.anchor = anchor.immutable();
            target = anchor.offset(1, 0, 0);
            waterCell = target.above();
            /*
             * A source recessed into the non-flammable work floor is both
             * physically contained and fairly visible from above. Four
             * same-height full-block walls would conceal every selectable
             * lava face from a legitimate first-person ray.
             */
            lavaSource = anchor.offset(0, -1, -1);
            temporaryFormwork = List.of(
                    target.west(),
                    target.south(),
                    waterCell.east(),
                    waterCell.south()
            );

            final var server = helper.getLevel().getServer();
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactions = new ServerOwnedInteractionSkillActuator(
                    server,
                    player.getUUID()
            );
            interactionFrames = new ServerInteractionSkillFrameSource(
                    server,
                    player.getUUID()
            );
            inventory = new ServerInventorySkillActuator(
                    server,
                    player.getUUID()
            );

            prepareFixture();
            fixtureFrozen = true;
            stageStartedAt = helper.getTick();
            MinecraftAiCompanion.LOGGER.info(
                    "Started real_portal_cast_and_light GameTest"
            );
        }

        private void tick() {
            helper.assertTrue(
                    fixtureFrozen,
                    "Portal-cast fixture was not frozen before execution"
            );
            helper.assertTrue(
                    player.isAlive() && !player.isRemoved(),
                    "Portal-cast companion body disappeared"
            );
            try {
                sampleIfDue();
                switch (stage) {
                    case SETTLING -> settle();
                    case START_CAST -> tryStartCast();
                    case CASTING -> tickActive(CAST_WINDOW_TICKS);
                    case VERIFY_CAST -> verifyCastAndPrepareLight();
                    case START_LIGHT -> tryStartLight();
                    case LIGHTING -> tickActive(LIGHT_WINDOW_TICKS);
                    case VERIFY_LIGHT -> verifyLightAndComplete();
                    case FINISHED -> {
                        // GameTest has already been completed.
                    }
                }
            } finally {
                if (stage != Stage.FINISHED) {
                    core.postServerTick();
                }
            }
        }

        /**
         * FAIRNESS BOUNDARY: this is the only method that mutates the level
         * or seeds inventory. It runs before fixtureFrozen and before either
         * production skill is constructed or started.
         */
        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -4; x <= 6; x++) {
                for (int z = -6; z <= 4; z++) {
                    level.setBlockAndUpdate(
                            anchor.offset(x, -1, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 6; y++) {
                        level.setBlockAndUpdate(
                                anchor.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }

            final List<GridPos> frame =
                    CastObservedNetherPortalSkill.minimumFramePlan(
                            grid(anchor),
                            PortalBuildAxis.X
                    );
            for (int index = 1; index < frame.size(); index++) {
                level.setBlockAndUpdate(
                        block(frame.get(index)),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
            }

            level.setBlockAndUpdate(
                    lavaSource.below(),
                    Blocks.SMOOTH_STONE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    lavaSource,
                    Blocks.LAVA.defaultBlockState()
            );
            /*
             * The casting stance is one block above the containment ring so
             * the player can legitimately see an inward support face after
             * the ring is closed. A ground-level body would have every such
             * face occluded by its own near wall.
             */
            level.setBlockAndUpdate(
                    anchor.offset(1, 0, -2),
                    Blocks.SMOOTH_STONE.defaultBlockState()
            );

            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.BUCKET)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(
                            Items.DIRT,
                            9
                    )
            );
            player.getInventory().setItem(
                    3,
                    new ItemStack(Items.FLINT_AND_STEEL)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    anchor.getX() + 1.5,
                    anchor.getY() + 1.0,
                    anchor.getZ() - 1.9
            );
            face(initialViewTarget());

            emptyBucketUsesBefore = itemUses(Items.BUCKET);
            lavaBucketUsesBefore = itemUses(Items.LAVA_BUCKET);
            waterBucketUsesBefore = itemUses(Items.WATER_BUCKET);
            dirtUsesBefore = itemUses(Items.DIRT);
            dirtMinedBefore = blockMined(Blocks.DIRT);
            flintUsesBefore = itemUses(Items.FLINT_AND_STEEL);
            flintDamageBefore = ownedDamage(Items.FLINT_AND_STEEL);
        }

        private void settle() {
            if (player.onGround()
                    /*
                     * Vanilla retains its gravity component
                     * (about -0.0784) while collision holds a player on a
                     * floor. Requiring the complete velocity vector to reach
                     * zero therefore rejects a genuinely settled body.
                     */
                    && player.getDeltaMovement()
                        .horizontalDistanceSqr() < 1.0E-4) {
                stableGroundTicks++;
            } else {
                stableGroundTicks = 0;
            }
            if (stableGroundTicks >= 3 && latest != null) {
                enter(Stage.START_CAST);
                return;
            }
            bounded(
                    BODY_START_TIMEOUT_TICKS,
                    "Portal-cast companion never settled on its fixture"
            );
        }

        private void tryStartCast() {
            final var parameters =
                    new CastObservedNetherPortalParameters(
                            DimensionRef.OVERWORLD,
                            latest.sequence(),
                            grid(anchor),
                            PortalBuildAxis.X,
                            PortalCastOperation.CAST_NEXT,
                            OptionalInt.of(0),
                            Optional.of(grid(lavaSource))
                    );
            final var candidate = newSkill();
            final Optional<SkillFailure> precondition =
                    candidate.preconditions(context(), parameters);
            if (precondition.isPresent()) {
                lastStartFailure = precondition.orElseThrow();
                if (lastStartFailure.code().endsWith(
                        "visible_lava_source_required"
                )) {
                    face(new Vec3(
                            lavaSource.getX() + 0.5,
                            lavaSource.getY() + 1.0,
                            lavaSource.getZ() + 0.5
                    ));
                } else {
                    face(initialViewTarget());
                }
                bounded(
                        OBSERVATION_START_WINDOW_TICKS,
                        "Fair first-person cast precondition remained "
                            + lastStartFailure.code()
                );
                return;
            }
            candidate.start(context(), parameters);
            activeSkill = candidate;
            activeParameters = parameters;
            enter(Stage.CASTING);
        }

        private void verifyCastAndPrepareLight() {
            helper.assertTrue(
                    helper.getLevel().getBlockState(target)
                            .is(Blocks.OBSIDIAN),
                    "Production bucket chain did not cast real obsidian"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(lavaSource).isAir(),
                    "Production skill did not collect the observed source lava"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(waterCell).isAir(),
                    "Production skill did not recover and drain its water"
            );
            for (BlockPos formwork : temporaryFormwork) {
                helper.assertTrue(
                        helper.getLevel().getBlockState(formwork).isAir(),
                        "Production skill left temporary formwork at "
                            + formwork
                );
            }
            helper.assertTrue(
                    count(Items.BUCKET) == 1
                        && count(Items.WATER_BUCKET) == 1
                        && count(Items.LAVA_BUCKET) == 0,
                    "Real bucket transactions did not preserve the owned "
                        + "empty/water bucket pair"
            );
            helper.assertTrue(
                    itemUses(Items.BUCKET) - emptyBucketUsesBefore == 2,
                    "Source lava and water were not collected through two "
                        + "ordinary empty-bucket uses"
            );
            helper.assertTrue(
                    itemUses(Items.LAVA_BUCKET)
                        - lavaBucketUsesBefore == 1,
                    "Lava was not placed through one ordinary bucket use"
            );
            helper.assertTrue(
                    itemUses(Items.WATER_BUCKET)
                        - waterBucketUsesBefore == 1,
                    "Water was not placed through one ordinary bucket use"
            );
            helper.assertTrue(
                    itemUses(Items.DIRT) - dirtUsesBefore
                        == temporaryFormwork.size()
                        && blockMined(Blocks.DIRT) - dirtMinedBefore
                            == temporaryFormwork.size(),
                    "Temporary containment was not placed and mined through "
                        + "ordinary player statistics"
            );

            face(Vec3.atCenterOf(anchor.offset(1, 2, 0)));
            latest = null;
            lastSemanticTick = Long.MIN_VALUE;
            activeSkill = null;
            activeParameters = null;
            enter(Stage.START_LIGHT);
        }

        private void tryStartLight() {
            if (latest == null) {
                return;
            }
            final var parameters =
                    new CastObservedNetherPortalParameters(
                            DimensionRef.OVERWORLD,
                            latest.sequence(),
                            grid(anchor),
                            PortalBuildAxis.X,
                            PortalCastOperation.LIGHT,
                            OptionalInt.empty(),
                            Optional.empty()
                    );
            final var candidate = newSkill();
            final Optional<SkillFailure> precondition =
                    candidate.preconditions(context(), parameters);
            if (precondition.isPresent()) {
                lastStartFailure = precondition.orElseThrow();
                face(Vec3.atCenterOf(anchor.offset(1, 2, 0)));
                bounded(
                        OBSERVATION_START_WINDOW_TICKS,
                        "Fair first-person lighting precondition remained "
                            + lastStartFailure.code()
                );
                return;
            }
            candidate.start(context(), parameters);
            activeSkill = candidate;
            activeParameters = parameters;
            enter(Stage.LIGHTING);
        }

        private void tickActive(final int maximumTicks) {
            final String before =
                    activeSkill.checkpoint(
                        context(),
                        activeParameters
                    ).payload();
            final SkillTickResult result = activeSkill.tick(
                    context(),
                    activeParameters
            );
            final String after = activeSkill.checkpoint(
                    context(),
                    activeParameters
            ).payload();
            if (result.status() == SkillTickResult.Status.FAILED) {
                throw helper.assertionException(
                        "Production portal-cast skill failed: "
                            + result.failure()
                                .map(SkillFailure::code)
                                .orElse("unknown")
                            + " stage=" + stage
                            + " before=" + before
                            + " after="
                            + after
                            + " targetState="
                            + helper.getLevel().getBlockState(target)
                            + " waterState="
                            + helper.getLevel().getBlockState(waterCell)
                            + " buckets="
                            + count(Items.BUCKET)
                            + "/"
                            + count(Items.LAVA_BUCKET)
                            + "/"
                            + count(Items.WATER_BUCKET)
                );
            }
            if (result.status()
                    == SkillTickResult.Status.COMPLETED) {
                enter(stage == Stage.CASTING
                        ? Stage.VERIFY_CAST
                        : Stage.VERIFY_LIGHT);
                return;
            }
            bounded(
                    maximumTicks,
                    "Production portal-cast skill exceeded its bounded "
                        + "window at " + stage
            );
        }

        private void verifyLightAndComplete() {
            int portalBlocks = 0;
            for (int x = 1; x <= 2; x++) {
                for (int y = 1; y <= 3; y++) {
                    helper.assertTrue(
                            helper.getLevel().getBlockState(
                                anchor.offset(x, y, 0)
                            ).is(Blocks.NETHER_PORTAL),
                            "Vanilla ignition did not fill the complete "
                                + "portal interior"
                    );
                    portalBlocks++;
                }
            }
            for (GridPos frame
                    : CastObservedNetherPortalSkill.minimumFramePlan(
                        grid(anchor),
                        PortalBuildAxis.X
                    )) {
                helper.assertTrue(
                        helper.getLevel().getBlockState(block(frame))
                                .is(Blocks.OBSIDIAN),
                        "Portal ignition changed a minimal frame block"
                );
            }
            helper.assertTrue(
                    portalBlocks == 6
                        && itemUses(Items.FLINT_AND_STEEL)
                            - flintUsesBefore == 1
                        && ownedDamage(Items.FLINT_AND_STEEL)
                            - flintDamageBefore == 1,
                    "Portal activation did not use exactly one real "
                        + "flint-and-steel durability"
            );

            cleanup();
            stage = Stage.FINISHED;
            MinecraftAiCompanion.LOGGER.info(
                    "Completed real_portal_cast_and_light GameTest"
            );
            helper.succeed();
        }

        private CastObservedNetherPortalSkill newSkill() {
            return new CastObservedNetherPortalSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    interactions,
                    interactionFrames,
                    inventory
            );
        }

        private void sampleIfDue() {
            final long now = player.level().getGameTime();
            if (latest != null
                    && now >= lastSemanticTick
                    && now - lastSemanticTick
                        < SEMANTIC_INTERVAL_TICKS) {
                return;
            }
            final SemanticObservation observation =
                    sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            latest = observation;
            lastSemanticTick = now;
        }

        private SkillContext context() {
            return new SkillContext(
                    1,
                    latest == null ? 0 : latest.sequence(),
                    Integer.toUnsignedLong(
                        helper.getLevel().getServer().getTickCount()
                    ),
                    true,
                    true,
                    0.0
            );
        }

        private void face(final Vec3 targetPosition) {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    targetPosition
            );
            player.setYHeadRot(player.getYRot());
        }

        private Vec3 initialViewTarget() {
            return Vec3.atCenterOf(waterCell);
        }

        private int count(final Item item) {
            return player.getInventory().countItem(item);
        }

        private int itemUses(final Item item) {
            return player.getStats().getValue(
                    Stats.ITEM_USED.get(item)
            );
        }

        private int blockMined(final Block block) {
            return player.getStats().getValue(
                    Stats.BLOCK_MINED.get(block)
            );
        }

        private int ownedDamage(final Item item) {
            final List<ItemStack> found = new ArrayList<>();
            for (int slot = 0;
                    slot < player.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                        player.getInventory().getItem(slot);
                if (stack.is(item)) {
                    found.add(stack);
                }
            }
            helper.assertTrue(
                    found.size() == 1,
                    "Expected exactly one owned "
                        + item.getDescriptionId()
            );
            return found.getFirst().getDamageValue();
        }

        private void bounded(
                final int maximumTicks,
                final String message
        ) {
            helper.assertTrue(
                    helper.getTick() - stageStartedAt <= maximumTicks,
                    message
            );
        }

        private void enter(final Stage next) {
            stage = next;
            stageStartedAt = helper.getTick();
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            core.quiesceNow();
            interactions.quiesceNow();
        }
    }

    private static GridPos grid(final BlockPos position) {
        return new GridPos(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private static BlockPos block(final GridPos position) {
        return new BlockPos(
                position.x(),
                position.y(),
                position.z()
        );
    }

    private enum Stage {
        SETTLING,
        START_CAST,
        CASTING,
        VERIFY_CAST,
        START_LIGHT,
        LIGHTING,
        VERIFY_LIGHT,
        FINISHED
    }
}
