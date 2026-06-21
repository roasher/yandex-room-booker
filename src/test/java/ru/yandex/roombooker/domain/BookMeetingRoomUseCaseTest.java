package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

@ExtendWith(MockitoExtension.class)
class BookMeetingRoomUseCaseTest {

    @Mock
    private CalendarEventGateway calendarEventGateway;

    @InjectMocks
    private BookMeetingRoomUseCase bookMeetingRoomUseCase;

    @Test
    void shouldDelegateBookingToCalendarGateway() {
        BookingRequest request = BookingRequest.builder()
                .meetingName("Team sync")
                .roomEmail("cr_000004170@yandex-team.ru")
                .start(LocalDateTime.parse("2026-06-22T14:00:00"))
                .end(LocalDateTime.parse("2026-06-22T15:00:00"))
                .timeZone("Europe/Moscow")
                .build();
        CreatedEvent expected = CreatedEvent.builder()
                .eventId("event-123")
                .summary("Team sync")
                .roomEmail("cr_000004170@yandex-team.ru")
                .build();
        when(calendarEventGateway.createMeetingWithRoom(request)).thenReturn(expected);

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway).createMeetingWithRoom(request);
    }
}
