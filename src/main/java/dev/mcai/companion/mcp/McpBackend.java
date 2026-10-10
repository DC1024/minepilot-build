package dev.mcai.companion.mcp;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@FunctionalInterface
public interface McpBackend {
    CompletableFuture<JsonElement> call(String toolName, JsonObject arguments);
}
