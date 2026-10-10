package dev.mcai.companion.skills.progress;

import java.util.Objects;

public record ProgressNoteParameters(String note) {
    public ProgressNoteParameters {
        note = Objects.requireNonNull(note, "note").strip();
        if (note.isEmpty()
                || note.codePointCount(0, note.length()) > 256) {
            throw new IllegalArgumentException("Progress note is invalid");
        }
        for (int offset = 0; offset < note.length();) {
            final int codePoint = note.codePointAt(offset);
            if (codePoint == 0
                    || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\t')) {
                throw new IllegalArgumentException(
                    "Progress note contains a control character"
                );
            }
            offset += Character.charCount(codePoint);
        }
    }
}
