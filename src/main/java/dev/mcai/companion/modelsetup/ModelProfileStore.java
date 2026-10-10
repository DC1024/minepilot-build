package dev.mcai.companion.modelsetup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcai.companion.model.ChatTokenField;
import dev.mcai.companion.model.EndpointValidationException;
import dev.mcai.companion.model.EndpointValidator;
import dev.mcai.companion.model.ModelEndpoint;
import dev.mcai.companion.model.OutputContract;
import dev.mcai.companion.model.Protocol;
import dev.mcai.companion.model.ProviderCapabilities;
import dev.mcai.companion.model.ReasoningControl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Cross-platform persistence for the non-secret model endpoint and name.
 *
 * <p>The API key is deliberately absent. It remains in Keychain, DPAPI,
 * Secret Service, an environment injection, or process memory. This small
 * profile is a fallback for loader configurations that are not yet attached
 * when an integrated/dedicated server runtime starts.</p>
 */
public final class ModelProfileStore {
    static final String FILE_NAME = "model-profile.json";
    private static final int FORMAT_VERSION = 4;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int PRE_LOW_REASONING_FORMAT_VERSION = 2;
    private static final int PRE_VISION_FORMAT_VERSION = 3;
    private static final int MAX_FILE_BYTES = 8_192;
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final Path directory;
    private final Path file;

    public ModelProfileStore(final Path configDirectory) {
        directory = Objects.requireNonNull(
                configDirectory,
                "configDirectory"
        ).toAbsolutePath().normalize().resolve("mcai-companion");
        file = directory.resolve(FILE_NAME);
    }

    public Optional<Profile> load() {
        try {
            if (!Files.isRegularFile(file)
                    || Files.isSymbolicLink(file)
                    || Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            final JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            if (!root.has("version")
                    || !root.has("baseUrl")
                    || !root.has("modelName")) {
                return Optional.empty();
            }
            final int version = root.get("version").getAsInt();
            if (version != FORMAT_VERSION
                    && version != LEGACY_FORMAT_VERSION
                    && version != PRE_LOW_REASONING_FORMAT_VERSION
                    && version != PRE_VISION_FORMAT_VERSION) {
                return Optional.empty();
            }
            final ModelEndpoint endpoint = new EndpointValidator().validate(
                    root.get("baseUrl").getAsString(),
                    root.get("modelName").getAsString()
            );
            return Optional.of(new Profile(
                    endpoint.baseUri().toASCIIString(),
                    endpoint.modelName(),
                    version == FORMAT_VERSION
                            ? decodeCapabilitiesSafely(root)
                            : Optional.empty()
            ));
        } catch (IOException
                | RuntimeException
                | EndpointValidationException ignored) {
            return Optional.empty();
        }
    }

    public void save(
            final String baseUrl,
            final String modelName
    ) {
        save(baseUrl, modelName, Optional.empty());
    }

    public void save(
            final String baseUrl,
            final String modelName,
            final ProviderCapabilities capabilities
    ) {
        save(
                baseUrl,
                modelName,
                Optional.of(Objects.requireNonNull(
                        capabilities,
                        "capabilities"
                ))
        );
    }

    /**
     * Retains the non-secret endpoint while removing cached wire capabilities.
     * A provider authentication failure must force a fresh setup/probe on the
     * next world start; restoring a stale capability object would otherwise
     * make an invalid key appear ready until the first billable request.
     */
    public void invalidateCapabilities() {
        load().ifPresent(profile -> save(
                profile.baseUrl(),
                profile.modelName()
        ));
    }

    private void save(
            final String baseUrl,
            final String modelName,
            final Optional<ProviderCapabilities> capabilities
    ) {
        Objects.requireNonNull(capabilities, "capabilities");
        final ModelEndpoint endpoint;
        try {
            endpoint = new EndpointValidator().validate(
                    baseUrl,
                    modelName
            );
        } catch (EndpointValidationException exception) {
            throw new IllegalArgumentException(
                    "Invalid non-secret model profile",
                    exception
            );
        }
        final JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty(
                "baseUrl",
                endpoint.baseUri().toASCIIString()
        );
        root.addProperty("modelName", endpoint.modelName());
        capabilities.ifPresent(value ->
                root.add("capabilities", encodeCapabilities(value))
        );
        final byte[] encoded = GSON.toJson(root)
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Non-secret model profile exceeds its size limit"
            );
        }

