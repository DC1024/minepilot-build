package dev.mcai.companion.integration.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class XaeroWaypointParserTest {
    @Test
    void parsesModern2643ShareAndExplicitDimension() throws Exception {
        ParsedXaeroWaypoint parsed = XaeroWaypointParser.parse(
                "xaero-waypoint:Home:H:120:64:-45:12:false:0:Internal-overworld"
        );

        assertEquals(XaeroShareFormat.MODERN, parsed.format());
        assertEquals("Home", parsed.displayName());
        assertEquals(120, parsed.x());
        assertEquals(64, parsed.y().orElseThrow());
        assertEquals(-45, parsed.z());
        assertEquals(12, parsed.colorIndex());
        assertFalse(parsed.rotation());
        assertEquals(0, parsed.yaw());
        assertEquals("Internal-overworld", parsed.rawTargetDescription());
        assertEquals(XaeroDestinationKind.EXPLICIT_DIMENSION, parsed.destinationKind());
        assertEquals(DimensionRef.OVERWORLD, parsed.dimension().orElseThrow());
    }

    @Test
    void parsesLegacyPrefixChineseEscapesAndMissingY() throws Exception {
        ParsedXaeroWaypoint parsed = XaeroWaypointParser.parse(
                "\u00a7axaero_waypoint:北部^col^仓库:北:20:~:30:4:true:-90:Internal-the_nether"
        );

        assertEquals(XaeroShareFormat.LEGACY, parsed.format());
        assertEquals("北部:仓库", parsed.displayName());
        assertEquals("北", parsed.initials());
        assertTrue(parsed.y().isEmpty());
        assertTrue(parsed.rotation());
        assertEquals(-90, parsed.yaw());
        assertEquals(DimensionRef.NETHER, parsed.dimension().orElseThrow());
    }

    @Test
    void internalRequiresExplicitCallerPolicy() throws Exception {
        ParsedXaeroWaypoint parsed = XaeroWaypointParser.parse(
                "xaero-waypoint:家:H:0:70:0:1:false:0:Internal"
        );

        assertTrue(parsed.dimension().isEmpty());
        assertEquals(
                java.util.Optional.empty(),
                parsed.resolveDimension(
                        CurrentDimensionPolicy.REJECT_UNQUALIFIED,
                        DimensionRef.END
                )
        );
        assertEquals(
                DimensionRef.END,
                parsed.resolveDimension(
                        CurrentDimensionPolicy.USE_CALLER_CURRENT,
                        DimensionRef.END
                ).orElseThrow()
        );
    }

    @Test
    void doesNotInterpretUnknownOrEncodedDimensionAsAuthority() throws Exception {
        ParsedXaeroWaypoint parsed = XaeroWaypointParser.parse(
                "xaero-waypoint:Portal:P:1:64:2:3:false:0:"
                        + "Internal-minecraft^col^the_nether"
        );

        assertEquals("Internal-minecraft:the_nether", parsed.targetDescription());
        assertEquals(XaeroDestinationKind.OPAQUE, parsed.destinationKind());
        assertTrue(parsed.dimension().isEmpty());
        assertTrue(parsed.resolveDimension(
                CurrentDimensionPolicy.USE_CALLER_CURRENT,
                DimensionRef.NETHER
        ).isEmpty());
    }

    @Test
    void rejectsOverlongNanFakePrefixTraversalAndExtraFields() {
        assertReason(
                "xaero-waypoint:" + "a".repeat(600),
                XaeroWaypointParseException.Reason.MESSAGE_TOO_LONG
        );
        assertReason(
                "xaero-waypoint:Home:H:NaN:64:0:1:false:0:Internal",
                XaeroWaypointParseException.Reason.INVALID_NUMBER
        );
        assertReason(
                "player says xaero-waypoint:Home:H:0:64:0:1:false:0:Internal",
                XaeroWaypointParseException.Reason.NOT_XAERO_MESSAGE
        );
        assertReason(
                "xaero-waypoint:Home:H:0:64:0:1:false:0:Internal-../../the_end",
                XaeroWaypointParseException.Reason.INVALID_DESTINATION
        );
        assertReason(
                "xaero-waypoint:Home:H:0:64:0:1:false:0:Internal:extra",
                XaeroWaypointParseException.Reason.WRONG_FIELD_COUNT
        );
    }

    @Test
    void rejectsOutOfRangeAndNonCanonicalNumbers() {
        assertReason(
                "xaero-waypoint:Home:H:30000001:64:0:1:false:0:Internal",
                XaeroWaypointParseException.Reason.OUT_OF_RANGE
        );
        assertReason(
                "xaero-waypoint:Home:H:01:64:0:1:false:0:Internal",
                XaeroWaypointParseException.Reason.INVALID_NUMBER
        );
        assertReason(
                "xaero-waypoint:Home:H:0:64:0:21:false:0:Internal",
                XaeroWaypointParseException.Reason.OUT_OF_RANGE
        );
    }

    private static void assertReason(
            String message,
            XaeroWaypointParseException.Reason expected
    ) {
        XaeroWaypointParseException exception = assertThrows(
                XaeroWaypointParseException.class,
                () -> XaeroWaypointParser.parse(message)
        );
        assertEquals(expected, exception.reason());
    }
}
