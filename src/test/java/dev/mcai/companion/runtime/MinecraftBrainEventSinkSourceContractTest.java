package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the player-visible failure boundary.  A failed model request must not
 * look like a successful idle companion, while evaluation worlds must remain
 * silent.  The production sink is loaded by Forge slices, while this source
 * contract keeps the injected failure-status branch from being removed;
 * direct authenticated-model broadcast evidence still requires the external
 * Actor/Observer gate.
 */
final class MinecraftBrainEventSinkSourceContractTest {
    @Test
    void transientAndTerminalModelFailuresHaveHonestSingleShotStatuses()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "MinecraftBrainEventSink.java"
        ));

        assertTrue(source.contains("emitModelAvailabilityStatus(notice)"));
        assertTrue(source.contains("model_request_timeout"));
        assertTrue(source.contains("model_transport_failure"));
        assertTrue(source.contains("model_transient_failure"));
        assertTrue(source.contains("model_provider_outage_backoff"));
        assertTrue(source.contains("model_failures_exhausted"));
        assertTrue(source.contains("lastModelAvailabilityStatusRevision"));
        assertTrue(source.contains("lastModelHaltedStatusRevision"));
        assertTrue(source.contains("当前还没有执行动作"));
        assertTrue(source.contains("请检查 API 配置后重新发送任务"));
        assertTrue(source.contains("模型连接已关闭"));
        assertTrue(source.contains("model_safe_idle_rejected_for_active_goal"));
        assertTrue(source.contains("模型误判为暂停"));
        assertTrue(source.contains("model_completion_without_action"));
        assertTrue(source.contains("不会把口头回复当成完成"));
        assertTrue(source.contains("emitSkillLifecycleStatus(notice)"));
        assertTrue(source.contains("SkillLifecycleStatus.parse(notice.code())"));
        assertTrue(source.contains("lastSkillLifecycleStatusKey"));
        assertTrue(source.contains("status.chineseMessage()"));
        assertTrue(source.contains("conversation_task_accepted"));
        assertTrue(source.contains("messageSha256"));
        assertFalse(source.contains("taskAccepted.message()"));
        assertTrue(source.contains("worldData.evaluationLocked()"));
        assertFalse(source.contains("模型已完成"));
    }
}
