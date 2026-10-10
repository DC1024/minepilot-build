package dev.mcai.companion.model;

import java.util.Objects;

/**
 * A previously verified wire profile. Capability probing is intentionally
 * separate from the gateway so an ordinary game start performs no API call.
 */
public record ProviderCapabilities(
        Protocol protocol,
        OutputContract outputContract,
        boolean serverEnforcesSchema,
        boolean streaming,
        ChatTokenField chatTokenField,
        ReasoningControl reasoningControl,
        boolean imageInput
) {
    public ProviderCapabilities {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(outputContract, "outputContract");
        Objects.requireNonNull(chatTokenField, "chatTokenField");
        Objects.requireNonNull(reasoningControl, "reasoningControl");
    }

    public ProviderCapabilities(
            final Protocol protocol,
            final OutputContract outputContract,
            final boolean serverEnforcesSchema,
            final boolean streaming,
            final ChatTokenField chatTokenField,
            final ReasoningControl reasoningControl
    ) {
        this(
                protocol,
                outputContract,
                serverEnforcesSchema,
                streaming,
                chatTokenField,
                reasoningControl,
                false
        );
    }

    public ProviderCapabilities withImageInput(final boolean supported) {
        return new ProviderCapabilities(
                protocol,
                outputContract,
                serverEnforcesSchema,
                streaming,
                chatTokenField,
                reasoningControl,
                supported
        );
    }

    public static ProviderCapabilities responsesJsonSchema(boolean streaming) {
        return new ProviderCapabilities(
                Protocol.RESPONSES,
                OutputContract.JSON_SCHEMA,
                true,
                streaming,
                ChatTokenField.MAX_COMPLETION_TOKENS,
                ReasoningControl.DISABLED,
                false
        );
    }

    public static ProviderCapabilities chatJsonSchema(boolean streaming) {
        return new ProviderCapabilities(
                Protocol.CHAT_COMPLETIONS,
                OutputContract.JSON_SCHEMA,
                true,
                streaming,
                ChatTokenField.MAX_COMPLETION_TOKENS,
                ReasoningControl.DISABLED,
                false
        );
    }
}
