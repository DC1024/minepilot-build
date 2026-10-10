package dev.mcai.companion.client.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Util;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import dev.mcai.companion.vision.ClientboundVisionCaptureRequest;
import dev.mcai.companion.vision.ServerboundVisionCaptureResult;
import dev.mcai.companion.vision.VisionCaptureNetwork;

/** Client renderer for one authenticated, HUD-free AI first-person frame. */
public final class ClientVisionCaptureRuntime {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static ClientboundVisionCaptureRequest pending;
    private static Entity previousCamera;
    private static CameraType previousCameraType;
    private static boolean previousHudHidden;
    private static boolean captureArmed;

    private ClientVisionCaptureRuntime() {
    }

    public static void register() {
        registerEvents();
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> {
            if (Boolean.getBoolean(
                    "mcai.companion.hiddenRenderer"
            )) {
                VisionCaptureNetwork.registerRenderer(true);
            }
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            if (captureArmed || pending != null) {
                failAndRestore("client_world_closed");
            }
        });
    }

    public static void request(
            final ClientboundVisionCaptureRequest request
    ) {
        Objects.requireNonNull(request, "request");
        if (!Boolean.getBoolean("mcai.companion.hiddenRenderer")) {
            reply(request, "renderer_not_opted_in", new byte[0]);
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (captureArmed || pending != null) {
            reply(request, "capture_busy", new byte[0]);
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            reply(request, "client_world_unavailable", new byte[0]);
            return;
        }
        final Entity companion = minecraft.level.getPlayerByUUID(
                request.companionId()
        );
        if (companion == null || companion.isRemoved()) {
            reply(request, "companion_not_rendered", new byte[0]);
            return;
        }
        pending = request;
        previousCamera = minecraft.getCameraEntity();
        previousCameraType = minecraft.options.getCameraType();
        previousHudHidden = minecraft.gui.hud.isHidden();
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        if (!previousHudHidden) {
            minecraft.gui.hud.toggle();
        }
        minecraft.setCameraEntity(companion);
        captureArmed = true;
    }

    private static void registerEvents() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        TickEvent.RenderTickEvent.Post.BUS.addListener(
                ClientVisionCaptureRuntime::afterRender
        );
        TickEvent.ClientTickEvent.Post.BUS.addListener(ignored -> {
            if (captureArmed) {
                final Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level == null || minecraft.player == null) {
                    failAndRestore("client_world_closed");
                }
            }
        });
        RenderNameTagEvent.BUS.addListener(event -> {
            if (captureArmed) {
                event.setContent(null);
                event.setScoreContent(null);
            }
        });
    }

    private static void afterRender(
            final TickEvent.RenderTickEvent.Post event
    ) {
        if (!captureArmed || pending == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientboundVisionCaptureRequest request = pending;
        captureArmed = false;
        pending = null;
        try {
            final var target = minecraft.gameRenderer.mainRenderTarget();
            final int downscale = target.width % 2 == 0
                    && target.height % 2 == 0 ? 2 : 1;
            Screenshot.takeScreenshot(target, downscale, image ->
                    Util.ioPool().execute(() -> {
                        Path temporary = null;
                        try (image) {
                            temporary = Files.createTempFile(
                                    "mcai-ai-view-",
                                    ".png"
                            );
                            image.writeToFile(temporary);
                            final byte[] png = Files.readAllBytes(temporary);
                            minecraft.execute(() -> reply(
                                    request,
                                    png.length <= 2_500_000
                                            ? "ok"
                                            : "png_too_large",
                                    png.length <= 2_500_000
                                            ? png
                                            : new byte[0]
                            ));
                        } catch (IOException | RuntimeException exception) {
                            minecraft.execute(() -> reply(
                                    request,
                                    "capture_encode_failed",
                                    new byte[0]
                            ));
                        } finally {
                            if (temporary != null) {
                                try {
                                    Files.deleteIfExists(temporary);
                                } catch (IOException ignored) {
                                    // The OS temporary directory will clean
                                    // up a failed transient deletion.
                                }
                            }
                        }
                    })
            );
        } catch (RuntimeException exception) {
            reply(request, "capture_render_failed", new byte[0]);
        } finally {
            restore(minecraft);
        }
    }

    private static void failAndRestore(final String code) {
        final ClientboundVisionCaptureRequest request = pending;
        captureArmed = false;
        pending = null;
        restore(Minecraft.getInstance());
        if (request != null) {
            reply(request, code, new byte[0]);
        }
    }

    private static void restore(final Minecraft minecraft) {
        minecraft.options.setCameraType(
                previousCameraType == null
                        ? CameraType.FIRST_PERSON
                        : previousCameraType
        );
        if (minecraft.gui.hud.isHidden() != previousHudHidden) {
            minecraft.gui.hud.toggle();
        }
        minecraft.setCameraEntity(
                previousCamera == null ? minecraft.player : previousCamera
        );
        previousCamera = null;
        previousCameraType = null;
        previousHudHidden = false;
    }

    private static void reply(
            final ClientboundVisionCaptureRequest request,
            final String code,
            final byte[] png
    ) {
        VisionCaptureNetwork.reply(new ServerboundVisionCaptureResult(
                request.requestId(),
                request.companionId(),
                code,
                png
        ));
    }
}
