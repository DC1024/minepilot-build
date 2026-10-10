package dev.mcai.companion.perception;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Produces the bounded, token-conscious observation sent to a high-level
 * model. Internal player/entity UUIDs are intentionally omitted.
 */
public final class SemanticObservationJsonCodec {
    public static final int FORMAT_VERSION = 9;
    public static final int MAX_JSON_CHARACTERS = 262_144;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String encode(final SemanticObservation observation) {
        Objects.requireNonNull(observation, "observation");
        final JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("sampleSequence", observation.sequence());
        root.add("self", encodeBody(observation.body()));
        root.add("visibleEntities", encodeEntities(observation));
        root.add("visibleBlockFaces", encodeBlocks(observation));
        root.add("localGeometry", encodeLocalGeometry(observation));
        root.add("dangers", encodeDangers(observation));
        observation.openMenu().ifPresent(menu ->
            root.add("openMenu", encodeMenu(menu))
        );
        observation.craftingAffordances().ifPresent(snapshot ->
            root.add(
                "craftingAffordances",
                GSON.toJsonTree(snapshot)
            )
        );
        root.add("budgetAudit", GSON.toJsonTree(observation.budgetUsage()));
        root.add("provenance", GSON.toJsonTree(
            observation.provenance().stream()
                .map(Enum::name)
                .sorted()
                .toList()
        ));
        final String json = GSON.toJson(root);
        if (json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException("Semantic observation exceeds JSON limit");
        }
        return json;
    }

    private static JsonObject encodeBody(final BodySnapshot body) {
        final JsonObject result = new JsonObject();
        result.addProperty("dimension", body.dimensionId());
        result.addProperty("gameTime", body.gameTime());
        result.add("position", encodeVector(body.position(), 2));
        result.add("lookDirection", encodeVector(body.lookDirection(), 3));
        result.addProperty("health", decimal(body.health(), 2));
        result.addProperty("maxHealth", decimal(body.maxHealth(), 2));
        result.addProperty("absorption", decimal(body.absorption(), 2));
        result.addProperty("food", body.foodLevel());
        result.addProperty("saturation", decimal(body.saturation(), 2));
        result.addProperty("air", body.airSupply());
        result.addProperty("maxAir", body.maxAirSupply());
        result.addProperty("onGround", body.onGround());
        result.addProperty("inWater", body.inWater());
        result.addProperty("onFire", body.onFire());
        result.addProperty("fallDistance", decimal(body.fallDistance(), 2));
        result.add("mainHand", encodeHeldItem(body.mainHand()));
        result.add("offHand", encodeHeldItem(body.offHand()));
        result.add("inventory", GSON.toJsonTree(body.inventory()));
        result.add("effects", GSON.toJsonTree(body.effects()));
        return result;
    }

    private static JsonObject encodeHeldItem(final HeldItemSummary item) {
        final JsonObject result = new JsonObject();
        result.addProperty("item", item.itemId());
        result.addProperty("count", item.count());
        if (item.maxDamage() > 0) {
            result.addProperty("damage", item.damage());
            result.addProperty("maxDamage", item.maxDamage());
        }
        return result;
    }

    private static JsonArray encodeEntities(final SemanticObservation observation) {
        final JsonArray result = new JsonArray();
        for (int index = 0; index < observation.visibleEntities().size(); index++) {
            final VisibleEntity entity = observation.visibleEntities().get(index);
            final JsonObject encoded = new JsonObject();
            encoded.addProperty("observationId", "visible-" + index);
            encoded.addProperty("type", entity.entityTypeId());
            encoded.add("relativePosition", encodeVector(entity.relativePosition(), 2));
            encoded.addProperty("distance", decimal(entity.distance(), 1));
            encoded.addProperty("hostile", entity.hostile());
            encoded.addProperty("projectile", entity.projectile());
            encoded.add(
                "properties",
                GSON.toJsonTree(entity.visibleProperties())
            );
            encoded.addProperty("source", entity.provenance().name());
            result.add(encoded);
        }
        return result;
    }

