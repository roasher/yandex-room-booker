package ru.yandex.roombooker.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import lombok.Getter;
import lombok.Setter;

/**
 * Shared settings for automatic room booking on startup.
 *
 * <p>Mode-specific auth/transport settings live in {@link ApiRoomBookerProperties}
 * and {@link BrowserRoomBookerProperties}.
 */
@Getter
@Setter
public abstract class RoomBookerProperties {

    private boolean enabled;
    private String timeZone;
    private String meetingName;
    private String room;
    private String roomEmail;
    private String start;
    private String duration;
    private String bookingOpenBuffer;
    private String slotShiftStep;
    private int bookingMaxRetries;
    private String bookingRetryBackoff;
    private double bookingRetryMultiplier;

    /**
     * Returns the active booking transport: {@code api} or {@code browser}.
     */
    public abstract String bookingMode();

    /**
     * Base URL for the calendar HTTP client used in this mode.
     */
    public abstract String calendarBaseUrl();

    /**
     * Validates mode-specific auth settings (OAuth token, cookies, etc.).
     */
    public abstract void validateAuth();

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
            return Duration.ofSeconds(60);
        }
        return DurationParsing.parse(bookingOpenBuffer);
    }

    public Duration resolvedSlotShiftStep() {
        return DurationParsing.parseRequired(slotShiftStep, "room-booker.slot-shift-step (e.g. 30m)");
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

    protected static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }
}
