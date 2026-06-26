package ru.yandex.roombooker.config;

/**
 * A meeting room from the local catalog ({@code rooms.yml}).
 */
public record MeetingRoomEntry(
        String displayName,
        String exchange,
        String office,
        String maxDuration,
        String bookableAhead
) {
}
