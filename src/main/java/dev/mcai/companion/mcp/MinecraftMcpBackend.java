package dev.mcai.companion.mcp;

import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.runtime.RuntimeTickMetrics;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;
import dev.mcai.companion.world.CompanionWorldData;
import dev.mcai.companion.vision.VisionCaptureService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server-thread bridge for the small, high-level MCP surface.
 */
public final class MinecraftMcpBackend implements McpBackend {
    private static final int MAX_CHAT_CHARACTERS = 512;

    private final MinecraftServer server;
    private final CompanionWorldData worldData;
    private final MemoryDatabase memory;
    private final GoalCoordinator goals;
    private final LongSupplier decisionEpoch;
    private final RuntimeTickMetrics tickMetrics;
    private final Optional<VisionCaptureService> visionCapture;

    public MinecraftMcpBackend(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase memory,
        final GoalCoordinator goals,
        final LongSupplier decisionEpoch,
        final RuntimeTickMetrics tickMetrics
    ) {
        this(
            server,
            worldData,
            memory,
            goals,
            decisionEpoch,
            tickMetrics,
            Optional.empty()
        );
    }

    public MinecraftMcpBackend(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase memory,
        final GoalCoordinator goals,
        final LongSupplier decisionEpoch,
        final RuntimeTickMetrics tickMetrics,
        final VisionCaptureService visionCapture
    ) {
        this(
            server,
            worldData,
            memory,
            goals,
            decisionEpoch,
            tickMetrics,
            Optional.of(Objects.requireNonNull(
                visionCapture,
                "visionCapture"
            ))
        );
    }

    private MinecraftMcpBackend(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase memory,
        final GoalCoordinator goals,
        final LongSupplier decisionEpoch,
        final RuntimeTickMetrics tickMetrics,
        final Optional<VisionCaptureService> visionCapture
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.worldData = Objects.requireNonNull(worldData, "worldData");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.decisionEpoch = Objects.requireNonNull(
            decisionEpoch,
            "decisionEpoch"
        );
        this.tickMetrics = Objects.requireNonNull(
            tickMetrics,
            "tickMetrics"
        );
        this.visionCapture = Objects.requireNonNull(
            visionCapture,
            "visionCapture"
        );
    }

