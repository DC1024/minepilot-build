package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PlayerTaskIntentTest {
    @Test
    void promotesDirectChineseAndEnglishGameplayCommands() {
        assertTrue(classify("跟我").task());
        assertTrue(classify("跟我").reason().equals(
                "immediate_follow_request"
        ));
        assertTrue(classify("跟我走").task());
        assertTrue(PlayerTaskIntent.isFollowRequest("请跟我来"));
        assertTrue(PlayerTaskIntent.isFollowRequest("跟我，走"));
        assertTrue(PlayerTaskIntent.isFollowRequest("跟我……"));
        assertTrue(PlayerTaskIntent.isFollowRequest("跟我去村庄"));
        assertTrue(PlayerTaskIntent.isFollowRequest("跟过来"));
        assertTrue(PlayerTaskIntent.isFollowRequest("Please follow me"));
        assertTrue(classify("你先过来").task());
        assertTrue(classify("合成啊").task());
        assertTrue(classify("你还是丢给我吧").task());
        assertTrue(classify("快跑").task());
        assertTrue(classify("Please follow me").task());
        assertTrue(classify("Could you gather some wood?").task());
        assertTrue(classify("你后面有僵尸").task());
        assertTrue(classify("僵尸就在附近，快跑").task());
        assertTrue(classify(
                "我们先从零开始。请在附近砍一棵树，捡起木头；不要只回复，先完成动作。"
        ).task());
    }

    @Test
    void promotesNaturalChineseEatCommands() {
        for (String command : List.of(
                "给你了吃吧",
                "那既然这样，那你就快吃吧",
                "赶紧吃金苹果",
                "把它吃了吧"
        )) {
            assertTrue(classify(command).task(), command);
        }
    }

    @Test
    void doesNotTurnLanguageOrOrdinaryQuestionsIntoGameplayGoals() {
        assertFalse(classify("Could you speak Chinese?").task());
        assertFalse(classify("你会砍树吗？").task());
        assertFalse(classify("你还有多少血量？").task());
        assertFalse(classify("在吗？").task());
        assertFalse(classify("你附近有僵尸吗？").task());
        assertFalse(classify("请问你会砍树吗？").task());
    }

    @Test
    void recognizesProviderQuestionAndGreetingMistakes() {
        assertTrue(
                PlayerTaskIntent.looksLikeConversationalQuestion(
                        "Could you speak Chinese?"
                )
        );
        assertTrue(
                PlayerTaskIntent.looksLikeConversationalQuestion(
                        "What do you mean by speak Chinese?"
                )
        );
        assertTrue(
                PlayerTaskIntent.looksLikeConversationalQuestion("Hello")
        );
        assertFalse(
                PlayerTaskIntent.looksLikeConversationalQuestion(
                        "Turn that village into an iron farm"
                )
        );
        assertFalse(
                CompanionConversationCoordinator.acceptsModelSelectedTask(
                        true,
                        false,
                        "Could you speak Chinese?"
                )
        );
        assertTrue(
                CompanionConversationCoordinator.acceptsModelSelectedTask(
                        true,
                        false,
                        "Turn that village into an iron farm"
                )
        );
        assertFalse(
                CompanionConversationCoordinator.acceptsModelSelectedTask(
                        false,
                        false,
                        "Turn that village into an iron farm"
                )
        );
        assertTrue(
                CompanionConversationCoordinator.acceptsModelSelectedTask(
                        false,
                        true,
                        "Turn that village into an iron farm"
                )
        );
    }

    @Test
    void bindsAnAffirmationToTheImmediatelyPreviousProposal() {
        final var result = PlayerTaskIntent.classify(
                "行",
                "行",
                Optional.of("要我先去砍树吗？")
        );
        assertTrue(result.task());
        assertTrue(result.goalText().contains("砍树"));
    }

    @Test
    void preservesAnActiveFollowGoalAcrossNaturalShortNudges() {
        final String active = "跟随发出请求的玩家；玩家原话：跟我走";
        for (String nudge : List.of(
                "走啊",
                "来啊",
                "过来啊",
                "跟上啊",
                "快点",
                "come on"
        )) {
            final var result = PlayerTaskIntent.classify(
                    nudge,
                    nudge,
                    Optional.empty(),
                    Optional.of(active)
            );
            assertTrue(result.task(), nudge);
            assertFalse(result.replacesGoal(), nudge);
            assertTrue(result.goalText().equals(active), nudge);
        }
    }

    @Test
    void preservesNamedFoodGoalAcrossNaturalEatItNudges() {
        final String active = "我已经把金苹果丢给你了，快吃吧";
        for (String nudge : List.of(
                "那既然这样，那你就快吃吧",
                "把它吃了吧",
                "吃吧",
                "eat it now"
        )) {
            final var result = PlayerTaskIntent.classify(
                    nudge,
                    nudge,
                    Optional.empty(),
                    Optional.of(active)
            );
            assertTrue(result.task(), nudge);
            assertFalse(result.replacesGoal(), nudge);
            assertEquals(active, result.goalText(), nudge);
            assertEquals(
                    "active_food_consumption_continuation",
                    result.reason(),
                    nudge
            );
        }
    }

    @Test
    void namedDifferentFoodRequestStillReplacesTheActiveGoldenAppleGoal() {
        final var result = PlayerTaskIntent.classify(
                "请吃面包",
                "请吃面包",
                Optional.empty(),
                Optional.of("我已经把金苹果丢给你了，快吃吧")
        );

        assertTrue(result.task());
        assertTrue(result.replacesGoal());
        assertEquals("请吃面包", result.goalText());
    }

    @Test
    void aFreshFollowRequestReplacesAnUnrelatedRunningGoal() {
        final var result = PlayerTaskIntent.classify(
                "跟我走",
                "跟我走",
                Optional.empty(),
                Optional.of("帮我砍树")
        );
        assertTrue(result.task());
        assertTrue(result.replacesGoal());
        assertTrue(result.reason().equals("immediate_follow_request"));
    }

    @Test
    void aModelSelectedTaskOutsideTheLocalGrammarReplacesTheOldGoal() {
        final var localIntent = PlayerTaskIntent.classify(
                "从空背包开始建立安全据点并生存到第二天",
                "从空背包开始建立安全据点并生存到第二天",
                Optional.empty(),
                Optional.of("跟随发出请求的玩家；玩家原话：跟我走")
        );
        assertFalse(localIntent.task());
        assertFalse(
                CompanionConversationCoordinator
                        .preservesExistingGoal(localIntent)
        );
    }

    @Test
    void identifiesUnboundActionPromises() {
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment(
                "好的，我这就过来！"
        ));
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment("好好好"));
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment("OK"));
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment(
                "Starting a fresh survey."
        ));
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment(
                "目标已接受。revision=2"
        ));
        assertTrue(PlayerTaskIntent.looksLikeActionCommitment(
                "Task accepted; I am proceeding."
        ));
        assertFalse(PlayerTaskIntent.looksLikeActionCommitment(
                "我还有16点血量。"
        ));
    }

    @Test
    void answersCommonGreetingAndLanguageQuestionWithoutPlannerRoundTrip() {
        assertEquals(
                "Hi, I am here.",
                PlayerTaskIntent.immediateSocialReply("Hello?")
                        .orElseThrow()
        );
        assertEquals(
                "可以，我会说中文。",
                PlayerTaskIntent.immediateSocialReply(
                        "Could you speak Chinese?"
                ).orElseThrow()
        );
        assertEquals(
                "Yes, I can speak English.",
                PlayerTaskIntent.immediateSocialReply(
                        "Can you speak English?"
                ).orElseThrow()
        );
        assertTrue(
                PlayerTaskIntent.immediateSocialReply("帮我砍树")
                        .isEmpty()
        );
    }

    private static PlayerTaskIntent.Result classify(
            final String message
    ) {
        return PlayerTaskIntent.classify(
                message,
                message,
                Optional.empty()
        );
    }
}