        final Path temporary = directory.resolve(
                FILE_NAME + ".tmp"
        );
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                    || Files.exists(temporary)
                    && Files.isSymbolicLink(temporary)) {
                throw new IOException("Unsafe profile path");
            }
            Files.write(
                    temporary,
                    encoded,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The original failure remains authoritative.
            }
            throw new IllegalStateException(
                    "Could not persist non-secret model profile",
                    exception
            );
        }
    }

    private static JsonObject encodeCapabilities(
            final ProviderCapabilities capabilities
    ) {
        final JsonObject encoded = new JsonObject();
        encoded.addProperty(
                "protocol",
                capabilities.protocol().name()
        );
        encoded.addProperty(
                "outputContract",
                capabilities.outputContract().name()
        );
        encoded.addProperty(
                "serverEnforcesSchema",
                capabilities.serverEnforcesSchema()
        );
        encoded.addProperty(
                "streaming",
                capabilities.streaming()
        );
        encoded.addProperty(
                "chatTokenField",
                capabilities.chatTokenField().name()
        );
        encoded.addProperty(
                "reasoningControl",
                capabilities.reasoningControl().name()
        );
        encoded.addProperty("imageInput", capabilities.imageInput());
        return encoded;
    }

    private static Optional<ProviderCapabilities> decodeCapabilities(
            final JsonObject root
    ) {
        if (!root.has("capabilities")
                || !root.get("capabilities").isJsonObject()) {
            return Optional.empty();
        }
        final JsonObject encoded =
                root.getAsJsonObject("capabilities");
        return Optional.of(new ProviderCapabilities(
                Protocol.valueOf(
                        requiredString(encoded, "protocol")
                ),
                OutputContract.valueOf(
                        requiredString(encoded, "outputContract")
                ),
                requiredBoolean(
                        encoded,
                        "serverEnforcesSchema"
                ),
                requiredBoolean(encoded, "streaming"),
                ChatTokenField.valueOf(
                        requiredString(encoded, "chatTokenField")
                ),
                ReasoningControl.valueOf(
                        requiredString(encoded, "reasoningControl")
                ),
                requiredBoolean(encoded, "imageInput")
        ));
    }

    /**
     * Capability metadata is an optimization, not the user's endpoint
     * configuration. A partially written or future capability object must
     * therefore trigger a fresh probe without discarding the still-valid
     * base URL and model name.
     */
    private static Optional<ProviderCapabilities> decodeCapabilitiesSafely(
            final JsonObject root
    ) {
        try {
            return decodeCapabilities(root);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String requiredString(
            final JsonObject object,
            final String field
    ) {
        if (!object.has(field)
                || !object.get(field).isJsonPrimitive()
                || !object.get(field)
                        .getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "Missing capability string " + field
            );
        }
        return object.get(field).getAsString();
    }

    private static boolean requiredBoolean(
            final JsonObject object,
            final String field
    ) {
        if (!object.has(field)
                || !object.get(field).isJsonPrimitive()
                || !object.get(field)
                        .getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(
                    "Missing capability boolean " + field
            );
        }
        return object.get(field).getAsBoolean();
    }

    public record Profile(
            String baseUrl,
            String modelName,
            Optional<ProviderCapabilities> capabilities
    ) {
        public Profile(
                final String baseUrl,
                final String modelName
        ) {
            this(baseUrl, modelName, Optional.empty());
        }

        public Profile {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(modelName, "modelName");
            capabilities = Objects.requireNonNull(
                    capabilities,
                    "capabilities"
            );
        }
    }
}
