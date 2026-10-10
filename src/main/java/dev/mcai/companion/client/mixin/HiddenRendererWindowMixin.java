package dev.mcai.companion.client.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Enables an opt-in, off-screen rendering client for local black-box tests.
 * Minecraft already creates the GLFW window hidden. On macOS, leaving it in
 * that state can pause the in-world frame loop, so the background renderer
 * uses a logically visible but fully transparent, non-focusing window. The
 * normal game path remains unchanged.
 */
@Mixin(Minecraft.class)
public abstract class HiddenRendererWindowMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V"
            )
    )
    private void mcai$showOrKeepRendererHidden(final long windowHandle) {
        if (Boolean.getBoolean("mcai.companion.hiddenRenderer")) {
            GLFW.glfwSetWindowAttrib(
                    windowHandle,
                    GLFW.GLFW_FOCUS_ON_SHOW,
                    GLFW.GLFW_FALSE
            );
            GLFW.glfwSetWindowOpacity(windowHandle, 0.0F);
        }
        GLFW.glfwShowWindow(windowHandle);
    }
}
