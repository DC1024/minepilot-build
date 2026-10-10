package dev.mcai.companion.model;

public enum ChatTokenField {
    MAX_COMPLETION_TOKENS("max_completion_tokens"),
    MAX_TOKENS("max_tokens");

    private final String jsonName;

    ChatTokenField(String jsonName) {
        this.jsonName = jsonName;
    }

    String jsonName() {
        return jsonName;
    }
}
