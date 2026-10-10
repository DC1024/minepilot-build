package dev.mcai.companion.client.modelsetup;

import dev.mcai.companion.modelsetup.network.ModelSetupClientBridge;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

public final class ClientModelSetupRegistration {
    private ClientModelSetupRegistration() {
    }

    public static void register(final ModContainer container) {
        container.registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (minecraft, parent) -> new ModelSetupScreen(parent)
            )
        );
        ScreenEvent.Init.Post.BUS.addListener(event -> {
            if (!(event.getScreen() instanceof PauseScreen pause)
                || !pause.showsPauseMenu()) {
                return;
            }
            final Component returnToGame =
                Component.translatable("menu.returnToGame");
            Button anchor = event.getListenersList().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> isReturnToGame(button, returnToGame))
                .findFirst()
                .orElse(null);
            if (anchor == null) {
                /*
                 * Some Forge/Minecraft GUI paths hand us a resolved literal
                 * component instead of the translatable component used above.
                 * Infer the same column from the first full-width primary
                 * button rather than falling back to a fixed y=54, which can
                 * overlap the Game Menu title at large GUI scales.
                 */
                anchor = event.getListenersList().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getWidth() >= 180)
                    .min(java.util.Comparator.comparingInt(Button::getY))
                    .orElse(null);
            }
            final int buttonWidth = anchor == null ? 204 : anchor.getWidth();
            final int buttonX = anchor == null
                ? (pause.width - buttonWidth) / 2
                : anchor.getX();
            final int buttonY = anchor == null
                ? Math.max(30, pause.height / 5)
                /*
                 * Leave the vanilla "Game Menu" title clear at large GUI
                 * scales. One logical pixel remains between this button and
                 * Return to Game instead of spending that space above it.
                 */
                : Math.max(6, anchor.getY() - 21);
            event.addListener(
                Button.builder(
                    Component.translatable(
                        "mcai_companion.pause_button"
                    ),
                    ignored -> net.minecraft.client.Minecraft
                        .getInstance()
                        .gui
                        .setScreen(new ModelSetupScreen(pause))
                )
                    .bounds(
                        buttonX,
                        buttonY,
                        buttonWidth,
                        20
                    )
                    .build()
            );
        });
        ModelSetupClientBridge.install(state -> {
            final var minecraft =
                net.minecraft.client.Minecraft.getInstance();
            if (minecraft.gui.screen()
                    instanceof ModelSetupScreen screen) {
                screen.acceptState(state);
            }
        });
    }

    private static boolean isReturnToGame(
            final Button button,
            final Component translatedReturnToGame
    ) {
        return button.getMessage().equals(translatedReturnToGame)
            || button.getMessage().getString().equals(
                translatedReturnToGame.getString()
            );
    }
}
