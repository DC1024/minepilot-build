package dev.mcai.companion.model;

public enum Protocol {
    RESPONSES("responses"),
    CHAT_COMPLETIONS("chat/completions");

    private final String relativePath;

    Protocol(String relativePath) {
        this.relativePath = relativePath;
    }

    String relativePath() {
        return relativePath;
    }
}
