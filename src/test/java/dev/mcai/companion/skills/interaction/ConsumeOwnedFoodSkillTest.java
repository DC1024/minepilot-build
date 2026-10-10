package dev.mcai.companion.skills.interaction;

import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConsumeOwnedFoodSkillTest {
    @Test
    void equipsUsesAndRequiresNewInventoryProofOfConsumption() {
        final var frames =
                new InteractionSkillTestFixtures.MutableFrames(
                        frame(12, 1)
                );
        final var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        final var skill = new ConsumeOwnedFoodSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final var parameters = new ConsumeOwnedFoodParameters(
                DimensionRef.OVERWORLD,
                "minecraft:golden_apple"
        );

        assertTrue(
                skill.preconditions(context(100), parameters).isEmpty()
        );
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(
                List.of("minecraft:golden_apple"),
                actuator.equippedMainHandItems
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(
                List.of(ActionHand.MAIN_HAND),
                actuator.itemUses
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103), parameters).status()
        );

        actuator.continueUsingOutcome =
                ActionOutcome.NO_ACTIVE_ACTION;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(104), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(105), parameters).status(),
                "The old inventory snapshot must not claim completion"
        );

        frames.frame = frame(13, 0);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(106), parameters).status()
        );
    }

    private static InteractionSkillFrame frame(
            final long observation,
            final int apples
    ) {
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + observation,
                100 + observation,
                observation,
                SESSION,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of(),
                apples == 0
                        ? List.of()
                        : List.of(new InventoryItemSummary(
                            "minecraft:golden_apple",
                            apples
                        ))
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }
}
