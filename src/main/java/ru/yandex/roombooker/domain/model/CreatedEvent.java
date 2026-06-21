package ru.yandex.roombooker.domain.model;

import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * Result of a successful calendar booking.
 */
@Accessors(chain = true)
@Builder
public record CreatedEvent(
        String eventId,
        String summary,
        String roomEmail
) {
}