    private static JsonArray encodeBlocks(final SemanticObservation observation) {
        final JsonArray result = new JsonArray();
        for (VisibleBlockFace face : observation.visibleBlockFaces()) {
            final JsonObject encoded = new JsonObject();
            encoded.add("block", encodeBlock(face.block()));
            encoded.addProperty("type", face.blockTypeId());
            encoded.addProperty("face", face.face());
            encoded.addProperty("distance", decimal(face.distance(), 1));
            encoded.add("state", GSON.toJsonTree(face.stateProperties()));
            encoded.addProperty(
                "topSupport",
                face.topSupportAffordance().name()
            );
            encoded.addProperty(
                "collision",
                face.collisionAffordance().name()
            );
            if (face.adjacentLightLevel() >= 0) {
                encoded.addProperty(
                        "adjacentLight",
                        face.adjacentLightLevel()
                );
            }
            encoded.addProperty("source", face.provenance().name());
            result.add(encoded);
        }
        return result;
    }

    private static JsonArray encodeDangers(final SemanticObservation observation) {
        final JsonArray result = new JsonArray();
        for (DangerSignal danger : observation.dangers()) {
            final JsonObject encoded = new JsonObject();
            encoded.addProperty("kind", danger.kind().name());
            encoded.addProperty("severity", decimal(danger.severity(), 2));
            encoded.addProperty(
                "distanceUpperBound",
                decimal(danger.distanceUpperBound(), 1)
            );
            danger.contactDirection().ifPresent(direction ->
                encoded.add("contactDirection", encodeVector(direction, 2))
            );
            encoded.addProperty("source", danger.provenance().name());
            result.add(encoded);
        }
        return result;
    }

    /**
     * Emits a compact, first-person-only interpretation of the already
     * sampled surfaces.  This is deliberately derived from the finite ray
     * result rather than from a second world scan: it gives a language model
     * useful shape cues (side walls, upper/lower surfaces, open ray segments)
     * without exposing neighboring blocks, chunk contents, or hidden terrain.
     */
    private static JsonObject encodeLocalGeometry(
        final SemanticObservation observation
    ) {
        final List<VisibleBlockFace> faces =
            observation.visibleBlockFaces();
        int upwardFaces = 0;
        int downwardFaces = 0;
        int sideFaces = 0;
        int sturdyTopSurfaces = 0;
        int minimumRelativeY = 0;
        int maximumRelativeY = 0;
        double nearestSurfaceDistance = Double.POSITIVE_INFINITY;
        boolean heightInitialized = false;
        final double bodyY = observation.body().position().y();
        for (VisibleBlockFace face : faces) {
            switch (face.face()) {
                case "up" -> upwardFaces++;
                case "down" -> downwardFaces++;
                default -> sideFaces++;
            }
            if (face.topSupportAffordance().safelySupportsStanding()) {
                sturdyTopSurfaces++;
            }
            final int relativeY = (int) Math.floor(face.block().y() - bodyY);
            if (!heightInitialized) {
                minimumRelativeY = relativeY;
                maximumRelativeY = relativeY;
                heightInitialized = true;
            } else {
                minimumRelativeY = Math.min(minimumRelativeY, relativeY);
                maximumRelativeY = Math.max(maximumRelativeY, relativeY);
            }
            nearestSurfaceDistance = Math.min(
                nearestSurfaceDistance,
                face.distance()
            );
        }
        final List<String> cues = new ArrayList<>(5);
        if (sturdyTopSurfaces > 0) {
            cues.add("walkable_top_surface_observed");
        }
        if (sideFaces >= 2) {
            cues.add("vertical_side_surfaces_observed");
        }
        if (upwardFaces > 0) {
            cues.add("upper_surface_observed");
        }
        if (downwardFaces > 0) {
            cues.add("lower_surface_observed");
        }
        if (!observation.clearSightRays().isEmpty()) {
            cues.add("clear_ray_segment_observed");
        }
        if (faces.size() >= 4 && nearestSurfaceDistance <= 4.0) {
            cues.add("nearby_surface_cluster_observed");
        }
        final int relativeHeightSpan = maximumRelativeY - minimumRelativeY;
        if (sideFaces >= 2
                && relativeHeightSpan >= 3
                && nearestSurfaceDistance <= 8.0) {
            cues.add("possible_canyon_or_cliff_wall");
        }
        if (sideFaces >= 2
                && upwardFaces > 0
                && downwardFaces > 0) {
            cues.add("possible_confined_uneven_terrain");
        }
        if (downwardFaces > 0 && sturdyTopSurfaces == 0) {
            cues.add("possible_drop_or_overhang");
        }

        final JsonObject result = new JsonObject();
        result.addProperty("basis", "bounded_first_person_surface_rays");
        result.addProperty("raysCast", observation.budgetUsage().blockRaysCast());
        result.addProperty("surfaceHits", faces.size());
        result.addProperty(
            "clearRaySegments",
            observation.clearSightRays().size()
        );
        result.addProperty("upwardFaces", upwardFaces);
        result.addProperty("downwardFaces", downwardFaces);
        result.addProperty("sideFaces", sideFaces);
        result.addProperty("sturdyTopSurfaces", sturdyTopSurfaces);
        final JsonObject height = new JsonObject();
        height.addProperty("minRelativeBlockY", minimumRelativeY);
        height.addProperty("maxRelativeBlockY", maximumRelativeY);
        result.add("relativeHeightSpan", height);
        if (Double.isFinite(nearestSurfaceDistance)) {
            result.addProperty(
                "nearestSurfaceDistance",
                decimal(nearestSurfaceDistance, 1)
            );
        }
        result.add("cues", GSON.toJsonTree(List.copyOf(cues)));
        result.addProperty(
            "warning",
            "Shape cues are uncertain first-person hypotheses; absence never proves open space."
        );
        return result;
    }

