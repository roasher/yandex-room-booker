package ru.yandex.roombooker.domain.model;

import java.util.List;

import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * One meeting configuration: a single room and its own resolved target slots.
 */
@Builder
@Accessors(chain = true)
public record BookingPlan(String meetingName, String roomReference, List<BookingSlot> slots) {
    public BookingPlan {
        slots = List.copyOf(slots);
    }
}
