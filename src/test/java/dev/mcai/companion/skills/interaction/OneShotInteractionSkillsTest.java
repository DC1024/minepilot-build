package dev.mcai.companion.skills.interaction;

import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.ENTITY_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.OBSERVED_BLOCK;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.SEQUENCE;
import static dev.mcai.companion.skills.interaction.InteractionSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class OneShotInteractionSkillsTest {
    @Test
    void useBlockDispatchesResolvedVisibleFaceOnce() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new UseBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = new UseBlockParameters(
                DimensionRef.OVERWORLD,
                OBSERVED_BLOCK,
                ActionHand.MAIN_HAND
        );

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.blockTargets.size());
    }

    @Test
    void attackResolvesOpaqueIdWithoutExposingUuid() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new AttackEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var parameters = new AttackEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                "visible-0"
        );

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(java.util.List.of(ENTITY_ID), actuator.attacks);
    }

    @Test
    void interactionResolvesVisibleEntityAndUsesRequestedHand()
            throws Exception {
        final var frames =
                new InteractionSkillTestFixtures.MutableFrames(frame());
        final var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        final var skill = new InteractEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        final var parameters = new InteractEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                "visible-0",
                ActionHand.OFF_HAND
        );

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(java.util.List.of(ENTITY_ID), actuator.interactions);
        assertEquals(
                java.util.List.of(ActionHand.OFF_HAND),
                actuator.interactionHands
        );
    }

    @Test
    void modelLatencyDoesNotExpireStillVisibleInteractionTargets()
            throws Exception {
        final var newer = InteractionSkillTestFixtures.frame(
                SEQUENCE + 1,
                101,
                101,
                InteractionSkillTestFixtures.SESSION,
                true,
                true
        );

        final var blockFrames =
                new InteractionSkillTestFixtures.MutableFrames(frame());
        blockFrames.publish(newer);
        final var blockActuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        final var useBlock = new UseBlockSkill(
                PLAYER_ID,
                blockActuator,
                blockFrames,
                InteractionSkillPolicy.defaults()
        );
        final var blockParameters = new UseBlockParameters(
                DimensionRef.OVERWORLD,
                OBSERVED_BLOCK,
                ActionHand.MAIN_HAND
        );
        assertTrue(
                useBlock.preconditions(
                        context(101),
                        blockParameters
                ).isEmpty()
        );
        useBlock.start(context(101), blockParameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                useBlock.tick(context(102), blockParameters).status()
        );

        final var attackFrames =
                new InteractionSkillTestFixtures.MutableFrames(frame());
        attackFrames.publish(newer);
        final var attackActuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        final var attack = new AttackEntitySkill(
                PLAYER_ID,
                attackActuator,
                attackFrames,
                InteractionSkillPolicy.defaults()
        );
        final var attackParameters = new AttackEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                "visible-0"
        );
        assertTrue(
                attack.preconditions(
                        context(101),
                        attackParameters
                ).isEmpty()
        );
        attack.start(context(101), attackParameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                attack.tick(context(102), attackParameters).status()
        );
        assertEquals(
                java.util.List.of(ENTITY_ID),
                attackActuator.attacks
        );

        final var interactFrames =
                new InteractionSkillTestFixtures.MutableFrames(frame());
        interactFrames.publish(newer);
        final var interactActuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        final var interact = new InteractEntitySkill(
                PLAYER_ID,
                interactActuator,
                interactFrames,
                InteractionSkillPolicy.defaults()
        );
        final var interactParameters = new InteractEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                "visible-0",
                ActionHand.OFF_HAND
        );
        assertTrue(
                interact.preconditions(
                        context(101),
                        interactParameters
                ).isEmpty()
        );
        interact.start(context(101), interactParameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                interact.tick(
                        context(102),
                        interactParameters
                ).status()
        );
        assertEquals(
                java.util.List.of(ENTITY_ID),
                interactActuator.interactions
        );
    }

    @Test
    void forgedAndExpiredObservationIdsNeverDispatch() throws Exception {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var forgedSkill = new AttackEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var forged = new AttackEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                "visible-99"
        );
        assertEquals(
                "attack_entity.target_not_visible",
                forgedSkill.preconditions(context(100), forged)
                        .orElseThrow()
                        .code()
        );

        var expiredSkill = new AttackEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var expired = new AttackEntityParameters(
                DimensionRef.OVERWORLD,
                SEQUENCE - 1,
                "visible-0"
        );
        assertEquals(
                "attack_entity.observation_expired",
                expiredSkill.preconditions(context(100), expired)
                        .orElseThrow()
                        .code()
        );
        assertTrue(actuator.attacks.isEmpty());
    }

    @Test
    void staleBlockSampleIsRejectedBeforeUse() {
        var frames = new InteractionSkillTestFixtures.MutableFrames(frame());
        var actuator =
                new InteractionSkillTestFixtures.RecordingActuator();
        var skill = new UseBlockSkill(
                PLAYER_ID,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
        var expiredTarget = new ObservedBlockTarget(
                SEQUENCE - 1,
                OBSERVED_BLOCK.x(),
                OBSERVED_BLOCK.y(),
                OBSERVED_BLOCK.z(),
                OBSERVED_BLOCK.face()
        );
        var parameters = new UseBlockParameters(
                DimensionRef.OVERWORLD,
                expiredTarget,
                ActionHand.MAIN_HAND
        );

        assertEquals(
                "use_block.observation_expired",
                skill.preconditions(context(100), parameters)
                        .orElseThrow()
                        .code()
        );
        assertTrue(actuator.blockTargets.isEmpty());
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
