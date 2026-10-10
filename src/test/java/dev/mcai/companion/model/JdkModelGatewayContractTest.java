package dev.mcai.companion.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkModelGatewayContractTest {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void sendsResponsesJsonSchemaAndValidatesTheReturnedDecision() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestIdHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        char[] acquiredSecret = "contract-test-credential".toCharArray();
        DecisionEnvelope expectedDecision = safeIdle("req-responses", 41, 7);

        HttpServer server = startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestIdHeader.set(exchange.getRequestHeaders().getFirst("X-Client-Request-Id"));
            requestBody.set(readRequestBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-request-id", "provider-request-1");
            send(exchange, 200, responsesBody(expectedDecision, 10, 5, 15));
        });

        try (JdkModelGateway gateway = gateway(
                server,
                () -> acquiredSecret,
                ProviderCapabilities.responsesJsonSchema(false)
        )) {
            ModelOutcome.Success success = assertInstanceOf(
                    ModelOutcome.Success.class,
                    await(gateway.decide(input(
                            expectedDecision,
                            Map.of(
                                    "prepare_stone_tools",
                                    ignored -> java.util.Optional.empty()
                            )
                    )))
            );

            assertEquals(expectedDecision, success.decision());
            assertEquals(new TokenUsage(10, 5, 15), success.usage());
            assertEquals("provider-request-1", success.trace().providerRequestId());
            assertEquals("/v1/responses", requestPath.get());
            assertEquals("Bearer contract-test-credential", authorization.get());
            assertEquals("req-responses", requestIdHeader.get());

            JsonObject sent = GSON.fromJson(requestBody.get(), JsonObject.class);
            assertEquals("test-model", sent.get("model").getAsString());
            assertEquals(false, sent.get("store").getAsBoolean());
            assertEquals(false, sent.get("stream").getAsBoolean());
            assertEquals(0.2, sent.get("temperature").getAsDouble());
            assertEquals(
                    "json_schema",
                    sent.getAsJsonObject("text")
                            .getAsJsonObject("format")
                            .get("type")
                            .getAsString()
            );
            JsonArray admittedSkills = sent
                    .getAsJsonObject("text")
                    .getAsJsonObject("format")
                    .getAsJsonObject("schema")
                    .getAsJsonObject("properties")
                    .getAsJsonObject("skillName")
                    .getAsJsonArray("enum");
            assertEquals(2, admittedSkills.size());
            assertEquals("", admittedSkills.get(0).getAsString());
            assertEquals(
                    "prepare_stone_tools",
                    admittedSkills.get(1).getAsString()
            );
            assertTrue(
                    sent.get("instructions")
                            .getAsString()
                            .contains("Never invent an alias")
            );
            assertTrue(
                    sent.get("instructions")
                            .getAsString()
                            .contains("skill from a future phase")
            );
            assertTrue(
                    sent.getAsJsonArray("input")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonArray("content")
                            .get(0)
                            .getAsJsonObject()
                            .get("text")
                            .getAsString()
                            .contains("UNTRUSTED_WORLD_OBSERVATION_JSON")
            );
            assertArrayEquals(new char[acquiredSecret.length], acquiredSecret);
            assertEquals(GatewayStatus.IDLE, gateway.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bindsProviderEchoFieldsToTheOwningLocalRequest() throws Exception {
        final DecisionEnvelope expectedDecision = safeIdle(
                "req-local-binding",
                41,
                7
        );
        final DecisionEnvelope providerDecision = safeIdle(
                "provider-invented-request",
                999,
                8
        );
        final HttpServer server = startServer(exchange -> {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            send(
                    exchange,
                    200,
                    responsesBody(providerDecision, 10, 5, 15)
            );
        });

        try (JdkModelGateway gateway = gateway(
                server,
                () -> "placeholder-only".toCharArray(),
                ProviderCapabilities.responsesJsonSchema(false)
        )) {
            final ModelOutcome.Success success = assertInstanceOf(
                    ModelOutcome.Success.class,
                    await(gateway.decide(input(expectedDecision)))
            );

            assertEquals(expectedDecision, success.decision());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void assemblesAStreamingChatToolCallWithoutUsingARealCredential() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        DecisionEnvelope expectedDecision = safeIdle("req-chat-stream", 12, 3);
        String decisionJson = new DecisionEnvelopeCodec().encode(expectedDecision);
        int split = decisionJson.length() / 2;

        HttpServer server = startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(readRequestBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            String stream = chatToolChunk(
                    "submit_decision",
                    decisionJson.substring(0, split),
                    ""
            ) + chatToolChunk(
                    "",
                    decisionJson.substring(split),
                    "tool_calls"
            ) + "data: [DONE]\n\n";
            send(exchange, 200, stream);
        });

        ProviderCapabilities capabilities = new ProviderCapabilities(
                Protocol.CHAT_COMPLETIONS,
                OutputContract.FORCED_FUNCTION,
                true,
                true,
                ChatTokenField.MAX_COMPLETION_TOKENS,
                ReasoningControl.DISABLED
        );
        try (JdkModelGateway gateway = gateway(
                server,
                () -> "placeholder-only".toCharArray(),
                capabilities
        )) {
            ModelOutcome.Success success = assertInstanceOf(
                    ModelOutcome.Success.class,
                    await(gateway.decide(input(expectedDecision)))
            );

            assertEquals(expectedDecision, success.decision());
            assertEquals("/v1/chat/completions", requestPath.get());
            JsonObject sent = GSON.fromJson(requestBody.get(), JsonObject.class);
            assertTrue(sent.get("stream").getAsBoolean());
            assertTrue(sent.has("tools"));
            assertEquals(
                    "submit_decision",
                    sent.getAsJsonObject("tool_choice")
                            .getAsJsonObject("function")
                            .get("name")
                            .getAsString()
            );
            assertEquals(512, sent.get("max_completion_tokens").getAsInt());
            assertEquals(0.2, sent.get("temperature").getAsDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stripsProviderFillersFromAnExactParameterlessCompound()
            throws Exception {
        final DecisionEnvelope providerDecision = new DecisionEnvelope(
                "req-parameterless",
                15,
                6,
                DecisionKind.START_SKILL,
                "secure_visible_food_reserve",
                java.util.List.of(
                        new SkillArgument("hand", "main_hand")
                ),
                RequestedObservation.none(),
                "Securing food.",
                0.9
        );
        final HttpServer server = startServer(exchange -> {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            send(
                    exchange,
                    200,
                    responsesBody(providerDecision, 20, 4, 24)
            );
        });

        try (JdkModelGateway gateway = gateway(
                server,
                () -> "placeholder-only".toCharArray(),
                ProviderCapabilities.responsesJsonSchema(false)
        )) {
            final ModelOutcome.Success success = assertInstanceOf(
                    ModelOutcome.Success.class,
                    await(gateway.decide(input(
                            providerDecision,
                            Map.of(
                                    "secure_visible_food_reserve",
                                    arguments -> arguments.isEmpty()
                                            ? java.util.Optional.empty()
                                            : java.util.Optional.of(
                                                    "unexpected_arguments"
                                            )
                            )
                    )))
            );

            assertEquals(
                    DecisionKind.START_SKILL,
                    success.decision().decision()
            );
            assertEquals(
                    "secure_visible_food_reserve",
                    success.decision().skillName()
            );
            assertTrue(success.decision().typedArguments().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesAuthenticationErrorsWithoutRetainingProviderBodyOrCredential() throws Exception {
        String placeholderCredential = "do-not-retain-this-placeholder";
        HttpServer server = startServer(exchange -> send(
                exchange,
                401,
                """
                        {"error":{
                          "type":"invalid_request_error",
                          "code":"invalid_api_key",
                          "message":"credential do-not-retain-this-placeholder was rejected"
                        }}
                        """
        ));

        DecisionEnvelope expectedDecision = safeIdle("req-auth", 1, 1);
        try (JdkModelGateway gateway = gateway(
                server,
                () -> placeholderCredential.toCharArray(),
                ProviderCapabilities.responsesJsonSchema(false)
        )) {
            ModelOutcome.Failure failure = assertInstanceOf(
                    ModelOutcome.Failure.class,
                    await(gateway.decide(input(expectedDecision)))
            );

            assertEquals(ModelFailureKind.AUTHENTICATION, failure.error().kind());
            assertEquals(401, failure.error().httpStatus());
            assertEquals("invalid_api_key", failure.error().providerCode());
            assertTrue(!failure.toString().contains(placeholderCredential));
            assertEquals(64, failure.error().diagnosticHash().length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesOneInFlightRequestAndRecoversAfterCompletion() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        DecisionEnvelope firstDecision = safeIdle("req-first", 4, 9);
        HttpServer server = startServer(exchange -> {
            requestArrived.countDown();
            try {
                if (!releaseResponse.await(3, TimeUnit.SECONDS)) {
                    send(exchange, 500, "{}");
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                send(exchange, 500, "{}");
                return;
            }
            send(exchange, 200, responsesBody(firstDecision, 1, 1, 2));
        });

        try (JdkModelGateway gateway = gateway(
                server,
                () -> "placeholder-only".toCharArray(),
                ProviderCapabilities.responsesJsonSchema(false)
        )) {
            CompletionStage<ModelOutcome> first = gateway.decide(input(firstDecision));
            assertTrue(requestArrived.await(2, TimeUnit.SECONDS));
            assertEquals(GatewayStatus.REQUESTING, gateway.status());

            DecisionEnvelope secondDecision = safeIdle("req-second", 4, 9);
            ModelOutcome.Failure busy = assertInstanceOf(
                    ModelOutcome.Failure.class,
                    await(gateway.decide(input(secondDecision)))
            );
            assertEquals(ModelFailureKind.BUSY, busy.error().kind());

            releaseResponse.countDown();
            assertInstanceOf(ModelOutcome.Success.class, await(first));
            assertEquals(GatewayStatus.IDLE, gateway.status());
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void hardDeadlineCoversSlowResponseBodyAndReleasesSingleFlight()
            throws Exception {
        CountDownLatch firstBodyStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        DecisionEnvelope firstDecision = safeIdle("req-slow-body", 5, 11);
        DecisionEnvelope secondDecision = safeIdle("req-after-timeout", 5, 11);
        HttpServer server = startServer(exchange -> {
            readRequestBody(exchange);
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set(
                        "Content-Type",
                        "application/json"
                );
                exchange.sendResponseHeaders(200, 0);
                OutputStream body = exchange.getResponseBody();
                body.write('{');
                body.flush();
                firstBodyStarted.countDown();
                try {
                    releaseFirstBody.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                try {
                    body.write(responsesBody(firstDecision, 1, 1, 2)
                            .substring(1)
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The expected client-side timeout closes this body.
                }
                return;
            }
            send(exchange, 200, responsesBody(secondDecision, 1, 1, 2));
        });

        try (JdkModelGateway gateway = gateway(
                server,
                () -> "placeholder-only".toCharArray(),
                ProviderCapabilities.responsesJsonSchema(false),
                Duration.ofMillis(300)
        )) {
            long started = System.nanoTime();
            CompletionStage<ModelOutcome> pending =
                    gateway.decide(input(firstDecision));
            assertTrue(firstBodyStarted.await(1, TimeUnit.SECONDS));

            ModelOutcome.Failure timeout = assertInstanceOf(
                    ModelOutcome.Failure.class,
                    await(pending)
            );
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );
            assertEquals(ModelFailureKind.TIMEOUT, timeout.error().kind());
            assertTrue(elapsedMillis < 2_000);
            assertEquals(GatewayStatus.IDLE, gateway.status());

            releaseFirstBody.countDown();
            ModelOutcome.Success recovered = assertInstanceOf(
                    ModelOutcome.Success.class,
                    await(gateway.decide(input(secondDecision)))
            );
            assertEquals(secondDecision, recovered.decision());
            assertEquals(2, requests.get());
        } finally {
            releaseFirstBody.countDown();
            server.stop(0);
        }
    }

    private static JdkModelGateway gateway(
            HttpServer server,
            SecretSource secretSource,
            ProviderCapabilities capabilities
    ) throws EndpointValidationException {
        return gateway(server, secretSource, capabilities, HARD_TIMEOUT);
    }

    private static JdkModelGateway gateway(
            HttpServer server,
            SecretSource secretSource,
            ProviderCapabilities capabilities,
            Duration hardTimeout
    ) throws EndpointValidationException {
        int port = server.getAddress().getPort();
        ModelEndpoint endpoint = new EndpointValidator().validate(
                "http://127.0.0.1:" + port + "/v1",
                "test-model"
        );
        return new JdkModelGateway(
                endpoint,
                secretSource,
                capabilities,
                CONNECT_TIMEOUT,
                hardTimeout
        );
    }

    private static PlannerInput input(DecisionEnvelope expectedDecision) {
        return input(expectedDecision, Map.of());
    }

    private static PlannerInput input(
            DecisionEnvelope expectedDecision,
            Map<String, SkillArgumentValidator> availableSkills
    ) {
        return new PlannerInput(
                new DecisionContext(
                        expectedDecision.requestId(),
                        expectedDecision.observedWorldRevision(),
                        expectedDecision.goalRevision(),
                        false,
                        availableSkills
                ),
                "You are a local high-level planner.",
                "{\"visibleBlocks\":[],\"health\":20}",
                512
        );
    }

    private static DecisionEnvelope safeIdle(
            String requestId,
            long worldRevision,
            long goalRevision
    ) {
        return new DecisionEnvelope(
                requestId,
                worldRevision,
                goalRevision,
                DecisionKind.SAFE_IDLE,
                "",
                java.util.List.of(),
                RequestedObservation.none(),
                "",
                0.85
        );
    }

    private static String responsesBody(
            DecisionEnvelope decision,
            long inputTokens,
            long outputTokens,
            long totalTokens
    ) {
        JsonObject outputText = new JsonObject();
        outputText.addProperty("type", "output_text");
        outputText.addProperty("text", new DecisionEnvelopeCodec().encode(decision));
        JsonArray content = new JsonArray();
        content.add(outputText);
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.add("content", content);
        JsonArray output = new JsonArray();
        output.add(message);

        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", inputTokens);
        usage.addProperty("output_tokens", outputTokens);
        usage.addProperty("total_tokens", totalTokens);

        JsonObject response = new JsonObject();
        response.addProperty("id", "response-test");
        response.addProperty("object", "response");
        response.addProperty("status", "completed");
        response.add("output", output);
        response.add("usage", usage);
        return GSON.toJson(response);
    }

    private static String chatToolChunk(
            String functionName,
            String arguments,
            String finishReason
    ) {
        JsonObject function = new JsonObject();
        if (!functionName.isEmpty()) {
            function.addProperty("name", functionName);
        }
        function.addProperty("arguments", arguments);
        JsonObject call = new JsonObject();
        call.addProperty("index", 0);
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject delta = new JsonObject();
        delta.add("tool_calls", calls);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("delta", delta);
        if (finishReason.isEmpty()) {
            choice.add("finish_reason", null);
        } else {
            choice.addProperty("finish_reason", finishReason);
        }
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject chunk = new JsonObject();
        chunk.add("choices", choices);
        return "data: " + GSON.toJson(chunk) + "\n\n";
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return server;
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ModelOutcome await(CompletionStage<ModelOutcome> stage) throws Exception {
        return stage.toCompletableFuture().get(6, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
