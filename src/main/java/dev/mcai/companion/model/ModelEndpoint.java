package dev.mcai.companion.model;

import java.net.URI;
import java.util.Objects;

/**
 * A validated API prefix and model name. It never contains credentials.
 */
public record ModelEndpoint(URI baseUri, String modelName) {
    public ModelEndpoint {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(modelName, "modelName");
    }

    public URI endpoint(Protocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        return URI.create(baseUri.toASCIIString() + "/" + protocol.relativePath());
    }

    public String origin() {
        int port = baseUri.getPort();
        String effectivePort = port < 0 ? "" : ":" + port;
        return baseUri.getScheme() + "://" + baseUri.getHost() + effectivePort;
    }
}
