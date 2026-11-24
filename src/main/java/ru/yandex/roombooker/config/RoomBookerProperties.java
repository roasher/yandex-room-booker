package ru.yandex.roombooker.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Shared transport, time zone, and ladder settings for all configured booking plans.
 * Mode-specific authentication lives in the API and browser subclasses.
 */
@Getter
@Setter
public abstract class RoomBookerProperties {

    private String timeZone;
    private String bookingOpenBuffer;
    private String slotShiftStep;
    private int bookingMaxRetries;
    private String bookingRetryBackoff;
    private double bookingRetryMultiplier;

    /** Returns the active booking transport: api or browser. */
    public abstract String bookingMode();

    /** Returns the base URL for the selected calendar transport. */
    public abstract String calendarBaseUrl();

    /** Validates the selected transport's authentication settings. */
    public abstract void validateAuth();

    public Duration resolvedBookingOpenBuffer() {
        if (bookingOpenBuffer == null || bookingOpenBuffer.isBlank()) {
            return Duration.ofSeconds(60);
        }
        return DurationParsing.parse(bookingOpenBuffer);
    }

    public Duration resolvedSlotShiftStep() {
        return DurationParsing.parseRequired(slotShiftStep, "room-booker-settings.slot-shift-step (e.g. 30m)");
    }

    public Duration resolvedBookingRetryBackoff() {
        if (bookingRetryBackoff == null || bookingRetryBackoff.isBlank()) {
            return Duration.ZERO;
        }
        return DurationParsing.parse(bookingRetryBackoff);
    }

    protected static void requireNonBlank(@Nullable String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }
}
