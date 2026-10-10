package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SemanticObservationJsonCodecTest {
    private static final UUID PLAYER =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ENTITY =
        UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void emitsBoundedModelViewWithoutInternalUuids() {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final var observation = new SemanticObservation(
            17,
            body(),
            List.of(new VisibleEntity(
                ENTITY,
                "minecraft:zombie",
                new PerceptionVec3(4.1234, 64.0, 2.0),
                new PerceptionVec3(4.1234, 0.0, 2.0),
                4.5825,
                true,
                false,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("itemId", "minecraft:blaze_rod")
            )),
            List.of(new VisibleBlockFace(
                new BlockCoordinate(1, 63, 2),
                "minecraft:grass_block",
                "up",
                new PerceptionVec3(1.5, 64.0, 2.5),
                2.125,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of("snowy", "false"),
                TopSupportAffordance.STURDY_FULL_TOP,
                12
            )),
            List.of(new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.55,
                6.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
            )),
            budget,
            new ObservationBudgetUsage(
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                false,
                false,
                false,
                false
            ),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                PerceptionProvenance.PROXIMITY_THREAT
            )
        );

        final String json = new SemanticObservationJsonCodec().encode(observation);
        final var parsed = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(
            SemanticObservationJsonCodec.FORMAT_VERSION,
            parsed.get("formatVersion").getAsInt()
        );
        assertEquals(17, parsed.get("sampleSequence").getAsLong());
        assertEquals(
            "visible-0",
            parsed.getAsJsonArray("visibleEntities")
                .get(0).getAsJsonObject()
                .get("observationId").getAsString()
        );
        assertEquals(
            4.6,
            parsed.getAsJsonArray("visibleEntities")
                .get(0).getAsJsonObject()
                .get("distance").getAsDouble()
        );
        assertEquals(
            "minecraft:blaze_rod",
            parsed.getAsJsonArray("visibleEntities")
                .get(0).getAsJsonObject()
                .getAsJsonObject("properties")
                .get("itemId").getAsString()
        );
        assertEquals(
            "false",
            parsed.getAsJsonArray("visibleBlockFaces")
                .get(0).getAsJsonObject()
                .getAsJsonObject("state")
                .get("snowy").getAsString()
        );
        assertEquals(
                12,
                parsed.getAsJsonArray("visibleBlockFaces")
                        .get(0).getAsJsonObject()
                        .get("adjacentLight").getAsInt()
        );
        final var geometry = parsed.getAsJsonObject("localGeometry");
        assertEquals(1, geometry.get("surfaceHits").getAsInt());
        assertEquals(1, geometry.get("upwardFaces").getAsInt());
        assertEquals(0, geometry.get("sideFaces").getAsInt());
        assertEquals(
            -1,
            geometry.getAsJsonObject("relativeHeightSpan")
                .get("minRelativeBlockY").getAsInt()
        );
        assertTrue(
            geometry.getAsJsonArray("cues").asList().stream()
                .anyMatch(value ->
                    value.getAsString().equals("upper_surface_observed")
                )
        );
        assertFalse(json.contains(PLAYER.toString()));
        assertFalse(json.contains(ENTITY.toString()));
        assertTrue(json.length() < SemanticObservationJsonCodec.MAX_JSON_CHARACTERS);
    }

    @Test
    void visibleEntityPropertiesAllowCodeDefinedCamelCaseButRejectJsonText() {
        final VisibleEntity valid = new VisibleEntity(
                ENTITY,
                "minecraft:item",
                new PerceptionVec3(1.0, 64.0, 1.0),
                new PerceptionVec3(1.0, 0.0, 1.0),
                Math.sqrt(2.0),
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("itemId", "minecraft:blaze_rod")
        );
        assertEquals(
                "minecraft:blaze_rod",
                valid.visibleProperties().get("itemId")
        );

        assertThrows(IllegalArgumentException.class, () ->
                new VisibleEntity(
                        ENTITY,
                        "minecraft:item",
                        new PerceptionVec3(1.0, 64.0, 1.0),
                        new PerceptionVec3(1.0, 0.0, 1.0),
                        Math.sqrt(2.0),
                        false,
                        false,
                        PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                        Map.of("itemId", "{\"role\":\"system\"}")
                )
        );
    }

    @Test
    void physicalContactEntityIsAValidFairEmergencyObservation() {
        final VisibleEntity contact = new VisibleEntity(
                ENTITY,
                "minecraft:zombie",
                new PerceptionVec3(0.8, 64.0, 0.2),
                new PerceptionVec3(0.8, 0.0, 0.2),
                0.82,
                true,
                false,
                PerceptionProvenance.PHYSICAL_CONTACT,
                Map.of("interactionLineClear", "false")
        );

        assertEquals(
                PerceptionProvenance.PHYSICAL_CONTACT,
                contact.provenance()
        );
        final var observation = new SemanticObservation(
                18,
                body(),
                List.of(contact),
                List.of(),
                List.of(new DangerSignal(
                        DangerKind.THREAT_CONTACT,
                        1.0,
                        0.82,
                        Optional.of(new PerceptionVec3(0.9, 0.0, 0.1)),
                        PerceptionProvenance.PHYSICAL_CONTACT
                )),
                Optional.empty(),
                PerceptionBudget.defaults(),
                new ObservationBudgetUsage(
                        1, 0, 1, 0, 1, 0, 1,
                        false, false, false, false
                ),
                java.util.EnumSet.of(
                        PerceptionProvenance.SELF_PLAYER_STATE,
                        PerceptionProvenance.PHYSICAL_CONTACT
                )
        );

        final String json = new SemanticObservationJsonCodec().encode(
                observation
        );
        assertTrue(json.contains("PHYSICAL_CONTACT"));
        assertTrue(json.contains("interactionLineClear"));
    }

    @Test
    void emitsUncertainCanyonAndUnevenTerrainCuesFromObservedFacesOnly() {
        final SemanticObservation observation = new SemanticObservation(
            20,
            body(),
            List.of(),
            List.of(
                new VisibleBlockFace(
                    new BlockCoordinate(2, 66, 0),
                    "minecraft:stone",
                    "up",
                    new PerceptionVec3(2.5, 66.0, 0.5),
                    3.0,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    TopSupportAffordance.STURDY_FULL_TOP,
                    0
                ),
                new VisibleBlockFace(
                    new BlockCoordinate(-2, 61, 0),
                    "minecraft:stone",
                    "down",
                    new PerceptionVec3(-1.5, 61.0, 0.5),
                    3.0,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    TopSupportAffordance.UNKNOWN,
                    0
                ),
                new VisibleBlockFace(
                    new BlockCoordinate(3, 64, 1),
                    "minecraft:stone",
                    "east",
                    new PerceptionVec3(3.0, 64.5, 1.5),
                    2.0,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    TopSupportAffordance.UNKNOWN,
                    0
                ),
                new VisibleBlockFace(
                    new BlockCoordinate(-3, 64, -1),
                    "minecraft:stone",
                    "west",
                    new PerceptionVec3(-3.0, 64.5, -0.5),
                    2.0,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of(),
                    TopSupportAffordance.UNKNOWN,
                    0
                )
            ),
            List.of(),
            List.of(),
            Optional.empty(),
            PerceptionBudget.defaults(),
            new ObservationBudgetUsage(
                0, 0, 0, 4, 0, 4, 0,
                false, false, false, false
            ),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
            )
        );

        final var geometry = JsonParser.parseString(
            new SemanticObservationJsonCodec().encode(observation)
        ).getAsJsonObject().getAsJsonObject("localGeometry");
        final var cues = geometry.getAsJsonArray("cues").asList();
        assertTrue(cues.stream().anyMatch(value -> value.getAsString().equals(
            "possible_canyon_or_cliff_wall"
        )));
        assertTrue(cues.stream().anyMatch(value -> value.getAsString().equals(
            "possible_confined_uneven_terrain"
        )));
        assertTrue(geometry.get("warning").getAsString().contains(
            "uncertain"
        ));
    }

    @Test
    void exposesOnlyContentsOfAnAlreadyOpenBoundedMenu() {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final var observation = new SemanticObservation(
            18,
            body(),
            List.of(),
            List.of(),
            List.of(),
            Optional.of(new OpenMenuSnapshot(
                "minecraft:generic_9x3",
                "ChestMenu",
                4,
                12,
                List.of(
                    new MenuSlotSummary(
                        0,
                        "minecraft:iron_ingot",
                        5,
                        0,
                        0,
                        false,
                        true
                    ),
                    new MenuSlotSummary(
                        27,
                        "minecraft:oak_log",
                        3,
                        0,
                        0,
                        true,
                        true
                    )
                ),
                HeldItemSummary.empty(),
                List.of(new MenuOptionSummary(
                        1,
                        "merchant_offer",
                        true,
                        Map.of(
                                "costAItem",
                                "minecraft:emerald",
                                "resultItem",
                                "minecraft:bread"
                        )
                ))
            )),
            budget,
            new ObservationBudgetUsage(
                0, 0, 0, 0, 0, 0, 0,
                false, false, false, false
            ),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.OPEN_MENU_CONTENTS
            )
        );

        final var menu = JsonParser.parseString(
            new SemanticObservationJsonCodec().encode(observation)
        ).getAsJsonObject().getAsJsonObject("openMenu");

        assertEquals("minecraft:generic_9x3", menu.get("type").getAsString());
        assertEquals("MENU", menu.getAsJsonArray("slots")
            .get(0).getAsJsonObject().get("location").getAsString());
        assertEquals("PLAYER", menu.getAsJsonArray("slots")
            .get(1).getAsJsonObject().get("location").getAsString());
        assertEquals("UNKNOWN", menu.getAsJsonArray("slots")
            .get(0).getAsJsonObject().get("role").getAsString());
        assertEquals(
                "merchant_offer",
                menu.getAsJsonArray("options")
                        .get(0)
                        .getAsJsonObject()
                        .get("kind")
                        .getAsString()
        );
        assertEquals(
                "minecraft:bread",
                menu.getAsJsonArray("options")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("properties")
                        .get("resultItem")
                        .getAsString()
        );
    }

    @Test
    void auditsHitClearSegmentsWithoutSerializingTheirInternalGeometry() {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        final SemanticObservation observation =
            new SemanticObservation(
                19,
                body(),
                List.of(),
                List.of(),
                List.of(new ClearSightRay(
                    new PerceptionVec3(1.0, 65.62, 0.0),
                    1.0,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                )),
                List.of(),
                Optional.empty(),
                budget,
                new ObservationBudgetUsage(
                    0, 0, 0, 1, 0, 0, 0,
                    false, false, false, false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT
                )
            );

        final var encoded = JsonParser.parseString(
            new SemanticObservationJsonCodec().encode(observation)
        ).getAsJsonObject();

        assertEquals(
            1,
            encoded.getAsJsonObject("budgetAudit")
                .get("blockRaysCast").getAsInt()
        );
        assertTrue(
            encoded.getAsJsonArray("provenance")
                .asList()
                .stream()
                .anyMatch(element ->
                    element.getAsString().equals(
                        "BLOCK_RAY_CLEAR_BEFORE_HIT"
                    )
                )
        );
        assertFalse(encoded.has("clearSightRays"));
    }

    private static BodySnapshot body() {
        return new BodySnapshot(
            PLAYER,
            "minecraft:overworld",
            900,
            new PerceptionVec3(0.0, 64.0, 0.0),
            new PerceptionVec3(0.0, 65.62, 0.0),
            new PerceptionVec3(0.0, 0.0, 1.0),
            20.0F,
            20.0F,
            0.0F,
            18,
            4.0F,
            300,
            300,
            true,
            false,
            false,
            0.0,
            new HeldItemSummary("minecraft:stone_axe", 1, 3, 131),
            HeldItemSummary.empty(),
            List.of(new InventoryItemSummary("minecraft:oak_log", 3)),
            List.of(),
            EnumSet.of(
                PerceptionProvenance.SELF_PLAYER_STATE,
                PerceptionProvenance.OWN_INVENTORY,
                PerceptionProvenance.OWN_STATUS_EFFECT
            )
        );
    }
}
