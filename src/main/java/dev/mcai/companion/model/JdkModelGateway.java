package dev.mcai.companion.model;

import dev.mcai.companion.MinecraftAiCompanion;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java 25 HTTP gateway skeleton with a strict single-flight invariant.
 *
 * <p>It executes a previously verified provider profile. Capability
 * negotiation is intentionally not implicit: opening a world must never cause
 * an unexpected paid API request.</p>
 */
public final class JdkModelGateway implements ModelGateway {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_HARD_TIMEOUT = Duration.ofSeconds(90);
    public static final int MAX_RESPONSE_BYTES = 4 * 1_048_576;
    public static final int MAX_ERROR_RESPONSE_BYTES = 65_536;
    private static final int MAX_SECRET_CHARS = 8_192;
    private static final int MAX_REQUEST_BODY_CHARS = 4_500_000;

    private final ModelEndpoint endpoint;
    private final SecretSource secretSource;
    private final ProviderCapabilities capabilities;
    private final DecisionEnvelopeCodec codec;
    private final DecisionEnvelopeValidator validator;
    private final ProviderErrorClassifier errorClassifier;
    private final ModelRequestFactory requestFactory;
    private final ModelResponseExtractor responseExtractor;
    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService deadlineExecutor;
    private final HttpClient httpClient;
    private final Duration hardTimeout;
    private final AtomicReference<InFlight> inFlight = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdkModelGateway(
            ModelEndpoint endpoint,
            SecretSource secretSource,
            ProviderCapabilities capabilities
    ) {
        this(
                endpoint,
                secretSource,
                capabilities,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_HARD_TIMEOUT
        );
    }

