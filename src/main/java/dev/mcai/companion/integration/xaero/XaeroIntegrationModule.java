package dev.mcai.companion.integration.xaero;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraftforge.event.ServerChatEvent;

/**
 * Converts an authorized Xaero structured chat share into durable waypoint
 * data. It never cancels chat or executes Xaero's clickable command. When no
 * task is active, a successfully persisted authorized share starts an
 * ordinary coordinate-travel goal; otherwise it leaves the active task
 * untouched.
 */
public final class XaeroIntegrationModule {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private XaeroIntegrationModule() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ServerChatEvent.BUS.addListener(XaeroIntegrationModule::onServerChat);
        }
    }

    private static void onServerChat(final ServerChatEvent event) {
        final String chat = event.getRawText();
        if (XaeroWaypointParser.tryParse(chat).isEmpty()) {
            return;
        }

        final ServerPlayer sender = event.getPlayer();
        final var server = sender.level().getServer();
        final XaeroWaypointReceiver receiver = new XaeroWaypointReceiver(
            new XaeroSenderAuthorizationPolicy(
                configuredSenders(),
                senderId -> {
                    if (server.isDedicatedServer()) {
                        return false;
                    }
                    final ServerPlayer candidate = server.getPlayerList().getPlayer(senderId);
                    return candidate != null
                        && server.isSingleplayerOwner(new NameAndId(candidate.getGameProfile()));
                }
            )
        );
        final XaeroReceiveResult received = receiver.receive(sender.getUUID(), chat);
        if (received.status() == XaeroReceiveStatus.UNAUTHORIZED_SENDER) {
            sender.sendSystemMessage(Component.literal("[AI] This player is not allowed to share AI waypoints."));
            return;
        }
        if (received.status() != XaeroReceiveStatus.ACCEPTED) {
            sender.sendSystemMessage(Component.literal("[AI] The Xaero waypoint was malformed."));
            return;
        }

        final var runtime = CompanionRuntime.active()
            .filter(candidate -> candidate.server() == server);
        if (runtime.isEmpty()) {
            sender.sendSystemMessage(Component.literal("[AI] Companion memory is not ready."));
            return;
        }
        if (runtime.orElseThrow().goals().snapshot().externalWritesLocked()) {
            sender.sendSystemMessage(Component.literal("[AI] Waypoint writes are locked for this evaluation."));
            return;
        }

        final ParsedXaeroWaypoint parsed = received.waypoint().orElseThrow();
        final DimensionRef senderDimension = DimensionRef.parse(
            sender.level().dimension().identifier().toString()
        );
        final Optional<DimensionRef> dimension = parsed.resolveDimension(
            CurrentDimensionPolicy.USE_CALLER_CURRENT,
            senderDimension
        );
        if (dimension.isEmpty()) {
            sender.sendSystemMessage(
                Component.literal("[AI] The shared Xaero dimension is not safely resolvable.")
            );
            return;
        }

        final Instant now = Instant.now();
        final boolean inferredY = parsed.y().isEmpty();
        final double y = parsed.y().isPresent()
            ? parsed.y().getAsInt()
            : sender.getY();
        final Set<String> aliases = parsed.initials().isBlank()
            ? Set.of()
            : Set.of(parsed.initials());
        final Waypoint waypoint = new Waypoint(
            UUID.randomUUID(),
            runtime.orElseThrow().worldData().companionUuid(),
            dimension.orElseThrow(),
            new WaypointPoint(parsed.x(), y, parsed.z()),
            parsed.displayName(),
            aliases,
            "xaero_shared",
            sender.getUUID(),
            inferredY ? "xaero_chat_share_y_inferred" : "xaero_chat_share",
            WaypointProvenance.HUMAN_EXPLICIT,
            inferredY ? 0.80 : 1.0,
            0L,
            WaypointStatus.ACTIVE,
            now,
            now,
            Optional.empty(),
            Optional.empty()
        );

        runtime.orElseThrow().worldData().markEvaluationContaminated();
        runtime.orElseThrow().memory().waypoints().upsert(waypoint)
            .whenComplete((ignored, failure) -> server.execute(() -> {
                if (failure == null) {
                    final var current = runtime.orElseThrow()
                        .goals()
                        .snapshot();
                    final boolean mayStartTravel =
                        current.status() != GoalStatus.RUNNING
                            && current.status() != GoalStatus.CANCEL_PENDING;
                    if (mayStartTravel) {
                        final String travelGoal = """
                            前往已授权玩家共享的坐标；不得传送或读取小地图隐藏数据。
                            dimension=%s, x=%.3f, y=%.3f, z=%.3f。
                            抵达目标三格内，若目标点危险则停在最近的安全站立点。
                            """.formatted(
                                waypoint.dimension().id(),
                                (double) parsed.x(),
                                y,
                                (double) parsed.z()
                            ).strip();
                        final var started = runtime.orElseThrow()
                            .goals()
                            .setGoal(travelGoal, GoalSource.PLAYER_CHAT);
                        sender.sendSystemMessage(Component.literal(
                            started.accepted()
                                ? "[AI] Waypoint saved and normal travel started: "
                                    + waypoint.name()
                                : "[AI] Waypoint saved, but travel could not start: "
                                    + started.code()
                        ));
                    } else {
                        sender.sendSystemMessage(Component.literal(
                            "[AI] Waypoint saved without replacing the active task: "
                                + waypoint.name()
                        ));
                    }
                } else {
                    sender.sendSystemMessage(Component.literal(
                        "[AI] Waypoint could not be saved."
                    ));
                }
            }));
    }

    private static Set<UUID> configuredSenders() {
        final Set<UUID> result = new LinkedHashSet<>();
        for (String configured : CompanionConfig.WAYPOINT_ALLOWED_SENDERS.get()) {
            try {
                result.add(UUID.fromString(configured));
            } catch (IllegalArgumentException ignored) {
                // Forge config validation should prevent this; remain fail-closed.
            }
        }
        return Set.copyOf(result);
    }
}
