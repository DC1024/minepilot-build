package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.junit.jupiter.api.Test;

final class EndCreditsResponseGateTest {
    @Test
    void acceptsOnlyTheFirstVanillaWinGameEvent() {
        final EndCreditsResponseGate gate =
            new EndCreditsResponseGate();

        assertFalse(gate.claim(
            ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE
        ));
        assertFalse(gate.claim(
            ClientboundGameEventPacket.IMMEDIATE_RESPAWN
        ));
        assertTrue(gate.claim(ClientboundGameEventPacket.WIN_GAME));
        assertFalse(gate.claim(ClientboundGameEventPacket.WIN_GAME));
        assertFalse(gate.claim(
            ClientboundGameEventPacket.START_RAINING
        ));
    }
}
