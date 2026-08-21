package ru.yandex.roombooker.domain.model;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * A single duration-length window on the progressive booking ladder.
 */
@Accessors(chain = true)
@Builder
public record BookingSlot(
        LocalDateTime start,
        LocalDateTime end
) {
}
