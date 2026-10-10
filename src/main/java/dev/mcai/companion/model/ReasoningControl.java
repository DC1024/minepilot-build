package dev.mcai.companion.model;

/**
 * A capability-probed request hint for latency-sensitive game control.
 *
 * <p>{@link #DISABLED} is used only after the provider accepted the
 * protocol-specific no-reasoning field during capability negotiation.
 * {@link #LOW} is the latency-sensitive fallback for reasoning models that
 * reject disabling reasoning but accept a bounded low effort. {@link #DEFAULT}
 * omits the field completely so providers which implement neither retain
 * their native behavior.</p>
 */
public enum ReasoningControl {
    DISABLED,
    LOW,
    DEFAULT
}
