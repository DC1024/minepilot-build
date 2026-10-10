package dev.mcai.companion.skin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.mcai.companion.security.CompanionCommandAccess;
import dev.mcai.companion.skin.network.SkinNetwork;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

public final class SkinModule {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<MinecraftServer, ServerSkinService> SERVICES =
        new IdentityHashMap<>();

    private SkinModule() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        SkinNetwork.initialize();
        RegisterCommandsEvent.BUS.addListener(SkinModule::registerCommands);
        ServerStartedEvent.BUS.addListener(event ->
            service(event.getServer())
        );
        ServerStoppedEvent.BUS.addListener(event ->
            SERVICES.remove(event.getServer())
        );
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
            SkinModule::onPlayerLoggedIn
        );
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientRuntime();
        }
    }

    private static void onPlayerLoggedIn(
        final PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || AiProfileMarker.isMarked(player.getGameProfile())) {
            return;
        }
        final MinecraftServer server = player.level().getServer();
        if (server != null) {
            service(server).syncTo(player);
        }
    }

    private static void registerCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mcai")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.literal("skin")
                    .requires(CompanionCommandAccess::mayAdmin)
                    .executes(SkinModule::status)
                    .then(Commands.literal("status")
                        .executes(SkinModule::status))
                    .then(Commands.literal("reload")
                        .executes(context -> reload(
                            context,
                            ArmType.parse(
                                dev.mcai.companion.CompanionConfig
                                    .SKIN_ARM_TYPE
                                    .get()
                            )
                        ))
                        .then(Commands.literal("classic")
                            .executes(context ->
                                reload(context, ArmType.CLASSIC)
                            ))
                        .then(Commands.literal("slim")
                            .executes(context ->
                                reload(context, ArmType.SLIM)
                            )))
                    .then(Commands.literal("clear")
                        .executes(SkinModule::clear)))
        );
    }

    private static int status(
        final CommandContext<CommandSourceStack> context
    ) {
        final CommandSourceStack source = context.getSource();
        final var snapshot = service(source.getServer()).current();
        source.sendSuccess(
            () -> Component.literal(
                snapshot.map(value ->
                    "[AI] skin=custom, arm="
                        + value.spec().armType()
                        + ", sha256="
                        + value.spec().sha256()
                ).orElse(
                    "[AI] skin=UUID_DEFAULT (Steve/Alex)"
                )
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(
        final CommandContext<CommandSourceStack> context,
        final ArmType armType
    ) {
        final CommandSourceStack source = context.getSource();
        final var result = service(source.getServer()).reload(armType);
        if (!result.accepted()) {
            source.sendFailure(Component.literal(
                "[AI] Skin import failed. Verify the fixed 64x64 alpha PNG at config/mcai-companion/skin.png."
            ));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal(
                "[AI] Custom skin loaded: arm="
                    + result.spec().armType()
                    + ", sha256="
                    + result.spec().sha256()
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(
        final CommandContext<CommandSourceStack> context
    ) {
        final CommandSourceStack source = context.getSource();
        service(source.getServer()).disable();
        source.sendSuccess(
            () -> Component.literal(
                "[AI] Custom skin disabled; UUID-stable Steve/Alex fallback is active."
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static ServerSkinService service(
        final MinecraftServer server
    ) {
        return SERVICES.computeIfAbsent(server, ServerSkinService::new);
    }

    private static void registerClientRuntime() {
        try {
            Class.forName(
                "dev.mcai.companion.client.ClientSkinRuntime"
            ).getMethod("register").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Could not initialize the client skin runtime",
                exception
            );
        }
    }
}
