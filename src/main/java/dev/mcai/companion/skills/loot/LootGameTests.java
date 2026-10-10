package dev.mcai.companion.skills.loot;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.interaction.ServerInteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ServerOwnedInteractionSkillActuator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Development-only real-Forge gates for fair loot skills.
 */
public final class LootGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private LootGameTests() {
    }

    /**
     * Proves that the M1 food skill binds a fairly visible adult cow, attacks
     * it through the normal player path, and succeeds only after vanilla beef
     * enters the headless player's inventory.
     */
    public static void realFoodAnimalHunt(
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
                "Food-hunt gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Food-hunt companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Food-hunt companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                            <= BODY_START_TIMEOUT_TICKS,
                        "Food-hunt companion did not become active"
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
        private static final int START_WINDOW_TICKS = 160;
        private static final int SKILL_WINDOW_TICKS = 700;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerOwnedCoreSkillActuator core;
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final Cow cow;
        private final int swordDamageBefore;
        private final long createdAt;

        private HuntObservedFoodAnimalSkill skill;
        private HuntObservedFoodAnimalParameters parameters;
        private long skillStartedAt = -1;
        private boolean finished;
        private boolean cleaned;

        private Scenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            createdAt = helper.getTick();
            prepareFixture();

            final var server = helper.getLevel().getServer();
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactions =
                    new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID()
                    );
            interactionFrames =
                    new ServerInteractionSkillFrameSource(
                            server,
                            player.getUUID()
                    );
            cow = createCow();
            swordDamageBefore =
                    player.getMainHandItem().getDamageValue();
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 8; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
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
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.IRON_SWORD)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
        }

        private Cow createCow() {
            final Cow created = EntityTypes.COW.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    created != null,
                    "GameTest could not create an adult cow"
            );
            created.setBaby(false);
            created.setNoAi(true);
            created.setHealth(1.0F);
            created.setPos(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 2.8
            );
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(created),
                    "GameTest could not add the adult cow"
            );
            return created;
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (skill == null) {
                    tryStart();
                } else {
                    tickSkill();
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    cow.getEyePosition()
            );
            final SemanticObservation observation = publish();
            final Optional<Integer> targetIndex =
                    observedCowIndex(observation);
            if (targetIndex.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= START_WINDOW_TICKS,
                        "Adult cow never entered fair first-person view"
                );
                return;
            }
            final int index = targetIndex.orElseThrow();
            final VisibleEntity visible =
                    observation.visibleEntities().get(index);
            helper.assertTrue(
                    HuntObservedFoodAnimalSkill
                            .legalFoodAnimalTarget(visible),
                    "Fair cow semantics did not prove adult ownership "
                            + "safety: " + visible.visibleProperties()
            );
            parameters = new HuntObservedFoodAnimalParameters(
                    observation.sequence(),
                    "visible-" + index,
                    "minecraft:beef",
                    600
            );
            final HuntObservedFoodAnimalSkill candidate =
                    new HuntObservedFoodAnimalSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames
                    );
            helper.assertTrue(
                    candidate.preconditions(
                            context(),
                            parameters
                    ).isEmpty(),
                    "Real food-hunt preconditions were rejected"
            );
            candidate.start(context(), parameters);
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill() {
            publish();
            final SkillTickResult result = skill.tick(
                    context(),
                    parameters
            );
            helper.assertTrue(
                    result.status()
                            != SkillTickResult.Status.FAILED,
                    "Real food hunt failed: "
                            + result.failure()
                                    .map(failure -> failure.code())
                                    .orElse("unknown")
            );
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                            <= SKILL_WINDOW_TICKS,
                        "Real food hunt exceeded its bounded window"
                );
                return;
            }
            helper.assertTrue(
                    !cow.isAlive() || cow.isRemoved(),
                    "Food hunt completed before defeating the cow"
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.BEEF) >= 1,
                    "Food hunt did not confirm vanilla beef in inventory"
            );
            helper.assertTrue(
                    player.getMainHandItem().is(Items.IRON_SWORD)
                            && player.getMainHandItem().getDamageValue()
                                > swordDamageBefore,
                    "Food hunt bypassed vanilla weapon durability"
            );
            finished = true;
            helper.succeed();
        }

        private SemanticObservation publish() {
            final SemanticObservation observation =
                    sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            return observation;
        }

        private SkillContext context() {
            return new SkillContext(
                    0,
                    0,
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private static Optional<Integer> observedCowIndex(
                final SemanticObservation observation
        ) {
            for (int index = 0;
                    index < observation.visibleEntities().size();
                    index++) {
                if ("minecraft:cow".equals(
                        observation.visibleEntities()
                                .get(index)
                                .entityTypeId()
                )) {
                    return Optional.of(index);
                }
            }
            return Optional.empty();
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (skill != null && parameters != null) {
                skill.cancel(context(), parameters);
            }
            cow.discard();
        }
    }
}
