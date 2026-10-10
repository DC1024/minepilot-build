package dev.mcai.companion.blackbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.embodiment.AiPlayerManager;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Development-only black-box player used for no-window field trials.
 *
 * <p>This is deliberately dormant unless two independent launch-time gates
 * are present. It logs in through {@link net.minecraft.server.players.PlayerList},
 * submits ordinary {@link ServerboundChatPacket} instances, and observes only
 * packets delivered to that player plus authoritative, player-visible body
 * state. It never reads the companion planner, audit trail, memory database,
 * goals, or skill state.</p>
 */
public final class HeadlessBlackboxModule {
    private static final String ENABLE_PROPERTY = "mcai.blackbox.enabled";
    private static final String INPUT_PROPERTY = "mcai.blackbox.input";
    private static final String OUTPUT_PROPERTY = "mcai.blackbox.output";
    private static final String NAME_PROPERTY = "mcai.blackbox.playerName";
    private static final String WALK_DELAY_PROPERTY =
            "mcai.blackbox.observerWalkDelayTicks";
    private static final String WALK_DURATION_PROPERTY =
            "mcai.blackbox.observerWalkDurationTicks";
    private static final String WALK_YAW_PROPERTY =
            "mcai.blackbox.observerWalkYaw";
    private static final String ACK_ENV = "MCAI_BLACKBOX_ACKNOWLEDGE";
    private static final String ACK_VALUE = "development-only";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static Session active;

