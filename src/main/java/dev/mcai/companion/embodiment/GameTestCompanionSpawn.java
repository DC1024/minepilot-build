package dev.mcai.companion.embodiment;

import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.runtime.CompanionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Release-excluded deterministic spawn bridge for physical Forge GameTests.
 *
 * <p>Every GameTest in one server process shares the companion's vanilla
 * player-data file. Without an explicit anchor, Minecraft correctly prepares
 * the previously saved position and can therefore load a removed fixture's
 * chunk before the next test begins. This bridge creates only a small setup
 * pad, then uses the same bounded safe-spawn locator as a real nearby player.
 * It never participates in production gameplay or model-visible actions.</p>
 */
public final class GameTestCompanionSpawn {
    private static final int PAD_RADIUS = 2;

    private GameTestCompanionSpawn() {
    }

    /**
     * Clears state owned by an earlier fixture before a test that requires an
     * isolated body starts.  GameTest listeners run on the same server thread
     * as production recovery, so removing a body alone is insufficient when
     * its old RUNNING goal can immediately request a replacement on the next
     * tick.  This bridge is excluded from release artifacts and never changes
     * production auto-respawn policy.
     */
    public static void resetForIsolatedFixture(
            final MinecraftServer server
    ) {
        CompanionRuntime.active()
                .filter(runtime -> runtime.server() == server)
                .ifPresent(runtime -> {
                    final GoalStatus status = runtime.goals()
                            .snapshot()
                            .status();
                    if (status == GoalStatus.RUNNING
                            || status == GoalStatus.CANCEL_PENDING) {
                        runtime.goals().markTerminal(
                                GoalStatus.SAFE_IDLE,
                                "gametest_isolation_cleanup"
                        );
                    }
                    runtime.survival().reset();
                    runtime.coreActions().quiesceNow();
                    runtime.interactionActions().quiesceNow();
                    runtime.boatActions().quiesceNow();
                    runtime.minecartActions().quiesceNow();
                    runtime.skillSupervisor().abandonForSessionEnd();
                });
        if (AiPlayerManager.status(server).state()
                != SessionState.ABSENT) {
            AiPlayerManager.requestRemove(server);
        }
    }

    public static AiPlayerManager.OperationResult request(
            final GameTestHelper helper,
            final BlockPos relativeAnchor
    ) {
        final var level = helper.getLevel();
        /*
         * Every physical fixture owns its body.  Centralising the cleanup
         * here also covers Embodiment tests that predate the isolated-body
         * helper and otherwise only registered an end-of-test cleanup.  This
         * is test-only; production login/server-start paths never call this
         * bridge.
         */
        resetForIsolatedFixture(level.getServer());
        final BlockPos anchor =
                helper.absolutePos(relativeAnchor).immutable();
        for (int x = -PAD_RADIUS; x <= PAD_RADIUS; x++) {
            for (int z = -PAD_RADIUS; z <= PAD_RADIUS; z++) {
                level.setBlockAndUpdate(
                        anchor.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                );
                for (int y = 0; y <= 2; y++) {
                    level.setBlockAndUpdate(
                            anchor.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                    );
                }
            }
        }
        return AiPlayerManager.requestSpawnAtFixtureAnchor(
                level.getServer(),
                level,
                anchor,
                0.0F
        );
    }
}
