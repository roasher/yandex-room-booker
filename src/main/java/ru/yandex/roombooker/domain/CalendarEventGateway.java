package ru.yandex.roombooker.domain;

import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Outgoing port for Yandex Calendar API.
 */
public interface CalendarEventGateway {

    CreatedEvent createMeetingWithRoom(BookingRequest request);
}
