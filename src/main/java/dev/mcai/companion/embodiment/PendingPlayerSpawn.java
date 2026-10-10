package dev.mcai.companion.embodiment;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.world.CompanionWorldData;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * Advances asynchronous player spawn preparation without blocking a server
 * tick. The handoff deliberately uses the same {@code ServerPlayer} and
 * {@code PlayerList.placeNewPlayer} lifecycle as vanilla, while avoiding the
 * synchronous entity-wait in {@code PrepareSpawnTask} for a headless body.
 */
final class PendingPlayerSpawn implements AutoCloseable {
    /**
     * A server can finish the vanilla async spawn preparation before an
     * integrated-server client has sent its first login packet.  Keep an
     * unanchored preparation in a short admission window so the login event
     * can still replace it with a bounded safe anchor.  The window is only a
     * grace period: a dedicated server with no humans still receives a real
     * ServerPlayer after it expires.
     */
    static final int UNANCHORED_LOGIN_GRACE_TICKS = 40;

    private final MinecraftServer server;
    private final GameProfile profile;
    private final CommonListenerCookie cookie;
    private final AnchoredPlayerSpawn spawnPreparation;
    private final boolean explicitAnchor;
    private int unanchoredLoginGraceTicks;
    private boolean closed;

    PendingPlayerSpawn(MinecraftServer server, GameProfile profile) {
        this(server, profile, Optional.empty());
    }

    PendingPlayerSpawn(
            final MinecraftServer server,
            final GameProfile profile,
            final Optional<SafeCompanionSpawnLocator.Anchor> spawnAnchor
    ) {
        this.server = server;
        this.profile = AiProfileMarker.markedCopy(profile);
        final Optional<SafeCompanionSpawnLocator.Anchor> checkedAnchor =
                Objects.requireNonNull(
                spawnAnchor,
                "spawnAnchor"
        );
        this.cookie = new CommonListenerCookie(
                this.profile,
                0,
                headlessClientInformation(
                        server.getPlayerList().getViewDistance()
                ),
                false
        );
        this.explicitAnchor = checkedAnchor.isPresent();
        this.spawnPreparation = new AnchoredPlayerSpawn(
                server,
                new NameAndId(this.profile),
                checkedAnchor.orElseGet(() ->
                        savedOrWorldSpawnAnchor(server, this.profile)
                )
        );
        if (!explicitAnchor) {
            this.unanchoredLoginGraceTicks =
                    UNANCHORED_LOGIN_GRACE_TICKS;
        }
    }

