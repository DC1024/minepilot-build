package dev.mcai.companion.skills.portal;

import dev.mcai.companion.memory.transport.VerifiedPortalEdge;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Non-blocking lookup for body-observed portal arrival endpoints.
 */
@FunctionalInterface
public interface VerifiedPortalArrivalLookup {
    CompletionStage<List<VerifiedPortalEdge>> findNearby(
            DimensionRef currentDimension,
            PerceptionVec3 currentPosition,
            double radius,
            int limit
    );
}
