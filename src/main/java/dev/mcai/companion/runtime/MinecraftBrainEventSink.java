package dev.mcai.companion.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.mcai.companion.brain.BrainEvent;
import dev.mcai.companion.brain.BrainEventSink;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.memory.MemoryEvent;
import dev.mcai.companion.world.CompanionWorldData;
import java.time.Clock;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Routes model speech through an explicit unsigned AI system channel and
 * records bounded orchestration events asynchronously.
 */
public final class MinecraftBrainEventSink implements BrainEventSink {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MinecraftServer server;
    private final CompanionWorldData worldData;
    private final MemoryDatabase memory;
    private final Clock clock;
    private final LongSupplier worldRevision;
    private final RuntimeActionTrace actionTrace;
    private long lastPlannerNoActionStatusRevision = -1L;
    private long lastPlannerNoActionEscalationRevision = -1L;
    private long lastModelAvailabilityStatusRevision = -1L;
    private String lastModelAvailabilityStatusCode = "";
    private long lastModelHaltedStatusRevision = -1L;
    private long lastSafeIdleRejectionStatusRevision = -1L;
    private long lastCompletionWithoutActionStatusRevision = -1L;
    private long lastSkillLifecycleStatusRevision = -1L;
    private String lastSkillLifecycleStatusKey = "";

    public MinecraftBrainEventSink(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase memory,
        final LongSupplier worldRevision,
        final RuntimeActionTrace actionTrace
    ) {
        this(
            server,
            worldData,
            memory,
            worldRevision,
            actionTrace,
            Clock.systemUTC()
        );
    }

