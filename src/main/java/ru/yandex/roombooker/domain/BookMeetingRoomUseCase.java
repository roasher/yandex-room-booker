package ru.yandex.roombooker.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Creates a calendar event and books a meeting room for the given time slot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMeetingRoomUseCase {

    private final CalendarEventGateway calendarEventGateway;

    public CreatedEvent execute(BookingRequest request) {
        log.info(
                "Booking room {} for meeting '{}' from {} to {}",
                request.roomEmail(),
                request.meetingName(),
                request.start(),
                request.end()
        );
        return calendarEventGateway.createMeetingWithRoom(request);
    }
}
