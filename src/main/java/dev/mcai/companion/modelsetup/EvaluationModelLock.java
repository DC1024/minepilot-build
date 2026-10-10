package dev.mcai.companion.modelsetup;

import dev.mcai.companion.runtime.ModelRuntime;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Server-authoritative model edit lock.
 *
 * <p>The world lock survives a restart; the runtime reservation covers the
 * short start transaction before the world lock is committed. Every external
 * model setup entry must consult their union.</p>
 */
public final class EvaluationModelLock {
    private EvaluationModelLock() {
    }

    public static boolean isLocked(
            final MinecraftServer server,
            final ModelRuntime runtime
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(runtime, "runtime");
        return isLocked(
            CompanionWorldData.get(server).evaluationLocked(),
            runtime.snapshot().evaluationModelFrozen()
        );
    }

    static boolean isLocked(
            final boolean persistentWorldLock,
            final boolean runtimeReservation
    ) {
        return persistentWorldLock || runtimeReservation;
    }
}
