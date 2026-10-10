package dev.mcai.companion.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit, one-shot capability negotiation for one model.
 *
 * <p>The probe always tries the Responses API first and downgrades its output
 * contract only when the provider explicitly names the current capability as
 * unsupported. Chat Completions is tried only after an explicit Responses
 * endpoint rejection. Authentication, authorization, billing, missing-model,
 * rate-limit, server, timeout, and transport failures are terminal because
 * another request could duplicate cost. If a provider accepts a strict output
 * contract but emits malformed text, one explicit plain-JSON request is
 * allowed: this does not switch endpoints and records only the weaker
 * capability that was actually verified.</p>
 */
public final class JdkProviderCapabilityProbe implements ProviderCapabilityProbe {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /*
     * Capability negotiation is provider inference too. Applying a shorter
     * deadline than ordinary decisions made a cold or temporarily queued
     * compatible endpoint look permanently unsupported during dedicated
     * server startup. Keep the same hard deadline as the production model
     * lane; the 5 second connect timeout still rejects unreachable hosts
     * promptly.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    public static final int MAX_PROBE_RESPONSE_BYTES = 256 * 1_024;
    private static final int MAX_SECRET_CHARS = 8_192;
    private static final int MAX_ERROR_RESPONSE_BYTES = 65_536;
    /*
     * Completion budgets can include hidden/reported reasoning tokens on
     * OpenAI-compatible reasoning models. Sixteen tokens is therefore not
     * enough even when the visible contract is one tiny JSON object.
     */
    private static final int PROBE_OUTPUT_TOKENS = 512;
    private static final String PROBE_PROMPT =
            "Return exactly this JSON object and nothing else: {\"probe\":\"ok\"}";
    private static final String IMAGE_PROBE_DATA_URL =
            "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAA"
            + "KklEQVR42mMI6DlBU8QwasGoBaMWjFowasGoBaMWjFowasGo"
            + "BaMWDBULAIDnkFsPn1rVAAAAAElFTkSuQmCC";
    private static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().create();

    private static final OutputContract[] OUTPUT_CONTRACT_PREFERENCE = {
            OutputContract.JSON_SCHEMA,
            OutputContract.FORCED_FUNCTION,
            OutputContract.JSON_OBJECT,
            OutputContract.PLAIN_JSON
    };

    private final ModelEndpoint endpoint;
    private final SecretSource secretSource;
    private final Duration requestTimeout;
    private final boolean probeImageInput;
    private final ProviderErrorClassifier errorClassifier =
            new ProviderErrorClassifier();
    private final ModelResponseExtractor responseExtractor =
            new ModelResponseExtractor();
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient;
    private final AtomicReference<CompletableFuture<CapabilityProbeOutcome>> invocation =
            new AtomicReference<>();
    private final AtomicReference<ProbeExchange> activeExchange =
            new AtomicReference<>();
    private final AtomicInteger requestsMade = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdkProviderCapabilityProbe(
            ModelEndpoint endpoint,
            SecretSource secretSource
    ) {
        this(
                endpoint,
                secretSource,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                false
        );
    }

    public JdkProviderCapabilityProbe(
            ModelEndpoint endpoint,
            SecretSource secretSource,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this(
                endpoint,
                secretSource,
                connectTimeout,
                requestTimeout,
                false
        );
    }

