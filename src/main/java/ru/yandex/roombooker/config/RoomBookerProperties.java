package ru.yandex.roombooker.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

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
    private @Nullable String date;
    private @Nullable Integer dayOfWeek;
    private String time;
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

    /**
     * Combines the configured time with an ISO date or the next ISO weekday (1 = Monday, 7 = Sunday).
     * At least one of date and day-of-week is required; when both are set, they must agree.
     * The next weekday is strictly after today, even if the requested time is still ahead today.
     */
    public LocalDateTime bookingStart(Clock clock) {
        requireNonBlank(time, "room-booker.time");
        LocalDate bookingDate = resolveBookingDate(clock);
        try {
            return bookingDate.atTime(LocalTime.parse(time.strip()));
        } catch (DateTimeException exception) {
            throw new IllegalStateException(
                    "Invalid room-booker.time '%s', expected HH:mm or HH:mm:ss".formatted(time),
                    exception
            );
        }
    }

    private LocalDate resolveBookingDate(Clock clock) {
        if (dayOfWeek != null && (dayOfWeek < 1 || dayOfWeek > 7)) {
            throw new IllegalStateException(
                    "Invalid room-booker.day-of-week '%s', expected an integer from 1 (Monday) to 7 (Sunday)"
                            .formatted(dayOfWeek)
            );
        }
        if (date != null && !date.isBlank()) {
            LocalDate bookingDate;
            try {
                bookingDate = LocalDate.parse(date.strip());
            } catch (DateTimeException exception) {
                throw new IllegalStateException(
                        "Invalid room-booker.date '%s', expected YYYY-MM-DD like 2026-06-22".formatted(date),
                        exception
                );
            }
            if (dayOfWeek != null && bookingDate.getDayOfWeek().getValue() != dayOfWeek) {
                throw new IllegalStateException(
                        "room-booker.date '%s' is %s (%d), but room-booker.day-of-week is %d".formatted(
                                bookingDate,
                                bookingDate.getDayOfWeek(),
                                bookingDate.getDayOfWeek().getValue(),
                                dayOfWeek
                        )
                );
            }
            return bookingDate;
        }
        if (dayOfWeek == null) {
            throw new IllegalStateException(
                    "At least one of room-booker.date or room-booker.day-of-week is required"
            );
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
        return today.with(TemporalAdjusters.next(DayOfWeek.of(dayOfWeek)));
    }

    public LocalDateTime bookingEnd(Clock clock) {
        return bookingStart(clock).plus(bookingDuration());
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

    protected static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }
}
