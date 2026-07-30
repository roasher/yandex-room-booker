package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Mock
    private CalendarEventGateway calendarEventGateway;

    @Mock
    private RoomBookerProperties properties;

    @Mock
    private Clock clock;

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

    @BeforeEach
    void setUpClock() {
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(MOSCOW);
        org.mockito.Mockito.lenient().when(clock.instant())
                .thenReturn(Instant.parse("2026-06-22T11:00:00Z"));
    }

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
        verify(calendarEventGateway).deleteEvent("event-123");
    }

    @Test
    void shouldRetryWhenRoomIsBusyThenSucceed() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        doThrow(new ru.yandex.roombooker.adapter.out.client.CalendarApiException(
                "Calendar API request failed: HTTP 400 rooms are busy",
                400
        ))
                .doNothing()
                .when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway, times(1)).createEvent(request);
        verify(calendarEventGateway, times(2)).attachRoom("event-123", "cr_000004170");
        verify(calendarEventGateway, times(0)).deleteEvent("event-123");
    }

    @Test
    void shouldRetryAttachWhenRoomNotYetBookable() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        doThrow(new ru.yandex.roombooker.adapter.out.client.CalendarApiException(
                "Calendar API request failed: HTTP 400 Some rooms are not bookable",
                400
        ))
                .doNothing()
                .when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        CreatedEvent actual = bookMeetingRoomUseCase.execute(request);

        assertThat(actual).isEqualTo(expected);
        verify(calendarEventGateway, times(1)).createEvent(request);
        verify(calendarEventGateway, times(2)).attachRoom("event-123", "cr_000004170");
        verify(calendarEventGateway, times(0)).deleteEvent("event-123");
    }

    @Test
    void shouldStopNotBookableRetriesWhenDeadlineReached() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(180);
        when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        when(clock.instant())
                .thenReturn(Instant.parse("2026-06-22T11:00:00Z"))
                .thenReturn(Instant.parse("2026-06-22T11:00:30Z"));
        doThrow(new ru.yandex.roombooker.adapter.out.client.CalendarApiException(
                "Calendar API request failed: HTTP 400 Some rooms are not bookable",
                400
        )).when(calendarEventGateway).attachRoom("event-123", "cr_000004170");

        Instant deadline = Instant.parse("2026-06-22T11:00:10Z");
        assertThatThrownBy(() -> bookMeetingRoomUseCase.execute(request, deadline))
                .isInstanceOf(ru.yandex.roombooker.adapter.out.client.CalendarApiException.class)
                .hasMessageContaining("not bookable");
        verify(calendarEventGateway, times(2)).attachRoom("event-123", "cr_000004170");
        verify(calendarEventGateway).deleteEvent("event-123");
    }

    @Test
    void shouldStillFailWhenRollbackDeleteAlsoFails() {
        when(calendarEventGateway.createEvent(request)).thenReturn(createdEvent);
        when(calendarEventGateway.booksRoomDuringCreate()).thenReturn(false);
        when(properties.getBookingMaxRetries()).thenReturn(0);
        doThrow(new ru.yandex.roombooker.adapter.out.client.CalendarApiException(
                "Calendar API request failed: HTTP 400 rooms are busy",
                400
        )).when(calendarEventGateway).attachRoom("event-123", "cr_000004170");
        doThrow(new RuntimeException("delete failed"))
                .when(calendarEventGateway).deleteEvent("event-123");

        assertThatThrownBy(() -> bookMeetingRoomUseCase.execute(request))
                .isInstanceOf(ru.yandex.roombooker.adapter.out.client.CalendarApiException.class)
                .hasMessageContaining("rooms are busy");
        verify(calendarEventGateway).deleteEvent("event-123");
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
