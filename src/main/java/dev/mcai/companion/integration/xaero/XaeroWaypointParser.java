package dev.mcai.companion.integration.xaero;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Strict parser for Xaero 26.4.2/26.4.3 structured chat waypoint shares.
 *
 * <p>It intentionally parses only an entire chat payload beginning with a
 * supported prefix. It does not scan prose, OCR a screen, execute Xaero's
 * clickable command, invoke teleportation, or infer modded dimensions.</p>
 */
public final class XaeroWaypointParser {
    public static final int MAX_MESSAGE_LENGTH = 512;
    public static final int MAX_NAME_CODE_POINTS = 32;
    public static final int MAX_INITIALS_CODE_POINTS = 3;
    public static final int MAX_TARGET_CODE_POINTS = 192;
    public static final int MAX_ABSOLUTE_COORDINATE = 30_000_000;
    public static final int MAX_COLOR_INDEX = 20;
    public static final int MAX_ABSOLUTE_YAW = 360;

    private static final String ESCAPED_COLON = "^col^";

    private XaeroWaypointParser() {
    }

    public static ParsedXaeroWaypoint parse(String chat) throws XaeroWaypointParseException {
        if (chat == null || chat.isEmpty()) {
            throw failure(XaeroWaypointParseException.Reason.NOT_XAERO_MESSAGE);
        }
        if (chat.length() > MAX_MESSAGE_LENGTH) {
            throw failure(XaeroWaypointParseException.Reason.MESSAGE_TOO_LONG);
        }

        String plainChat = stripMinecraftFormatting(chat);
        rejectForbiddenCodePoints(plainChat);
        XaeroShareFormat format = detectFormat(plainChat);
        String payload = plainChat.substring(format.prefix().length());
        String[] fields = payload.split(":", -1);
        if (fields.length != 9) {
            throw failure(XaeroWaypointParseException.Reason.WRONG_FIELD_COUNT);
        }

        String name = parseDisplayField(fields[0], MAX_NAME_CODE_POINTS);
        String initials = parseDisplayField(fields[1], MAX_INITIALS_CODE_POINTS);
        int x = parseCoordinate(fields[2]);
        OptionalInt y = fields[3].equals("~")
                ? OptionalInt.empty()
                : OptionalInt.of(parseCoordinate(fields[3]));
        int z = parseCoordinate(fields[4]);
        int color = parseBoundedInteger(fields[5], 0, MAX_COLOR_INDEX);
        boolean rotation = parseBoolean(fields[6]);
        int yaw = parseBoundedInteger(fields[7], -MAX_ABSOLUTE_YAW, MAX_ABSOLUTE_YAW);
        Destination destination = parseDestination(fields[8]);

        return new ParsedXaeroWaypoint(
                format,
                name,
                initials,
                x,
                y,
                z,
                color,
                rotation,
                yaw,
                fields[8],
                destination.description(),
                destination.kind(),
                destination.dimension()
        );
    }

    public static Optional<ParsedXaeroWaypoint> tryParse(String chat) {
        try {
            return Optional.of(parse(chat));
        } catch (XaeroWaypointParseException exception) {
            return Optional.empty();
        }
    }

    private static XaeroShareFormat detectFormat(String chat) throws XaeroWaypointParseException {
        for (XaeroShareFormat format : XaeroShareFormat.values()) {
            if (chat.startsWith(format.prefix())) {
                return format;
            }
        }
        throw failure(XaeroWaypointParseException.Reason.NOT_XAERO_MESSAGE);
    }

