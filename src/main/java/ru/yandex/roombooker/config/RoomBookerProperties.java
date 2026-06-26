package ru.yandex.roombooker.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings for automatic room booking on startup.
 */
@ConfigurationProperties(prefix = "room-booker")
public record RoomBookerProperties(
        boolean enabled,
        String apiBaseUrl,
        String timeZone,
        String meetingName,
        String room,
        String roomEmail,
        String start,
        String duration,
        String oauthToken,
        String bookingOpenBuffer,
        int bookingMaxRetries,
        String bookingRetryBackoff,
        double bookingRetryMultiplier
) {
    public String effectiveRoomReference() {
        if (room != null && !room.isBlank()) {
            return room;
        }
        return roomEmail;
    }

    public LocalDateTime bookingStart() {
        return parseDateTime(start);
    }

    public LocalDateTime bookingEnd() {
        return bookingStart().plus(bookingDuration());
    }

    public Duration bookingDuration() {
        return DurationParsing.parseRequired(duration, "room-booker.duration (e.g. 90m, 1h, 1h30m)");
    }

    public Duration resolvedBookingOpenBuffer() {
        if (bookingOpenBuffer == null || bookingOpenBuffer.isBlank()) {
            return Duration.ZERO;
        }
        return DurationParsing.parse(bookingOpenBuffer);
    }

    public Duration resolvedBookingRetryBackoff() {
        if (bookingRetryBackoff == null || bookingRetryBackoff.isBlank()) {
            return Duration.ZERO;
        }
        return DurationParsing.parse(bookingRetryBackoff);
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Invalid datetime '%s', expected ISO-8601 like 2026-06-22T14:00:00".formatted(value),
                    exception
            );
        }
    }
}
