package dev.mcai.companion.skills.mining;

import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.initial;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

final class MiningSkillsRegistrationTest {
    @Test
    void registersProductionSkillAndDocumentsFairBoundary() {
        final var frames = initial(TunnelMode.HORIZONTAL);
        final var registry = new SkillRegistry();
        MiningSkills.registerAll(
                registry,
                PLAYER_ID,
                new MiningSkillTestFixtures.RecordingCoreActuator(
                        frames
                ),
                frames::coreCurrent,
                new MiningSkillTestFixtures
                        .RecordingInteractionActuator(frames),
                frames::interactionCurrent,
                frames::inventoryCurrent
        );

        assertTrue(registry.contains(
                MiningSkills.EXCAVATE_SAFE_TUNNEL
        ));
        assertTrue(
                MiningSkills.plannerGuide().contains(
                        "never reads hidden blocks"
                )
        );
        assertTrue(
                MiningSkills.plannerGuide().contains(
                        "target first becomes visible"
                )
        );
    }
}