    public JdkProviderCapabilityProbe(
            ModelEndpoint endpoint,
            SecretSource secretSource,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean probeImageInput
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.secretSource = Objects.requireNonNull(secretSource, "secretSource");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.probeImageInput = probeImageInput;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    @Override
    public CompletionStage<CapabilityProbeOutcome> probe() {
        CompletableFuture<CapabilityProbeOutcome> existing = invocation.get();
        if (existing != null) {
            return existing;
        }

        CompletableFuture<CapabilityProbeOutcome> created = new CompletableFuture<>();
        if (!invocation.compareAndSet(null, created)) {
            return invocation.get();
        }
        if (closed.get()) {
            created.complete(failure(
                    ModelFailureKind.CANCELLED,
                    "The capability probe is closed"
            ));
            return created;
        }

        try {
            executor.submit(() -> {
                try {
                    created.complete(runProbe());
                } catch (RuntimeException exception) {
                    created.complete(failure(
                            ModelFailureKind.INTERNAL,
                            "The capability probe failed locally"
                    ));
                }
            });
        } catch (RuntimeException exception) {
            created.complete(failure(
                    ModelFailureKind.CANCELLED,
                    "The capability probe could not be started"
            ));
        }
        return created;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<CapabilityProbeOutcome> current = invocation.get();
        if (current != null && !current.isDone()) {
            current.complete(failure(
                    ModelFailureKind.CANCELLED,
                    "The capability probe was closed"
            ));
        }
        ProbeExchange exchange = activeExchange.getAndSet(null);
        if (exchange != null) {
            exchange.cancel();
        }
        executor.shutdownNow();
    }

    private CapabilityProbeOutcome runProbe() {
        ProtocolAttempt responses = probeProtocol(
                Protocol.RESPONSES,
                ChatTokenField.MAX_COMPLETION_TOKENS
        );
        if (responses.succeeded()) {
            return supported(withVerifiedImageInput(
                    responses.capabilities()
            ));
        }
        if (responses.failure().kind() != ModelFailureKind.ENDPOINT_UNSUPPORTED) {
            return failed(responses.failure());
        }

        ProtocolAttempt chat = probeProtocol(
                Protocol.CHAT_COMPLETIONS,
                ChatTokenField.MAX_COMPLETION_TOKENS
        );
        if (chat.succeeded()) {
            return supported(withVerifiedImageInput(chat.capabilities()));
        }
        return failed(chat.failure());
    }

    private ProviderCapabilities withVerifiedImageInput(
            final ProviderCapabilities textCapabilities
    ) {
        if (!probeImageInput) {
            return textCapabilities;
        }
        final Attempt imageAttempt = execute(
                textCapabilities.protocol(),
                textCapabilities.outputContract(),
                textCapabilities.chatTokenField(),
                textCapabilities.reasoningControl(),
                true
        );
        return textCapabilities.withImageInput(imageAttempt.succeeded());
    }

    private ProtocolAttempt probeProtocol(
            Protocol protocol,
            ChatTokenField initialChatTokenField
    ) {
        ChatTokenField chatTokenField = initialChatTokenField;
        ReasoningControl reasoningControl = ReasoningControl.DISABLED;
        for (OutputContract outputContract : OUTPUT_CONTRACT_PREFERENCE) {
            while (true) {
                Attempt attempt = execute(
                        protocol,
                        outputContract,
                        chatTokenField,
                        reasoningControl
                );
                if (attempt.succeeded()) {
                    final OutputContract negotiated =
                            attempt.outputContract();
                    return ProtocolAttempt.supported(new ProviderCapabilities(
                            protocol,
                            negotiated,
                            negotiated == OutputContract.JSON_SCHEMA
                                    || negotiated
                                        == OutputContract.FORCED_FUNCTION,
                            false,
                            chatTokenField,
                            reasoningControl
                    ));
                }

                ModelFailure failure = attempt.failure();
                if (failure.kind() == ModelFailureKind.ENDPOINT_UNSUPPORTED) {
                    return ProtocolAttempt.failed(failure);
                }
                if (reasoningControl != ReasoningControl.DEFAULT
                        && explicitlyRejectsReasoningControl(
                                failure,
                                protocol,
                                reasoningControl
                        )) {
                    reasoningControl = reasoningControl
                            == ReasoningControl.DISABLED
                                    ? ReasoningControl.LOW
                                    : ReasoningControl.DEFAULT;
                    continue;
                }
                if (protocol == Protocol.CHAT_COMPLETIONS
                        && chatTokenField == ChatTokenField.MAX_COMPLETION_TOKENS
                        && explicitlyRejectsTokenField(
                                failure,
                                ChatTokenField.MAX_COMPLETION_TOKENS
                        )) {
                    chatTokenField = ChatTokenField.MAX_TOKENS;
                    continue;
                }
                if (explicitlyRejectsOutputContract(
                        failure,
                        protocol,
                        outputContract
                )) {
                    break;
                }
                if (failure.kind() == ModelFailureKind.MALFORMED_RESPONSE
                        && outputContract != OutputContract.PLAIN_JSON) {
                    /*
                     * Compatible reasoning models can occasionally accept a
                     * schema/tool request but answer in an untrustworthy text
                     * shape. Do not claim that strict capability and do not
                     * fan out across every intermediate contract. One direct
                     * plain-JSON probe gives a bounded recovery opportunity
                     * and proves exactly what the gateway will use.
                     */
                    final Attempt plainJson = execute(
                            protocol,
                            OutputContract.PLAIN_JSON,
                            chatTokenField,
                            reasoningControl
                    );
                    if (plainJson.succeeded()) {
                        return ProtocolAttempt.supported(
                                new ProviderCapabilities(
                                        protocol,
                                        OutputContract.PLAIN_JSON,
                                        false,
                                        false,
                                        chatTokenField,
                                        reasoningControl
                                )
                        );
                    }
                    return ProtocolAttempt.failed(plainJson.failure());
                }
                return ProtocolAttempt.failed(failure);
            }
        }
        throw new IllegalStateException(
                "PLAIN_JSON has no optional output capability to reject"
        );
    }

    private Attempt execute(
            Protocol protocol,
            OutputContract outputContract,
            ChatTokenField chatTokenField,
            ReasoningControl reasoningControl
    ) {
        return execute(
                protocol,
                outputContract,
                chatTokenField,
                reasoningControl,
                false
        );
    }

    private Attempt execute(
            Protocol protocol,
            OutputContract outputContract,
            ChatTokenField chatTokenField,
            ReasoningControl reasoningControl,
            boolean includeImage
    ) {
        if (closed.get()) {
            return Attempt.failed(localFailure(
                    ModelFailureKind.CANCELLED,
                    "",
                    "The capability probe is closed"
            ));
        }

        String clientRequestId = "mcai-capability-" + UUID.randomUUID();
        final HttpRequest request;
        try {
            request = createRequest(
                    protocol,
                    outputContract,
                    chatTokenField,
                    reasoningControl,
                    clientRequestId,
                    includeImage
            );
        } catch (RuntimeException exception) {
            return Attempt.failed(localFailure(
                    ModelFailureKind.INVALID_CONFIGURATION,
                    clientRequestId,
                    "The capability probe request could not be constructed"
            ));
        }

        ProbeExchange exchange = new ProbeExchange();
        if (!activeExchange.compareAndSet(null, exchange)) {
            return Attempt.failed(localFailure(
                    ModelFailureKind.INTERNAL,
                    clientRequestId,
                    "Another capability request is unexpectedly active"
            ));
        }
        try {
            requestsMade.incrementAndGet();
            Future<Attempt> task = executor.submit(
                    () -> executeTransport(
                            request,
                            protocol,
                            outputContract,
                            chatTokenField,
                            reasoningControl,
                            clientRequestId,
                            exchange
                    )
            );
            exchange.task.set(task);
            if (exchange.cancelled.get()) {
                task.cancel(true);
            }
            return task.get(
                    requestTimeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException exception) {
            exchange.cancel();
            return Attempt.failed(localFailure(
                    ModelFailureKind.TIMEOUT,
                    clientRequestId,
                    "The capability probe timed out"
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.cancel();
            return Attempt.failed(localFailure(
                    ModelFailureKind.CANCELLED,
                    clientRequestId,
                    "The capability probe was cancelled"
            ));
        } catch (ExecutionException exception) {
            return Attempt.failed(localFailure(
                    ModelFailureKind.INTERNAL,
                    clientRequestId,
                    "The capability probe failed locally"
            ));
        } catch (CancellationException exception) {
            return Attempt.failed(localFailure(
                    ModelFailureKind.CANCELLED,
                    clientRequestId,
                    "The capability probe was cancelled"
            ));
        } catch (RuntimeException exception) {
            return Attempt.failed(localFailure(
                    closed.get()
                            ? ModelFailureKind.CANCELLED
                            : ModelFailureKind.INTERNAL,
                    clientRequestId,
                    closed.get()
                            ? "The capability probe was closed"
                            : "The capability probe failed locally"
            ));
        } finally {
            activeExchange.compareAndSet(exchange, null);
        }
    }

    private Attempt executeTransport(
            HttpRequest request,
            Protocol protocol,
            OutputContract outputContract,
            ChatTokenField chatTokenField,
            ReasoningControl reasoningControl,
            String clientRequestId,
            ProbeExchange exchange
    ) {
        final HttpResponse<InputStream> response;
        try {
            CompletableFuture<HttpResponse<InputStream>> transport =
                    httpClient.sendAsync(
                            request,
                            HttpResponse.BodyHandlers.ofInputStream()
                    );
            exchange.transport.set(transport);
            if (exchange.cancelled.get()) {
                transport.cancel(true);
                return cancelledAttempt(clientRequestId);
            }
            response = transport.join();
            exchange.responseBody.set(response.body());
            if (exchange.cancelled.get()) {
                closeQuietly(response.body());
                return cancelledAttempt(clientRequestId);
            }
        } catch (CompletionException exception) {
            Throwable cause = unwrap(exception);
            if (exchange.cancelled.get()
                    || cause instanceof CancellationException) {
                return cancelledAttempt(clientRequestId);
            }
            if (cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return Attempt.failed(localFailure(
                        ModelFailureKind.TIMEOUT,
                        clientRequestId,
                        "The capability probe timed out"
                ));
            }
            if (cause instanceof IOException) {
                return Attempt.failed(localFailure(
                        ModelFailureKind.NETWORK_TRANSIENT,
                        clientRequestId,
                        "The model provider could not be reached"
                ));
            }
            return Attempt.failed(localFailure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    clientRequestId,
                    "The capability probe transport failed"
            ));
        } catch (RuntimeException exception) {
            return Attempt.failed(localFailure(
                    exchange.cancelled.get()
                            ? ModelFailureKind.CANCELLED
                            : ModelFailureKind.NETWORK_TRANSIENT,
                    clientRequestId,
                    exchange.cancelled.get()
                            ? "The capability probe was cancelled"
                            : "The capability probe transport failed"
            ));
        }

        int maximumBytes = response.statusCode() >= 200
                && response.statusCode() < 300
                ? MAX_PROBE_RESPONSE_BYTES
                : MAX_ERROR_RESPONSE_BYTES;
        final String body;
        try {
            body = BoundedBodyReader.readUtf8(response.body(), maximumBytes);
        } catch (IOException exception) {
            if (exchange.cancelled.get()) {
                return cancelledAttempt(clientRequestId);
            }
            return Attempt.failed(localFailure(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    clientRequestId,
                    "The capability probe response was malformed or exceeded limits"
            ));
        }

        Optional<ModelFailure> providerFailure = errorClassifier.classify(
                response.statusCode(),
                response.headers(),
                body,
                clientRequestId,
                capabilityFields(
                        protocol,
                        outputContract,
                        chatTokenField,
                        reasoningControl
                )
        );
        if (providerFailure.isPresent()) {
            return Attempt.failed(providerFailure.orElseThrow());
        }
        return validateSuccessfulResponse(
                protocol,
                outputContract,
                body,
                clientRequestId
        );
    }

    private static Attempt cancelledAttempt(String clientRequestId) {
        return Attempt.failed(localFailure(
                ModelFailureKind.CANCELLED,
                clientRequestId,
                "The capability probe was cancelled"
        ));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation also interrupts the virtual-thread worker.
        }
    }

    private Attempt validateSuccessfulResponse(
            Protocol protocol,
            OutputContract outputContract,
            String body,
            String clientRequestId
    ) {
        try {
            ExtractedModelResponse extracted = responseExtractor.extract(
                    protocol,
                    outputContract,
                    false,
                    body
            );
            return validateProbeJson(
                    extracted.decisionJson(),
                    outputContract,
                    protocol,
                    body,
                    clientRequestId
            );
        } catch (ModelResponseException exception) {
            /*
             * Some OpenAI-compatible providers accept a forced-function
             * request but intermittently return the requested JSON in the
             * ordinary text channel. That same response proves only plain
             * JSON support, not tool support. Reclassify it without issuing
             * another billable request.
             */
            if (outputContract == OutputContract.FORCED_FUNCTION) {
                try {
                    final ExtractedModelResponse plain =
                            responseExtractor.extract(
                                    protocol,
                                    OutputContract.PLAIN_JSON,
                                    false,
                                    body
                            );
                    return validateProbeJson(
                            plain.decisionJson(),
                            OutputContract.PLAIN_JSON,
                            protocol,
                            body,
                            clientRequestId
                    );
                } catch (ModelResponseException
                        | IOException
                        | RuntimeException ignored) {
                    // Preserve the original strict-contract failure below.
                }
            }
            return malformedProbe(
                    clientRequestId,
                    protocol,
                    body,
                    exception.kind()
            );
        } catch (IOException | RuntimeException exception) {
            return malformedProbe(
                    clientRequestId,
                    protocol,
                    body,
                    ModelFailureKind.MALFORMED_RESPONSE
            );
        }
    }

    private static Attempt validateProbeJson(
            final String decisionJson,
            final OutputContract negotiatedContract,
            final Protocol protocol,
            final String body,
            final String clientRequestId
    ) throws IOException {
        final JsonElement parsed = BoundedJsonParser.parse(
                decisionJson,
                1_024,
                4,
                8
        );
        if (!parsed.isJsonObject()) {
            return malformedProbe(
                    clientRequestId,
                    protocol,
                    body,
                    ModelFailureKind.MALFORMED_RESPONSE
            );
        }
        final JsonObject object = parsed.getAsJsonObject();
        if (!object.keySet().equals(Set.of("probe"))) {
            return malformedProbe(
                    clientRequestId,
                    protocol,
                    body,
                    ModelFailureKind.MALFORMED_RESPONSE
            );
        }
        final JsonElement value = object.get("probe");
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().equals("ok")) {
            return malformedProbe(
                    clientRequestId,
                    protocol,
                    body,
                    ModelFailureKind.MALFORMED_RESPONSE
            );
        }
        return Attempt.supported(negotiatedContract);
    }

    private static Attempt malformedProbe(
            String clientRequestId,
            Protocol protocol,
            String body,
            ModelFailureKind kind
    ) {
        String shape = ProviderResponseShape.summarize(protocol, body);
        return Attempt.failed(localFailure(
                kind,
                clientRequestId,
                "The capability probe response did not satisfy its contract"
                        + " (safe shape: " + shape + ")"
        ));
    }

    private HttpRequest createRequest(
            Protocol protocol,
            OutputContract outputContract,
            ChatTokenField chatTokenField,
            ReasoningControl reasoningControl,
            String clientRequestId,
            boolean includeImage
    ) {
        String body = protocol == Protocol.RESPONSES
                ? responsesRequestBody(
                        outputContract,
                        reasoningControl,
                        includeImage
                )
                : chatRequestBody(
                        outputContract,
                        chatTokenField,
                        reasoningControl,
                        includeImage
                );
        char[] secret = secretSource.acquire();
        if (secret == null || secret.length == 0 || secret.length > MAX_SECRET_CHARS) {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
            throw new IllegalArgumentException("API credential is missing or invalid");
        }
        for (char character : secret) {
            if (Character.isISOControl(character)) {
                Arrays.fill(secret, '\0');
                throw new IllegalArgumentException(
                        "API credential contains a control character"
                );
            }
        }

        final HttpRequest.Builder request = HttpRequest.newBuilder(
                endpoint.endpoint(protocol)
        )
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Client-Request-Id", clientRequestId);
        try {
            ModelApiAuthentication.apply(request, endpoint, secret);
            return request
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body,
                            StandardCharsets.UTF_8
                    ))
                    .build();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private String responsesRequestBody(
            OutputContract outputContract,
            ReasoningControl reasoningControl,
            boolean includeImage
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", endpoint.modelName());
        root.addProperty("store", false);
        root.addProperty("stream", false);
        root.addProperty("max_output_tokens", PROBE_OUTPUT_TOKENS);
        if (includeImage) {
            JsonObject image = new JsonObject();
            image.addProperty("type", "input_image");
            image.addProperty("image_url", IMAGE_PROBE_DATA_URL);
            image.addProperty("detail", "low");
            JsonObject text = new JsonObject();
            text.addProperty("type", "input_text");
            text.addProperty("text", PROBE_PROMPT);
            JsonArray content = new JsonArray();
            content.add(image);
            content.add(text);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);
            JsonArray input = new JsonArray();
            input.add(message);
            root.add("input", input);
        } else {
            root.addProperty("input", PROBE_PROMPT);
        }
        if (reasoningControl != ReasoningControl.DEFAULT) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty(
                    "effort",
                    reasoningControl == ReasoningControl.DISABLED
                            ? "none"
                            : "low"
            );
            root.add("reasoning", reasoning);
        }
        applyResponsesOutputContract(root, outputContract);
        return GSON.toJson(root);
    }

    private String chatRequestBody(
            OutputContract outputContract,
            ChatTokenField tokenField,
            ReasoningControl reasoningControl,
            boolean includeImage
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", endpoint.modelName());
        root.addProperty("stream", false);
        root.addProperty(tokenField.jsonName(), PROBE_OUTPUT_TOKENS);
        if (reasoningControl == ReasoningControl.DISABLED) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            root.add("thinking", thinking);
        } else if (reasoningControl == ReasoningControl.LOW) {
            root.addProperty("reasoning_effort", "low");
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        if (includeImage) {
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", IMAGE_PROBE_DATA_URL);
            imageUrl.addProperty("detail", "low");
            JsonObject image = new JsonObject();
            image.addProperty("type", "image_url");
            image.add("image_url", imageUrl);
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", PROBE_PROMPT);
            JsonArray content = new JsonArray();
            content.add(image);
            content.add(text);
            message.add("content", content);
        } else {
            message.addProperty("content", PROBE_PROMPT);
        }
        JsonArray messages = new JsonArray();
        messages.add(message);
        root.add("messages", messages);

        applyChatOutputContract(root, outputContract);
        return GSON.toJson(root);
    }

    private static void applyResponsesOutputContract(
            JsonObject root,
            OutputContract outputContract
    ) {
        switch (outputContract) {
            case JSON_SCHEMA -> {
                JsonObject text = new JsonObject();
                text.add("format", schemaFormat());
                root.add("text", text);
            }
            case FORCED_FUNCTION -> {
                JsonObject function = responsesFunction();
                JsonArray tools = new JsonArray();
                tools.add(function);
                root.add("tools", tools);

                JsonObject choice = new JsonObject();
                choice.addProperty("type", "function");
                choice.addProperty(
                        "name",
                        ModelRequestFactory.DECISION_FUNCTION_NAME
                );
                root.add("tool_choice", choice);
                root.addProperty("parallel_tool_calls", false);
            }
            case JSON_OBJECT -> {
                JsonObject format = new JsonObject();
                format.addProperty("type", "json_object");
                JsonObject text = new JsonObject();
                text.add("format", format);
                root.add("text", text);
            }
            case PLAIN_JSON -> {
                // The prompt still requires one bare JSON object.
            }
        }
    }

    private static void applyChatOutputContract(
            JsonObject root,
            OutputContract outputContract
    ) {
        switch (outputContract) {
            case JSON_SCHEMA -> {
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_schema");
                JsonObject schema = schemaFormat();
                schema.remove("type");
                responseFormat.add("json_schema", schema);
                root.add("response_format", responseFormat);
            }
            case FORCED_FUNCTION -> {
                JsonObject function = responsesFunction();
                function.remove("type");
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                JsonArray tools = new JsonArray();
                tools.add(tool);
                root.add("tools", tools);

                JsonObject selectedFunction = new JsonObject();
                selectedFunction.addProperty(
                        "name",
                        ModelRequestFactory.DECISION_FUNCTION_NAME
                );
                JsonObject choice = new JsonObject();
                choice.addProperty("type", "function");
                choice.add("function", selectedFunction);
                root.add("tool_choice", choice);
                root.addProperty("parallel_tool_calls", false);
            }
            case JSON_OBJECT -> {
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_object");
                root.add("response_format", responseFormat);
            }
            case PLAIN_JSON -> {
                // The prompt still requires one bare JSON object.
            }
        }
    }

    private static JsonObject responsesFunction() {
        JsonObject function = new JsonObject();
        function.addProperty("type", "function");
        function.addProperty(
                "name",
                ModelRequestFactory.DECISION_FUNCTION_NAME
        );
        function.addProperty(
                "description",
                "Return the capability probe result"
        );
        function.addProperty("strict", true);
        function.add("parameters", probeSchema());
        return function;
    }

    private static JsonObject schemaFormat() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "json_schema");
        value.addProperty("name", "mcai_capability_probe");
        value.addProperty("strict", true);
        value.add("schema", probeSchema());
        return value;
    }

    private static JsonObject probeSchema() {
        JsonObject probe = new JsonObject();
        probe.addProperty("type", "string");
        JsonArray allowed = new JsonArray();
        allowed.add("ok");
        probe.add("enum", allowed);
        JsonObject properties = new JsonObject();
        properties.add("probe", probe);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("probe");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static Set<String> capabilityFields(
            Protocol protocol,
            OutputContract outputContract,
            ChatTokenField chatTokenField,
            ReasoningControl reasoningControl
    ) {
        HashSet<String> fields = new HashSet<>(
                outputCapabilityFields(protocol, outputContract)
        );
        if (protocol == Protocol.CHAT_COMPLETIONS) {
            fields.add(chatTokenField.jsonName());
        }
        if (reasoningControl != ReasoningControl.DEFAULT) {
            fields.addAll(reasoningCapabilityFields(
                    protocol,
                    reasoningControl
            ));
        }
        return Set.copyOf(fields);
    }

    private static Set<String> reasoningCapabilityFields(
            final Protocol protocol,
            final ReasoningControl reasoningControl
    ) {
        if (protocol == Protocol.RESPONSES) {
            return Set.of("reasoning", "reasoning.effort");
        }
        return reasoningControl == ReasoningControl.DISABLED
                ? Set.of("thinking", "thinking.type")
                : Set.of("reasoning_effort");
    }

    private static Set<String> outputCapabilityFields(
            Protocol protocol,
            OutputContract outputContract
    ) {
        return switch (outputContract) {
            case JSON_SCHEMA -> protocol == Protocol.RESPONSES
                    ? Set.of("text", "text.format", "json_schema", "strict")
                    : Set.of("response_format", "json_schema", "strict");
            case FORCED_FUNCTION -> Set.of(
                    "tools",
                    "tool_choice",
                    "parallel_tool_calls",
                    "strict"
            );
            case JSON_OBJECT -> protocol == Protocol.RESPONSES
                    ? Set.of("text", "text.format")
                    : Set.of("response_format");
            case PLAIN_JSON -> Set.of();
        };
    }

    private static boolean explicitlyRejectsOutputContract(
            ModelFailure failure,
            Protocol protocol,
            OutputContract outputContract
    ) {
        if (failure.kind() != ModelFailureKind.CAPABILITY_UNSUPPORTED) {
            return false;
        }
        String rejected = failure.providerParam();
        return outputCapabilityFields(protocol, outputContract).stream()
                .anyMatch(field -> isSameOrDescendant(rejected, field));
    }

    private static boolean explicitlyRejectsTokenField(
            ModelFailure failure,
            ChatTokenField field
    ) {
        String rejected = failure.providerParam();
        return failure.kind() == ModelFailureKind.CAPABILITY_UNSUPPORTED
                && isSameOrDescendant(rejected, field.jsonName());
    }

    private static boolean explicitlyRejectsReasoningControl(
            final ModelFailure failure,
            final Protocol protocol,
            final ReasoningControl reasoningControl
    ) {
        if (failure.kind() != ModelFailureKind.CAPABILITY_UNSUPPORTED) {
            return false;
        }
        String rejected = failure.providerParam();
        return reasoningCapabilityFields(protocol, reasoningControl).stream()
                .anyMatch(field -> isSameOrDescendant(rejected, field));
    }

    private static boolean isSameOrDescendant(String value, String field) {
        return value.equalsIgnoreCase(field)
                || value.regionMatches(
                        true,
                        0,
                        field + ".",
                        0,
                        field.length() + 1
                )
                || value.regionMatches(
                        true,
                        0,
                        field + "[",
                        0,
                        field.length() + 1
                );
    }

    private CapabilityProbeOutcome supported(ProviderCapabilities capabilities) {
        return new CapabilityProbeOutcome.Supported(
                capabilities,
                requestsMade.get()
        );
    }

    private CapabilityProbeOutcome failed(ModelFailure failure) {
        return new CapabilityProbeOutcome.Failure(
                failure,
                requestsMade.get()
        );
    }

    private CapabilityProbeOutcome failure(
            ModelFailureKind kind,
            String message
    ) {
        return failed(localFailure(kind, "", message));
    }

    private static ModelFailure localFailure(
            ModelFailureKind kind,
            String clientRequestId,
            String message
    ) {
        return new ModelFailure(
                kind,
                0,
                "",
                "",
                clientRequestId,
                "",
                Optional.empty(),
                "",
                message
        );
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static final class ProbeExchange {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<?>> transport =
                new AtomicReference<>();
        private final AtomicReference<InputStream> responseBody =
                new AtomicReference<>();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture<?> currentTransport = transport.get();
            if (currentTransport != null) {
                currentTransport.cancel(true);
            }
            closeQuietly(responseBody.get());
            Future<?> currentTask = task.get();
            if (currentTask != null) {
                currentTask.cancel(true);
            }
        }
    }

    private record Attempt(
            OutputContract outputContract,
            ModelFailure failure
    ) {
        private Attempt {
            if ((outputContract == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Probe attempt must contain either success or failure"
                );
            }
        }

        boolean succeeded() {
            return outputContract != null;
        }

        static Attempt supported(
                final OutputContract outputContract
        ) {
            return new Attempt(
                    Objects.requireNonNull(
                            outputContract,
                            "outputContract"
                    ),
                    null
            );
        }

        static Attempt failed(ModelFailure failure) {
            return new Attempt(
                    null,
                    Objects.requireNonNull(failure, "failure")
            );
        }
    }

    private record ProtocolAttempt(
            ProviderCapabilities capabilities,
            ModelFailure failure
    ) {
        private ProtocolAttempt {
            if ((capabilities == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Protocol attempt must contain either capabilities or failure"
                );
            }
        }

        boolean succeeded() {
            return capabilities != null;
        }

        static ProtocolAttempt supported(ProviderCapabilities capabilities) {
            return new ProtocolAttempt(
                    Objects.requireNonNull(capabilities, "capabilities"),
                    null
            );
        }

        static ProtocolAttempt failed(ModelFailure failure) {
            return new ProtocolAttempt(
                    null,
                    Objects.requireNonNull(failure, "failure")
            );
        }
    }
}
