package dev.mcai.companion.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LoopbackMcpServerTest {
    private static final String TEST_TOKEN =
        "local_test_token_0123456789_ABCDEFG";

    private LoopbackMcpServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = LoopbackMcpServer.start(
            0,
            (name, arguments) -> {
                final JsonObject result = new JsonObject();
                result.addProperty("tool", name);
                result.add("arguments", arguments);
                return CompletableFuture.completedFuture(result);
            },
            TEST_TOKEN
        );
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        server.close();
        client.close();
    }

    @Test
    void initializesAndListsToolsWithBearerAuthentication() throws Exception {
        final HttpResponse<String> initialize = post("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """, TEST_TOKEN, null);
        final HttpResponse<String> list = post("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """, TEST_TOKEN, null);

        assertEquals(200, initialize.statusCode());
        assertEquals(LoopbackMcpServer.PROTOCOL_VERSION,
            JsonParser.parseString(initialize.body()).getAsJsonObject()
                .getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(McpTool.values().length,
            JsonParser.parseString(list.body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").size());
    }

    @Test
    void callsToolAndWrapsStructuredResultAsMcpText() throws Exception {
        final HttpResponse<String> response = post("""
            {
              "jsonrpc":"2.0",
              "id":"call-1",
              "method":"tools/call",
              "params":{"name":"say","arguments":{"message":"你好"}}
            }
            """, TEST_TOKEN, null);

        assertEquals(200, response.statusCode());
        final String text = JsonParser.parseString(response.body()).getAsJsonObject()
            .getAsJsonObject("result")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
        assertTrue(text.contains("\"tool\":\"say\""));
        assertTrue(text.contains("你好"));
    }

    @Test
    void rejectsMissingTokenAndForeignOrigin() throws Exception {
        final HttpResponse<String> noToken = post("""
            {"jsonrpc":"2.0","id":1,"method":"ping"}
            """, null, null);
        final HttpResponse<String> foreignOrigin = post("""
            {"jsonrpc":"2.0","id":1,"method":"ping"}
            """, TEST_TOKEN, "https://attacker.example");

        assertEquals(401, noToken.statusCode());
        assertEquals(403, foreignOrigin.statusCode());
    }

    @Test
    void returnsJsonRpcErrorWithoutExposingBackendFailure() throws Exception {
        final HttpResponse<String> response = post("""
            {"jsonrpc":"2.0","id":9,"method":"unknown/method","params":{}}
            """, TEST_TOKEN, null);

        assertEquals(200, response.statusCode());
        final JsonObject error = JsonParser.parseString(response.body())
            .getAsJsonObject()
            .getAsJsonObject("error");
        assertEquals(-32601, error.get("code").getAsInt());
        assertEquals("Method not found", error.get("message").getAsString());
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        final String huge = " ".repeat(LoopbackMcpServer.MAX_BODY_BYTES + 1);
        final HttpResponse<String> response = post(
            huge,
            TEST_TOKEN,
            null
        );

        assertEquals(413, response.statusCode());
    }

    @Test
    void acceptsExplicitUrlSafeTokenWithoutLoggingOrReencodingIt() throws Exception {
        final String configured =
            "another_local_token_0123456789_ABCDE";
        try (LoopbackMcpServer configuredServer = LoopbackMcpServer.start(
            0,
            (name, arguments) -> CompletableFuture.completedFuture(new JsonObject()),
            configured
        )) {
            assertEquals(
                200,
                post(
                    configuredServer,
                    """
                    {"jsonrpc":"2.0","id":1,"method":"ping"}
                    """,
                    configured,
                    null
                ).statusCode()
            );
        }
    }

    private HttpResponse<String> post(
        final String body,
        final String token,
        final String origin
    ) throws Exception {
        return post(server, body, token, origin);
    }

    private HttpResponse<String> post(
        final LoopbackMcpServer target,
        final String body,
        final String token,
        final String origin
    ) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(
                "http://127.0.0.1:"
                    + target.port()
                    + LoopbackMcpServer.PATH
            ))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