    private static JsonObject encodeMenu(final OpenMenuSnapshot menu) {
        final JsonObject result = new JsonObject();
        result.addProperty("type", menu.menuType());
        result.addProperty("class", menu.menuClass());
        result.addProperty("containerId", menu.containerId());
        result.addProperty("stateId", menu.stateId());
        final JsonArray slots = new JsonArray();
        for (MenuSlotSummary slot : menu.slots()) {
            final JsonObject encoded = new JsonObject();
            encoded.addProperty("slot", slot.slot());
            encoded.addProperty("item", slot.itemId());
            encoded.addProperty("count", slot.count());
            if (slot.maxDamage() > 0) {
                encoded.addProperty("damage", slot.damage());
                encoded.addProperty("maxDamage", slot.maxDamage());
            }
            encoded.addProperty(
                "location",
                slot.playerInventory() ? "PLAYER" : "MENU"
            );
            encoded.addProperty("role", slot.role());
            encoded.addProperty("mayPickup", slot.mayPickup());
            slots.add(encoded);
        }
        result.add("slots", slots);
        result.add("carried", encodeHeldItem(menu.carried()));
        result.add("options", GSON.toJsonTree(menu.options()));
        return result;
    }

    private static JsonObject encodeVector(
        final PerceptionVec3 vector,
        final int scale
    ) {
        final JsonObject result = new JsonObject();
        result.addProperty("x", decimal(vector.x(), scale));
        result.addProperty("y", decimal(vector.y(), scale));
        result.addProperty("z", decimal(vector.z(), scale));
        return result;
    }

    private static JsonObject encodeBlock(final BlockCoordinate block) {
        final JsonObject result = new JsonObject();
        result.addProperty("x", block.x());
        result.addProperty("y", block.y());
        result.addProperty("z", block.z());
        return result;
    }

    private static BigDecimal decimal(final double value, final int scale) {
        return BigDecimal.valueOf(value)
            .setScale(scale, RoundingMode.HALF_UP)
            .stripTrailingZeros();
    }
}
