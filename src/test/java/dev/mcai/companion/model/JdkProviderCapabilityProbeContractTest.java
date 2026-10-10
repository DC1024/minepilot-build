package dev.mcai.companion.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkProviderCapabilityProbeContractTest {
    private static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().create();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void performsNoIoUntilExplicitlyTriggeredAndCachesTheVerifiedResponsesProfile()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<char[]> suppliedSecret = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(readRequestBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, successfulResponse(
                    exchange.getRequestURI().getPath()
            ));
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-provider-model",
                () -> {
                    char[] secret = "local-contract-placeholder".toCharArray();
                    suppliedSecret.set(secret);
                    return secret;
                }
        )) {
            assertEquals(0, requestCount.get());

            CompletionStage<CapabilityProbeOutcome> first = probe.probe();
            CompletionStage<CapabilityProbeOutcome> second = probe.probe();
            assertSame(first, second);
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(first)
            );

            assertEquals(
                    ProviderCapabilities.responsesJsonSchema(false),
                    supported.capabilities()
            );
            assertEquals(1, supported.requestsMade());
            assertEquals(1, requestCount.get());
            assertEquals("/v1/responses", requestPath.get());
            assertEquals(
                    "Bearer local-contract-placeholder",
                    authorization.get()
            );
            assertTrue(allZero(suppliedSecret.get()));

            JsonObject sent = GSON.fromJson(requestBody.get(), JsonObject.class);
            assertEquals("opaque-provider-model", sent.get("model").getAsString());
            assertFalse(sent.get("stream").getAsBoolean());
            assertEquals(512, sent.get("max_output_tokens").getAsInt());
            assertEquals(
                    "none",
                    sent.getAsJsonObject("reasoning")
                            .get("effort")
                            .getAsString()
            );
            assertEquals(
                    ReasoningControl.DISABLED,
                    supported.capabilities().reasoningControl()
            );
            assertEquals(
                    "json_schema",
                    sent.getAsJsonObject("text")
                            .getAsJsonObject("format")
                            .get("type")
                            .getAsString()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enablesImageInputOnlyAfterASecondAcceptedImageRequest()
            throws Exception {
        final List<JsonObject> requests = new ArrayList<>();
        final HttpServer server = startServer(exchange -> {
            final String body = readRequestBody(exchange);
            requests.add(GSON.fromJson(body, JsonObject.class));
            send(
                    exchange,
                    200,
                    successfulResponse(exchange.getRequestURI().getPath())
            );
        });

        try (JdkProviderCapabilityProbe probe =
                new JdkProviderCapabilityProbe(
                        endpoint(
                                server.getAddress().getPort(),
                                "verified-vision-model"
                        ),
                        placeholderSecret(),
                        CONNECT_TIMEOUT,
                        REQUEST_TIMEOUT,
                        true
                )) {
            final CapabilityProbeOutcome.Supported supported =
                    assertInstanceOf(
                            CapabilityProbeOutcome.Supported.class,
                            await(probe.probe())
                    );

            assertEquals(2, supported.requestsMade());
            assertEquals(2, requests.size());
            assertTrue(supported.capabilities().imageInput());
            assertTrue(requests.get(0).get("input").isJsonPrimitive());
            final var imageContent = requests.get(1)
                    .getAsJsonArray("input")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("content")
                    .get(0).getAsJsonObject();
            assertEquals(
                    "input_image",
                    imageContent.get("type").getAsString()
            );
            assertTrue(imageContent.get("image_url").getAsString()
                    .startsWith("data:image/png;base64,"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToChatOnlyAfterExplicitResponsesEndpointRejection()
            throws Exception {
        List<String> paths = new ArrayList<>();
        AtomicReference<String> chatBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String body = readRequestBody(exchange);
            if (path.endsWith("/responses")) {
                send(
                        exchange,
                        404,
                        "{\"error\":{\"message\":\"route not found\","
                                + "\"code\":\"not_found\"}}"
                );
            } else {
                chatBody.set(body);
                send(exchange, 200, successfulResponse(path));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "name-does-not-select-a-protocol",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );

            assertEquals(
                    List.of("/v1/responses", "/v1/chat/completions"),
                    paths
            );
            assertEquals(2, supported.requestsMade());
            assertEquals(Protocol.CHAT_COMPLETIONS, supported.capabilities().protocol());
            assertEquals(
                    ChatTokenField.MAX_COMPLETION_TOKENS,
                    supported.capabilities().chatTokenField()
            );
            JsonObject sent = GSON.fromJson(chatBody.get(), JsonObject.class);
            assertTrue(sent.has("max_completion_tokens"));
            assertEquals(512, sent.get("max_completion_tokens").getAsInt());
            assertEquals(
                    "disabled",
                    sent.getAsJsonObject("thinking")
                            .get("type")
                            .getAsString()
            );
            assertEquals(
                    "json_schema",
                    sent.getAsJsonObject("response_format")
                            .get("type")
                            .getAsString()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitReasoningControlRejectionRetriesSameContractWithoutHint()
            throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonObject request = GSON.fromJson(
                    readRequestBody(exchange),
                    JsonObject.class
            );
            requests.add(request);
            if (request.has("reasoning")) {
                send(
                        exchange,
                        400,
                        unsupported("reasoning.effort")
                );
            } else {
                send(exchange, 200, successfulResponse(path));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "provider-without-reasoning-controls",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );

            assertEquals(3, supported.requestsMade());
            assertEquals(3, requests.size());
            assertTrue(requests.get(0).has("reasoning"));
            assertEquals(
                    "none",
                    requests.get(0).getAsJsonObject("reasoning")
                            .get("effort").getAsString()
            );
            assertEquals(
                    "low",
                    requests.get(1).getAsJsonObject("reasoning")
                            .get("effort").getAsString()
            );
            assertFalse(requests.get(2).has("reasoning"));
            assertEquals(
                    ReasoningControl.DEFAULT,
                    supported.capabilities().reasoningControl()
            );
            assertEquals(
                    OutputContract.JSON_SCHEMA,
                    supported.capabilities().outputContract()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reasoningModelThatRejectsNoneNegotiatesLowLatencyEffort()
            throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonObject request = GSON.fromJson(
                    readRequestBody(exchange),
                    JsonObject.class
            );
            requests.add(request);
            final String effort = request.has("reasoning")
                    ? request.getAsJsonObject("reasoning")
                            .get("effort").getAsString()
                    : "";
            if (effort.equals("none")) {
                send(
                        exchange,
                        400,
                        unsupported("reasoning.effort")
                );
            } else {
                send(exchange, 200, successfulResponse(path));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "latency-sensitive-reasoning-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );

            assertEquals(2, supported.requestsMade());
            assertEquals(ReasoningControl.LOW,
                    supported.capabilities().reasoningControl());
            assertEquals(
                    "low",
                    requests.get(1).getAsJsonObject("reasoning")
                            .get("effort").getAsString()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitResponsesCapabilityRejectionDowngradesWithinResponses()
            throws Exception {
        List<String> paths = new ArrayList<>();
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String body = readRequestBody(exchange);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            if (contract == OutputContract.JSON_SCHEMA) {
                send(
                        exchange,
                        400,
                        "{\"error\":{\"message\":"
                                + "\"Unsupported parameter: text.format\","
                                + "\"param\":\"text.format\","
                                + "\"code\":\"unsupported_parameter\"}}"
                );
            } else {
                send(exchange, 200, successfulResponse(path, contract));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(2, supported.requestsMade());
            assertEquals(
                    List.of("/v1/responses", "/v1/responses"),
                    paths
            );
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION
                    ),
                    contracts
            );
            assertEquals(
                    OutputContract.FORCED_FUNCTION,
                    supported.capabilities().outputContract()
            );
            assertEquals(
                    Protocol.RESPONSES,
                    supported.capabilities().protocol()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void triesEveryResponsesOutputContractInStrictPreferenceOrder()
            throws Exception {
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            switch (contract) {
                case JSON_SCHEMA -> send(
                        exchange,
                        400,
                        unsupported("text.format")
                );
                case FORCED_FUNCTION -> send(
                        exchange,
                        400,
                        unsupported("tools")
                );
                case JSON_OBJECT -> send(
                        exchange,
                        400,
                        unsupported("text.format")
                );
                case PLAIN_JSON -> send(
                        exchange,
                        200,
                        successfulResponse(path, contract)
                );
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION,
                            OutputContract.JSON_OBJECT,
                            OutputContract.PLAIN_JSON
                    ),
                    contracts
            );
            assertEquals(4, supported.requestsMade());
            assertEquals(Protocol.RESPONSES, supported.capabilities().protocol());
            assertEquals(
                    OutputContract.PLAIN_JSON,
                    supported.capabilities().outputContract()
            );
            assertFalse(supported.capabilities().serverEnforcesSchema());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatTokenFallbackRetriesTheSameContractBeforeOutputDowngrade()
            throws Exception {
        List<String> paths = new ArrayList<>();
        List<OutputContract> contracts = new ArrayList<>();
        List<ChatTokenField> tokenFields = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            paths.add(path);
            if (path.endsWith("/responses")) {
                send(exchange, 405, "{\"error\":{\"message\":\"method not allowed\"}}");
                return;
            }

            JsonObject request = GSON.fromJson(body, JsonObject.class);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            tokenFields.add(request.has("max_completion_tokens")
                    ? ChatTokenField.MAX_COMPLETION_TOKENS
                    : ChatTokenField.MAX_TOKENS);
            if (contracts.size() == 1) {
                send(
                        exchange,
                        400,
                        unsupported("max_completion_tokens")
                );
            } else if (contracts.size() == 2) {
                send(exchange, 400, unsupported("response_format"));
            } else {
                send(exchange, 200, successfulResponse(path, contract));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "model-name-is-not-a-capability-signal",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(
                    List.of(
                            "/v1/responses",
                            "/v1/chat/completions",
                            "/v1/chat/completions",
                            "/v1/chat/completions"
                    ),
                    paths
            );
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION
                    ),
                    contracts
            );
            assertEquals(
                    List.of(
                            ChatTokenField.MAX_COMPLETION_TOKENS,
                            ChatTokenField.MAX_TOKENS,
                            ChatTokenField.MAX_TOKENS
                    ),
                    tokenFields
            );
            assertEquals(4, supported.requestsMade());
            assertEquals(
                    OutputContract.FORCED_FUNCTION,
                    supported.capabilities().outputContract()
            );
            assertEquals(
                    ChatTokenField.MAX_TOKENS,
                    supported.capabilities().chatTokenField()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void triesEveryChatOutputContractInStrictPreferenceOrder()
            throws Exception {
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            if (path.endsWith("/responses")) {
                send(
                        exchange,
                        404,
                        "{\"error\":{\"code\":\"route_not_found\","
                                + "\"message\":\"endpoint route not found\"}}"
                );
                return;
            }

            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            switch (contract) {
                case JSON_SCHEMA, JSON_OBJECT -> send(
                        exchange,
                        400,
                        unsupported("response_format")
                );
                case FORCED_FUNCTION -> send(
                        exchange,
                        400,
                        unsupported("tools[0].function")
                );
                case PLAIN_JSON -> send(
                        exchange,
                        200,
                        successfulResponse(path, contract)
                );
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION,
                            OutputContract.JSON_OBJECT,
                            OutputContract.PLAIN_JSON
                    ),
                    contracts
            );
            assertEquals(5, supported.requestsMade());
            assertEquals(
                    Protocol.CHAT_COMPLETIONS,
                    supported.capabilities().protocol()
            );
            assertEquals(
                    OutputContract.PLAIN_JSON,
                    supported.capabilities().outputContract()
            );
            assertEquals(
                    ChatTokenField.MAX_COMPLETION_TOKENS,
                    supported.capabilities().chatTokenField()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedFunctionResultFallsBackToOneExplicitPlainJsonProbe()
            throws Exception {
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            if (contract == OutputContract.JSON_SCHEMA) {
                send(exchange, 400, unsupported("text.format"));
            } else if (contract == OutputContract.FORCED_FUNCTION) {
                send(
                        exchange,
                        200,
                        successfulFunctionResponse(
                                path,
                                "{\"probe\":\"ok\",\"unexpected\":true}"
                        )
                );
            } else {
                send(
                        exchange,
                        200,
                        successfulResponse(path, OutputContract.PLAIN_JSON)
                );
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(3, supported.requestsMade());
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION,
                            OutputContract.PLAIN_JSON
                    ),
                    contracts
            );
            assertEquals(
                    OutputContract.PLAIN_JSON,
                    supported.capabilities().outputContract()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forcedFunctionTextFallbackNegotiatesPlainJsonWithoutAnotherRequest()
            throws Exception {
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            if (contract == OutputContract.JSON_SCHEMA) {
                send(exchange, 400, unsupported("text.format"));
            } else {
                send(
                    exchange,
                    200,
                    successfulResponse(
                        path,
                        OutputContract.PLAIN_JSON
                    )
                );
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported =
                    assertInstanceOf(
                        CapabilityProbeOutcome.Supported.class,
                        await(probe.probe())
                    );
            assertEquals(2, supported.requestsMade());
            assertEquals(
                    List.of(
                        OutputContract.JSON_SCHEMA,
                        OutputContract.FORCED_FUNCTION
                    ),
                    contracts
            );
            assertEquals(
                    OutputContract.PLAIN_JSON,
                    supported.capabilities().outputContract()
            );
            assertEquals(
                    Protocol.RESPONSES,
                    supported.capabilities().protocol()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void terminalFailureAfterAContractRejectionStopsImmediately()
            throws Exception {
        List<OutputContract> contracts = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            OutputContract contract = outputContract(path, body);
            contracts.add(contract);
            if (contract == OutputContract.JSON_SCHEMA) {
                send(exchange, 400, unsupported("text.format"));
            } else {
                send(
                        exchange,
                        429,
                        "{\"error\":{\"code\":\"rate_limit\","
                                + "\"message\":\"slow down\"}}"
                );
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(probe.probe())
            );
            assertEquals(ModelFailureKind.RATE_LIMITED, failure.error().kind());
            assertEquals(2, failure.requestsMade());
            assertEquals(
                    List.of(
                            OutputContract.JSON_SCHEMA,
                            OutputContract.FORCED_FUNCTION
                    ),
                    contracts
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void detectsChatTokenFieldByResponseRatherThanByModelName() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<JsonObject> chatRequests = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            int ordinal = requestCount.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = readRequestBody(exchange);
            if (path.endsWith("/responses")) {
                send(exchange, 405, "{\"error\":{\"message\":\"method not allowed\"}}");
            } else if (ordinal == 2) {
                chatRequests.add(GSON.fromJson(body, JsonObject.class));
                send(
                        exchange,
                        400,
                        "{\"error\":{\"message\":"
                                + "\"Unknown parameter: max_completion_tokens\","
                                + "\"code\":\"unknown_parameter\"}}"
                );
            } else {
                chatRequests.add(GSON.fromJson(body, JsonObject.class));
                send(exchange, 200, successfulResponse(path));
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "gpt-looking-name-that-must-not-be-inspected",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    await(probe.probe())
            );
            assertEquals(3, supported.requestsMade());
            assertEquals(
                    ChatTokenField.MAX_TOKENS,
                    supported.capabilities().chatTokenField()
            );
            assertTrue(chatRequests.get(0).has("max_completion_tokens"));
            assertFalse(chatRequests.get(0).has("max_tokens"));
            assertTrue(chatRequests.get(1).has("max_tokens"));
            assertFalse(chatRequests.get(1).has("max_completion_tokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void terminalProviderFailuresNeverTryChat() throws Exception {
        assertTerminalFailure(
                401,
                "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"bad key\"}}",
                ModelFailureKind.AUTHENTICATION
        );
        assertTerminalFailure(
                403,
                "{\"error\":{\"code\":\"forbidden\",\"message\":\"denied\"}}",
                ModelFailureKind.PERMISSION
        );
        assertTerminalFailure(
                402,
                "{\"error\":{\"code\":\"billing\",\"message\":\"no balance\"}}",
                ModelFailureKind.BILLING
        );
        assertTerminalFailure(
                404,
                "{\"error\":{\"code\":\"model_not_found\",\"param\":\"model\","
                        + "\"message\":\"The model does not exist\"}}",
                ModelFailureKind.MODEL_NOT_FOUND
        );
        assertTerminalFailure(
                429,
                "{\"error\":{\"code\":\"rate_limit\",\"message\":\"slow down\"}}",
                ModelFailureKind.RATE_LIMITED
        );
        assertTerminalFailure(
                503,
                "{\"error\":{\"code\":\"unavailable\",\"message\":\"try later\"}}",
                ModelFailureKind.SERVER_TRANSIENT
        );
        assertTerminalFailure(
                501,
                "{\"error\":{\"code\":\"not_implemented\","
                        + "\"message\":\"try another time\"}}",
                ModelFailureKind.SERVER_TRANSIENT
        );
        assertTerminalFailure(
                400,
                "{\"error\":{\"code\":\"invalid_request\","
                        + "\"param\":\"text.format\","
                        + "\"message\":\"text.format must be an object\"}}",
                ModelFailureKind.INVALID_REQUEST
        );
    }

    @Test
    void timeoutIsTerminalAndDoesNotTryChat() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            readRequestBody(exchange);
            requestArrived.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, successfulResponse(
                    exchange.getRequestURI().getPath()
            ));
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret(),
                Duration.ofMillis(100)
        )) {
            CompletionStage<CapabilityProbeOutcome> pending = probe.probe();
            assertTrue(requestArrived.await(1, TimeUnit.SECONDS));
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(pending)
            );
            assertEquals(ModelFailureKind.TIMEOUT, failure.error().kind());
            assertEquals(1, failure.requestsMade());
            assertEquals(1, requestCount.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void hardDeadlineCoversSlowResponseBodyAndCancelsTheOnlyAttempt()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            readRequestBody(exchange);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write('{');
            body.flush();
            bodyStarted.countDown();
            try {
                releaseBody.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            try {
                body.write(successfulResponse(
                        exchange.getRequestURI().getPath()
                ).substring(1).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The expected client-side timeout closes this body.
            }
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret(),
                Duration.ofMillis(300)
        )) {
            long started = System.nanoTime();
            CompletionStage<CapabilityProbeOutcome> pending = probe.probe();
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
            CapabilityProbeOutcome.Failure timeout = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(pending)
            );
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );
            assertEquals(ModelFailureKind.TIMEOUT, timeout.error().kind());
            assertEquals(1, timeout.requestsMade());
            assertEquals(1, requestCount.get());
            assertTrue(elapsedMillis < 2_000);
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void networkFailureIsTerminalAndCountsOnlyTheAttemptedRequest()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            readRequestBody(exchange);
            exchange.close();
        });
        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret(),
                Duration.ofMillis(500)
        )) {
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(probe.probe())
            );
            assertEquals(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    failure.error().kind()
            );
            assertEquals(1, failure.requestsMade());
            assertEquals(1, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void twoHundredWithoutNegotiatedOutputGetsOneBoundedPlainJsonRecovery()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            readRequestBody(exchange);
            send(exchange, 200, "{}");
        });
        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(probe.probe())
            );
            assertEquals(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    failure.error().kind()
            );
            assertEquals(2, failure.requestsMade());
            assertEquals(2, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesReasoningBudgetExhaustionAndReportsOnlySafeResponseShape()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        String sensitiveReasoning = "private-reasoning-canary-must-never-appear";
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            readRequestBody(exchange);
            if (exchange.getRequestURI().getPath().endsWith("/responses")) {
                send(exchange, 405, "{\"error\":{\"message\":\"method not allowed\"}}");
                return;
            }
            send(
                    exchange,
                    200,
                    """
                            {
                              "choices": [{
                                "finish_reason": "length",
                                "message": {
                                  "content": null,
                                  "reasoning_content": "%s"
                                }
                              }]
                            }
                            """.formatted(sensitiveReasoning)
            );
        });

        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-reasoning-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(probe.probe())
            );
            assertEquals(ModelFailureKind.CONTEXT_LIMIT, failure.error().kind());
            assertEquals(2, failure.requestsMade());
            assertEquals(2, requestCount.get());
            assertTrue(failure.error().safeMessage().contains("finish=length"));
            assertTrue(failure.error().safeMessage().contains("content=null"));
            assertTrue(failure.error().safeMessage().contains("reasoning=nonempty"));
            assertFalse(failure.error().safeMessage().contains(sensitiveReasoning));
        } finally {
            server.stop(0);
        }
    }

    private static void assertTerminalFailure(
            int status,
            String body,
            ModelFailureKind expectedKind
    ) throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            requestCount.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            readRequestBody(exchange);
            send(exchange, status, body);
        });
        try (JdkProviderCapabilityProbe probe = probe(
                server,
                "opaque-model",
                placeholderSecret()
        )) {
            CapabilityProbeOutcome.Failure failure = assertInstanceOf(
                    CapabilityProbeOutcome.Failure.class,
                    await(probe.probe())
            );
            assertEquals(expectedKind, failure.error().kind());
            assertEquals(1, failure.requestsMade());
            assertEquals(1, requestCount.get());
            assertEquals("/v1/responses", path.get());
        } finally {
            server.stop(0);
        }
    }

    private static JdkProviderCapabilityProbe probe(
            HttpServer server,
            String model,
            SecretSource secretSource
    ) throws EndpointValidationException {
        return probe(server, model, secretSource, REQUEST_TIMEOUT);
    }

    private static JdkProviderCapabilityProbe probe(
            HttpServer server,
            String model,
            SecretSource secretSource,
            Duration requestTimeout
    ) throws EndpointValidationException {
        return new JdkProviderCapabilityProbe(
                endpoint(server.getAddress().getPort(), model),
                secretSource,
                CONNECT_TIMEOUT,
                requestTimeout
        );
    }

    private static ModelEndpoint endpoint(int port, String model)
            throws EndpointValidationException {
        return new EndpointValidator().validate(
                "http://127.0.0.1:" + port + "/v1",
                model
        );
    }

    private static SecretSource placeholderSecret() {
        return () -> "local-placeholder-only".toCharArray();
    }

    private static CapabilityProbeOutcome await(
            CompletionStage<CapabilityProbeOutcome> stage
    ) throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static boolean allZero(char[] value) {
        return value != null && Arrays.equals(value, new char[value.length]);
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return server;
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String successfulResponse(String requestPath) {
        return successfulResponse(requestPath, OutputContract.JSON_SCHEMA);
    }

    private static String successfulResponse(
            String requestPath,
            OutputContract outputContract
    ) {
        if (outputContract == OutputContract.FORCED_FUNCTION) {
            return successfulFunctionResponse(
                    requestPath,
                    "{\"probe\":\"ok\"}"
            );
        }
        if (requestPath.endsWith("/responses")) {
            return """
                    {
                      "status": "completed",
                      "output": [{
                        "type": "message",
                        "content": [{
                          "type": "output_text",
                          "text": "{\\"probe\\":\\"ok\\"}"
                        }]
                      }]
                    }
                    """;
        }
        return """
                {
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {
                      "content": "{\\"probe\\":\\"ok\\"}"
                    }
                  }]
                }
                """;
    }

    private static String successfulFunctionResponse(
            String requestPath,
            String arguments
    ) {
        if (requestPath.endsWith("/responses")) {
            JsonObject function = new JsonObject();
            function.addProperty("type", "function_call");
            function.addProperty(
                    "name",
                    ModelRequestFactory.DECISION_FUNCTION_NAME
            );
            function.addProperty("arguments", arguments);
            com.google.gson.JsonArray output = new com.google.gson.JsonArray();
            output.add(function);
            JsonObject response = new JsonObject();
            response.addProperty("status", "completed");
            response.add("output", output);
            return GSON.toJson(response);
        }

        JsonObject function = new JsonObject();
        function.addProperty(
                "name",
                ModelRequestFactory.DECISION_FUNCTION_NAME
        );
        function.addProperty("arguments", arguments);
        JsonObject call = new JsonObject();
        call.addProperty("type", "function");
        call.add("function", function);
        com.google.gson.JsonArray calls = new com.google.gson.JsonArray();
        calls.add(call);
        JsonObject message = new JsonObject();
        message.add("tool_calls", calls);
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "tool_calls");
        choice.add("message", message);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return GSON.toJson(response);
    }

    private static OutputContract outputContract(
            String requestPath,
            String requestBody
    ) {
        JsonObject request = GSON.fromJson(requestBody, JsonObject.class);
        if (request.has("tools")) {
            return OutputContract.FORCED_FUNCTION;
        }
        JsonObject format = requestPath.endsWith("/responses")
                ? request.has("text")
                        ? request.getAsJsonObject("text")
                                .getAsJsonObject("format")
                        : null
                : request.has("response_format")
                        ? request.getAsJsonObject("response_format")
                        : null;
        if (format == null) {
            return OutputContract.PLAIN_JSON;
        }
        return format.get("type").getAsString().equals("json_schema")
                ? OutputContract.JSON_SCHEMA
                : OutputContract.JSON_OBJECT;
    }

    private static String unsupported(String parameter) {
        JsonObject error = new JsonObject();
        error.addProperty("message", "Unsupported parameter: " + parameter);
        error.addProperty("param", parameter);
        error.addProperty("code", "unsupported_parameter");
        JsonObject envelope = new JsonObject();
        envelope.add("error", error);
        return GSON.toJson(envelope);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