    private static String stripMinecraftFormatting(String input) throws XaeroWaypointParseException {
        StringBuilder plain = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character != '\u00a7') {
                plain.append(character);
                continue;
            }
            if (index + 1 >= input.length()) {
                throw failure(XaeroWaypointParseException.Reason.INVALID_FORMATTING);
            }
            index++;
        }
        return plain.toString();
    }

    private static String parseDisplayField(
            String encoded,
            int maximumCodePoints
    ) throws XaeroWaypointParseException {
        String decoded = decodeColon(encoded).strip();
        if (decoded.isEmpty()
                || decoded.length() > maximumCodePoints
                || decoded.codePointCount(0, decoded.length()) > maximumCodePoints) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_TEXT);
        }
        rejectForbiddenCodePoints(decoded);
        return decoded;
    }

    private static int parseCoordinate(String encoded) throws XaeroWaypointParseException {
        return parseBoundedInteger(
                encoded,
                -MAX_ABSOLUTE_COORDINATE,
                MAX_ABSOLUTE_COORDINATE
        );
    }

    private static int parseBoundedInteger(
            String encoded,
            int minimum,
            int maximum
    ) throws XaeroWaypointParseException {
        if (encoded.isEmpty() || encoded.length() > 11) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_NUMBER);
        }
        final int value;
        try {
            value = Integer.parseInt(encoded);
        } catch (NumberFormatException exception) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_NUMBER);
        }
        if (!Integer.toString(value).equals(encoded)) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_NUMBER);
        }
        if (value < minimum || value > maximum) {
            throw failure(XaeroWaypointParseException.Reason.OUT_OF_RANGE);
        }
        return value;
    }

    private static boolean parseBoolean(String encoded) throws XaeroWaypointParseException {
        if (encoded.equals("true")) {
            return true;
        }
        if (encoded.equals("false")) {
            return false;
        }
        throw failure(XaeroWaypointParseException.Reason.INVALID_TEXT);
    }

    private static Destination parseDestination(String encoded)
            throws XaeroWaypointParseException {
        if (encoded.isEmpty()
                || encoded.length() > MAX_TARGET_CODE_POINTS
                || encoded.codePointCount(0, encoded.length()) > MAX_TARGET_CODE_POINTS) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_DESTINATION);
        }
        String description = decodeColon(encoded);
        rejectForbiddenCodePoints(description);

        return switch (description) {
            case "Internal" -> new Destination(
                    description,
                    XaeroDestinationKind.CALLER_CURRENT_DIMENSION,
                    Optional.empty()
            );
            case "Internal-overworld" -> explicit(description, DimensionRef.OVERWORLD);
            case "Internal-the_nether" -> explicit(description, DimensionRef.NETHER);
            case "Internal-the_end" -> explicit(description, DimensionRef.END);
            case "External" -> new Destination(
                    description,
                    XaeroDestinationKind.OPAQUE,
                    Optional.empty()
            );
            default -> opaqueInternalDestination(description);
        };
    }

    private static Destination explicit(String description, DimensionRef dimension) {
        return new Destination(
                description,
                XaeroDestinationKind.EXPLICIT_DIMENSION,
                Optional.of(dimension)
        );
    }

    private static Destination opaqueInternalDestination(String description)
            throws XaeroWaypointParseException {
        if (!description.startsWith("Internal-")
                || description.length() == "Internal-".length()
                || description.contains("..")
                || description.indexOf('/') >= 0
                || description.indexOf('\\') >= 0) {
            throw failure(XaeroWaypointParseException.Reason.INVALID_DESTINATION);
        }
        return new Destination(
                description,
                XaeroDestinationKind.OPAQUE,
                Optional.empty()
        );
    }

    private static String decodeColon(String value) {
        return value.replace(ESCAPED_COLON, ":");
    }

    private static void rejectForbiddenCodePoints(String value)
            throws XaeroWaypointParseException {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || codePoint == '\u00a7') {
                throw failure(XaeroWaypointParseException.Reason.INVALID_TEXT);
            }
        }
    }

    private static XaeroWaypointParseException failure(
            XaeroWaypointParseException.Reason reason
    ) {
        return new XaeroWaypointParseException(reason);
    }

    private record Destination(
            String description,
            XaeroDestinationKind kind,
            Optional<DimensionRef> dimension
    ) {
    }
}
