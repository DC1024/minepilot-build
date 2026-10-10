package dev.mcai.companion.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mcai.companion.MinecraftAiCompanion;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-authoritative selection metadata. The source path is deliberately not
 * persisted; only a content digest and arm geometry leave the importing host.
 */
public final class CompanionSkinData extends SavedData {
    private static final int SCHEMA_VERSION = 1;

    public static final Codec<CompanionSkinData> CODEC =
        RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("mode", "DEFAULT")
                .forGetter(CompanionSkinData::storedMode),
            Codec.STRING.optionalFieldOf("sha256", "")
                .forGetter(CompanionSkinData::storedSha256),
            Codec.STRING.optionalFieldOf("arm_type", "CLASSIC")
                .forGetter(CompanionSkinData::storedArmType),
            Codec.INT.optionalFieldOf("schema_version", SCHEMA_VERSION)
                .forGetter(CompanionSkinData::schemaVersion)
        ).apply(instance, CompanionSkinData::new));

    public static final SavedDataType<CompanionSkinData> TYPE =
        new SavedDataType<>(
            Identifier.fromNamespaceAndPath(
                MinecraftAiCompanion.MOD_ID,
                "skin"
            ),
            CompanionSkinData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
        );

    private SkinSelectionMode mode;
    private String sha256;
    private ArmType armType;
    private int schemaVersion;

    public CompanionSkinData() {
        this("DEFAULT", "", "CLASSIC", SCHEMA_VERSION);
    }

    private CompanionSkinData(
        final String storedMode,
        final String storedSha256,
        final String storedArmType,
        final int schemaVersion
    ) {
        mode = SkinSelectionMode.parseStored(storedMode);
        sha256 = storedSha256 == null ? "" : storedSha256;
        ArmType parsedArm;
        try {
            parsedArm = ArmType.parse(storedArmType);
        } catch (IllegalArgumentException ignored) {
            parsedArm = ArmType.CLASSIC;
        }
        armType = parsedArm;
        this.schemaVersion = schemaVersion;
    }

    public static CompanionSkinData get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<SkinSpec> selection() {
        if (mode != SkinSelectionMode.CUSTOM) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SkinSpec(
                sha256,
                armType,
                SkinFallback.UUID_DEFAULT
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public boolean isDefault() {
        return mode == SkinSelectionMode.DEFAULT;
    }

    public boolean isDisabled() {
        return mode == SkinSelectionMode.DISABLED;
    }

    public void select(final SkinSpec spec) {
        mode = SkinSelectionMode.CUSTOM;
        sha256 = spec.sha256();
        armType = spec.armType();
        schemaVersion = SCHEMA_VERSION;
        setDirty();
    }

    public void disable() {
        mode = SkinSelectionMode.DISABLED;
        sha256 = "";
        armType = ArmType.CLASSIC;
        schemaVersion = SCHEMA_VERSION;
        setDirty();
    }

    private String storedMode() {
        return mode.name();
    }

    private String storedSha256() {
        return sha256;
    }

    private String storedArmType() {
        return armType.name();
    }

    private int schemaVersion() {
        return schemaVersion;
    }
}