    private HeadlessBlackboxModule() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerStartedEvent.BUS.addListener(HeadlessBlackboxModule::onStarted);
        TickEvent.ServerTickEvent.Post.BUS.addListener(
                event -> onTick(event.server())
        );
        ServerStoppingEvent.BUS.addListener(
                event -> close(event.getServer())
        );
        ServerStoppedEvent.BUS.addListener(
                event -> close(event.getServer())
        );
    }

    private static void onStarted(final ServerStartedEvent event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }
        if (!ACK_VALUE.equals(System.getenv(ACK_ENV))) {
            MinecraftAiCompanion.LOGGER.error(
                    "Headless black-box mode was requested without its development acknowledgement"
            );
            return;
        }
        final MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) {
            MinecraftAiCompanion.LOGGER.error(
                    "Headless black-box mode is restricted to dedicated servers"
            );
            return;
        }
        try {
            active = Session.open(server);
        } catch (RuntimeException | IOException exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Unable to start the headless black-box player",
                    exception
            );
        }
    }

    private static void onTick(final MinecraftServer server) {
        final Session session = active;
        if (session == null || session.server != server) {
            return;
        }
        try {
            session.tick();
        } catch (RuntimeException | IOException exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Headless black-box player stopped after an infrastructure failure",
                    exception
            );
            close(server);
        }
    }

    private static void close(final MinecraftServer server) {
        final Session session = active;
        if (session == null || session.server != server) {
            return;
        }
        active = null;
        try {
            session.close();
        } catch (RuntimeException | IOException exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Unable to close the headless black-box player cleanly",
                    exception
            );
        }
    }

    private static final class Session implements AutoCloseable {
        private static final Gson GSON = new GsonBuilder()
                .disableHtmlEscaping()
                .create();
        private static final int SAMPLE_INTERVAL_TICKS = 20;
        private static final int INPUT_INTERVAL_TICKS = 5;
        private static final int MAX_INPUT_LINES = 10_000;
        private static final int MAX_MESSAGE_LENGTH = 256;
        private static final int NEARBY_RADIUS = 16;

        private final MinecraftServer server;
        private final Path input;
        private final BufferedWriter output;
        private final NameAndId identity;
        private final Connection connection;
        private final EmbeddedChannel channel;
        private final ServerGamePacketListenerImpl listener;
        private final ServerPlayer player;
        private final FairPlayerActuator observerActuator;
        private final int observerWalkDelayTicks;
        private final int observerWalkDurationTicks;
        private final float observerWalkYaw;
        private int consumedLines;
        private long tick;
        private boolean observerMotionStopped;
        private boolean closed;

        private Session(
                final MinecraftServer server,
                final Path input,
                final BufferedWriter output,
                final NameAndId identity,
                final Connection connection,
                final EmbeddedChannel channel,
                final ServerGamePacketListenerImpl listener,
                final ServerPlayer player,
                final FairPlayerActuator observerActuator,
                final int observerWalkDelayTicks,
                final int observerWalkDurationTicks,
                final float observerWalkYaw
        ) {
            this.server = server;
            this.input = input;
            this.output = output;
            this.identity = identity;
            this.connection = connection;
            this.channel = channel;
            this.listener = listener;
            this.player = player;
            this.observerActuator = observerActuator;
            this.observerWalkDelayTicks = observerWalkDelayTicks;
            this.observerWalkDurationTicks = observerWalkDurationTicks;
            this.observerWalkYaw = observerWalkYaw;
        }

        static Session open(final MinecraftServer server) throws IOException {
            final String rawName = System.getProperty(
                    NAME_PROPERTY,
                    "BlackBoxPlayer"
            );
            if (!rawName.matches("[A-Za-z0-9_]{3,16}")) {
                throw new IllegalArgumentException(
                        "mcai.blackbox.playerName must match [A-Za-z0-9_]{3,16}"
                );
            }
            final Path input = requiredAbsolutePath(INPUT_PROPERTY);
            final Path outputPath = requiredAbsolutePath(OUTPUT_PROPERTY);
            final Path outputParent = outputPath.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            final BufferedWriter output = Files.newBufferedWriter(
                    outputPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            final UUID uuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + rawName).getBytes(
                            StandardCharsets.UTF_8
                    )
            );
            final GameProfile profile = new GameProfile(uuid, rawName);
            final NameAndId identity = new NameAndId(profile);
            final CommonListenerCookie cookie =
                    CommonListenerCookie.createInitial(profile, false);
            final Connection connection = new Connection(
                    PacketFlow.SERVERBOUND
            );
            final EmbeddedChannel channel = new EmbeddedChannel(connection);
            final PrepareSpawnTask spawnTask = new PrepareSpawnTask(
                    server,
                    identity
            );
            final ServerPlayer player;
            try {
                /*
                 * This is the same configuration task used by a networked
                 * client. It loads persisted player data, resolves a safe
                 * world spawn, acquires the normal PLAYER_SPAWN ticket, and
                 * only then calls PlayerList.placeNewPlayer. Constructing a
                 * ServerPlayer directly leaves it at (0,0,0) on a fresh
                 * world and can bury the black-box observer in terrain.
                 */
                spawnTask.start(ignored -> { });
                server.managedBlock(spawnTask::tick);
                player = spawnTask.spawnPlayer(connection, cookie);
            } finally {
                spawnTask.close();
            }
            final ServerGamePacketListenerImpl listener = player.connection;
            if (listener == null) {
                channel.finishAndReleaseAll();
                output.close();
                throw new IllegalStateException(
                        "Vanilla login did not install a game listener"
                );
            }
            player.setGameMode(GameType.SURVIVAL);
            server.getPlayerList().op(
                    identity,
                    Optional.of(LevelBasedPermissionSet.OWNER),
                    Optional.empty()
            );
            listener.handleAcceptPlayerLoad(
                    new ServerboundPlayerLoadedPacket()
            );
            final Session result = new Session(
                    server,
                    input,
                    output,
                    identity,
                    connection,
                    channel,
                    listener,
                    player,
                    new FairPlayerActuator(player),
                    boundedIntegerProperty(
                            WALK_DELAY_PROPERTY,
                            0,
                            0,
                            72_000
                    ),
                    boundedIntegerProperty(
                            WALK_DURATION_PROPERTY,
                            0,
                            0,
                            72_000
                    ),
                    boundedFloatProperty(
                            WALK_YAW_PROPERTY,
                            player.getYRot(),
                            -180.0F,
                            180.0F
                    )
            );
            result.writeLifecycle("connected");
            return result;
        }

        private static Path requiredAbsolutePath(final String property) {
            final String raw = System.getProperty(property, "").strip();
            if (raw.isEmpty()) {
                throw new IllegalArgumentException(
                        property + " is required in headless black-box mode"
                );
            }
            final Path path = Path.of(raw).normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(
                        property + " must be an absolute path"
                );
            }
            return path;
        }

        private static int boundedIntegerProperty(
                final String name,
                final int defaultValue,
                final int minimum,
                final int maximum
        ) {
            final String raw = System.getProperty(name, "").strip();
            if (raw.isEmpty()) {
                return defaultValue;
            }
            final int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        name + " must be an integer",
                        exception
                );
            }
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be between " + minimum + " and "
                                + maximum
                );
            }
            return value;
        }

        private static float boundedFloatProperty(
                final String name,
                final float defaultValue,
                final float minimum,
                final float maximum
        ) {
            final String raw = System.getProperty(name, "").strip();
            if (raw.isEmpty()) {
                return defaultValue;
            }
            final float value;
            try {
                value = Float.parseFloat(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        name + " must be a number",
                        exception
                );
            }
            if (!Float.isFinite(value)
                    || value < minimum
                    || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be between " + minimum + " and "
                                + maximum
                );
            }
            return value;
        }

        void tick() throws IOException {
            if (closed || !connection.isConnected()) {
                return;
            }
            tick++;
            connection.tick();
            if (!connection.isConnected()) {
                return;
            }
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            drainOutboundPackets();
            channel.runPendingTasks();
            advanceObserverMotion();
            if (tick % INPUT_INTERVAL_TICKS == 0L) {
                submitPendingChat();
            }
            if (tick % SAMPLE_INTERVAL_TICKS == 0L) {
                writeObservation();
            }
        }

        /**
         * Moves only the development observer through the same clientless,
         * vanilla-collision input path as the product body. It is disabled by
         * default and exists solely to make a live follow target walk without
         * opening a Minecraft window or assigning coordinates directly.
         */
        private void advanceObserverMotion() {
            if (observerWalkDurationTicks <= 0
                    || tick < observerWalkDelayTicks) {
                return;
            }
            final long motionAge = tick - observerWalkDelayTicks;
            if (motionAge >= observerWalkDurationTicks) {
                if (!observerMotionStopped) {
                    observerActuator.stop();
                    observerActuator.tick();
                    observerMotionStopped = true;
                }
                return;
            }
            final Optional<Float> safeHeading = safeObserverHeading();
            if (safeHeading.isEmpty()) {
                observerActuator.stop();
                observerActuator.tick();
                return;
            }
            final float heading = safeHeading.orElseThrow();
            observerActuator.turnTo(new LookIntent(
                    heading,
                    0.0F
            ));
            observerActuator.setMovement(new MovementIntent(
                    1.0,
                    0.0,
                    false,
                    false
            ));
            if (player.onGround() && player.horizontalCollision) {
                observerActuator.jump();
            }
            observerActuator.tick();
        }

        /**
         * Keeps the development observer from blindly walking off a cliff.
         * It checks only the next ordinary collision step, preferring the
         * configured heading and then the three cardinal alternatives. The
         * observer never receives a route, teleports, or changes position
         * directly.
         */
        private Optional<Float> safeObserverHeading() {
            final float[] offsets = {0.0F, 90.0F, -90.0F, 180.0F};
            for (float offset : offsets) {
                final float heading = wrapYaw(observerWalkYaw + offset);
                if (hasSafeLevelStep(heading)) {
                    return Optional.of(heading);
                }
            }
            return Optional.empty();
        }

        private boolean hasSafeLevelStep(final float yaw) {
            final double radians = Math.toRadians(yaw);
            final double deltaX = -Math.sin(radians) * 0.72D;
            final double deltaZ = Math.cos(radians) * 0.72D;
            final int feetY = player.blockPosition().getY();
            final BlockPos nextFeet = new BlockPos(
                    (int) Math.floor(player.getX() + deltaX),
                    feetY,
                    (int) Math.floor(player.getZ() + deltaZ)
            );
            final BlockPos nextSupport = nextFeet.below();
            final BlockState support = player.level().getBlockState(
                    nextSupport
            );
            if (support.getCollisionShape(
                            player.level(),
                            nextSupport
                    ).isEmpty()
                    || !support.getFluidState().isEmpty()) {
                return false;
            }
            return player.level().noCollision(
                    player,
                    player.getBoundingBox().move(
                            deltaX,
                            0.0D,
                            deltaZ
                    )
            );
        }

        private static float wrapYaw(final float yaw) {
            float wrapped = yaw % 360.0F;
            if (wrapped >= 180.0F) {
                wrapped -= 360.0F;
            }
            if (wrapped < -180.0F) {
                wrapped += 360.0F;
            }
            return wrapped;
        }

        private void drainOutboundPackets() throws IOException {
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                try {
                    if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                        listener.handleKeepAlive(
                                new ServerboundKeepAlivePacket(
                                        keepAlive.getId()
                                )
                        );
                    } else if (packet
                            instanceof ClientboundPlayerPositionPacket position) {
                        listener.handleAcceptTeleportPacket(
                                new ServerboundAcceptTeleportationPacket(
                                        position.id()
                                )
                        );
                    } else if (packet
                            instanceof ClientboundChunkBatchFinishedPacket) {
                        listener.handleChunkBatchReceived(
                                new ServerboundChunkBatchReceivedPacket(3.5F)
                        );
                    } else if (packet
                            instanceof ClientboundSystemChatPacket chat
                            && !chat.overlay()) {
                        writeChat("system", chat.content().getString());
                    } else if (packet
                            instanceof ClientboundPlayerChatPacket chat) {
                        final Component unsigned = chat.unsignedContent();
                        writeChat(
                                "player",
                                unsigned == null
                                        ? chat.body().content()
                                        : unsigned.getString()
                        );
                    }
                } finally {
                    ReferenceCountUtil.release(packet);
                }
            }
        }

        private void submitPendingChat() throws IOException {
            if (!Files.isRegularFile(input)) {
                return;
            }
            final List<String> lines = Files.readAllLines(
                    input,
                    StandardCharsets.UTF_8
            );
            if (lines.size() > MAX_INPUT_LINES) {
                throw new IOException("Black-box input exceeded line limit");
            }
            while (consumedLines < lines.size()) {
                final String message = lines.get(consumedLines++).strip();
                if (message.isEmpty()) {
                    continue;
                }
                if (message.length() > MAX_MESSAGE_LENGTH) {
                    throw new IOException(
                            "Black-box chat message exceeded 256 characters"
                    );
                }
                listener.handleChat(
                        new ServerboundChatPacket(
                                message,
                                Instant.now(),
                                0L,
                                null,
                                new LastSeenMessages.Update(
                                        0,
                                        new BitSet(),
                                        LastSeenMessages.Update.IGNORE_CHECKSUM
                                )
                        )
                );
                final JsonObject event = baseEvent("chat_sent");
                event.addProperty("message", message);
                write(event);
            }
        }

        private void writeChat(
                final String channelName,
                final String message
        ) throws IOException {
            final JsonObject event = baseEvent("chat_received");
            event.addProperty("channel", channelName);
            event.addProperty("message", message);
            write(event);
        }

        private void writeLifecycle(final String state) throws IOException {
            final JsonObject event = baseEvent("player_lifecycle");
            event.addProperty("state", state);
            write(event);
        }

        private void writeObservation() throws IOException {
            final JsonObject event = baseEvent("observation");
            event.add("human", playerJson(player));
            final Optional<ServerPlayer> body =
                    AiPlayerManager.onlinePlayer(server);
            if (body.isPresent()) {
                final ServerPlayer companion = body.orElseThrow();
                event.add("companion", playerJson(companion));
                event.add("nearbyWorld", nearbyWorld(companion));
            } else {
                event.addProperty("companion", "absent");
            }
            write(event);
        }

        private JsonObject baseEvent(final String type) {
            final JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.addProperty("time", Instant.now().toString());
            event.addProperty("serverTick", server.getTickCount());
            return event;
        }

        private JsonObject playerJson(final ServerPlayer subject) {
            final JsonObject result = new JsonObject();
            result.addProperty("name", subject.getPlainTextName());
            result.addProperty(
                    "dimension",
                    subject.level().dimension().identifier().toString()
            );
            result.addProperty("x", round(subject.getX()));
            result.addProperty("y", round(subject.getY()));
            result.addProperty("z", round(subject.getZ()));
            result.addProperty("yaw", round(subject.getYRot()));
            result.addProperty("pitch", round(subject.getXRot()));
            result.addProperty("health", subject.getHealth());
            result.addProperty("absorption", subject.getAbsorptionAmount());
            result.addProperty("food", subject.getFoodData().getFoodLevel());
            result.addProperty("air", subject.getAirSupply());
            result.addProperty("alive", subject.isAlive());
            result.addProperty("onGround", subject.onGround());
            result.addProperty("selectedHotbarSlot", subject.getInventory().getSelectedSlot());
            result.add("mainHand", itemJson(subject.getMainHandItem()));
            result.add("offHand", itemJson(subject.getOffhandItem()));
            final JsonArray hotbar = new JsonArray();
            final JsonArray inventory = new JsonArray();
            final List<ItemStack> stacks =
                    subject.getInventory().getNonEquipmentItems();
            for (int slot = 0; slot < stacks.size(); slot++) {
                final JsonObject stack = itemJson(stacks.get(slot));
                stack.addProperty("slot", slot);
                if (slot < 9) {
                    hotbar.add(stack);
                }
                if (!stacks.get(slot).isEmpty()) {
                    inventory.add(stack);
                }
            }
            result.add("hotbar", hotbar);
            result.add("inventory", inventory);
            return result;
        }

        private JsonObject nearbyWorld(final ServerPlayer companion) {
            final JsonObject world = new JsonObject();
            final BlockPos feet = companion.blockPosition();
            world.add("feetBlock", blockJson(
                    companion.level().getBlockState(feet),
                    feet
            ));
            world.add("belowBlock", blockJson(
                    companion.level().getBlockState(feet.below()),
                    feet.below()
            ));
            final JsonArray nearbyEntities = new JsonArray();
            final AABB bounds = companion.getBoundingBox().inflate(
                    NEARBY_RADIUS,
                    8.0D,
                    NEARBY_RADIUS
            );
            for (Entity entity : companion.level().getEntities(
                    companion,
                    bounds,
                    entity -> entity.isAlive()
            )) {
                final JsonObject item = new JsonObject();
                item.addProperty(
                        "type",
                        BuiltInRegistries.ENTITY_TYPE
                                .getKey(entity.getType())
                                .toString()
                );
                item.addProperty("distance", round(companion.distanceTo(entity)));
                item.addProperty("x", round(entity.getX()));
                item.addProperty("y", round(entity.getY()));
                item.addProperty("z", round(entity.getZ()));
                item.addProperty("hostile", entity instanceof Enemy);
                if (entity instanceof LivingEntity living) {
                    item.addProperty("health", living.getHealth());
                }
                nearbyEntities.add(item);
            }
            world.add("nearbyEntities", nearbyEntities);
            return world;
        }

        private JsonObject blockJson(
                final BlockState state,
                final BlockPos position
        ) {
            final JsonObject result = new JsonObject();
            result.addProperty("x", position.getX());
            result.addProperty("y", position.getY());
            result.addProperty("z", position.getZ());
            result.addProperty(
                    "id",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
            );
            return result;
        }

        private JsonObject itemJson(final ItemStack stack) {
            final JsonObject result = new JsonObject();
            result.addProperty(
                    "id",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
            );
            result.addProperty("count", stack.getCount());
            result.addProperty("damage", stack.getDamageValue());
            return result;
        }

        private void write(final JsonObject event) throws IOException {
            output.write(GSON.toJson(event));
            output.newLine();
            output.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            writeLifecycle("disconnected");
            server.getPlayerList().deop(identity);
            if (connection.isConnected()) {
                connection.disconnect(Component.literal(
                        "Headless black-box trial complete"
                ));
            }
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
            output.close();
        }

        private static double round(final double value) {
            return Math.rint(value * 100.0D) / 100.0D;
        }
    }
}
