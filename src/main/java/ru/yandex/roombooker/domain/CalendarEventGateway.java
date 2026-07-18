package ru.yandex.roombooker.domain;

import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Outgoing port for Yandex Calendar (Public API or web UI API).
 */
public interface CalendarEventGateway {

    /**
     * When {@code true}, the room is booked inside {@link #createEvent} (browser UI path).
     * Callers should retry create instead of a separate attach step.
     */
    default boolean booksRoomDuringCreate() {
        return false;
    }

    CreatedEvent createEvent(BookingRequest request);

    void attachRoom(String eventId, String roomId);
}
