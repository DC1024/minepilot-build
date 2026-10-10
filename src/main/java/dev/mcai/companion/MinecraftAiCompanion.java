package dev.mcai.companion;

import com.mojang.logging.LogUtils;
import dev.mcai.companion.communication.CommunicationModule;
import dev.mcai.companion.blackbox.HeadlessBlackboxModule;
import dev.mcai.companion.embodiment.EmbodimentModule;
import dev.mcai.companion.evaluation.EvaluationVictoryTracker;
import dev.mcai.companion.gametest.GameTestRegistrar;
import dev.mcai.companion.integration.xaero.XaeroIntegrationModule;
import dev.mcai.companion.modelsetup.ModelSetupModule;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.skin.SkinModule;
import dev.mcai.companion.skills.loot.VanillaLootReceiptLedger;
import dev.mcai.companion.vision.VisionCaptureModule;
import org.slf4j.Logger;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entry point. Real-time control and model calls deliberately live in
 * server-owned services rather than this lifecycle class.
 */
@Mod(MinecraftAiCompanion.MOD_ID)
public final class MinecraftAiCompanion {
    public static final String MOD_ID = "mcai_companion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MinecraftAiCompanion(final FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, CompanionConfig.SPEC, "mcai-companion.toml");
        GameTestRegistrar.register(context);
        ModelSetupModule.register(context);
        SkinModule.register();
        VisionCaptureModule.register();
        EmbodimentModule.register();
        VanillaLootReceiptLedger.register();
        CompanionRuntime.register();
        HeadlessBlackboxModule.register();
        EvaluationVictoryTracker.register();
        XaeroIntegrationModule.register();
        CommunicationModule.register();
        LOGGER.info("Minecraft AI Companion {} initialized", BuildInfo.VERSION);
    }
}
