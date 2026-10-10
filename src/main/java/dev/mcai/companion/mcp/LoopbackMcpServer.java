package dev.mcai.companion.mcp;

import dev.mcai.companion.BuildInfo;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal MCP Streamable HTTP server. It deliberately has no non-loopback bind
 * mode and owns a process-lifetime bearer token.
 */
public final class LoopbackMcpServer implements AutoCloseable {
    public static final String PATH = "/mcp";
    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final Gson GSON = new Gson();
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private static final Pattern CONFIGURED_TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    private final HttpServer server;
    private final ExecutorService executor;
    private final McpBackend backend;
    private final byte[] bearerToken;
    private final int port;

    private LoopbackMcpServer(
        final HttpServer server,
        final ExecutorService executor,
        final McpBackend backend,
        final byte[] bearerToken
    ) {
        this.server = server;
        this.executor = executor;
        this.backend = backend;
        this.bearerToken = bearerToken;
        this.port = server.getAddress().getPort();
    }

    public static LoopbackMcpServer start(
        final int requestedPort,
        final McpBackend backend,
        final String configuredToken
    ) throws IOException {
        Objects.requireNonNull(configuredToken, "configuredToken");
        if (!CONFIGURED_TOKEN.matcher(configuredToken).matches()) {
            throw new IllegalArgumentException(
                "Configured MCP token must be 32-128 URL-safe characters"
            );
        }
        return start(
            requestedPort,
            backend,
            configuredToken.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static LoopbackMcpServer start(
        final int requestedPort,
        final McpBackend backend,
        final byte[] encodedToken
    ) throws IOException {
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException("Invalid MCP port");
        }
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(encodedToken, "encodedToken");

        final InetAddress loopback = InetAddress.getLoopbackAddress();
        if (!loopback.isLoopbackAddress()) {
            throw new IOException("JVM loopback address is not a loopback interface");
        }

        final HttpServer server = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final LoopbackMcpServer result = new LoopbackMcpServer(
            server,
            executor,
            backend,
            encodedToken.clone()
        );
        server.createContext(PATH, result::handle);
        server.setExecutor(executor);
        server.start();
        return result;
    }

    public int port() {
        return port;
    }

    private void handle(final HttpExchange exchange) {
        try (exchange) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                sendStatus(exchange, 403);
                return;
            }
            if (!validHost(exchange) || !validOrigin(exchange)) {
                sendStatus(exchange, 403);
                return;
            }
            if (!authenticated(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendStatus(exchange, 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendStatus(exchange, 405);
                return;
            }

            final JsonObject request;
            try {
                request = readRequest(exchange);
            } catch (BodyTooLargeException exception) {
                sendStatus(exchange, 413);
                return;
            } catch (IOException | JsonParseException | IllegalStateException exception) {
                sendJson(exchange, 400, error(null, -32700, "Parse error"));
                return;
            }

            final JsonElement id = request.has("id") ? request.get("id").deepCopy() : null;
            if (!"2.0".equals(readString(request, "jsonrpc"))) {
                sendJson(exchange, 400, error(id, -32600, "Invalid Request"));
                return;
            }

            final String method = readString(request, "method");
            if (method == null) {
                sendJson(exchange, 400, error(id, -32600, "Invalid Request"));
                return;
            }
            if (method.startsWith("notifications/")) {
                sendStatus(exchange, 202);
                return;
            }

            final JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();
            dispatch(method, params)
                .orTimeout(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync((result, failure) -> {
                    try {
                        if (failure == null) {
                            sendJson(exchange, 200, success(id, result));
                        } else {
                            final Throwable cause = unwrap(failure);
                            if (cause instanceof McpMethodException methodFailure) {
                                sendJson(
                                    exchange,
                                    200,
                                    error(id, methodFailure.code, methodFailure.safeMessage)
                                );
                            } else {
                                sendJson(exchange, 200, error(id, -32603, "Internal error"));
                            }
                        }
                    } catch (IOException ignored) {
                        // Peer closed the loopback connection.
                    }
                }, executor)
                .join();
        } catch (IOException exception) {
            // Peer closed the loopback connection before a response completed.
        } catch (RuntimeException exception) {
            try {
                sendStatus(exchange, 500);
            } catch (IOException ignored) {
                // The response may already have been committed.
            }
        }
    }

    private CompletableFuture<JsonElement> dispatch(final String method, final JsonObject params) {
        return switch (method) {
            case "initialize" -> CompletableFuture.completedFuture(initializeResult());
            case "ping" -> CompletableFuture.completedFuture(new JsonObject());
            case "tools/list" -> CompletableFuture.completedFuture(toolsResult());
            case "tools/call" -> callTool(params);
            default -> CompletableFuture.failedFuture(new McpMethodException(-32601, "Method not found"));
        };
    }

    private CompletableFuture<JsonElement> callTool(final JsonObject params) {
        final String name = readString(params, "name");
        if (name == null || !McpTool.supports(name)) {
            return CompletableFuture.failedFuture(new McpMethodException(-32602, "Unknown tool"));
        }
        final JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
            ? params.getAsJsonObject("arguments")
            : new JsonObject();
        return backend.call(name, arguments).thenApply(result -> {
            final JsonObject toolResult = new JsonObject();
            final JsonArray content = new JsonArray();
            final JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", GSON.toJson(result == null ? new JsonObject() : result));
            content.add(text);
            toolResult.add("content", content);
            toolResult.addProperty("isError", false);
            return toolResult;
        });
    }

    private static JsonObject initializeResult() {
        final JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        final JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        final JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "minecraft-ai-companion");
        serverInfo.addProperty("version", BuildInfo.VERSION);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private static JsonObject toolsResult() {
        final JsonObject result = new JsonObject();
        final JsonArray tools = new JsonArray();
        for (McpTool tool : McpTool.values()) {
            tools.add(tool.descriptor());
        }
        result.add("tools", tools);
        return result;
    }

    private boolean authenticated(final HttpExchange exchange) {
        final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        final byte[] candidate = authorization
            .substring("Bearer ".length())
            .getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.isEqual(candidate, bearerToken);
        } finally {
            java.util.Arrays.fill(candidate, (byte) 0);
        }
    }

    private boolean validHost(final HttpExchange exchange) {
        final String rawHost = exchange.getRequestHeaders().getFirst("Host");
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            final int close = host.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = host.substring(0, close + 1);
        } else {
            final int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        return LOOPBACK_HOSTS.contains(host);
    }

    private static boolean validOrigin(final HttpExchange exchange) {
        final String rawOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (rawOrigin == null) {
            return true;
        }
        try {
            final URI origin = URI.create(rawOrigin);
            if (origin.getHost() == null || origin.getUserInfo() != null) {
                return false;
            }
            final String host = origin.getHost().toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static JsonObject readRequest(final HttpExchange exchange)
        throws IOException, BodyTooLargeException {
        final byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        final JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("JSON-RPC body must be an object");
        }
        return parsed.getAsJsonObject();
    }

    private static String readString(final JsonObject object, final String property) {
        if (!object.has(property) || !object.get(property).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(property).getAsString();
        } catch (UnsupportedOperationException | ClassCastException exception) {
            return null;
        }
    }

    private static JsonObject success(final JsonElement id, final JsonElement result) {
        final JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("result", result == null ? new JsonObject() : result);
        return response;
    }

    private static JsonObject error(
        final JsonElement id,
        final int code,
        final String message
    ) {
        final JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        final JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static void sendJson(
        final HttpExchange exchange,
        final int status,
        final JsonObject response
    ) throws IOException {
        final byte[] encoded = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
    }

    private static void sendStatus(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static Throwable unwrap(final Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        java.util.Arrays.fill(bearerToken, (byte) 0);
    }

    private static final class BodyTooLargeException extends Exception {
    }

    private static final class McpMethodException extends RuntimeException {
        private final int code;
        private final String safeMessage;

        private McpMethodException(final int code, final String message) {
            super(message);
            this.code = code;
            this.safeMessage = message;
        }
    }
}
