package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.ModelFailureKind;
import org.junit.jupiter.api.Test;

final class ConversationFailureMessageTest {
    @Test
    void retryStatusNamesProviderFailureAndDoesNotClaimAnAction() {
        final String message =
                CompanionConversationCoordinator.transientRetryMessage(
                        true,
                        ModelFailureKind.TIMEOUT
                );

        assertTrue(message.contains("超时"));
        assertTrue(message.contains("自动重试"));
        assertTrue(message.contains("尚未执行"));
        assertFalse(message.contains("我这就过去"));
    }

    @Test
    void exhaustedNetworkFailureIsActionableInBothLanguages() {
        final String chinese =
                CompanionConversationCoordinator.exhaustedFailureMessage(
                        ModelFailureKind.NETWORK_TRANSIENT,
                        true
                );
        final String english =
                CompanionConversationCoordinator.exhaustedFailureMessage(
                        ModelFailureKind.NETWORK_TRANSIENT,
                        false
                );

        assertTrue(chinese.contains("连接连续失败"));
        assertTrue(chinese.contains("未执行"));
        assertTrue(english.contains("connection failed repeatedly"));
        assertTrue(english.contains("was not executed"));
    }

    @Test
    void rateLimitAndMalformedResponseAreNotReportedAsUnclearSpeech() {
        final String rateLimit =
                CompanionConversationCoordinator.exhaustedFailureMessage(
                        ModelFailureKind.RATE_LIMITED,
                        true
                );
        final String malformed =
                CompanionConversationCoordinator.exhaustedFailureMessage(
                        ModelFailureKind.MALFORMED_RESPONSE,
                        true
                );

        assertTrue(rateLimit.contains("限流"));
        assertTrue(malformed.contains("无法使用"));
        assertFalse(rateLimit.contains("没听清"));
        assertFalse(malformed.contains("没听清"));
    }

    @Test
    void missingOutcomeIsExplicitlyNotExecuted() {
        assertTrue(
                CompanionConversationCoordinator.missingOutcomeMessage(true)
                        .contains("未执行")
        );
        assertTrue(
                CompanionConversationCoordinator.missingOutcomeMessage(false)
                        .contains("not executed")
        );
    }
}
