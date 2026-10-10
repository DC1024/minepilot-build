package dev.mcai.companion.brain;

import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.RequestTrace;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A server-thread output from the high-level brain.
 *
 * <p>Speech is data for the runtime to route through the explicitly
 * AI-labelled channel. The brain never calls chat APIs directly.</p>
 */
public sealed interface BrainEvent permits BrainEvent.Speech, BrainEvent.Notice,
        BrainEvent.TaskAccepted, BrainEvent.Usage, BrainEvent.ModelAudit {
    int MAX_SPEECH_CODE_POINTS = 512;
    int MAX_CODE_CHARACTERS = 64;
    Pattern SAFE_CODE = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    long goalRevision();

    record Speech(long goalRevision, String requestId, String message)
            implements BrainEvent {
        public Speech {
            if (goalRevision < 0) {
                throw new IllegalArgumentException("goalRevision must be non-negative");
            }
            requestId = requireRequestId(requestId);
            message = Objects.requireNonNull(message, "message");
            if (message.isBlank()
                    || message.codePointCount(0, message.length())
                    > MAX_SPEECH_CODE_POINTS
                    || hasDisallowedControl(message)) {
                throw new IllegalArgumentException("Speech is invalid");
            }
        }
    }

    record Notice(long goalRevision, String code) implements BrainEvent {
        public Notice {
            if (goalRevision < 0) {
                throw new IllegalArgumentException("goalRevision must be non-negative");
            }
            code = safeCode(code);
        }
    }

    /**
     * Non-content audit binding emitted only after a player chat task has
     * been accepted by {@code GoalCoordinator}.
     *
     * <p>The full player message is intentionally not copied to the durable
     * brain audit. Its SHA-256 fingerprint lets an external black-box oracle
     * bind one normal chat packet to the resulting goal revision without
     * treating temporal proximity as causal evidence.</p>
     */
    record TaskAccepted(
            long goalRevision,
            UUID senderId,
            String messageSha256,
            String intentCode
    ) implements BrainEvent {
        private static final Pattern SHA256_HEX =
                Pattern.compile("[0-9a-f]{64}");

        public TaskAccepted {
            if (goalRevision < 0) {
                throw new IllegalArgumentException(
                        "goalRevision must be non-negative"
                );
            }
            senderId = Objects.requireNonNull(senderId, "senderId");
            messageSha256 = Objects.requireNonNull(
                    messageSha256,
                    "messageSha256"
            );
            if (!SHA256_HEX.matcher(messageSha256).matches()) {
                throw new IllegalArgumentException(
                        "messageSha256 must be lowercase SHA-256 hex"
                );
            }
            intentCode = safeCode(intentCode);
        }
    }

    /**
     * Provider-reported usage for one accepted response. Unknown values remain
     * {@code -1}; prompts, response bodies and credentials are never included.
     */
    record Usage(
        long goalRevision,
        String requestId,
        long inputTokens,
        long outputTokens,
        long totalTokens
    ) implements BrainEvent {
        public Usage {
            if (goalRevision < 0) {
                throw new IllegalArgumentException(
                    "goalRevision must be non-negative"
                );
            }
            requestId = requireRequestId(requestId);
            requireTokenCount(inputTokens, "inputTokens");
            requireTokenCount(outputTokens, "outputTokens");
            requireTokenCount(totalTokens, "totalTokens");
        }
    }

    /**
     * Log-safe causal metadata for one high-level gameplay-model request.
     *
     * <p>The payload deliberately excludes prompts, observations, response
     * bodies, typed argument values and credentials. A successful gateway
     * trace is retained only after the provider response has passed the
     * structured decision decoder and validator.</p>
     */
    record ModelAudit(
        long goalRevision,
        String requestId,
        ModelAuditStage stage,
        long observedWorldRevision,
        Optional<DecisionKind> decision,
        String skillName,
        Optional<RequestTrace> trace
    ) implements BrainEvent {
        public ModelAudit {
            if (goalRevision < 0 || observedWorldRevision < 0) {
                throw new IllegalArgumentException(
                    "Model audit revisions must be non-negative"
                );
            }
            requestId = requireRequestId(requestId);
            stage = Objects.requireNonNull(stage, "stage");
            decision = Objects.requireNonNull(decision, "decision");
            skillName = safeAuditText(skillName, 128);
            trace = Objects.requireNonNull(trace, "trace");
            if (trace.isPresent()) {
                final RequestTrace value = trace.orElseThrow();
                if (!requestId.equals(value.clientRequestId())) {
                    throw new IllegalArgumentException(
                        "Model audit trace request does not match"
                    );
                }
                if (value.httpStatus() < 100
                        || value.httpStatus() > 599
                        || value.elapsedMillis() < 0) {
                    throw new IllegalArgumentException(
                        "Model audit trace metadata is invalid"
                    );
                }
                safeAuditText(value.providerRequestId(), 256);
            }
            if (stage.requiresDecision() && decision.isEmpty()) {
                throw new IllegalArgumentException(
                    "Model audit stage requires a decision"
                );
            }
            if (stage.requiresTrace() && trace.isEmpty()) {
                throw new IllegalArgumentException(
                    "Model audit stage requires a provider trace"
                );
            }
            if (stage == ModelAuditStage.SKILL_STARTED
                    && (decision.orElse(null)
                            != DecisionKind.START_SKILL
                        || skillName.isEmpty())) {
                throw new IllegalArgumentException(
                    "Skill-start audit requires START_SKILL and a skill"
                );
            }
        }
    }

    enum ModelAuditStage {
        AI_PERCEPTION_RECEIVED(false, false),
        MODEL_REQUEST_STARTED(false, false),
        MODEL_RESPONSE_RECEIVED(true, true),
        DECISION_SCHEMA_VALIDATED(true, true),
        DECISION_REVISION_ACCEPTED(true, true),
        SKILL_STARTED(true, true);

        private final boolean requiresDecision;
        private final boolean requiresTrace;

        ModelAuditStage(
            final boolean requiresDecision,
            final boolean requiresTrace
        ) {
            this.requiresDecision = requiresDecision;
            this.requiresTrace = requiresTrace;
        }

        boolean requiresDecision() {
            return requiresDecision;
        }

        boolean requiresTrace() {
            return requiresTrace;
        }
    }

    private static String safeCode(String candidate) {
        String value = Objects.requireNonNullElse(candidate, "");
        return value.length() >= 1
                && value.length() <= MAX_CODE_CHARACTERS
                && SAFE_CODE.matcher(value).matches()
                ? value
                : "brain_event";
    }

    private static String requireRequestId(String requestId) {
        String value = Objects.requireNonNull(requestId, "requestId");
        if (value.isEmpty() || value.length() > 512) {
            throw new IllegalArgumentException("requestId has an invalid length");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("requestId must be printable ASCII");
            }
        }
        return value;
    }

    private static String safeAuditText(
        final String candidate,
        final int maximumCharacters
    ) {
        final String value = Objects.requireNonNullElse(candidate, "");
        if (value.length() > maximumCharacters) {
            throw new IllegalArgumentException(
                "Audit text exceeds its bound"
            );
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException(
                    "Audit text must be printable ASCII"
                );
            }
        }
        return value;
    }

    private static void requireTokenCount(
        final long value,
        final String name
    ) {
        if (value < -1L) {
            throw new IllegalArgumentException(
                name + " must be unknown or non-negative"
            );
        }
    }

    private static boolean hasDisallowedControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0
                    || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\r'
                    && codePoint != '\t')) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}
