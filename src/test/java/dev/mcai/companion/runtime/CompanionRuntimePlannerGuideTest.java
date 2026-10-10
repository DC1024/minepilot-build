package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CompanionRuntimePlannerGuideTest {
    @Test
    void productionSkillGuideFitsPlannerBoundary() {
        final String guide = CompanionRuntime.coreSkillGuide();

        assertTrue(guide.contains("reach_end_island"));

        assertTrue(
                guide.length()
                        <= MinecraftPlannerInputFactory
                            .MAX_SKILL_GUIDE_CHARACTERS,
                () -> "Production skill guide length was " + guide.length()
        );
        final MinecraftPlannerInputFactory factory = assertDoesNotThrow(
                () -> new MinecraftPlannerInputFactory(
                        new SkillRegistry(),
                        guide
                )
        );
        assertDoesNotThrow(() -> factory.create(
                "production-foundation-prompt",
                new GoalSnapshot(
                        Optional.empty(),
                        1,
                        GoalStatus.RUNNING,
                        GoalSource.PLAYER_CHAT,
                        "建立安全据点并生存到第二天",
                        "",
                        Instant.EPOCH,
                        false
                ),
                new BrainObservation(
                        1,
                        new SkillContext(
                                1,
                                1,
                                1,
                                true,
                                true,
                                0.0
                        ),
                        "{}",
                        "{}"
                )
        ));
    }
}
