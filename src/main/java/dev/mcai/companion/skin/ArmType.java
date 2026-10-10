package dev.mcai.companion.skin;

public enum ArmType {
    CLASSIC(0),
    SLIM(1);

    private final int wireId;

    ArmType(final int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static ArmType fromWireId(final int wireId) {
        return switch (wireId) {
            case 0 -> CLASSIC;
            case 1 -> SLIM;
            default -> throw new IllegalArgumentException(
                "Unknown skin arm type: " + wireId
            );
        };
    }

    public static ArmType parse(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("Skin arm type is required");
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "classic", "wide", "steve" -> CLASSIC;
            case "slim", "alex" -> SLIM;
            default -> throw new IllegalArgumentException(
                "Skin arm type must be CLASSIC or SLIM"
            );
        };
    }
}
