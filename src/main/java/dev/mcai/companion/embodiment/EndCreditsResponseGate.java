package dev.mcai.companion.embodiment;

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;

/**
 * Allows the headless client to acknowledge the vanilla End credits exactly
 * once. Other game events, including death-screen configuration events, do
 * not open this gate.
 */
final class EndCreditsResponseGate {
    private boolean claimed;

    boolean claim(final ClientboundGameEventPacket.Type event) {
        if (event != ClientboundGameEventPacket.WIN_GAME || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }
}
