package dev.mcai.companion.model;

final class ModelResponseException extends Exception {
    private final ModelFailureKind kind;

    ModelResponseException(ModelFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    ModelFailureKind kind() {
        return kind;
    }
}
