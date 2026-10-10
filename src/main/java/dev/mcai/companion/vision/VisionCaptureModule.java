package dev.mcai.companion.vision;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

public final class VisionCaptureModule {
    private VisionCaptureModule() {
    }

    public static void register() {
        VisionCaptureNetwork.initialize();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientRuntime();
        }
    }

    private static void registerClientRuntime() {
        dev.mcai.companion.client.vision.ClientVisionCaptureRuntime
                .register();
    }
}
