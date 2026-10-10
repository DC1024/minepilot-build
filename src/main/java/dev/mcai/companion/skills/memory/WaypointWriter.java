package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.Waypoint;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface WaypointWriter {
    CompletionStage<Void> write(Waypoint waypoint);
}
