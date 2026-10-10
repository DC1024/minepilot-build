package dev.mcai.e2e.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Test-only real Minecraft client driver.
 *
 * <p>This Mod never calls a production AI API. It joins a loopback server,
 * sends the configured Actor utterance through
 * {@code ClientPacketListener.sendChat}, observes normal client chat, and
 * records what an ordinary client can see. The same binary acts as Actor or
 * Observer according to an explicit startup property.</p>
 */
@Mod(McaiE2eClientMod.MOD_ID)
public final class McaiE2eClientMod {
    public static final String MOD_ID = "mcai_e2e_client";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static final Pattern SAFE_TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{4,64}");
    private static final Pattern SAFE_PLAYER_NAME =
            Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_CHAT_CODE_POINTS = 512;
    private static final int MAX_RECEIVED_CODE_POINTS = 2_048;
    private static final int CONNECT_DELAY_TICKS = 20;
    private static final int SEND_AFTER_READY_TICKS = 10;
    private static final int SEND_AFTER_FOLLOW_ARRIVAL_TICKS = 10;
    private static final int SAMPLE_INTERVAL_TICKS = 1;
    private static final double FOLLOW_MOVEMENT_THRESHOLD = 2.0;
    private static final double FOLLOW_ARRIVAL_DISTANCE = 4.0;
    private static final String OBSERVER_SCREENSHOT_NAME =
            "observer-rendered.png";
    private static final String ACTOR_SCREENSHOT_NAME =
            "actor-rendered.png";
    private static final int SCREENSHOT_POLL_TIMEOUT_TICKS = 200;

    private final AtomicLong sequence = new AtomicLong();
    private final boolean enabled;
    private final Role role;
    private final String nonce;
    private final String aiName;
    private final String scenario;
    private final String followChat;
    private final String inventoryChat;
    private final ServerAddress serverAddress;
    private final String serverText;
    private final Path eventFile;

    private long ticks;
    private long lastBlackBoxDriverNanos;
    private long readyAtTick = -1L;
    private long followArrivalAtTick = -1L;
    private long aiFollowupOrdinal;
    private boolean connectAttempted;
    private boolean loggedIn;
    private boolean followChatSent;
    private boolean firstAiFollowupReceived;
    private boolean actorObservedFollowArrival;
    private boolean inventoryChatSent;
    private boolean stopped;
    private Vec3 observedAiStart;
    private boolean observerScreenshotRequested;
    private boolean observerScreenshotRecorded;
    private long observerScreenshotRequestedAtTick = -1L;
    private boolean initialAnchorObserved;

