package dev.mcai.companion.skin;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.skin.network.SkinNetwork;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Server-authoritative skin selection and distribution. Import is restricted
 * to one documented path under the instance config directory.
 */
final class ServerSkinService {
    static final Path FIXED_RELATIVE_SOURCE =
        Path.of("mcai-companion", "skin.png");

    private final MinecraftServer server;
    private final SkinStore store;
    private final CompanionSkinData data;
    private final UUID companionId;
    private final Path configDirectory;
    private final Path fixedSource;

    private Optional<SkinWireSnapshot> current = Optional.empty();

    ServerSkinService(final MinecraftServer server) {
        this.server = server;
        this.data = CompanionSkinData.get(server);
        this.companionId =
            dev.mcai.companion.world.CompanionWorldData.get(server)
                .companionUuid();
        configDirectory = FMLPaths.CONFIGDIR.get()
            .toAbsolutePath()
            .normalize();
        fixedSource = configDirectory.resolve(FIXED_RELATIVE_SOURCE)
            .normalize();
        if (!fixedSource.startsWith(configDirectory)) {
            throw new IllegalStateException(
                "Fixed skin source escaped the config directory"
            );
        }
        store = new SkinStore(
            FMLPaths.GAMEDIR.get()
                .resolve("cache")
                .resolve(MinecraftAiCompanion.MOD_ID)
                .resolve("server-skins")
        );
        load();
    }

    Optional<SkinWireSnapshot> current() {
        requireServerThread();
        return current;
    }

    ReloadResult reload(final ArmType armType) {
        requireServerThread();
        try {
            if (!isAllowedFixedSource()) {
                throw new SkinImportException(
                    "The fixed skin source is missing, non-regular, or linked outside config"
                );
            }
            final SkinSpec spec = store.importLocal(fixedSource, armType);
            final SkinImageData image = store.readValidated(spec)
                .orElseThrow(() ->
                    new SkinImportException(
                        "Imported skin was not present in the content cache"
                    )
                );
            final SkinWireSnapshot snapshot = new SkinWireSnapshot(
                companionId,
                image.spec(),
                image.pngBytes()
            );
            data.select(spec);
            current = Optional.of(snapshot);
            SkinNetwork.broadcast(server, snapshot);
            return new ReloadResult(true, "skin_loaded", spec);
        } catch (SkinImportException | RuntimeException exception) {
            data.disable();
            current = Optional.empty();
            SkinNetwork.broadcastClear(server, companionId);
            MinecraftAiCompanion.LOGGER.warn(
                "Custom companion skin validation failed; UUID fallback remains active"
            );
            return new ReloadResult(
                false,
                "invalid_or_missing_fixed_skin",
                null
            );
        }
    }

    void disable() {
        requireServerThread();
        data.disable();
        current = Optional.empty();
        SkinNetwork.broadcastClear(server, companionId);
    }

    void syncTo(final ServerPlayer player) {
        requireServerThread();
        current.ifPresentOrElse(
            snapshot -> SkinNetwork.send(player, snapshot),
            () -> SkinNetwork.clear(player, companionId)
        );
    }

    private void load() {
        requireServerThread();
        final Optional<SkinSpec> selected = data.selection();
        if (selected.isPresent()) {
            current = readSnapshot(selected.orElseThrow());
            if (current.isEmpty() && isAllowedFixedSource()) {
                restoreSelectedFromSource(selected.orElseThrow());
            }
            if (current.isEmpty()) {
                MinecraftAiCompanion.LOGGER.warn(
                    "Selected custom companion skin is unavailable or corrupt; UUID fallback is active"
                );
            }
            return;
        }
        if (data.isDefault()
            && CompanionConfig.SKIN_AUTO_IMPORT.get()
            && isAllowedFixedSource()) {
            reload(ArmType.parse(CompanionConfig.SKIN_ARM_TYPE.get()));
        }
    }

    private Optional<SkinWireSnapshot> readSnapshot(final SkinSpec spec) {
        try {
            return store.readValidated(spec).map(image ->
                new SkinWireSnapshot(
                    companionId,
                    image.spec(),
                    image.pngBytes()
                )
            );
        } catch (SkinImportException exception) {
            return Optional.empty();
        }
    }

    private void restoreSelectedFromSource(final SkinSpec selected) {
        try {
            final SkinSpec restored = store.importLocal(
                fixedSource,
                selected.armType()
            );
            if (restored.sha256().equals(selected.sha256())) {
                current = readSnapshot(restored);
            }
        } catch (SkinImportException ignored) {
            current = Optional.empty();
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                "Companion skin state must run on the server thread"
            );
        }
    }

    private boolean isAllowedFixedSource() {
        try {
            return Files.isRegularFile(
                    fixedSource,
                    LinkOption.NOFOLLOW_LINKS
                )
                && fixedSource.toRealPath().startsWith(
                    configDirectory.toRealPath()
                );
        } catch (java.io.IOException | SecurityException ignored) {
            return false;
        }
    }

    record ReloadResult(
        boolean accepted,
        String code,
        SkinSpec spec
    ) {
    }
}
