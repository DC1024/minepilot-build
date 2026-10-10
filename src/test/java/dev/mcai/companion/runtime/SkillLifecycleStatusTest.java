package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SkillLifecycleStatusTest {
    @Test
    void parsesOnlyServerSkillLifecycleCodes() {
        final var started = SkillLifecycleStatus.parse(
                "skill_started.follow_entity"
        ).orElseThrow();
        assertEquals(SkillLifecycleStatus.Type.STARTED, started.type());
        assertEquals("follow_entity", started.skillName());
        assertEquals("STARTED:follow_entity", started.key());

        assertTrue(SkillLifecycleStatus.parse("skill_started").isEmpty());
        assertTrue(SkillLifecycleStatus.parse("planner_no_action").isEmpty());
        assertTrue(SkillLifecycleStatus.parse("skill_started.Follow").isEmpty());
    }

    @Test
    void messagesDescribeObservedTransitionsWithoutClaimingCompletionEarly() {
        final var started = SkillLifecycleStatus.parse(
                "skill_started.follow_entity"
        ).orElseThrow();
        final var completed = SkillLifecycleStatus.parse(
                "skill_completed.follow_entity"
        ).orElseThrow();
        final var failed = SkillLifecycleStatus.parse(
                "skill_failed.follow_entity"
        ).orElseThrow();

        assertTrue(started.chineseMessage().contains("开始执行"));
        assertTrue(started.chineseMessage().contains("跟随玩家"));
        assertTrue(completed.chineseMessage().contains("动作完成"));
        assertTrue(failed.chineseMessage().contains("不会假装已经完成"));
    }
}
