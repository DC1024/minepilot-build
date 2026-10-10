package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface WaypointLookup {
    CompletionStage<List<Waypoint>> search(
        DimensionRef dimension,
        String query,
        int limit
    );
}
