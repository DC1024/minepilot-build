package dev.mcai.companion.model;

public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
    public static final TokenUsage UNKNOWN = new TokenUsage(-1, -1, -1);
}
