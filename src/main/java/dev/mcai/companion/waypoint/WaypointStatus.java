package dev.mcai.companion.waypoint;

public enum WaypointStatus {
    ACTIVE(true, false),
    DANGEROUS(true, true),
    STALE(true, false),
    REMOVED(false, false),
    ARCHIVED(false, false);

    private final boolean searchable;
    private final boolean dangerous;

    WaypointStatus(boolean searchable, boolean dangerous) {
        this.searchable = searchable;
        this.dangerous = dangerous;
    }

    public boolean isSearchable() {
        return searchable;
    }

    public boolean isDangerous() {
        return dangerous;
    }
}
