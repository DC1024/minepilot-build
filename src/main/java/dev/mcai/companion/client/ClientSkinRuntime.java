package dev.mcai.companion.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.skin.ArmType;
import dev.mcai.companion.skin.SkinImageData;
import dev.mcai.companion.skin.SkinImportException;
import dev.mcai.companion.skin.SkinStore;
import dev.mcai.companion.skin.SkinWireSnapshot;
import dev.mcai.companion.skin.network.ClientSkinTransferAssembler;
import dev.mcai.companion.skin.network.ClientboundSkinChunk;
import dev.mcai.companion.skin.network.SkinClientBridge;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-owned texture lifecycle. The server sends bytes and a content digest,
 * never a source path or URL. Every client repeats validation before creating
 * a GPU texture.
 */
public final class ClientSkinRuntime {
    private static final int MAX_INSTALLED_SKINS = 16;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ClientSkinTransferAssembler ASSEMBLER =
        new ClientSkinTransferAssembler();
    private static final Map<UUID, InstalledSkin> INSTALLED =
        new ConcurrentHashMap<>();

    private ClientSkinRuntime() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            SkinClientBridge.install(
                ClientSkinRuntime::accept,
                ClientSkinRuntime::clear
            );
            ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
                event -> clearAll()
            );
        }
    }

    public static Optional<PlayerSkin> customSkin(final UUID companionId) {
        final InstalledSkin installed = INSTALLED.get(companionId);
        return installed == null
            ? Optional.empty()
            : Optional.of(installed.skin());
    }

    public static void accept(final ClientboundSkinChunk chunk) {
        if (chunk.chunkIndex() == 0) {
            removeInstalled(chunk.companionId());
        }
        try {
            ASSEMBLER.accept(chunk).ifPresent(ClientSkinRuntime::install);
        } catch (RuntimeException exception) {
            ASSEMBLER.clear(chunk.companionId());
            removeInstalled(chunk.companionId());
            MinecraftAiCompanion.LOGGER.warn(
                "Rejected invalid synchronized companion skin {}",
                chunk.sha256()
            );
        }
    }

    public static void clear(final UUID companionId) {
        ASSEMBLER.clear(companionId);
        removeInstalled(companionId);
    }

    public static void clearAll() {
        ASSEMBLER.clearAll();
        for (UUID companionId : java.util.List.copyOf(INSTALLED.keySet())) {
            removeInstalled(companionId);
        }
    }

    private static void install(final SkinWireSnapshot snapshot) {
        NativeImage nativeImage = null;
        DynamicTexture dynamicTexture = null;
        try {
            if (!INSTALLED.containsKey(snapshot.companionId())
                && INSTALLED.size() >= MAX_INSTALLED_SKINS) {
                clearAll();
            }
            final SkinStore store = new SkinStore(clientCacheRoot());
            final SkinImageData cached = store.readValidated(
                store.importBytes(
                    snapshot.pngBytes(),
                    snapshot.spec().armType()
                )
            ).orElseThrow(() ->
                new SkinImportException(
                    "The synchronized skin cache could not be verified"
                )
            );
            if (!cached.spec().sha256().equals(snapshot.spec().sha256())) {
                throw new SkinImportException(
                    "The synchronized skin digest changed during import"
                );
            }

            nativeImage = NativeImage.read(cached.pngBytes());
            if (nativeImage.getWidth() != SkinStore.SKIN_WIDTH
                || nativeImage.getHeight() != SkinStore.SKIN_HEIGHT) {
                throw new IOException(
                    "Decoded texture dimensions changed after validation"
                );
            }

            final Identifier textureId = Identifier.fromNamespaceAndPath(
                MinecraftAiCompanion.MOD_ID,
                "dynamic_skin/"
                    + snapshot.companionId().toString().replace("-", "")
                    + "/"
                    + snapshot.spec().sha256()
            );
            dynamicTexture = new DynamicTexture(
                () -> "Minecraft AI Companion custom skin",
                nativeImage
            );
            nativeImage = null;

            final Minecraft minecraft = Minecraft.getInstance();
            removeInstalled(snapshot.companionId());
            minecraft.getTextureManager().register(
                textureId,
                dynamicTexture
            );
            dynamicTexture = null;

            final PlayerModelType model =
                snapshot.spec().armType() == ArmType.CLASSIC
                    ? PlayerModelType.WIDE
                    : PlayerModelType.SLIM;
            final ClientAsset.ResourceTexture body =
                new ClientAsset.ResourceTexture(textureId, textureId);
            INSTALLED.put(
                snapshot.companionId(),
                new InstalledSkin(
                    textureId,
                    PlayerSkin.insecure(body, null, null, model)
                )
            );
        } catch (SkinImportException | IOException | RuntimeException exception) {
            if (dynamicTexture != null) {
                dynamicTexture.close();
            }
            if (nativeImage != null) {
                nativeImage.close();
            }
            removeInstalled(snapshot.companionId());
            MinecraftAiCompanion.LOGGER.warn(
                "Could not install synchronized companion skin {}; using UUID fallback",
                snapshot.spec().sha256()
            );
        }
    }

    private static void removeInstalled(final UUID companionId) {
        final InstalledSkin removed = INSTALLED.remove(companionId);
        if (removed == null) {
            return;
        }
        try {
            Minecraft.getInstance()
                .getTextureManager()
                .release(removed.textureId());
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.debug(
                "Texture manager was unavailable while releasing a companion skin"
            );
        }
    }

    private static Path clientCacheRoot() {
        return Minecraft.getInstance()
            .gameDirectory
            .toPath()
            .resolve("cache")
            .resolve(MinecraftAiCompanion.MOD_ID)
            .resolve("skins");
    }

    private record InstalledSkin(
        Identifier textureId,
        PlayerSkin skin
    ) {
    }
}