    @Override
    public CompletableFuture<JsonElement> call(final String toolName, final JsonObject arguments) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        return switch (toolName) {
            case "observe" -> onServerThread(this::observe);
            case "set_goal" -> onServerThread(() -> setGoal(arguments));
            case "goal_status" -> onServerThread(() -> goalJson(goals.snapshot()));
            case "say" -> onServerThread(() -> say(arguments));
            case "cancel_goal" -> onServerThread(this::cancelGoal);
            case "add_waypoint" -> addWaypoint(arguments);
            case "get_screenshot" -> onServerThread(this::screenshotState);
            case "get_audit_summary" -> auditSummary();
            default -> CompletableFuture.failedFuture(
                new IllegalArgumentException("Unsupported MCP tool")
            );
        };
    }

    private JsonElement observe() {
        final JsonObject result = new JsonObject();
        result.addProperty("goalRevision", worldData.goalRevision());
        result.addProperty("decisionEpoch", decisionEpoch.getAsLong());
        result.addProperty("serverTick", server.getTickCount());
        result.add("goal", goalJson(goals.snapshot()));

        final AiPlayerManager.Status embodiment = AiPlayerManager.status(server);
        final JsonObject body = new JsonObject();
        body.addProperty("state", embodiment.state().name());
        body.addProperty("online", embodiment.online());
        body.addProperty("profileName", embodiment.profileName());
        body.addProperty("failureCode", embodiment.failureCode());
        body.addProperty("sessionGeneration", embodiment.sessionGeneration());

        AiPlayerManager.onlinePlayer(server).ifPresent(player -> addPlayerObservation(body, player));
        result.add("companion", body);
        result.addProperty(
            "provenance",
            "server-authoritative companion body; no observer camera, seed, structure lookup, or hidden container data"
        );
        return result;
    }

    private JsonElement setGoal(final JsonObject arguments) {
        final String goal = requiredString(arguments, "goal", GoalCoordinator.MAX_GOAL_CHARACTERS);
        final GoalCoordinator.MutationResult mutation = goals.setGoal(goal, GoalSource.MCP);
        return mutationJson(mutation);
    }

    private JsonElement cancelGoal() {
        return mutationJson(goals.requestCancel(GoalSource.MCP));
    }

    private JsonElement say(final JsonObject arguments) {
        if (goals.snapshot().externalWritesLocked()) {
            return rejected("evaluation_locked");
        }
        final String message = requiredString(arguments, "message", MAX_CHAT_CHARACTERS);
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("[AI] " + worldData.displayName() + "：" + message),
            false
        );
        final JsonObject result = new JsonObject();
        result.addProperty("accepted", true);
        return result;
    }

    private CompletableFuture<JsonElement> addWaypoint(final JsonObject arguments) {
        return onServerThreadValue(() -> createWaypoint(arguments)).thenCompose(candidate -> {
            if (candidate.waypoint().isEmpty()) {
                return CompletableFuture.completedFuture(candidate.response());
            }
            return memory.waypoints()
                .upsert(candidate.waypoint().orElseThrow())
                .thenApply(ignored -> candidate.response());
        });
    }

    private WaypointCandidate createWaypoint(final JsonObject arguments) {
        if (goals.snapshot().externalWritesLocked()) {
            return new WaypointCandidate(rejected("evaluation_locked"), Optional.empty());
        }
        final Set<String> expected = Set.of("name", "dimension", "x", "y", "z");
        if (!arguments.keySet().equals(expected)) {
            throw new IllegalArgumentException("Waypoint arguments do not match the tool schema");
        }

        final String name = requiredString(arguments, "name", 256);
        final DimensionRef dimension = DimensionRef.parse(
            requiredString(arguments, "dimension", 257)
        );
        final WaypointPoint point = new WaypointPoint(
            requiredNumber(arguments, "x"),
            requiredNumber(arguments, "y"),
            requiredNumber(arguments, "z")
        );
        final Instant now = Instant.now();
        final UUID waypointId = UUID.randomUUID();
        final Waypoint waypoint = new Waypoint(
            waypointId,
            worldData.companionUuid(),
            dimension,
            point,
            name,
            Set.of(),
            "shared",
            worldData.companionUuid(),
            "codex_mcp",
            WaypointProvenance.IMPORTED_WITH_PERMISSION,
            1.0,
            0L,
            WaypointStatus.ACTIVE,
            now,
            now,
            Optional.empty(),
            Optional.empty()
        );
        worldData.markEvaluationContaminated();
        final JsonObject response = new JsonObject();
        response.addProperty("accepted", true);
        response.addProperty("waypointId", waypointId.toString());
        response.addProperty("revision", 0L);
        return new WaypointCandidate(response, Optional.of(waypoint));
    }

    private JsonElement screenshotState() {
        if (visionCapture.isPresent()) {
            final VisionCaptureService capture =
                visionCapture.orElseThrow();
            final var latest = capture.latest().filter(snapshot ->
                Duration.between(
                    snapshot.capturedAt(),
                    Instant.now()
                ).compareTo(Duration.ofSeconds(5)) <= 0
            );
            if (latest.isPresent()) {
                final var snapshot = latest.orElseThrow();
                final JsonObject result = new JsonObject();
                result.addProperty("available", true);
                result.addProperty("code", "ok");
                result.addProperty("mimeType", "image/png");
                result.addProperty("width", snapshot.width());
                result.addProperty("height", snapshot.height());
                result.addProperty(
                    "capturedAt",
                    snapshot.capturedAt().toString()
                );
                result.addProperty(
                    "base64",
                    Base64.getEncoder().encodeToString(snapshot.png())
                );
                result.addProperty(
                    "captureProvenance",
                    "authenticated_client_companion_first_person"
                );
                result.addProperty("modelInput", false);
                return result;
            }
            final VisionCaptureService.RequestState request =
                capture.request();
            final JsonObject result = new JsonObject();
            result.addProperty("available", false);
            result.addProperty("pending", request.pending());
            result.addProperty("accepted", request.accepted());
            result.addProperty("code", request.code());
            result.addProperty("requestId", request.requestId());
            result.addProperty(
                "requiresAuthenticatedClientCapture",
                true
            );
            return result;
        }
        final JsonObject result = new JsonObject();
        result.addProperty("available", false);
        result.addProperty("code", "first_person_capture_not_ready");
        /*
         * Keep the capability boundary explicit.  The current embodiment is
         * a headless ServerPlayer and the Observer screenshot used by the
         * external E2E harness is deliberately not a fair input source for
         * the model.  Returning these fields prevents a caller from treating
         * an unavailable capture as an empty or cached image and makes the
         * required authenticated client-capture work visible to operators.
         */
        result.addProperty("capturePath", "headless_server_player_unavailable");
        result.addProperty("modelInput", false);
        result.addProperty("observerCameraAllowed", false);
        result.addProperty("requiresAuthenticatedClientCapture", true);
        return result;
    }

    private CompletableFuture<JsonElement> auditSummary() {
        return onServerThreadValue(
            () -> new AuditState(
                worldData.goalRevision(),
                worldData.hardcoreDead(),
                worldData.evaluationLocked(),
                worldData.evaluationDragonKilled(),
                worldData.evaluationReturnedFromEnd(),
                worldData.evaluationStartedGameTick(),
                worldData.evaluationFinishedGameTick(),
                worldData.evaluationElapsedTicks(
                    server.overworld().getGameTime()
                ),
                tickMetrics.snapshot()
            )
        ).thenCompose(state -> memory.eventCount().thenApply(count -> {
                final JsonObject result = new JsonObject();
                result.addProperty("eventCount", count);
                result.addProperty("goalRevision", state.goalRevision());
                result.addProperty("hardcoreDead", state.hardcoreDead());
                result.addProperty(
                    "evaluationLocked",
                    state.evaluationLocked()
                );
                result.addProperty(
                    "evaluationDragonKilled",
                    state.evaluationDragonKilled()
                );
                result.addProperty(
                    "evaluationReturnedFromEnd",
                    state.evaluationReturnedFromEnd()
                );
                result.addProperty(
                    "evaluationStartedGameTick",
                    state.evaluationStartedGameTick()
                );
                result.addProperty(
                    "evaluationFinishedGameTick",
                    state.evaluationFinishedGameTick()
                );
                result.addProperty(
                    "evaluationElapsedTicks",
                    state.evaluationElapsedTicks()
                );
                result.addProperty(
                    "runtimeTickSamples",
                    state.tickMetrics().lifetimeSamples()
                );
                result.addProperty(
                    "runtimeTickAverageMicros",
                    state.tickMetrics().lifetimeAverageNanos() / 1_000.0
                );
                result.addProperty(
                    "runtimeTickP95Micros",
                    state.tickMetrics().windowP95Nanos() / 1_000.0
                );
                result.addProperty(
                    "runtimeTickMaximumMicros",
                    state.tickMetrics().windowMaximumNanos() / 1_000.0
                );
                result.addProperty(
                    "runtimeTickAverageTargetMet",
                    state.tickMetrics().averageTargetMet()
                );
                result.addProperty(
                    "runtimeTickP95TargetMet",
                    state.tickMetrics().p95TargetMet()
                );
                result.addProperty(
                    "secretPersistenceAuditStatus",
                    "not_runtime_verified"
                );
                result.addProperty(
                    "memoryRejectedOperations",
                    memory.rejectedOperationCount()
                );
                return result;
            }));
    }

    private CompletableFuture<JsonElement> onServerThread(final Supplier<JsonElement> operation) {
        return onServerThreadValue(operation);
    }

    private <T> CompletableFuture<T> onServerThreadValue(final Supplier<T> operation) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        final Runnable task = () -> {
            try {
                future.complete(operation.get());
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        };
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
        return future;
    }

    private static void addPlayerObservation(final JsonObject target, final ServerPlayer player) {
        target.addProperty("dimension", player.level().dimension().identifier().toString());
        target.addProperty("x", roundPosition(player.getX()));
        target.addProperty("y", roundPosition(player.getY()));
        target.addProperty("z", roundPosition(player.getZ()));
        target.addProperty("yaw", player.getYRot());
        target.addProperty("pitch", player.getXRot());
        target.addProperty("health", player.getHealth());
        target.addProperty("absorption", player.getAbsorptionAmount());
        target.addProperty("food", player.getFoodData().getFoodLevel());
        target.addProperty("air", player.getAirSupply());
        target.addProperty("experienceLevel", player.experienceLevel);
        target.add("mainHand", itemJson(player.getMainHandItem()));
        target.add("offHand", itemJson(player.getOffhandItem()));
    }

    private static JsonObject itemJson(final ItemStack stack) {
        final JsonObject item = new JsonObject();
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        item.addProperty("damage", stack.getDamageValue());
        return item;
    }

    private static double roundPosition(final double value) {
        return Math.rint(value * 100.0) / 100.0;
    }

    private static JsonObject mutationJson(final GoalCoordinator.MutationResult mutation) {
        final JsonObject result = goalJson(mutation.snapshot());
        result.addProperty("accepted", mutation.accepted());
        result.addProperty("code", mutation.code());
        return result;
    }

    private static JsonObject goalJson(final GoalSnapshot snapshot) {
        final JsonObject result = new JsonObject();
        snapshot.goalId().ifPresent(id -> result.addProperty("goalId", id.toString()));
        result.addProperty("revision", snapshot.revision());
        result.addProperty("status", snapshot.status().name());
        result.addProperty("source", snapshot.source().name());
        result.addProperty("goal", snapshot.goal());
        result.addProperty("detailCode", snapshot.detailCode());
        result.addProperty("updatedAt", snapshot.updatedAt().toString());
        result.addProperty("externalWritesLocked", snapshot.externalWritesLocked());
        return result;
    }

    private static JsonObject rejected(final String code) {
        final JsonObject result = new JsonObject();
        result.addProperty("accepted", false);
        result.addProperty("code", code);
        return result;
    }

    private static String requiredString(
        final JsonObject arguments,
        final String property,
        final int maximumLength
    ) {
        if (!arguments.has(property) || !arguments.get(property).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string argument: " + property);
        }
        final String value = arguments.get(property).getAsString().strip();
        if (value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid string argument: " + property);
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (Character.isISOControl(character)
                && character != '\n'
                && character != '\t') {
                throw new IllegalArgumentException("Control character in argument: " + property);
            }
        }
        return value;
    }

    private static double requiredNumber(final JsonObject arguments, final String property) {
        if (!arguments.has(property) || !arguments.get(property).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing numeric argument: " + property);
        }
        try {
            final double value = arguments.get(property).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite numeric argument: " + property);
            }
            return value;
        } catch (NumberFormatException | UnsupportedOperationException exception) {
            throw new IllegalArgumentException("Invalid numeric argument: " + property, exception);
        }
    }

    private record WaypointCandidate(
        JsonObject response,
        Optional<Waypoint> waypoint
    ) {
        private WaypointCandidate {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(waypoint, "waypoint");
        }
    }

    private record AuditState(
        long goalRevision,
        boolean hardcoreDead,
        boolean evaluationLocked,
        boolean evaluationDragonKilled,
        boolean evaluationReturnedFromEnd,
        long evaluationStartedGameTick,
        long evaluationFinishedGameTick,
        long evaluationElapsedTicks,
        RuntimeTickMetrics.Snapshot tickMetrics
    ) {
        private AuditState {
            Objects.requireNonNull(tickMetrics, "tickMetrics");
        }
    }
}