    Optional<AiPlayerSession> tick() {
        if (closed) {
            throw new IllegalStateException("Spawn preparation is already closed");
        }
        final boolean ready = spawnPreparation.tick();
        if (!ready) {
            return Optional.empty();
        }
        if (!explicitAnchor && unanchoredLoginGraceTicks > 0) {
            unanchoredLoginGraceTicks--;
            return Optional.empty();
        }

        if (server.getPlayerList().getPlayer(profile.id()) != null) {
            throw new IllegalStateException("Companion UUID is already online");
        }

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        boolean handedOff = false;
        try {
            final ServerPlayer player = spawnPreparation.spawnPlayer(
                    connection,
                    cookie
            );
            verifyEvaluationInitialBody(player);
            AiPlayerSession session = AiPlayerSession.connected(server, profile.id(), connection, channel, player);
            handedOff = true;
            close();
            return Optional.of(session);
        } finally {
            if (!handedOff) {
                connection.disconnect(net.minecraft.network.chat.Component.literal("AI spawn failed"));
                connection.handleDisconnection();
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Returns whether this preparation was explicitly anchored to an online
     * player's safe local position.  An unanchored vanilla preparation may be
     * replaced before it finishes when the first real player logs in; an
     * anchored preparation is already bound to that player's observed spawn
     * area and must not be silently moved.
     */
    boolean anchored() {
        return explicitAnchor;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            spawnPreparation.close();
        }
    }

    /**
     * Chooses the persisted body location when it is available, otherwise
     * the world's vanilla respawn anchor. This is only an initial placement;
     * {@link SafeCompanionSpawnLocator} still validates the complete player
     * collision box and searches a bounded safe ring before login.
     */
    private static SafeCompanionSpawnLocator.Anchor savedOrWorldSpawnAnchor(
            final MinecraftServer server,
            final GameProfile profile
    ) {
        final RespawnData respawn = server.getWorldData()
                .overworldData()
                .getRespawnData();
        ServerLevel fallbackLevel = server.getLevel(respawn.dimension());
        if (fallbackLevel == null) {
            fallbackLevel = server.overworld();
        }
        BlockPos fallbackPos = respawn.pos();
        float fallbackYaw = respawn.yaw();
        try (ProblemReporter.ScopedCollector reporter =
                new ProblemReporter.ScopedCollector(
                        dev.mcai.companion.MinecraftAiCompanion.LOGGER
                )) {
            final Optional<ValueInput> input = server.getPlayerList()
                    .loadPlayerData(new NameAndId(profile))
                    .map(tag -> TagValueInput.create(
                            reporter,
                            server.registryAccess(),
                            tag
                    ));
            final Optional<ServerPlayer.SavedPosition> saved = input
                    .flatMap(value -> value.read(
                            ServerPlayer.SavedPosition.MAP_CODEC
                    ));
            if (saved.isPresent()) {
                final ServerPlayer.SavedPosition position = saved
                        .orElseThrow();
                final ServerLevel savedLevel = position.dimension()
                        .flatMap(key -> Optional.ofNullable(
                                server.getLevel(key)
                        ))
                        .orElse(null);
                if (savedLevel != null) {
                    fallbackLevel = savedLevel;
                }
                fallbackPos = position.position()
                        .map(BlockPos::containing)
                        .orElse(fallbackPos);
                fallbackYaw = position.rotation()
                        .map(rotation -> rotation.x)
                        .orElse(fallbackYaw);
            }
        } catch (RuntimeException ignored) {
            // A corrupt or pre-migration player record must not prevent a
            // fresh body from spawning at the ordinary world spawn.
        }
        return new SafeCompanionSpawnLocator.Anchor(
                fallbackLevel,
                fallbackPos,
                fallbackYaw
        );
    }

    /**
     * A vanilla initial cookie requests only two chunks because an actual
     * client immediately replaces it with its options packet. The headless
     * body has no client and would otherwise retain that bootstrap radius
     * forever, eventually walking beyond its moving world window. Request
     * the server's configured player radius up front while preserving every
     * other vanilla default.
     */
    static ClientInformation headlessClientInformation(
            final int serverViewDistance
    ) {
        final ClientInformation defaults =
                ClientInformation.createDefault();
        final int boundedViewDistance =
                HeadlessViewDistance.requested(serverViewDistance);
        return new ClientInformation(
                defaults.language(),
                boundedViewDistance,
                defaults.chatVisibility(),
                defaults.chatColors(),
                defaults.modelCustomisation(),
                defaults.mainHand(),
                defaults.textFilteringEnabled(),
                defaults.allowsListing(),
                defaults.particleStatus()
        );
    }

    private void verifyEvaluationInitialBody(
            final ServerPlayer player
    ) {
        final CompanionWorldData worldData =
            CompanionWorldData.get(server);
        if (!worldData.evaluationLocked()) {
            return;
        }
        final var abilities = player.getAbilities();
        final boolean clean = player.gameMode
                .getGameModeForPlayer() == GameType.SURVIVAL
            && player.getInventory().isEmpty()
            && player.getEnderChestInventory().isEmpty()
            && player.experienceLevel == 0
            && player.totalExperience == 0
            && player.getActiveEffects().isEmpty()
            && player.getHealth() == player.getMaxHealth()
            && player.getFoodData().getFoodLevel() == 20
            && !abilities.instabuild
            && !abilities.flying
            && !abilities.mayfly
            && !abilities.invulnerable;
        if (!clean) {
            worldData.markEvaluationContaminated();
            throw new IllegalStateException(
                "Evaluation companion body was not fresh survival state"
            );
        }
    }
}
