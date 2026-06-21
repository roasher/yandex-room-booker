package ru.yandex.roombooker.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

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
        String oauthToken
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
        if (duration == null || duration.isBlank()) {
            throw new IllegalStateException(
                    "Missing or invalid property: room-booker.duration (e.g. 90m, 1h, 1h30m)"
            );
        }
        try {
            return parseDuration(duration);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid property room-booker.duration='%s' (e.g. 90m, 1h, 1h30m)".formatted(duration),
                    exception
            );
        }
    }

    private static Duration parseDuration(String value) {
        try {
            return DurationStyle.detectAndParse(value);
        } catch (IllegalArgumentException ignored) {
            Matcher matcher = COMPOUND_DURATION.matcher(value.trim());
            if (matcher.matches()) {
                long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
                long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
                long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
                if (hours + minutes + seconds > 0) {
                    return Duration.ofSeconds(hours * 3600 + minutes * 60 + seconds);
                }
            }
            throw new IllegalArgumentException("'%s' is not a valid duration".formatted(value));
        }
    }

    private static final Pattern COMPOUND_DURATION = Pattern.compile(
            "^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$",
            Pattern.CASE_INSENSITIVE
    );

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
