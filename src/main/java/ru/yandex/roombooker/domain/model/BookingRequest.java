package ru.yandex.roombooker.domain.model;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * Parameters for creating a calendar event with a booked meeting room.
 */
@Accessors(chain = true)
@Builder
public record BookingRequest(
        String meetingName,
        String roomEmail,
        LocalDateTime start,
        LocalDateTime end,
        String timeZone
) {
}
