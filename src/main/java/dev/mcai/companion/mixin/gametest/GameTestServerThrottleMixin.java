package dev.mcai.companion.mixin.gametest;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Development-only production-like server settings for GameTests.
 *
 * <p>Mojang's dedicated GameTest server intentionally omits the ordinary
 * 50 ms tick wait, which is ideal for deterministic local tests but makes
 * wall-clock model latency age item entities and crops thousands of times
 * faster than production. The player view/simulation distances are corrected
 * for every GameTest run; only the optional wall-clock throttle depends on
 * {@code mcai.realtimeGameTest}. This mixin is excluded from release JARs.</p>
 */
@Mixin(GameTestServer.class)
abstract class GameTestServerThrottleMixin {
    @Unique
    private static final int MCAI_PRODUCTION_VIEW_DISTANCE = 10;
    @Unique
    private static final int MCAI_PRODUCTION_SIMULATION_DISTANCE = 10;
    @Unique
    private static final int MCAI_NEAR_ORIGIN_TEST_COORDINATE = -320;

    /**
     * Mojang deliberately scatters dedicated GameTests anywhere in a
     * +/-14,999,992-block square. That is normally useful isolation, but it
     * makes any fair Eye-of-Ender test meaningless: concentric-ring
     * strongholds remain around the world origin, so two normal throws a few
     * hundred blocks apart are effectively parallel at that artificial
     * distance.
     *
     * <p>Use a reproducible near-origin grid only in the development
     * GameTest launcher. Production code still receives no structure
     * coordinate, and this entire mixin package is excluded from release
     * artifacts.</p>
     */
    @Redirect(
        method = "startTests",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;"
                + "nextIntBetweenInclusive(II)I"
        )
    )
    private int mcai$useNearOriginGameTestGrid(
            final RandomSource random,
            final int minimum,
            final int maximum
    ) {
        return MCAI_NEAR_ORIGIN_TEST_COORDINATE;
    }

    /**
     * Mojang's GameTestServer constructs a bare PlayerList and never applies
     * the dedicated-server view/simulation settings. Both distances therefore
     * remain zero. A headless player can appear healthy while its opponent
     * freezes the instant either participant crosses a chunk edge. Give every
     * GameTest launcher production-like distances so combat, travel and
     * zero-human gates exercise the same vanilla player-ticket window as a
     * normal server. This mixin is not packaged in release JARs.
     */
    @Inject(method = "initServer", at = @At("RETURN"))
    private void mcai$configureProductionPlayerDistances(
            final CallbackInfoReturnable<Boolean> callback
    ) {
        if (!callback.getReturnValueZ()) {
            return;
        }
        final GameTestServer server = (GameTestServer) (Object) this;
        server.getPlayerList().setViewDistance(
                MCAI_PRODUCTION_VIEW_DISTANCE
        );
        server.getPlayerList().setSimulationDistance(
                MCAI_PRODUCTION_SIMULATION_DISTANCE
        );
    }

    @Inject(method = "waitUntilNextTick", at = @At("RETURN"))
    private void mcai$waitForProductionTick(final CallbackInfo callback) {
        if (!Boolean.getBoolean("mcai.realtimeGameTest")) {
            return;
        }
        final GameTestServer server = (GameTestServer) (Object) this;
        /*
         * GameTestServer calls halt(false) as soon as its tracker finishes.
         * MinecraftServer.stopServer() then drains outstanding chunk work by
         * repeatedly calling waitUntilNextTick(). Production tick pacing is
         * meaningful only while the server is running; sleeping 50 ms for
         * every shutdown-drain pass turns a production-distance test world
         * into a multi-minute (or apparently stuck) shutdown.
         */
        if (!server.isRunning()) {
            return;
        }
        /*
         * MinecraftServer has already advanced this absolute deadline by one
         * production tick before it invokes waitUntilNextTick(). Pace the
         * otherwise-unthrottled GameTestServer to that same deadline. Waiting
         * at RETURN preserves GameTestServer.runAllTasks(), and subtracting
         * the work already spent in this tick prevents a few milliseconds of
         * server work from accumulating into a false two-second overload
         * every several hundred ticks.
         *
         * If a real tick is late, remaining is non-positive and the loop
         * returns immediately, matching the production server's catch-up
         * behavior instead of hiding the overload behind another 50 ms nap.
         */
        final long nextTickDeadline = server.getNextTickTime();
        long remaining;
        while ((remaining =
                nextTickDeadline - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }
}
