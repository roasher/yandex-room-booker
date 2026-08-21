package ru.yandex.roombooker.config;

import org.jspecify.annotations.Nullable;

/**
 * A meeting room from the local catalog ({@code rooms.yml}).
 */
public record MeetingRoomEntry(
        String displayName,
        String exchange,
        String office,
        String maxDuration,
        @Nullable String bookableAhead
) {
    /**
     * When false, the room can be booked anytime a free slot exists (no progressive ladder).
     */
    public boolean hasBookableAheadPolicy() {
        return bookableAhead != null && !bookableAhead.isBlank();
    }
}
