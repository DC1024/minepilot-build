package dev.mcai.companion.vision;

/**
 * Explicit opt-in from a dedicated off-screen rendering client.
 *
 * <p>An ordinary modded player never sends this packet. The server therefore
 * cannot borrow a human player's camera merely because the vision network
 * channel is present.</p>
 */
public record ServerboundVisionRendererRegistration(boolean available) {
}
