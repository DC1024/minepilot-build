package dev.mcai.companion.skills.portal;

import java.util.Optional;

/**
 * Vanilla portal blocks that may be selected from a fair semantic sample.
 */
public enum PortalKind {
    NETHER_PORTAL("minecraft:nether_portal", true),
    END_PORTAL("minecraft:end_portal", true),
    END_GATEWAY("minecraft:end_gateway", false);

    private final String blockTypeId;
    private final boolean dimensionChangeExpected;

    PortalKind(String blockTypeId, boolean dimensionChangeExpected) {
        this.blockTypeId = blockTypeId;
        this.dimensionChangeExpected = dimensionChangeExpected;
    }

    public String blockTypeId() {
        return blockTypeId;
    }

    public boolean dimensionChangeExpected() {
        return dimensionChangeExpected;
    }

    public static Optional<PortalKind> fromBlockTypeId(String blockTypeId) {
        for (PortalKind kind : values()) {
            if (kind.blockTypeId.equals(blockTypeId)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
