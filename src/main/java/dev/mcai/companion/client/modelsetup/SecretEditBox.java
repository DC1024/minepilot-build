package dev.mcai.companion.client.modelsetup;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Password-style field that masks rendered and narrated text and disables
 * copying/cutting the credential back into the system clipboard.
 */
final class SecretEditBox extends EditBox {
    SecretEditBox(
        final Font font,
        final int x,
        final int y,
        final int width,
        final int height,
        final Component narration
    ) {
        super(font, x, y, width, height, narration);
        addFormatter((text, offset) -> FormattedCharSequence.forward(
            "\u2022".repeat(text.codePointCount(0, text.length())),
            Style.EMPTY
        ));
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return Component.translatable(
            "gui.narrate.editBox",
            getMessage(),
            Component.literal(getValue().isEmpty() ? "empty" : "masked")
        );
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isCopy() || event.isCut()) {
            return true;
        }
        return super.keyPressed(event);
    }
}