    MinecraftBrainEventSink(
        final MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase memory,
        final LongSupplier worldRevision,
        final RuntimeActionTrace actionTrace,
        final Clock clock
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.worldData = Objects.requireNonNull(worldData, "worldData");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.worldRevision = Objects.requireNonNull(
            worldRevision,
            "worldRevision"
        );
        this.actionTrace = Objects.requireNonNull(
            actionTrace,
            "actionTrace"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void emit(final BrainEvent event) {
        Objects.requireNonNull(event, "event");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Brain events must be applied on the server thread");
        }
        final JsonObject payload = new JsonObject();
        final String type;
        if (event instanceof BrainEvent.Speech speech) {
            type = "brain_speech";
            payload.addProperty("requestId", speech.requestId());
            payload.addProperty("message", speech.message());
            server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                    "[AI] " + worldData.displayName() + "：" + speech.message()
                ),
                false
            );
        } else if (event instanceof BrainEvent.Notice notice) {
            type = "brain_notice";
            payload.addProperty("code", notice.code());
            emitModelAvailabilityStatus(notice);
            emitSkillLifecycleStatus(notice);
            if (notice.code().equals("planner_no_action_backoff")
                    && notice.goalRevision()
                        != lastPlannerNoActionStatusRevision
                    && !worldData.evaluationLocked()) {
                /*
                 * A valid speech-only planner response is not evidence that
                 * the body moved.  Keep the player informed once per goal
                 * revision without echoing model text or promising an action;
                 * the next request still has to select an admitted skill.
                 */
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal(
                        "[AI] " + worldData.displayName()
                            + "：我还没有选定可执行动作，正在重新规划。"
                    ),
                    false
                );
                lastPlannerNoActionStatusRevision = notice.goalRevision();
            }
            if (notice.code().equals("planner_no_action_waiting_for_player")
                    && notice.goalRevision()
                        != lastPlannerNoActionEscalationRevision
                    && !worldData.evaluationLocked()) {
                /*
                 * This is deliberately a status, not model speech: no skill
                 * was admitted and the companion must not imply that it is
                 * moving or working.  A new player message wakes the planner
                 * through the normal conversation-priority path.
                 */
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal(
                        "[AI] " + worldData.displayName()
                            + "：我还没有找到可执行动作，请把目标说得更具体一些。"
                    ),
                    false
                );
                lastPlannerNoActionEscalationRevision =
                    notice.goalRevision();
            }
            if ((notice.code().equals(
                        "model_safe_idle_rejected_for_active_goal"
                    ) || notice.code().equals(
                        "evaluation_safe_idle_rejected"
                    ))
                    && notice.goalRevision()
                        != lastSafeIdleRejectionStatusRevision
                    && !worldData.evaluationLocked()) {
                /*
                 * A provider selected the stop enum, but no skill was
                 * accepted and the active task remains authoritative.  Make
                 * that correction visible once instead of leaving a human
                 * staring at a silent body while the planner retries.
                 */
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal(
                        "[AI] " + worldData.displayName()
                            + "：模型误判为暂停，我正在重新规划；当前还没有执行动作。"
                    ),
                    false
                );
                lastSafeIdleRejectionStatusRevision =
                    notice.goalRevision();
            }
            if (notice.code().equals("model_completion_without_action")
                    && notice.goalRevision()
                        != lastCompletionWithoutActionStatusRevision
                    && !worldData.evaluationLocked()) {
                /*
                 * The model tried to self-certify a live gameplay task
                 * before the server accepted even one local skill.  The
                 * completion guard keeps the goal running, but without this
                 * explicit status a player sees the earlier acknowledgement
                 * and reasonably concludes that the body has stalled.
                 */
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal(
                        "[AI] " + worldData.displayName()
                            + "：任务还没有执行任何动作，我不会把口头回复当成完成，正在重新规划。"
                    ),
                    false
                );
                lastCompletionWithoutActionStatusRevision =
                    notice.goalRevision();
            }
        } else if (event instanceof BrainEvent.TaskAccepted taskAccepted) {
            /*
             * Keep the black-box causal link content-free.  The source
             * player and SHA-256 are sufficient for a test oracle to bind a
             * normal chat packet to this goal revision, while the actual
             * player message never enters the durable brain event log.
             */
            type = "conversation_task_accepted";
            payload.addProperty(
                    "senderUuid",
                    taskAccepted.senderId().toString()
            );
            payload.addProperty(
                    "messageSha256",
                    taskAccepted.messageSha256()
            );
            payload.addProperty("intentCode", taskAccepted.intentCode());
        } else if (event instanceof BrainEvent.Usage usage) {
            type = "brain_model_usage";
            payload.addProperty("requestId", usage.requestId());
            payload.addProperty("inputTokens", usage.inputTokens());
            payload.addProperty("outputTokens", usage.outputTokens());
            payload.addProperty("totalTokens", usage.totalTokens());
        } else {
            final BrainEvent.ModelAudit audit =
                (BrainEvent.ModelAudit) event;
            type = switch (audit.stage()) {
                case AI_PERCEPTION_RECEIVED ->
                    "ai_perception_received";
                case MODEL_REQUEST_STARTED ->
                    "model_request_started";
                case MODEL_RESPONSE_RECEIVED ->
                    "model_response_received";
                case DECISION_SCHEMA_VALIDATED ->
                    "decision_schema_validated";
                case DECISION_REVISION_ACCEPTED ->
                    "decision_revision_accepted";
                case SKILL_STARTED -> "skill_started";
            };
            payload.addProperty("requestId", audit.requestId());
            payload.addProperty(
                "observedWorldRevision",
                audit.observedWorldRevision()
            );
            audit.decision().ifPresent(value ->
                payload.addProperty("decision", value.name())
            );
            if (!audit.skillName().isEmpty()) {
                payload.addProperty("skillName", audit.skillName());
            }
            audit.trace().ifPresent(trace -> {
                payload.addProperty(
                    "providerRequestId",
                    trace.providerRequestId()
                );
                payload.addProperty(
                    "protocol",
                    trace.protocol().name()
                );
                payload.addProperty(
                    "httpStatus",
                    trace.httpStatus()
                );
                payload.addProperty(
                    "elapsedMillis",
                    trace.elapsedMillis()
                );
            });
            actionTrace.observe(audit);
        }
        memory.appendEvent(new MemoryEvent(
            clock.instant(),
            type,
            "brain",
            GSON.toJson(payload),
            Math.max(0L, worldRevision.getAsLong()),
            event.goalRevision()
        ));
    }

    /**
     * Keep a normal player informed when a model request failed before it
     * could produce a decision.  The brain still owns retry/backoff and the
     * status never claims that a skill ran.  Only one transient status is
     * emitted for a goal revision; a terminal halt gets its own final line.
     */
    private void emitModelAvailabilityStatus(final BrainEvent.Notice notice) {
        if (worldData.evaluationLocked() || notice.goalRevision() < 0) {
            return;
        }
        final String code = notice.code();
        final boolean transientFailure = code.equals("model_request_timeout")
                || code.equals("model_transport_failure")
                || code.equals("model_transient_failure")
                || code.equals("model_provider_outage_backoff");
        if (transientFailure) {
            if (notice.goalRevision() == lastModelAvailabilityStatusRevision
                    && code.equals(lastModelAvailabilityStatusCode)) {
                return;
            }
            if (notice.goalRevision() == lastModelAvailabilityStatusRevision) {
                return;
            }
            server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                    "[AI] " + worldData.displayName()
                        + "：模型暂时没有响应，我会保持安全并稍后重试；当前还没有执行动作。"
                ),
                false
            );
            lastModelAvailabilityStatusRevision = notice.goalRevision();
            lastModelAvailabilityStatusCode = code;
            return;
        }
        if (code.equals("model_failures_exhausted")
                || code.equals("model_gateway_closed")) {
            if (notice.goalRevision() == lastModelHaltedStatusRevision) {
                return;
            }
            final String message = code.equals("model_gateway_closed")
                    ? "模型连接已关闭，我已暂停自动操作；请在 AI 陪玩设置中验证 API 配置。"
                    : "模型连续失败，我已暂停自动操作；请检查 API 配置后重新发送任务。";
            server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                    "[AI] " + worldData.displayName() + "：" + message
                ),
                false
            );
            lastModelHaltedStatusRevision = notice.goalRevision();
        }
    }

    /**
     * Publish lifecycle updates only after the server-owned skill transition
     * has been recorded.  A repeated audit row cannot spam the player, while
     * distinct skills in one long task remain visible.
     */
    private void emitSkillLifecycleStatus(final BrainEvent.Notice notice) {
        if (worldData.evaluationLocked()) {
            return;
        }
        final var parsed = SkillLifecycleStatus.parse(notice.code());
        if (parsed.isEmpty()) {
            return;
        }
        final SkillLifecycleStatus status = parsed.orElseThrow();
        final String key = status.key();
        if (notice.goalRevision() == lastSkillLifecycleStatusRevision
                && key.equals(lastSkillLifecycleStatusKey)) {
            return;
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                        "[AI] " + worldData.displayName() + "："
                                + status.chineseMessage()
                ),
                false
        );
        lastSkillLifecycleStatusRevision = notice.goalRevision();
        lastSkillLifecycleStatusKey = key;
    }
}
