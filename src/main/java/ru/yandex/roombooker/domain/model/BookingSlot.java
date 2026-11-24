package ru.yandex.roombooker.domain.model;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * A requested booking window or a temporary window on the progressive booking ladder.
 */
@Accessors(chain = true)
@Builder
public record BookingSlot(
        LocalDateTime start,
        LocalDateTime end
) {
    /**
     * Whether these half-open windows overlap; adjacent bookings do not overlap.
     */
    public boolean overlaps(BookingSlot other) {
        return start.isBefore(other.end()) && other.start().isBefore(end);
    }
}
