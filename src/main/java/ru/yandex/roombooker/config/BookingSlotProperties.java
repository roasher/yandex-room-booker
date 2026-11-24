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

import org.jspecify.annotations.Nullable;
import ru.yandex.roombooker.domain.model.BookingSlot;

/**
 * One requested slot; an omitted duration inherits its booking plan's duration.
 */
public record BookingSlotProperties(
        @Nullable String date,
        @Nullable Integer dayOfWeek,
        @Nullable String time,
        @Nullable String duration
) {
    /** Resolves a date or next weekday and the effective duration into a fixed target window. */
    public BookingSlot resolve(Clock clock, String timeZone, @Nullable String defaultDuration, String prefix) {
        LocalDateTime start = bookingStart(clock, timeZone, prefix);
        String effectiveDuration = duration == null ? defaultDuration : duration;
        Duration parsedDuration;
        try {
            parsedDuration = DurationParsing.parseRequired(effectiveDuration, prefix + ".duration");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid " + prefix + ".duration: " + effectiveDuration, exception);
        }
        if (parsedDuration.isZero() || parsedDuration.isNegative()) {
            throw new IllegalStateException(prefix + ".duration must be positive");
        }
        return new BookingSlot(start, start.plus(parsedDuration));
    }

    /** Combines the configured time with an explicit date or the next weekday strictly after today. */
    public LocalDateTime bookingStart(Clock clock, String timeZone, String prefix) {
        RoomBookerProperties.requireNonBlank(time, prefix + ".time");
        LocalDate bookingDate = bookingDate(clock, timeZone, prefix);
        try {
            return bookingDate.atTime(LocalTime.parse(time.strip()));
        } catch (DateTimeException exception) {
            throw new IllegalStateException(
                    "Invalid %s.time '%s', expected HH:mm or HH:mm:ss".formatted(prefix, time), exception
            );
        }
    }

    private LocalDate bookingDate(Clock clock, String timeZone, String prefix) {
        if (dayOfWeek != null && (dayOfWeek < 1 || dayOfWeek > 7)) {
            throw new IllegalStateException(
                    "Invalid %s.day-of-week '%s', expected an integer from 1 (Monday) to 7 (Sunday)"
                            .formatted(prefix, dayOfWeek)
            );
        }
        if (date != null && !date.isBlank()) {
            LocalDate bookingDate;
            try {
                bookingDate = LocalDate.parse(date.strip());
            } catch (DateTimeException exception) {
                throw new IllegalStateException(
                        "Invalid %s.date '%s', expected YYYY-MM-DD".formatted(prefix, date), exception
                );
            }
            if (dayOfWeek != null && bookingDate.getDayOfWeek().getValue() != dayOfWeek) {
                throw new IllegalStateException(
                        "%s.date '%s' is %s (%d), but %s.day-of-week is %d".formatted(
                                prefix, bookingDate, bookingDate.getDayOfWeek(), bookingDate.getDayOfWeek().getValue(),
                                prefix, dayOfWeek
                        )
                );
            }
            return bookingDate;
        }
        if (dayOfWeek == null) {
            throw new IllegalStateException(
                    "At least one of %s.date or %s.day-of-week is required".formatted(prefix, prefix)
            );
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
        return today.with(TemporalAdjusters.next(DayOfWeek.of(dayOfWeek)));
    }
}
