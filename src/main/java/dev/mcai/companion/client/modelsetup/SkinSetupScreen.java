package dev.mcai.companion.client.modelsetup;

import dev.mcai.companion.skin.ArmType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Bounded local skin import UI. Drag-and-drop is a native Screen facility and
 * avoids arbitrary remote URLs or platform-specific file-picker code.
 */
final class SkinSetupScreen extends Screen {
    private static final int MAX_BYTES = 1024 * 1024;

    private final Screen parent;
    private CycleButton<ArmType> armType;
    private String statusCode = "drop_skin_here";

    SkinSetupScreen(final Screen parent) {
        super(Component.translatable(
            "mcai_companion.screen.skin.title"
        ));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final int left = Math.max(20, width / 2 - 150);
        armType = addRenderableWidget(
            CycleButton
                .builder(
                    type -> Component.translatable(
                        type == ArmType.CLASSIC
                            ? "mcai_companion.skin.classic"
                            : "mcai_companion.skin.slim"
                    ),
                    ArmType.CLASSIC
                )
                .withValues(ArmType.CLASSIC, ArmType.SLIM)
                .create(
                    left,
                    height / 2 + 14,
                    300,
                    20,
                    Component.translatable(
                        "mcai_companion.skin.arm_type"
                    )
                )
        );
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "mcai_companion.skin.use_default"
                ),
                ignored -> clearSkin()
            ).bounds(left, height / 2 + 42, 146, 20).build()
        );
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .bounds(left + 154, height / 2 + 42, 146, 20)
                .build()
        );
    }

    @Override
    public void onFilesDrop(final List<Path> paths) {
        if (paths.size() != 1) {
            statusCode = "select_one_png";
            return;
        }
        final Path source = paths.getFirst();
        try {
            final long size = Files.size(source);
            if (size <= 0 || size > MAX_BYTES) {
                statusCode = "skin_file_size_invalid";
                return;
            }
            final byte[] bytes = Files.readAllBytes(source);
            if (bytes.length > MAX_BYTES) {
                statusCode = "skin_file_size_invalid";
                return;
            }
            final var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null
                || image.getWidth() != 64
                || image.getHeight() != 64
                || !image.getColorModel().hasAlpha()) {
                statusCode = "skin_must_be_64x64_alpha_png";
                return;
            }
            final Path destination = minecraft.gameDirectory
                .toPath()
                .resolve("config")
                .resolve("mcai-companion")
                .resolve("skin.png");
            Files.createDirectories(destination.getParent());
            Files.write(
                destination,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            final var connection = minecraft.getConnection();
            if (connection == null) {
                statusCode = "join_world_to_apply_skin";
                return;
            }
            connection.sendCommand(
                "mcai skin reload "
                    + armType.getValue().name().toLowerCase(
                        java.util.Locale.ROOT
                    )
            );
            statusCode = "skin_import_requested";
        } catch (IOException | RuntimeException exception) {
            statusCode = "skin_import_failed";
        }
    }

    private void clearSkin() {
        final var connection = minecraft.getConnection();
        if (connection == null) {
            statusCode = "join_world_to_apply_skin";
            return;
        }
        connection.sendCommand("mcai skin clear");
        statusCode = "default_skin_requested";
    }

    @Override
    public void extractRenderState(
        final GuiGraphicsExtractor graphics,
        final int mouseX,
        final int mouseY,
        final float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 28, 0xFFFFFFFF);
        graphics.centeredText(
            font,
            Component.translatable("mcai_companion.skin.drop_instruction"),
            width / 2,
            height / 2 - 54,
            0xFFFFAA00
        );
        graphics.textWithWordWrap(
            font,
            Component.translatable("mcai_companion.skin.requirements"),
            width / 2 - 150,
            height / 2 - 32,
            300,
            0xFFC0C0C0
        );
        graphics.centeredText(
            font,
            Component.literal("Status: " + statusCode),
            width / 2,
            height / 2 + 72,
            statusCode.endsWith("requested")
                ? 0xFF55FF55
                : 0xFFA0A0A0
        );
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
