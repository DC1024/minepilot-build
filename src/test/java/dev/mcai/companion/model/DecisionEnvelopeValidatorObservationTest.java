package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DecisionEnvelopeValidatorObservationTest {
    private static final DecisionContext CONTEXT = new DecisionContext(
            "request-1",
            7L,
            3L,
            false,
            Map.of()
    );

    @Test
    void acceptsAnExtraSemanticObservationOnlyWithReplan() throws Exception {
        final DecisionEnvelope decision = envelope(
                DecisionKind.REPLAN,
                new RequestedObservation(
                        ObservationKind.SEMANTIC_REFRESH,
                        "Fresh ray sample needed"
                )
        );

        assertEquals(
                decision,
                new DecisionEnvelopeValidator().validate(
                        decision,
                        CONTEXT
                )
        );
    }

    @Test
    void safelyPrefersObservationOverAnActionWhenProviderFillsBoth()
            throws Exception {
        final DecisionEnvelope decision = envelope(
                DecisionKind.SAFE_IDLE,
                new RequestedObservation(
                        ObservationKind.SCREENSHOT_LOW,
                        "Need a picture"
                )
        );

        final DecisionEnvelope accepted =
                new DecisionEnvelopeValidator().validate(
                    decision,
                    CONTEXT
                );

        assertEquals(DecisionKind.REPLAN, accepted.decision());
        assertEquals("", accepted.skillName());
        assertEquals(List.of(), accepted.typedArguments());
        assertEquals(
                decision.requestedObservation(),
                accepted.requestedObservation()
        );
    }

    @Test
    void canonicalizesPollutedPayloadAccordingToItsAuthorityLane()
            throws Exception {
        final DecisionEnvelope polluted = new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                DecisionKind.ASK_PLAYER,
                "walk_to",
                List.of(new SkillArgument("x", "12")),
                new RequestedObservation(
                        ObservationKind.SEMANTIC_REFRESH,
                        "Look again"
                ),
                "我去。",
                0.8
        );
        final DecisionContext conversation = new DecisionContext(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                false,
                Map.of(),
                DecisionLane.CONVERSATION
        );

        assertThrows(
                DecisionValidationException.class,
                () -> new DecisionEnvelopeValidator().validate(
                        polluted,
                        conversation
                )
        );
        final DecisionEnvelope gameplay =
                new DecisionEnvelopeValidator().validate(
                    polluted,
                    CONTEXT
                );
        assertEquals(DecisionKind.REPLAN, gameplay.decision());
        assertEquals("", gameplay.skillName());
        assertEquals(List.of(), gameplay.typedArguments());
        assertEquals(
                polluted.requestedObservation(),
                gameplay.requestedObservation()
        );
    }

    @Test
    void conversationNeverLetsAProviderToolCallBlockGoalPromotion()
            throws Exception {
        final DecisionEnvelope providerToolCall = new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                DecisionKind.START_SKILL,
                "collect_visible_item",
                List.of(new SkillArgument("itemId", "minecraft:oak_log")),
                RequestedObservation.none(),
                "收到，我来处理。",
                0.8
        );
        final DecisionEnvelope accepted =
                new DecisionEnvelopeValidator().validate(
                        providerToolCall,
                        new DecisionContext(
                                CONTEXT.requestId(),
                                CONTEXT.observedWorldRevision(),
                                CONTEXT.goalRevision(),
                                false,
                                Map.of(),
                                DecisionLane.CONVERSATION
                        )
                );

        assertEquals(DecisionKind.REPLAN, accepted.decision());
        assertEquals("", accepted.skillName());
        assertEquals(List.of(), accepted.typedArguments());
        assertEquals("收到，我来处理。", accepted.optionalSpeech());
    }

    @Test
    void conversationRetainsOnlyAValidatedSemanticTaskPlan()
            throws Exception {
        final DecisionContext conversation = new DecisionContext(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                false,
                Map.of(),
                DecisionLane.CONVERSATION
        );
        final DecisionEnvelope encodedTask = new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                DecisionKind.ASK_PLAYER,
                "",
                List.of(
                        new SkillArgument(
                                "goalRouteProfile",
                                "FOUNDATION"
                        ),
                        new SkillArgument(
                                "goalTerminalMilestone",
                                "STONE_TOOL_OBTAINED"
                        )
                ),
                RequestedObservation.none(),
                "收到。",
                0.9
        );

        final DecisionEnvelope accepted =
                new DecisionEnvelopeValidator().validate(
                        encodedTask,
                        conversation
                );

        assertEquals(encodedTask.typedArguments(), accepted.typedArguments());
    }

    @Test
    void phaseOwnedCollectionAliasFollowsTheAdmittedFoundationCompound()
            throws Exception {
        final DecisionEnvelope providerAlias = new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                DecisionKind.START_SKILL,
                "collect_visible_item",
                List.of(new SkillArgument("itemId", "minecraft:raw_iron")),
                RequestedObservation.none(),
                "我先收集铁矿。",
                0.8
        );
        final DecisionEnvelope accepted =
                new DecisionEnvelopeValidator().validate(
                        providerAlias,
                        new DecisionContext(
                                CONTEXT.requestId(),
                                CONTEXT.observedWorldRevision(),
                                CONTEXT.goalRevision(),
                                false,
                                Map.of(
                                        "prepare_iron_toolkit",
                                        arguments -> java.util.Optional.empty()
                                )
                        )
                );

        assertEquals(DecisionKind.START_SKILL, accepted.decision());
        assertEquals("prepare_iron_toolkit", accepted.skillName());
        assertEquals(List.of(), accepted.typedArguments());
    }

    @Test
    void compactGatherClusterAliasFollowsTheAdmittedFoundationCompound()
            throws Exception {
        final DecisionEnvelope providerAlias = new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                DecisionKind.START_SKILL,
                "gather_cluster",
                List.of(new SkillArgument("blockId", "minecraft:iron_ore")),
                RequestedObservation.none(),
                "我先收集矿石。",
                0.8
        );
        final DecisionEnvelope accepted =
                new DecisionEnvelopeValidator().validate(
                        providerAlias,
                new DecisionContext(
                        CONTEXT.requestId(),
                        CONTEXT.observedWorldRevision(),
                        CONTEXT.goalRevision(),
                        false,
                        Map.of(
                                "prepare_iron_toolkit",
                                arguments -> java.util.Optional.empty()
                        ),
                        DecisionLane.GAMEPLAY
                )
        );
        assertEquals("prepare_iron_toolkit", accepted.skillName());
        assertEquals(List.of(), accepted.typedArguments());
    }

    private static DecisionEnvelope envelope(
            final DecisionKind kind,
            final RequestedObservation requestedObservation
    ) {
        return new DecisionEnvelope(
                CONTEXT.requestId(),
                CONTEXT.observedWorldRevision(),
                CONTEXT.goalRevision(),
                kind,
                "",
                List.of(),
                requestedObservation,
                "",
                0.8
        );
    }
}
