package dev.mcai.companion.model;

/**
 * A model response failed structural or contextual validation.
 */
public final class DecisionValidationException extends Exception {
    private final String code;

    public DecisionValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