    public McaiE2eClientMod() {
        enabled = Boolean.getBoolean("mcai.e2e.enabled");
        if (!enabled) {
            role = Role.DISABLED;
            nonce = "";
            aiName = "";
            scenario = "chat_follow_inventory";
            followChat = "";
            inventoryChat = "";
            serverAddress = ServerAddress.parseString(
                    "127.0.0.1:25565"
            );
            serverText = "";
            eventFile = Path.of(".").toAbsolutePath()
                    .resolve("mcai-e2e-disabled.jsonl");
            return;
        }

        role = Role.parse(requiredProperty("mcai.e2e.role", 16));
        nonce = checked(
                requiredProperty("mcai.e2e.nonce", 64),
                SAFE_TOKEN,
                "nonce"
        );
        aiName = checked(
                requiredProperty("mcai.e2e.aiName", 16),
                SAFE_PLAYER_NAME,
                "AI name"
        );
        scenario = checkedScenario(
                System.getProperty(
                        "mcai.e2e.scenario",
                        "chat_follow_inventory"
                )
        );
        serverText = requiredProperty("mcai.e2e.server", 128);
        serverAddress = ServerAddress.parseString(serverText);
        if (!isAllowedLoopback(serverAddress)) {
            throw new IllegalArgumentException(
                    "E2E client may connect only to numeric IPv4 loopback"
            );
        }
        final Path resultsRoot = validatedResultsRoot(
                requiredProperty("mcai.e2e.resultsRoot", 4_096)
        );
        eventFile = resultsRoot.resolve(
                role.id() + "-client-events.jsonl"
        );
        followChat = role == Role.ACTOR
                ? boundedOutboundChat(
                        requiredProperty("mcai.e2e.chat", 2_048),
                        nonce
                )
                : "";
        inventoryChat = role == Role.ACTOR
                ? boundedOutboundChat(
                        requiredProperty(
                                "mcai.e2e.inventoryChat",
                                2_048
                        ),
                        nonce
                )
                : "";

        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                this::onLogin
        );
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
                this::onLogout
        );
        ClientChatReceivedEvent.BUS.addListener(this::onChat);
        TickEvent.ClientTickEvent.Post.BUS.addListener(
                ignored -> onClientTick()
        );
        if (isBlackBoxChatScenario()) {
            TickEvent.RenderTickEvent.Post.BUS.addListener(
                    ignored -> onBlackBoxRenderTick()
            );
        }
        write("client_mod_ready", event -> {
            event.addProperty("server", serverText);
            event.addProperty("scenario", scenario);
            event.addProperty("minecraftThread", "pending");
        });
    }

    private void onLogin(
            final ClientPlayerNetworkEvent.LoggingIn event
    ) {
        if (!enabled || stopped) {
            return;
        }
        loggedIn = true;
        if (isBlackBoxChatScenario()) {
            /*
             * A black-box run has no privileged server-side Oracle and no
             * synthetic readiness marker. Login is the only prerequisite;
             * the utterance still travels through the ordinary client chat
             * packet below.
             */
            readyAtTick = ticks;
        }
        write("client_logged_in", payload -> {
            payload.addProperty(
                    "playerName",
                    event.getPlayer().getGameProfile().name()
            );
            payload.addProperty(
                    "playerUuid",
                    event.getPlayer().getUUID().toString()
            );
        });
    }

    private void onLogout(
            final ClientPlayerNetworkEvent.LoggingOut event
    ) {
        if (!enabled || stopped) {
            return;
        }
        loggedIn = false;
        write("client_logged_out", payload -> {
        });
    }

    private void onChat(final ClientChatReceivedEvent event) {
        if (!enabled || stopped) {
            return;
        }
        final String message = bounded(
                event.getMessage().getString(),
                MAX_RECEIVED_CODE_POINTS
        );
        write("client_chat_received", payload -> {
            payload.addProperty("message", message);
            payload.addProperty(
                    "senderUuid",
                    event.getSender() == null
                            ? ""
                            : event.getSender().toString()
            );
            payload.addProperty(
                    "playerChat",
                    event instanceof ClientChatReceivedEvent.Player
            );
        });
        final String readyMarker =
                "[[MCAI_E2E_READY:" + nonce + "]]";
        if (message.contains(readyMarker)) {
            readyAtTick = ticks;
            write("scenario_ready_received", payload ->
                    payload.addProperty("marker", readyMarker)
            );
        }
        if (message.contains("[AI]")
                && message.toLowerCase(Locale.ROOT).contains(
                        aiName.toLowerCase(Locale.ROOT)
                )) {
            final long ordinal = ++aiFollowupOrdinal;
            final boolean afterInventoryChat = inventoryChatSent;
            if (role == Role.ACTOR && !afterInventoryChat) {
                firstAiFollowupReceived = true;
            }
            write("ai_chat_followup_received_by_actor", payload -> {
                payload.addProperty("message", message);
                payload.addProperty("actorRole", role == Role.ACTOR);
                payload.addProperty("followupOrdinal", ordinal);
                payload.addProperty(
                        "afterInventoryChat",
                        afterInventoryChat
                );
            });
        }
    }

    private void onClientTick() {
        if (!enabled || stopped) {
            return;
        }
        ticks++;
        final Minecraft minecraft = Minecraft.getInstance();
        pollObserverScreenshot();
        if (!loggedIn
                && minecraft.getConnection() != null
                && minecraft.player != null
                && minecraft.level != null) {
            loggedIn = true;
            if (isBlackBoxChatScenario()) {
                readyAtTick = ticks;
            }
            write("client_login_inferred_from_world", payload -> {
                payload.addProperty(
                        "playerName",
                        minecraft.player.getGameProfile().name()
                );
            });
        }
        if (!connectAttempted
                && ticks >= CONNECT_DELAY_TICKS
                && minecraft.getConnection() == null
                && minecraft.player == null) {
            final Screen parent = minecraft.gui.screen();
            if (parent != null) {
                connectAttempted = true;
                write("client_connect_started", payload ->
                        payload.addProperty("server", serverText)
                );
                final ServerData data = new ServerData(
                        "MCAI E2E loopback",
                        serverText,
                        ServerData.Type.OTHER
                );
                ConnectScreen.startConnecting(
                        parent,
                        minecraft,
                        serverAddress,
                        data,
                        false,
                        null
                );
            }
        }

        if (role == Role.ACTOR
                && !isInitialAnchorScenario()
                && loggedIn
                && !followChatSent
                && readyAtTick >= 0L
                && ticks - readyAtTick >= SEND_AFTER_READY_TICKS
                && minecraft.getConnection() != null) {
            followChatSent = true;
            write("actor_chat_send_started", payload ->
                    payload.addProperty("message", followChat)
            );
            minecraft.getConnection().sendChat(followChat);
            write("actor_chat_sent", payload -> {
                payload.addProperty("message", followChat);
                payload.addProperty("objective", "follow");
            });
        }

        if (loggedIn && ticks % SAMPLE_INTERVAL_TICKS == 0L) {
            sampleVisibleState(minecraft);
        }

        if (role == Role.ACTOR
                && !isInitialAnchorScenario()
                && !isBlackBoxChatScenario()
                && loggedIn
                && followChatSent
                && firstAiFollowupReceived
                && actorObservedFollowArrival
                && !inventoryChatSent
                && followArrivalAtTick >= 0L
                && ticks - followArrivalAtTick
                    >= SEND_AFTER_FOLLOW_ARRIVAL_TICKS
                && minecraft.getConnection() != null) {
            inventoryChatSent = true;
            write("actor_inventory_chat_send_started", payload ->
                    payload.addProperty("message", inventoryChat)
            );
            minecraft.getConnection().sendChat(inventoryChat);
            write("actor_inventory_chat_sent", payload -> {
                payload.addProperty("message", inventoryChat);
                payload.addProperty("objective", "collect_item");
            });
        }
    }

    private void onBlackBoxRenderTick() {
        final long now = System.nanoTime();
        if (lastBlackBoxDriverNanos != 0L
                && now - lastBlackBoxDriverNanos < 50_000_000L) {
            return;
        }
        lastBlackBoxDriverNanos = now;
        onClientTick();
    }

    private void sampleVisibleState(final Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.getConnection() == null) {
            return;
        }
        write("client_world_sample", payload -> {
            payload.addProperty("gameTick", ticks);
            payload.addProperty(
                    "dimension",
                    minecraft.level.dimension()
                            .identifier().toString()
            );
            final JsonObject self = new JsonObject();
            addPosition(self, minecraft.player);
            payload.add("self", self);

            final JsonArray tabNames = new JsonArray();
            minecraft.getConnection().getListedOnlinePlayers()
                    .stream()
                    .map(info -> info.getProfile().name())
                    .filter(name ->
                            name.equals(aiName)
                                    || name.equals(
                                            minecraft.player
                                                    .getGameProfile()
                                                    .name()
                                    )
                    )
                    .sorted()
                    .forEach(tabNames::add);
            payload.add("relevantTabNames", tabNames);

            minecraft.level.players().stream()
                    .filter(player ->
                            player.getGameProfile().name()
                                    .equals(aiName)
                    )
                    .findFirst()
                    .ifPresent(player -> {
                        observeFollowArrival(
                                minecraft,
                                player.position()
                        );
                        observeInitialAnchor(
                                minecraft,
                                player
                        );
                        requestVisibleScreenshot(minecraft);
                        final JsonObject ai = new JsonObject();
                        addPosition(ai, player);
                        ai.addProperty(
                                "mainHand",
                                player.getMainHandItem()
                                        .getItem()
                                        .toString()
                        );
                        ai.addProperty(
                                "offHand",
                                player.getOffhandItem()
                                        .getItem()
                                        .toString()
                        );
                        payload.add("ai", ai);
                    });
        });
    }

    /**
     * Records the client-observed result of the delayed-first-human
     * lifecycle scenario.  The client never moves either player and does not
     * inspect server state; it only compares the two entities that vanilla
     * rendered in its own level.
     */
    private void observeInitialAnchor(
            final Minecraft minecraft,
            final net.minecraft.world.entity.player.Player ai
    ) {
        if (!isInitialAnchorScenario()
                || initialAnchorObserved
                || minecraft.player == null
                || minecraft.level == null
                || !minecraft.level.dimension().equals(
                        ai.level().dimension()
                )) {
            return;
        }
        final double distance = minecraft.player.distanceTo(ai);
        if (distance > 12.0D) {
            return;
        }
        initialAnchorObserved = true;
        write("client_initial_anchor_observed", payload -> {
            payload.addProperty("distance", distance);
            payload.addProperty(
                    "sameDimension",
                    minecraft.player.level().dimension().equals(
                            ai.level().dimension()
                    )
            );
            payload.addProperty("roleObservedBy", role.id());
        });
    }

    private boolean isInitialAnchorScenario() {
        return "delayed_first_human_anchor".equals(scenario);
    }

    private boolean isBlackBoxChatScenario() {
        return "blackbox_chat".equals(scenario);
    }

    /**
     * Capture one real frame only after the Observer has rendered the AI in
     * its own world.  This is audit evidence, never model input, and is kept
     * in the isolated run directory rather than the user's screenshots.
     */
    private void requestVisibleScreenshot(final Minecraft minecraft) {
        if ((role != Role.OBSERVER && !isBlackBoxChatScenario())
                || observerScreenshotRequested
                || minecraft.gameRenderer == null
                || eventFile.getParent() == null) {
            return;
        }
        observerScreenshotRequested = true;
        observerScreenshotRequestedAtTick = ticks;
        write("observer_screenshot_requested", payload ->
                payload.addProperty("file", screenshotPath().toString())
        );
        try {
            Screenshot.grab(
                    eventFile.getParent().toFile(),
                    screenshotName(),
                    minecraft.gameRenderer.mainRenderTarget(),
                    2,
                    ignored -> {
                        // The client tick polls the file and records only a
                        // valid PNG; callback text can contain local paths.
                    }
            );
        } catch (RuntimeException exception) {
            observerScreenshotRecorded = true;
            write("observer_screenshot_failed", payload ->
                    payload.addProperty("reason", "capture_error")
            );
        }
    }

    private void pollObserverScreenshot() {
        if ((role != Role.OBSERVER && !isBlackBoxChatScenario())
                || !observerScreenshotRequested
                || observerScreenshotRecorded) {
            return;
        }
        final Path path = screenshotPath();
        final PngHeader header = readPngHeader(path);
        if (header != null) {
            observerScreenshotRecorded = true;
            write("observer_screenshot_saved", payload -> {
                payload.addProperty("file", path.toString());
                payload.addProperty("bytes", header.bytes());
                payload.addProperty("width", header.width());
                payload.addProperty("height", header.height());
            });
            return;
        }
        if (observerScreenshotRequestedAtTick >= 0L
                && ticks - observerScreenshotRequestedAtTick
                    > SCREENSHOT_POLL_TIMEOUT_TICKS) {
            observerScreenshotRecorded = true;
            write("observer_screenshot_failed", payload ->
                    payload.addProperty("reason", "invalid_or_missing_png")
            );
        }
    }

    private Path screenshotPath() {
        return eventFile.getParent()
                .resolve(Screenshot.SCREENSHOT_DIR)
                .resolve(screenshotName());
    }

    private String screenshotName() {
        final String base = role == Role.OBSERVER
                ? OBSERVER_SCREENSHOT_NAME
                : ACTOR_SCREENSHOT_NAME;
        return nonce + "-" + base;
    }

    private static PngHeader readPngHeader(final Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            final long bytes = Files.size(path);
            if (bytes < 24L || bytes > 20L * 1024L * 1024L) {
                return null;
            }
            final byte[] header;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                header = input.readNBytes(24);
            }
            final byte[] signature = {
                    (byte) 0x89, 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a
            };
            for (int index = 0; index < signature.length; index++) {
                if (header[index] != signature[index]) {
                    return null;
                }
            }
            if (header[12] != 0x49
                    || header[13] != 0x48
                    || header[14] != 0x44
                    || header[15] != 0x52) {
                return null;
            }
            final int width = bigEndianInt(header, 16);
            final int height = bigEndianInt(header, 20);
            if (width <= 0 || height <= 0 || width > 16_384
                    || height > 16_384) {
                return null;
            }
            return new PngHeader(bytes, width, height);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static int bigEndianInt(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private record PngHeader(long bytes, int width, int height) {
    }

    private void observeFollowArrival(
            final Minecraft minecraft,
            final Vec3 aiPosition
    ) {
        if (role != Role.ACTOR
                || readyAtTick < 0L
                || minecraft.player == null) {
            return;
        }
        if (observedAiStart == null) {
            observedAiStart = aiPosition;
            return;
        }
        if (actorObservedFollowArrival) {
            return;
        }
        final double displacement =
                aiPosition.distanceTo(observedAiStart);
        final double actorDistance =
                aiPosition.distanceTo(minecraft.player.position());
        if (displacement < FOLLOW_MOVEMENT_THRESHOLD
                || actorDistance > FOLLOW_ARRIVAL_DISTANCE) {
            return;
        }
        actorObservedFollowArrival = true;
        followArrivalAtTick = ticks;
        write("actor_follow_arrival_observed", payload -> {
            payload.addProperty("aiDisplacement", displacement);
            payload.addProperty("actorDistance", actorDistance);
        });
    }

    private static void addPosition(
            final JsonObject target,
            final net.minecraft.world.entity.player.Player player
    ) {
        target.addProperty("x", player.getX());
        target.addProperty("y", player.getY());
        target.addProperty("z", player.getZ());
        target.addProperty("yaw", player.getYRot());
        target.addProperty("pitch", player.getXRot());
        target.addProperty("onGround", player.onGround());
        target.addProperty("sprinting", player.isSprinting());
        target.addProperty(
                "crouching",
                player.isShiftKeyDown()
        );
        target.addProperty("swimming", player.isSwimming());
    }

    private void write(
            final String type,
            final java.util.function.Consumer<JsonObject> fields
    ) {
        final JsonObject event = new JsonObject();
        event.addProperty("schemaVersion", 1);
        event.addProperty("sequence", sequence.incrementAndGet());
        event.addProperty("atUtc", Instant.now().toString());
        event.addProperty("role", role.id());
        event.addProperty("nonce", nonce);
        event.addProperty("type", type);
        fields.accept(event);
        try {
            Files.createDirectories(eventFile.getParent());
            Files.writeString(
                    eventFile,
                    GSON.toJson(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            stopped = true;
            LOGGER.error(
                    "MCAI E2E client evidence write failed; "
                            + "path and message are withheld",
                    exception
            );
        }
    }

    private static Path validatedResultsRoot(final String raw) {
        final Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Path.of(raw).isAbsolute()
                || path.getNameCount() < 3
                || path.getParent() == null
                || path.equals(path.getRoot())) {
            throw new IllegalArgumentException(
                    "E2E results root must be a narrow absolute directory"
            );
        }
        return path;
    }

    private static String boundedOutboundChat(
            final String value,
            final String requiredNonce
    ) {
        final String bounded = bounded(value, MAX_CHAT_CODE_POINTS);
        if (bounded.isBlank() || !bounded.contains(requiredNonce)) {
            throw new IllegalArgumentException(
                    "Actor chat must contain the configured nonce"
            );
        }
        return bounded;
    }

    private static String bounded(
            final String value,
            final int maximumCodePoints
    ) {
        final String nonNull = Objects.requireNonNull(value, "value");
        final int count = nonNull.codePointCount(0, nonNull.length());
        if (count <= maximumCodePoints) {
            return nonNull;
        }
        return nonNull.substring(
                0,
                nonNull.offsetByCodePoints(0, maximumCodePoints)
        );
    }

    private static String checked(
            final String value,
            final Pattern pattern,
            final String label
    ) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid E2E " + label);
        }
        return value;
    }

    private static String checkedScenario(final String value) {
        if (!"chat_follow_inventory".equals(value)
                && !"delayed_first_human_anchor".equals(value)
                && !"blackbox_chat".equals(value)) {
            throw new IllegalArgumentException(
                    "Unsupported E2E scenario"
            );
        }
        return value;
    }

    private static String requiredProperty(
            final String name,
            final int maximumCharacters
    ) {
        final String value = System.getProperty(name, "");
        if (value.isBlank() || value.length() > maximumCharacters) {
            throw new IllegalArgumentException(
                    "Missing or oversized E2E property " + name
            );
        }
        return value;
    }

    private static boolean isAllowedLoopback(
            final ServerAddress address
    ) {
        final String host = address.getHost();
        if (!host.startsWith("127.")) {
            return false;
        }
        final String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        try {
            for (String octet : octets) {
                final int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return Integer.parseInt(octets[0]) == 127;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private enum Role {
        ACTOR("actor"),
        OBSERVER("observer"),
        DISABLED("disabled");

        private final String id;

        Role(final String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Role parse(final String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "actor" -> ACTOR;
                case "observer" -> OBSERVER;
                default -> throw new IllegalArgumentException(
                        "E2E role must be actor or observer"
                );
            };
        }
    }
}
