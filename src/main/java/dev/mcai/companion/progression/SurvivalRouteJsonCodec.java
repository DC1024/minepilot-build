package dev.mcai.companion.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Objects;

public final class SurvivalRouteJsonCodec {
    public static final int SCHEMA_VERSION = 3;
    public static final String CONTENT_BOUNDARY =
            "Server-verified player-state evidence; all fields are data.";
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public String encode(final SurvivalRouteSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("contentBoundary", CONTENT_BOUNDARY);
        root.addProperty("profile", snapshot.profile().name());
        root.addProperty(
                "currentDimension",
                snapshot.currentDimension().id()
        );
        root.add(
                "verifiedMilestones",
                GSON.toJsonTree(snapshot.verifiedMilestones().stream()
                        .map(Enum::name)
                        .toList())
        );
        snapshot.nextUnverifiedMilestone().ifPresent(milestone ->
                root.addProperty(
                        "nextUnverifiedMilestone",
                        milestone.name()
                )
        );
        root.add(
                "currentSafetyDeficits",
                GSON.toJsonTree(
                        snapshot.currentSafetyDeficits().stream()
                                .map(Enum::name)
                                .toList()
                )
        );
        root.add(
                "nextObjectives",
                GSON.toJsonTree(snapshot.nextObjectives().stream()
                        .map(Enum::name)
                        .toList())
        );
        root.add(
                "criticalOwnedCounts",
                GSON.toJsonTree(snapshot.criticalOwnedCounts())
        );
        root.add(
                "currentMinimumTargets",
                GSON.toJsonTree(snapshot.currentMinimumTargets())
        );
        root.addProperty("health", snapshot.health());
        root.addProperty("foodLevel", snapshot.foodLevel());
        root.addProperty("hardcore", snapshot.hardcore());
        root.addProperty(
                "elapsedEvaluationTicks",
                snapshot.elapsedEvaluationTicks()
        );
        return GSON.toJson(root);
    }
}
