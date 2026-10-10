package dev.mcai.companion.embodiment;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.slf4j.Logger;

/**
 * Non-blocking login preparation for an explicitly bounded safe spawn.
 *
 * <p>Minecraft's {@code PrepareSpawnTask} correctly prepares the position
 * stored in player data, but its synchronous {@code waitForEntities} handoff
 * can block a headless server thread when no network configuration task is
 * driving the login pipeline (notably on a zero-human dedicated server). This
 * path keeps the same vanilla player-data load and
 * {@code PlayerList.placeNewPlayer} lifecycle while preparing an explicitly
 * bounded safe anchor asynchronously. It is used for both a real login
 * anchor and the saved/world-spawn fallback; no entity or block is spawned
 * directly.</p>
 */
final class AnchoredPlayerSpawn implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PREPARE_CHUNK_RADIUS = 3;

    private final MinecraftServer server;
    private final NameAndId nameAndId;
    private final SafeCompanionSpawnLocator.Anchor anchor;
    private final ChunkPos spawnChunk;
    private Optional<SafeCompanionSpawnLocator.Placement> placement =
            Optional.empty();

    private CompletableFuture<?> chunkLoadFuture;
    private boolean closed;

    AnchoredPlayerSpawn(
            final MinecraftServer server,
            final NameAndId nameAndId,
            final SafeCompanionSpawnLocator.Anchor anchor
    ) {
        this.server = server;
        this.nameAndId = nameAndId;
        this.anchor = anchor;
        this.spawnChunk = ChunkPos.containing(
                anchor.origin()
        );
    }

    boolean tick() {
        if (closed) {
            throw new IllegalStateException(
                    "Anchored spawn preparation is already closed"
            );
        }
        if (chunkLoadFuture == null) {
            chunkLoadFuture = anchor.level()
                    .getChunkSource()
                    .addTicketAndLoadWithRadius(
                            TicketType.PLAYER_SPAWN,
                            spawnChunk,
                            PREPARE_CHUNK_RADIUS
                    );
        }
        if (!chunkLoadFuture.isDone()) {
            return false;
        }
        /*
         * Surface an exceptional load to AiPlayerManager's bounded FAILED
         * state instead of reporting a permanently PREPARING body.
         */
        chunkLoadFuture.join();
        if (placement.isEmpty()) {
            placement = SafeCompanionSpawnLocator.locate(anchor);
            if (placement.isEmpty()) {
                throw new IllegalStateException(
                        "No vanilla-safe companion spawn near anchor"
                );
            }
        }
        return true;
    }

    ServerPlayer spawnPlayer(
            final Connection connection,
            final CommonListenerCookie cookie
    ) {
        if (!tick()) {
            throw new IllegalStateException(
                    "Anchored player spawn was not ready"
            );
        }
        final SafeCompanionSpawnLocator.Placement safePlacement =
                placement.orElseThrow(() -> new IllegalStateException(
                        "Anchored player spawn was not located"
                ));
        safePlacement.level().getChunkSource().addTicketWithRadius(
                TicketType.PLAYER_SPAWN,
                spawnChunk,
                PREPARE_CHUNK_RADIUS
        );
        final ServerPlayer player = new ServerPlayer(
                server,
                safePlacement.level(),
                cookie.gameProfile(),
                cookie.clientInformation()
        );

        try (ProblemReporter.ScopedCollector reporter =
                new ProblemReporter.ScopedCollector(
                        player.problemPath(),
                        LOGGER
                )) {
            final Optional<ValueInput> input = server
                    .getPlayerList()
                    .loadPlayerData(nameAndId)
                    .map(tag -> TagValueInput.create(
                            reporter,
                            server.registryAccess(),
                            tag
                    ));
            input.ifPresent(player::load);
            input.ifPresent(ignored ->
                    ForgeEventFactory.firePlayerLoadingEvent(
                            player,
                            server.getPlayerList()
                                    .getPlayerIo()
                                    .getPlayerDataFolder(),
                            nameAndId.id().toString()
                    ));

            /*
             * Apply once before login so player tickets and the initial
             * clientbound position are anchored, then once after restoring
             * parent-vehicle state so an obsolete mount cannot pull the body
             * back to the saved location.
             */
            player.snapTo(
                    safePlacement.position(),
                    safePlacement.yaw(),
                    0.0F
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            server.getPlayerList().placeNewPlayer(
                    connection,
                    player,
                    cookie
            );
            input.ifPresent(tag -> {
                player.loadAndSpawnEnderPearls(tag);
                player.loadAndSpawnParentVehicle(tag);
            });
            player.stopRiding();
            safePlacement.apply(player);
            return player;
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
