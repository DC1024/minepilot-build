package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PlayerTaskIntentCancellationTest {
    @Test
    void recognizesShortUnambiguousStopCommands() {
        assertTrue(PlayerTaskIntent.isCancellationRequest("停下！"));
        assertTrue(PlayerTaskIntent.isCancellationRequest("please stop now"));
        assertTrue(PlayerTaskIntent.isCancellationRequest("hold"));
        assertTrue(PlayerTaskIntent.isCancellationRequest("取消刚才的任务。"));
    }

    @Test
    void doesNotTurnOrdinaryConversationIntoCancellation() {
        assertFalse(PlayerTaskIntent.isCancellationRequest("你停下来了吗"));
        assertFalse(PlayerTaskIntent.isCancellationRequest("please stop the zombies"));
        assertFalse(PlayerTaskIntent.isCancellationRequest("wait for me at home"));
        assertFalse(PlayerTaskIntent.isCancellationRequest("好的"));
    }
}
