package dev.mcai.companion;

import java.net.URI;
import java.util.List;

import dev.mcai.companion.skin.ArmType;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Non-secret configuration. API keys must never be placed in this spec.
 */
public final class CompanionConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> MODEL_BASE_URL = BUILDER
        .comment("Provider base URL. HTTPS is required except for a loopback test server.")
        .define("model.baseUrl", "https://api.openai.com/v1", CompanionConfig::isValidBaseUrl);

    public static final ForgeConfigSpec.ConfigValue<String> MODEL_NAME = BUILDER
        .comment("Provider model name. Leave empty until configured in the in-game setup screen.")
        .define("model.name", "", value -> value instanceof String text && text.length() <= 256);

    public static final ForgeConfigSpec.IntValue MODEL_SOFT_TIMEOUT_SECONDS = BUILDER
        .comment("After this time the local controller reports that the model is still thinking.")
        .defineInRange("model.softTimeoutSeconds", 12, 1, 89);

    public static final ForgeConfigSpec.IntValue MODEL_HARD_TIMEOUT_SECONDS = BUILDER
        .comment("Hard deadline for a model request.")
        .defineInRange("model.hardTimeoutSeconds", 90, 2, 300);

    /**
     * Returns a safe soft deadline for the two-stage model policy. The
     * individual Forge config values intentionally have independent ranges
     * so existing files remain loadable; this cross-field clamp prevents a
     * user-selected hard deadline below the soft deadline from aborting the
     * whole server runtime during construction.
     */
    public static int effectiveModelSoftTimeoutSeconds(
        final int configuredSoftSeconds,
        final int configuredHardSeconds
    ) {
        if (configuredSoftSeconds < 1 || configuredHardSeconds < 2) {
            throw new IllegalArgumentException(
                "model timeout values are outside the validated range"
            );
        }
        return Math.min(configuredSoftSeconds, configuredHardSeconds - 1);
    }

    public static final ForgeConfigSpec.BooleanValue MCP_ENABLED = BUILDER
        .comment("Expose the optional loopback-only Codex MCP server.")
        .define("mcp.enabled", false);

    public static final ForgeConfigSpec.IntValue MCP_PORT = BUILDER
        .comment("Loopback port for the optional Codex MCP server.")
        .defineInRange("mcp.port", 25766, 1024, 65535);

    public static final ForgeConfigSpec.BooleanValue ACTIVE_VISION_ENABLED = BUILDER
        .comment(
            "Allow task-triggered redacted screenshots only after an "
                + "authenticated first-person capture path and model image "
                + "capability have both been verified. A dedicated off-screen "
                + "renderer must opt in; ordinary player cameras remain "
                + "ineligible and the headless ServerPlayer stays fail-closed."
        )
        .define("perception.activeVision", true);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WAYPOINT_ALLOWED_SENDERS = BUILDER
        .comment("Player UUIDs allowed to send the companion an external waypoint. Empty means singleplayer owner only.")
        .defineListAllowEmpty("waypoints.allowedSenders", List.of(), CompanionConfig::isUuidText);

    /**
     * Explicit non-operator chat principals for multiplayer companion tasks.
     * Administrative commands, model setup and evaluation remain gated by
     * {@code CompanionCommandAccess.mayAdmin}; this list only grants the
     * narrow ability to address the active companion through ordinary chat.
     */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CHAT_ALLOWED_SENDERS = BUILDER
        .comment(
            "Player UUIDs allowed to issue gameplay tasks to the companion via chat. "
                + "Empty means singleplayer owner or server gamemaster only."
        )
        .defineListAllowEmpty(
            "chat.allowedSenders",
            List.of(),
            CompanionConfig::isUuidText
        );

    public static final ForgeConfigSpec.BooleanValue SKIN_AUTO_IMPORT = BUILDER
        .comment(
            "On a world's first skin setup, import only the fixed local file",
            "config/mcai-companion/skin.png. No URL or arbitrary path is accepted."
        )
        .define("skin.autoImportFixedFile", true);

    public static final ForgeConfigSpec.ConfigValue<String> SKIN_ARM_TYPE = BUILDER
        .comment("Arm geometry for an automatically imported skin: CLASSIC or SLIM.")
        .define("skin.armType", "CLASSIC", CompanionConfig::isArmType);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CompanionConfig() {
    }

    private static boolean isValidBaseUrl(final Object value) {
        if (!(value instanceof String text) || text.length() > 2048) {
            return false;
        }
        try {
            final URI uri = URI.create(text);
            if (!uri.isAbsolute()
                || uri.isOpaque()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getHost() == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            final String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("127.0.0.1")
                || host.startsWith("127.")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isUuidText(final Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            java.util.UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isArmType(final Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            ArmType.parse(text);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
