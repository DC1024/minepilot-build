package dev.mcai.companion.perception;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One control that an ordinary player can see in an already-open menu.
 *
 * <p>Properties are generated from server-owned registry identifiers and
 * numeric menu state. They intentionally never contain hover text, renamed
 * items, books, signs, chat, or other player-authored strings.</p>
 */
public record MenuOptionSummary(
        int optionId,
        String kind,
        boolean available,
        Map<String, String> properties
) {
    public static final int MAX_PROPERTIES = 16;
    public static final int MAX_VALUE_CHARACTERS = 256;

    public MenuOptionSummary {
        if (optionId < 0 || optionId > 4_096) {
            throw new IllegalArgumentException("Menu option id is invalid");
        }
        kind = boundedToken(kind, "kind");
        Objects.requireNonNull(properties, "properties");
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException(
                    "Menu option has too many properties"
            );
        }
        final TreeMap<String, String> checked = new TreeMap<>();
        properties.forEach((key, value) -> {
            final String checkedKey = boundedToken(key, "property key");
            final String checkedValue = Objects.requireNonNull(
                    value,
                    "property value"
            );
            if (checkedValue.length() > MAX_VALUE_CHARACTERS
                    || checkedValue.indexOf('\0') >= 0
                    || checkedValue.indexOf('\r') >= 0
                    || checkedValue.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                        "Menu option property value is invalid"
                );
            }
            if (checked.putIfAbsent(checkedKey, checkedValue) != null) {
                throw new IllegalArgumentException(
                        "Duplicate menu option property"
                );
            }
        });
        properties = Map.copyOf(checked);
    }

    private static String boundedToken(
            final String value,
            final String name
    ) {
        final String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()
                || checked.length() > 64
                || checked.indexOf('\0') >= 0
                || checked.indexOf('\r') >= 0
                || checked.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "Menu option " + name + " is invalid"
            );
        }
        return checked;
    }
}
