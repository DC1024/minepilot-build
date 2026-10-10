package dev.mcai.companion.mcp;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public enum McpTool {
    OBSERVE(
        "observe",
        "Return a fair semantic observation from the companion's own current state.",
        schema(Map.of())
    ),
    SET_GOAL(
        "set_goal",
        "Set one high-level companion goal. Rejected while a locked evaluation is running.",
        schema(Map.of("goal", stringProperty("High-level goal for the in-world companion")), List.of("goal"))
    ),
    GOAL_STATUS(
        "goal_status",
        "Return the active goal, revision, progress, and safety state.",
        schema(Map.of())
    ),
    SAY(
        "say",
        "Send an explicitly AI-labelled in-game message.",
        schema(Map.of("message", stringProperty("Message text, at most 512 characters")), List.of("message"))
    ),
    CANCEL_GOAL(
        "cancel_goal",
        "Cancel the current goal at its next safe skill boundary.",
        schema(Map.of())
    ),
    ADD_WAYPOINT(
        "add_waypoint",
        "Add an explicitly shared waypoint without teleporting or exposing hidden map data.",
        schema(
            Map.of(
                "name", stringProperty("Human-readable waypoint name"),
                "dimension", stringProperty("Minecraft dimension resource key"),
                "x", numberProperty("X coordinate"),
                "y", numberProperty("Y coordinate"),
                "z", numberProperty("Z coordinate")
            ),
            List.of("name", "dimension", "x", "y", "z")
        )
    ),
    GET_SCREENSHOT(
        "get_screenshot",
        "Request a redacted first-person screenshot when active vision is available.",
        schema(Map.of())
    ),
    GET_AUDIT_SUMMARY(
        "get_audit_summary",
        "Return a bounded summary of actions, observation provenance, and policy failures.",
        schema(Map.of())
    );

    private final String wireName;
    private final String description;
    private final JsonObject inputSchema;

    McpTool(final String wireName, final String description, final JsonObject inputSchema) {
        this.wireName = wireName;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String wireName() {
        return wireName;
    }

    public JsonObject descriptor() {
        final JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", wireName);
        descriptor.addProperty("description", description);
        descriptor.add("inputSchema", inputSchema.deepCopy());
        return descriptor;
    }

    public static boolean supports(final String name) {
        for (McpTool tool : values()) {
            if (tool.wireName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject schema(final Map<String, JsonObject> properties) {
        return schema(properties, List.of());
    }

    private static JsonObject schema(
        final Map<String, JsonObject> properties,
        final List<String> required
    ) {
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);

        final JsonObject propertyObject = new JsonObject();
        properties.forEach(propertyObject::add);
        schema.add("properties", propertyObject);

        final JsonArray requiredArray = new JsonArray();
        required.forEach(requiredArray::add);
        schema.add("required", requiredArray);
        return schema;
    }

    private static JsonObject stringProperty(final String description) {
        final JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject numberProperty(final String description) {
        final JsonObject property = new JsonObject();
        property.addProperty("type", "number");
        property.addProperty("description", description);
        return property;
    }
}
