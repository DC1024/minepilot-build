package dev.mcai.companion.gametest;

import dev.mcai.companion.MinecraftAiCompanion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Development-only bridge for Forge's two-part 26.1 GameTest registration.
 *
 * <p>The fixture class and its data-driven test instance are excluded from
 * release JARs. Reflection keeps the production entry point free of a hard
 * linkage to that absent fixture. Forge also reports GameTests disabled in a
 * production installation, so this registrar is inert there.</p>
 */
public final class GameTestRegistrar {
    private static final List<String> FIXTURE_CLASSES = List.of(
        "dev.mcai.companion.embodiment.EmbodimentGameTests",
        "dev.mcai.companion.skills.inventory.InventoryGameTests",
        "dev.mcai.companion.skills.portal.PortalCastGameTests",
        "dev.mcai.companion.skills.end.EndIslandIngressGameTests"
    );
    private static Map<Identifier, ForgeGameTestHooks.TestReference> tests =
        Map.of();

    private GameTestRegistrar() {
    }

    public static void register(final FMLJavaModLoadingContext context) {
        if (!ForgeGameTestHooks.isGametestEnabled()) {
            return;
        }

        final Map<Identifier, ForgeGameTestHooks.TestReference> gathered =
            new LinkedHashMap<>();
        for (final String fixtureClass : FIXTURE_CLASSES) {
            final Class<?> fixture;
            try {
                fixture = Class.forName(fixtureClass);
            } catch (ClassNotFoundException exception) {
                MinecraftAiCompanion.LOGGER.debug(
                    "GameTest fixture {} is not present on this runtime "
                        + "classpath",
                    fixtureClass
                );
                continue;
            }
            ForgeGameTestHooks.gatherTests(fixture, null)
                .forEach((id, reference) -> {
                    final var duplicate = gathered.putIfAbsent(
                        id,
                        reference
                    );
                    if (duplicate != null) {
                        throw new IllegalStateException(
                            "Duplicate Minecraft AI Companion GameTest id: "
                                + id
                        );
                    }
                });
        }
        tests = Map.copyOf(gathered);
        if (tests.isEmpty()) {
            throw new IllegalStateException(
                "GameTest fixture was found but exposed no registered tests"
            );
        }

        RegisterEvent.getBus(context.getModBusGroup())
            .addListener(GameTestRegistrar::registerFunctions);
        MinecraftAiCompanion.LOGGER.info(
            "Prepared {} Minecraft AI Companion GameTest function(s): {}",
            tests.size(),
            tests.keySet()
        );
    }

    private static void registerFunctions(final RegisterEvent event) {
        if (!Registries.TEST_FUNCTION.equals(event.getRegistryKey())) {
            return;
        }

        tests.forEach((id, reference) ->
            event.register(
                Registries.TEST_FUNCTION,
                id,
                reference::consumer
            )
        );
        MinecraftAiCompanion.LOGGER.info(
            "Registered {} Minecraft AI Companion GameTest function(s)",
            tests.size()
        );
    }
}
