package ru.yandex.roombooker.domain;

import java.time.LocalDateTime;
import java.util.List;

import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.domain.model.ExistingEvent;

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

    /**
     * Deletes an event (used to roll back create+attach when room booking fails).
     */
    void deleteEvent(String eventId);

    /**
     * Updates event start/end (used to walk a held booking along the ladder).
     */
    CreatedEvent updateEventTime(String eventId, LocalDateTime start, LocalDateTime end, String timeZone);

    /**
     * Lists events overlapping {@code [from, to)} for resume-after-restart discovery.
     */
    List<ExistingEvent> findEvents(LocalDateTime from, LocalDateTime to, String timeZone);
}
