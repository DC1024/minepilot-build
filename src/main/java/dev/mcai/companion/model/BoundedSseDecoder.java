package dev.mcai.companion.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal WHATWG-compatible SSE field decoder for model streaming responses.
 */
final class BoundedSseDecoder {
    private static final int MAX_LINE_CHARS = 1_048_576;
    private static final int MAX_EVENT_CHARS = 1_048_576;
    private static final int MAX_EVENTS = 32_768;

    List<SseEvent> decode(String input) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        EventBuilder current = new EventBuilder();

        int start = 0;
        for (int index = 0; index <= input.length(); index++) {
            boolean end = index == input.length();
            char character = end ? '\n' : input.charAt(index);
            if (!end && character != '\n' && character != '\r') {
                continue;
            }

            int lineLength = index - start;
            if (lineLength > MAX_LINE_CHARS) {
                throw new IOException("SSE line exceeds the configured size limit");
            }
            String line = input.substring(start, index);
            if (!end && character == '\r' && index + 1 < input.length()
                    && input.charAt(index + 1) == '\n') {
                index++;
            }
            start = index + 1;

            if (line.isEmpty()) {
                dispatch(current, events);
                current = new EventBuilder();
            } else {
                consumeLine(line, current);
            }
        }
        dispatch(current, events);
        return List.copyOf(events);
    }

    private static void consumeLine(String line, EventBuilder event) throws IOException {
        if (line.startsWith(":")) {
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }

        switch (field) {
            case "event" -> event.name = value;
            case "data" -> {
                if (!event.data.isEmpty()) {
                    event.data.append('\n');
                }
                event.data.append(value);
                if (event.data.length() > MAX_EVENT_CHARS) {
                    throw new IOException("SSE event exceeds the configured size limit");
                }
                event.hasData = true;
            }
            default -> {
                // id, retry, and extension fields are irrelevant to this client.
            }
        }
    }

    private static void dispatch(EventBuilder current, List<SseEvent> events) throws IOException {
        if (!current.hasData) {
            return;
        }
        if (events.size() >= MAX_EVENTS) {
            throw new IOException("SSE event count exceeds the configured limit");
        }
        events.add(new SseEvent(current.name, current.data.toString()));
    }

    private static final class EventBuilder {
        private String name = "";
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;
    }
}
