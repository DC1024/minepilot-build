package dev.mcai.companion.skills.building;

public enum ShelterStepRole {
    LOWER_WALL,
    UPPER_WALL,
    ROOF,
    DOOR,
    LIGHT;

    public boolean usesStructuralMaterial() {
        return this == LOWER_WALL || this == UPPER_WALL || this == ROOF;
    }
}
