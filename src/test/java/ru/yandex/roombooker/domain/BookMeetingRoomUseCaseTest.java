package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

@ExtendWith(MockitoExtension.class)
class BookMeetingRoomUseCaseTest {

    @Mock
    private CalendarEventGateway calendarEventGateway;

    @Mock
    private RoomBookerProperties properties;

    @InjectMocks
    private BookMeetingRoomUseCase bookMeetingRoomUseCase;

    private final BookingRequest request = BookingRequest.builder()
            .meetingName("Team sync")
            .roomEmail("cr_000004170")
            .start(LocalDateTime.parse("2026-06-22T14:00:00"))
            .end(LocalDateTime.parse("2026-06-22T15:00:00"))
            .timeZone("Europe/Moscow")
            .build();

    private final CreatedEvent createdEvent = CreatedEvent.builder()
            .eventId("event-123")
            .summary("Team sync")
            .build();

    private final CreatedEvent expected = CreatedEvent.builder()
            .eventId("event-123")
            .summary("Team sync")
            .roomEmail("cr_000004170@yandex-team.ru")
            .build();

    @Test
    void shouldCreateEventOnceAndAttachRoom() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway).createEvent(request);
        verify(calendarEventGateway).attachRoom("event-123", "cr_000004170");
    }

    @Test
    void shouldRetryRoomAttachWithoutCreatingNewEvents() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        doThrow(new RuntimeException("HTTP 500"))
                .doNothing()
                .when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway, times(1)).createEvent(request);
        verify(calendarEventGateway, times(2)).attachRoom("event-123", "cr_000004170");
    }

    @Test
    void shouldRethrowAfterRoomAttachRetriesAreExhausted() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        doThrow(new RuntimeException("HTTP 500"))
                .when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        assertThatThrownBy(() -> bookMeetingRoomUseCase.execute(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("HTTP 500");
        verify(calendarEventGateway, times(1)).createEvent(request);
        verify(calendarEventGateway, times(3)).attachRoom("event-123", "cr_000004170");
    }

    @Test
    void shouldRetryCreateWhenRoomIsBookedDuringCreate() {
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(true);
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        when(calendarEventGateway.createEvent(request))
                .thenThrow(new RuntimeException("too-far-event"))
                .thenReturn(createdEvent);
        doNothing().when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway, times(2)).createEvent(request);
    }
}
