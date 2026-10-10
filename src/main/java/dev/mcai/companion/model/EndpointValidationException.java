package dev.mcai.companion.model;

public final class EndpointValidationException extends Exception {
    private final String code;

    public EndpointValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
