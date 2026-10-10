package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SemanticObservationTest {
    @Test
    void defensivelyCopiesEveryCollection() {
        List<InventoryItemSummary> inventory = new ArrayList<>();
        inventory.add(new InventoryItemSummary("minecraft:oak_log", 4));
        BodySnapshot body = body(inventory);
        List<VisibleBlockFace> faces = new ArrayList<>();
        faces.add(new VisibleBlockFace(
                new BlockCoordinate(0, 64, 1),
                "minecraft:stone",
                "north",
                new PerceptionVec3(0.5, 64.5, 1.0),
                1.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        ));
        PerceptionBudget budget = PerceptionBudget.defaults();
        SemanticObservation observation = new SemanticObservation(
                0,
                body,
                List.of(),
                faces,
                List.of(),
                budget,
                new ObservationBudgetUsage(
                        0,
                        0,
                        0,
                        1,
                        0,
                        1,
                        0,
                        false,
                        false,
                        false,
                        false
                ),
                EnumSet.of(
                        PerceptionProvenance.SELF_PLAYER_STATE,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
                )
        );

        inventory.clear();
        faces.clear();
        assertEquals(1, body.inventory().size());
        assertEquals(1, observation.visibleBlockFaces().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observation.visibleBlockFaces().clear()
        );
    }

    @Test
    void resultCountsMustMatchBudgetAudit() {
        PerceptionBudget budget = PerceptionBudget.defaults();
        assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticObservation(
                        0,
                        body(List.of()),
                        List.of(),
                        List.of(),
                        List.of(),
                        budget,
                        new ObservationBudgetUsage(
                                0,
                                0,
                                0,
                                0,
                                1,
                                0,
                                0,
                                false,
                                false,
                                false,
                                false
                        ),
                        Set.of(PerceptionProvenance.SELF_PLAYER_STATE)
                )
        );
    }

    private static BodySnapshot body(List<InventoryItemSummary> inventory) {
        return new BodySnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "minecraft:overworld",
                100,
                new PerceptionVec3(0.0, 64.0, 0.0),
                new PerceptionVec3(0.0, 65.62, 0.0),
                new PerceptionVec3(0.0, 0.0, 1.0),
                20.0F,
                20.0F,
                0.0F,
                20,
                5.0F,
                300,
                300,
                true,
                false,
                false,
                0.0,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                inventory,
                List.of(),
                EnumSet.of(
                        PerceptionProvenance.SELF_PLAYER_STATE,
                        PerceptionProvenance.OWN_INVENTORY,
                        PerceptionProvenance.OWN_STATUS_EFFECT
                )
        );
    }
}