    public JdkModelGateway(
            ModelEndpoint endpoint,
            SecretSource secretSource,
            ProviderCapabilities capabilities,
            Duration connectTimeout,
            Duration hardTimeout
    ) {
        this.endpoint = endpoint;
        this.secretSource = secretSource;
        this.capabilities = capabilities;
        this.hardTimeout = hardTimeout;
        this.codec = new DecisionEnvelopeCodec();
        this.validator = new DecisionEnvelopeValidator();
        this.errorClassifier = new ProviderErrorClassifier();
        this.requestFactory = new ModelRequestFactory(codec);
        this.responseExtractor = new ModelResponseExtractor();
        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("mcai-model-hard-deadline")
                        .factory()
        );
        deadlines.setRemoveOnCancelPolicy(true);
        this.deadlineExecutor = deadlines;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(ioExecutor)
                .build();
    }

    @Override
    public CompletionStage<ModelOutcome> decide(PlannerInput input) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(new ModelOutcome.Failure(
                    localFailure(
                            ModelFailureKind.CANCELLED,
                            input.decisionContext().requestId(),
                            "The model gateway is closed"
                    )
            ));
        }

        CompletableFuture<ModelOutcome> result = new CompletableFuture<>();
        InFlight request = new InFlight(
                input.decisionContext().requestId(),
                input.decisionContext().goalRevision(),
                System.nanoTime(),
                result
        );
        if (!inFlight.compareAndSet(null, request)) {
            return CompletableFuture.completedFuture(new ModelOutcome.Failure(
                    localFailure(
                            ModelFailureKind.BUSY,
                            input.decisionContext().requestId(),
                            "Another model request is already in flight"
                    )
            ));
        }

        final HttpRequest httpRequest;
        try {
            httpRequest = createRequest(input);
        } catch (RuntimeException exception) {
            inFlight.compareAndSet(request, null);
            result.complete(new ModelOutcome.Failure(localFailure(
                    ModelFailureKind.INVALID_CONFIGURATION,
                    request.clientRequestId,
                    "The model request could not be constructed"
            )));
            return result;
        }

        try {
            ScheduledFuture<?> deadlineTask = deadlineExecutor.schedule(
                    () -> timeout(request),
                    hardTimeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
            request.deadlineTask.set(deadlineTask);
            if (request.finished.get()) {
                deadlineTask.cancel(false);
            }
            Future<?> task = ioExecutor.submit(
                    () -> executeRequest(request, input, httpRequest)
            );
            request.task.set(task);
            if (request.finished.get()) {
                task.cancel(true);
            }
        } catch (RuntimeException exception) {
            finish(request, new ModelOutcome.Failure(localFailure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    request.clientRequestId,
                    "The model request could not be started"
            )));
            return result;
        }
        return result;
    }

    @Override
    public boolean configured() {
        return !closed.get();
    }

    private void executeRequest(
            InFlight request,
            PlannerInput input,
            HttpRequest httpRequest
    ) {
        try {
            CompletableFuture<HttpResponse<InputStream>> transport =
                    httpClient.sendAsync(
                            httpRequest,
                            HttpResponse.BodyHandlers.ofInputStream()
                    );
            request.transport.set(transport);
            if (request.finished.get()) {
                transport.cancel(true);
                return;
            }

            HttpResponse<InputStream> response = transport.join();
            InputStream body = response.body();
            request.responseBody.set(body);
            if (request.finished.get()) {
                closeQuietly(body);
                return;
            }
            finish(request, processResponse(request, input, response));
        } catch (RuntimeException exception) {
            finish(request, new ModelOutcome.Failure(
                    failureFromThrowable(request.clientRequestId, exception)
            ));
        }
    }

    private void timeout(InFlight request) {
        terminate(
                request,
                new ModelOutcome.Failure(localFailure(
                        ModelFailureKind.TIMEOUT,
                        request.clientRequestId,
                        "The model request timed out"
                ))
        );
    }

    private void finish(InFlight request, ModelOutcome outcome) {
        if (!request.finished.compareAndSet(false, true)) {
            return;
        }
        cancelDeadline(request);
        inFlight.compareAndSet(request, null);
        request.result.complete(outcome);
    }

    private void terminate(InFlight request, ModelOutcome outcome) {
        if (!request.finished.compareAndSet(false, true)) {
            return;
        }
        cancelDeadline(request);
        cancelResources(request);
        inFlight.compareAndSet(request, null);
        request.result.complete(outcome);
    }

    private static void cancelResources(InFlight request) {
        CompletableFuture<?> transport = request.transport.get();
        if (transport != null) {
            transport.cancel(true);
        }
        closeQuietly(request.responseBody.get());
        Future<?> task = request.task.get();
        if (task != null) {
            task.cancel(true);
        }
    }

    private static void cancelDeadline(InFlight request) {
        ScheduledFuture<?> deadlineTask = request.deadlineTask.get();
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation is best effort; the worker is interrupted as well.
        }
    }

    @Override
    public void cancelForGoalRevision(long currentGoalRevision) {
        InFlight current = inFlight.get();
        if (current == null || current.goalRevision == currentGoalRevision) {
            return;
        }
        terminate(current, new ModelOutcome.Failure(localFailure(
                    ModelFailureKind.STALE_RESPONSE,
                    current.clientRequestId,
                    "The goal changed while the model request was in flight"
        )));
    }

    @Override
    public GatewayStatus status() {
        if (closed.get()) {
            return GatewayStatus.CLOSED;
        }
        return inFlight.get() == null ? GatewayStatus.IDLE : GatewayStatus.REQUESTING;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        InFlight current = inFlight.getAndSet(null);
        if (current != null) {
            terminate(current, new ModelOutcome.Failure(localFailure(
                    ModelFailureKind.CANCELLED,
                    current.clientRequestId,
                    "The model gateway was closed"
            )));
        }
        deadlineExecutor.shutdownNow();
        ioExecutor.close();
    }

    private HttpRequest createRequest(PlannerInput input) {
        final String body;
        try {
            body = requestFactory.build(endpoint, capabilities, input);
        } finally {
            input.imageInput().ifPresent(ModelImageInput::destroy);
        }
        if (body.length() > MAX_REQUEST_BODY_CHARS) {
            throw new IllegalArgumentException("Model request body exceeds the local limit");
        }

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
                throw new IllegalArgumentException("API credential contains a control character");
            }
        }

        String clientRequestId = input.decisionContext().requestId();
        validateClientRequestId(clientRequestId);
        final HttpRequest.Builder request = HttpRequest.newBuilder(
                endpoint.endpoint(capabilities.protocol())
        )
                .timeout(hardTimeout)
                .header("Content-Type", "application/json")
                .header(
                        "Accept",
                        capabilities.streaming() ? "text/event-stream" : "application/json"
                )
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

    private ModelOutcome processResponse(
            InFlight request,
            PlannerInput input,
            HttpResponse<InputStream> response
    ) {
        int maxBytes = response.statusCode() >= 200 && response.statusCode() < 300
                ? MAX_RESPONSE_BYTES
                : MAX_ERROR_RESPONSE_BYTES;
        final String body;
        try {
            body = BoundedBodyReader.readUtf8(response.body(), maxBytes);
        } catch (IOException exception) {
            return new ModelOutcome.Failure(localFailure(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    request.clientRequestId,
                    "The provider response exceeded limits or was not valid UTF-8"
            ));
        }

        Set<String> capabilityFields = capabilityFields();
        Optional<ModelFailure> providerFailure = errorClassifier.classify(
                response.statusCode(),
                response.headers(),
                body,
                request.clientRequestId,
                capabilityFields
        );
        if (providerFailure.isPresent()) {
            return new ModelOutcome.Failure(providerFailure.get());
        }

        final ExtractedModelResponse extracted;
        try {
            extracted = responseExtractor.extract(
                    capabilities.protocol(),
                    capabilities.outputContract(),
                    capabilities.streaming(),
                    body
            );
        } catch (ModelResponseException exception) {
            return new ModelOutcome.Failure(responseFailure(
                    exception.kind(),
                    response,
                    request,
                    exception.getMessage()
            ));
        }

        final DecisionEnvelope decodedDecision;
        try {
            decodedDecision = codec.decode(extracted.decisionJson());
        } catch (DecisionValidationException exception) {
            return new ModelOutcome.Failure(responseFailure(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    response,
                    request,
                    "The model decision failed local validation: "
                            + exception.code()
            ));
        }
        /*
         * These three fields are transport bindings, not model decisions.
         * The completion is already owned by this exact InFlight request, so
         * asking a provider to copy opaque identifiers and numeric revisions
         * adds no freshness guarantee.  Some otherwise valid providers round
         * or increment a revision, which used to discard the whole semantic
         * decision and spend a second model round trip.  Bind the decoded
         * payload to the local request before validating its authoritative
         * decision fields.  The consumer still compares the request's goal
         * revision with live goal state before committing the result.
         */
        final DecisionEnvelope requestBoundDecision = new DecisionEnvelope(
                input.decisionContext().requestId(),
                input.decisionContext().observedWorldRevision(),
                input.decisionContext().goalRevision(),
                decodedDecision.decision(),
                decodedDecision.skillName(),
                decodedDecision.typedArguments(),
                decodedDecision.requestedObservation(),
                decodedDecision.optionalSpeech(),
                decodedDecision.confidence()
        );
        final DecisionEnvelope canonicalDecision =
                ObservationBindingCanonicalizer.canonicalize(
                        KnownSkillArgumentCanonicalizer.canonicalize(
                                requestBoundDecision
                        ),
                        input.observationJson()
                );
        DecisionEnvelope decision;
        try {
            decision = validator.validate(
                    canonicalDecision,
                    input.decisionContext()
            );
        } catch (DecisionValidationException exception) {
            /*
             * optionalSpeech is deliberately non-authoritative.  MiMo and a
             * few OpenAI-compatible providers sometimes return a long
             * natural-language acknowledgement next to a valid structured
             * START_SKILL call.  Rejecting the whole envelope makes the body
             * stand still even though the selected action is fully bound to
             * the current fair observation.  Preserve the action, retain only
             * a bounded speech prefix, and run the complete validator again;
             * malformed/overlong arguments still fail closed.
             */
            if (exception.code().equals("text_too_long")
                    && canonicalDecision.decision()
                            == DecisionKind.START_SKILL
                    && canonicalDecision.optionalSpeech()
                            .codePointCount(
                                    0,
                                    canonicalDecision.optionalSpeech().length()
                            ) > DecisionEnvelopeValidator.MAX_SPEECH_CODE_POINTS) {
                final DecisionEnvelope speechBounded = new DecisionEnvelope(
                        canonicalDecision.requestId(),
                        canonicalDecision.observedWorldRevision(),
                        canonicalDecision.goalRevision(),
                        canonicalDecision.decision(),
                        canonicalDecision.skillName(),
                        canonicalDecision.typedArguments(),
                        canonicalDecision.requestedObservation(),
                        DecisionEnvelopeValidator.boundedSpeech(
                                canonicalDecision.optionalSpeech()
                        ),
                        canonicalDecision.confidence()
                );
                try {
                    decision = validator.validate(
                            speechBounded,
                            input.decisionContext()
                    );
                    if (Boolean.getBoolean("mcai.liveModelTest")) {
                        MinecraftAiCompanion.LOGGER.info(
                                "Live-model action retained after optionalSpeech truncation: skill={}",
                                safeDecisionSummary(decision)
                        );
                    }
                } catch (DecisionValidationException boundedFailure) {
                    return new ModelOutcome.Failure(responseFailure(
                            ModelFailureKind.MALFORMED_RESPONSE,
                            response,
                            request,
                            "The model decision failed local validation: "
                                    + boundedFailure.code()
                    ));
                }
            } else {
            if (Boolean.getBoolean("mcai.liveModelTest")) {
                MinecraftAiCompanion.LOGGER.info(
                        "Live-model decoded decision rejected: {}, "
                                + "availableSkills={}, lane={}, goalRevision={}",
                        safeDecisionSummary(canonicalDecision),
                        input.decisionContext().availableSkills().keySet(),
                        input.decisionContext().lane(),
                        input.decisionContext().goalRevision()
                );
            }
            ModelFailureKind kind = exception.code().startsWith("stale_")
                    ? ModelFailureKind.STALE_RESPONSE
                    : ModelFailureKind.MALFORMED_RESPONSE;
            return new ModelOutcome.Failure(responseFailure(
                    kind,
                    response,
                    request,
                    "The model decision failed local validation: " + exception.code()
            ));
            }
        }
        if (Boolean.getBoolean("mcai.liveModelTest")) {
            MinecraftAiCompanion.LOGGER.info(
                    "Live-model accepted decision: {}",
                    safeDecisionSummary(decision)
            );
        }

        return new ModelOutcome.Success(
                decision,
                extracted.usage(),
                new RequestTrace(
                        request.clientRequestId,
                        response.headers().firstValue("x-request-id").orElse(""),
                        capabilities.protocol(),
                        response.statusCode(),
                        elapsedMillis(request.startedNanos)
                )
        );
    }

    private static String safeDecisionSummary(
            final DecisionEnvelope decision
    ) {
        final StringBuilder result = new StringBuilder(256)
                .append("decision=")
                .append(decision.decision())
                .append(", skill=")
                .append(safeDiagnosticValue(decision.skillName()));
        for (SkillArgument argument : decision.typedArguments()) {
            result.append(", ")
                    .append(safeDiagnosticValue(argument.name()))
                    .append('=')
                    .append(safeDiagnosticValue(argument.value()));
        }
        return result.toString();
    }

    private static String safeDiagnosticValue(final String value) {
        final String candidate = value == null ? "" : value;
        final StringBuilder safe = new StringBuilder(
                Math.min(candidate.length(), 96)
        );
        for (int offset = 0;
                offset < candidate.length() && safe.length() < 96;) {
            final int codePoint = candidate.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                safe.append('?');
            } else {
                safe.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        if (candidate.length() > 96) {
            safe.append("...");
        }
        return safe.toString();
    }

    private Set<String> capabilityFields() {
        return switch (capabilities.outputContract()) {
            case JSON_SCHEMA -> capabilities.protocol() == Protocol.RESPONSES
                    ? Set.of("text", "text.format")
                    : Set.of("response_format");
            case FORCED_FUNCTION -> Set.of("tools", "tool_choice", "parallel_tool_calls");
            case JSON_OBJECT -> capabilities.protocol() == Protocol.RESPONSES
                    ? Set.of("text", "text.format")
                    : Set.of("response_format");
            case PLAIN_JSON -> Set.of();
        };
    }

    private ModelFailure responseFailure(
            ModelFailureKind kind,
            HttpResponse<?> response,
            InFlight request,
            String safeMessage
    ) {
        return new ModelFailure(
                kind,
                response.statusCode(),
                "",
                "",
                request.clientRequestId,
                response.headers().firstValue("x-request-id").orElse(""),
                Optional.empty(),
                "",
                safeMessage
        );
    }

    private static ModelFailure failureFromThrowable(String requestId, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof CancellationException) {
            return localFailure(ModelFailureKind.CANCELLED, requestId, "The model request was cancelled");
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return localFailure(ModelFailureKind.TIMEOUT, requestId, "The model request timed out");
        }
        if (cause instanceof IOException) {
            return localFailure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    requestId,
                    "The model provider could not be reached"
            );
        }
        return localFailure(ModelFailureKind.INTERNAL, requestId, "The model request failed locally");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ModelFailure localFailure(
            ModelFailureKind kind,
            String requestId,
            String message
    ) {
        return new ModelFailure(
                kind,
                0,
                "",
                "",
                requestId,
                "",
                Optional.empty(),
                "",
                message
        );
    }

    private static void validateClientRequestId(String requestId) {
        if (requestId.isEmpty() || requestId.length() > 512) {
            throw new IllegalArgumentException("Client request ID has an invalid length");
        }
        for (int index = 0; index < requestId.length(); index++) {
            char character = requestId.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("Client request ID must be printable ASCII");
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static final class InFlight {
        private final String clientRequestId;
        private final long goalRevision;
        private final long startedNanos;
        private final CompletableFuture<ModelOutcome> result;
        private final AtomicReference<CompletableFuture<?>> transport = new AtomicReference<>();
        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> deadlineTask =
                new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private InFlight(
                String clientRequestId,
                long goalRevision,
                long startedNanos,
                CompletableFuture<ModelOutcome> result
        ) {
            this.clientRequestId = clientRequestId;
            this.goalRevision = goalRevision;
            this.startedNanos = startedNanos;
            this.result = result;
        }
    }
}
