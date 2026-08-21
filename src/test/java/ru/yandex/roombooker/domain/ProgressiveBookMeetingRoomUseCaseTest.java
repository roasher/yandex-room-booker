package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.BookingSlot;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.domain.model.ExistingEvent;

@ExtendWith(MockitoExtension.class)
class ProgressiveBookMeetingRoomUseCaseTest {

    private static final LocalDateTime TARGET_START = LocalDateTime.parse("2026-08-13T19:00:00");
    private static final Duration DURATION = Duration.ofHours(1);
    private static final Duration STEP = Duration.ofMinutes(30);
    private static final String MEETING = "Music practice";
    private static final String ROOM = "cr_000004198";
    private static final String TZ = "Europe/Moscow";

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Mock
    private BookingLadderPlanner bookingLadderPlanner;
    @Mock
    private BookingSchedulePlanner bookingSchedulePlanner;
    @Mock
    private BookingWaiter bookingWaiter;
    @Mock
    private BookMeetingRoomUseCase bookMeetingRoomUseCase;
    @Mock
    private CalendarEventGateway calendarEventGateway;
    @Mock
    private RoomBookerProperties properties;
    @Mock
    private Clock clock;

    @InjectMocks
    private ProgressiveBookMeetingRoomUseCase useCase;

    private final MeetingRoomEntry catalogEntry = new MeetingRoomEntry(
            "Лотте - музыкальная комната",
            ROOM,
            "БЦ Лотте",
            "3h",
            "14d"
    );

    private final List<BookingSlot> ladder = List.of(
            slot("2026-08-13T18:30:00", "2026-08-13T19:30:00"),
            slot("2026-08-13T19:00:00", "2026-08-13T20:00:00")
    );

    @BeforeEach
    void setUp() throws InterruptedException {
        when(properties.resolvedSlotShiftStep()).thenReturn(STEP);
        lenient().when(properties.resolvedBookingOpenBuffer()).thenReturn(Duration.ofSeconds(30));
        lenient().when(properties.getBookingMaxRetries()).thenReturn(0);
        lenient().when(properties.resolvedBookingRetryBackoff()).thenReturn(Duration.ZERO);
        lenient().when(properties.getBookingRetryMultiplier()).thenReturn(1.0);
        when(bookingLadderPlanner.buildLadder(TARGET_START, DURATION, STEP)).thenReturn(ladder);
        lenient().when(bookingSchedulePlanner.firstAttemptAt(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Far-future opens => no late-start skip for default tests.
        lenient().when(bookingSchedulePlanner.bookingWindowOpensAt(any(), any(), any()))
                .thenReturn(LocalDateTime.parse("2099-01-01T00:00:00"));
        lenient().when(clock.getZone()).thenReturn(MOSCOW);
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-07-30T10:00:00+03:00"));
        when(calendarEventGateway.findEvents(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void shouldCreateThenShiftAlongClearLadder() throws InterruptedException {
        when(bookMeetingRoomUseCase.execute(request(ladder.getFirst())))
                .thenReturn(created("event-1"));
        when(calendarEventGateway.updateEventTime(
                "event-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        )).thenReturn(created("event-1"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId("event-1")
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        verify(bookMeetingRoomUseCase, times(1)).execute(any());
        verify(calendarEventGateway).updateEventTime(
                "event-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        );
    }

    @Test
    void shouldSkipFailedCreateAndCreateLaterSlot() throws InterruptedException {
        when(bookMeetingRoomUseCase.execute(request(ladder.getFirst())))
                .thenThrow(new RuntimeException("busy"));
        when(bookMeetingRoomUseCase.execute(request(ladder.getLast())))
                .thenReturn(created("event-2"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId("event-2")
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        verify(calendarEventGateway, never()).updateEventTime(any(), any(), any(), any());
    }

    @Test
    void shouldKeepHeldSlotWhenShiftFailsThenReachTargetOnRetryPath() throws InterruptedException {
        List<BookingSlot> threeStepLadder = List.of(
                slot("2026-08-13T18:00:00", "2026-08-13T19:00:00"),
                slot("2026-08-13T18:30:00", "2026-08-13T19:30:00"),
                slot("2026-08-13T19:00:00", "2026-08-13T20:00:00")
        );
        when(bookingLadderPlanner.buildLadder(TARGET_START, DURATION, STEP)).thenReturn(threeStepLadder);
        when(bookMeetingRoomUseCase.execute(request(threeStepLadder.getFirst())))
                .thenReturn(created("event-1"));
        when(calendarEventGateway.updateEventTime(
                "event-1",
                threeStepLadder.get(1).start(),
                threeStepLadder.get(1).end(),
                TZ
        )).thenThrow(new RuntimeException("busy"));
        when(calendarEventGateway.updateEventTime(
                "event-1",
                threeStepLadder.getLast().start(),
                threeStepLadder.getLast().end(),
                TZ
        )).thenReturn(created("event-1"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual.eventId()).isEqualTo("event-1");
        verify(bookMeetingRoomUseCase, times(1)).execute(any());
        verify(calendarEventGateway, times(2)).updateEventTime(eq("event-1"), any(), any(), eq(TZ));
    }

    @Test
    void shouldRetryShiftWhenBusyThenSucceed() throws InterruptedException {
        when(properties.getBookingMaxRetries()).thenReturn(2);
        when(bookMeetingRoomUseCase.execute(request(ladder.getFirst())))
                .thenReturn(created("event-1"));
        when(calendarEventGateway.updateEventTime(
                "event-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        ))
                .thenThrow(new ru.yandex.roombooker.adapter.out.client.CalendarApiException(
                        "Calendar API request failed: HTTP 400 rooms are busy",
                        400
                ))
                .thenReturn(created("event-1"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual.eventId()).isEqualTo("event-1");
        verify(calendarEventGateway, times(2)).updateEventTime(
                "event-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        );
    }

    @Test
    void shouldResumeFromExistingMidLadderEventWithoutCreating() throws InterruptedException {
        when(calendarEventGateway.findEvents(any(), any(), any())).thenReturn(List.of(
                ExistingEvent.builder()
                        .eventId("held-1")
                        .summary(MEETING)
                        .start(ladder.getFirst().start())
                        .end(ladder.getFirst().end())
                        .roomReferences(List.of(ROOM))
                        .build()
        ));
        when(calendarEventGateway.updateEventTime(
                "held-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        )).thenReturn(created("held-1"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId("held-1")
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        verify(bookMeetingRoomUseCase, never()).execute(any());
        verify(calendarEventGateway).updateEventTime(
                "held-1",
                ladder.getLast().start(),
                ladder.getLast().end(),
                TZ
        );
    }

    @Test
    void shouldExitWhenExistingEventAlreadyAtTarget() throws InterruptedException {
        when(calendarEventGateway.findEvents(any(), any(), any())).thenReturn(List.of(
                ExistingEvent.builder()
                        .eventId("done-1")
                        .summary(MEETING)
                        .start(ladder.getLast().start())
                        .end(ladder.getLast().end())
                        .roomReferences(List.of(ROOM))
                        .build()
        ));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual.eventId()).isEqualTo("done-1");
        verify(bookMeetingRoomUseCase, never()).execute(any());
        verify(calendarEventGateway, never()).updateEventTime(any(), any(), any(), any());
        verify(bookingWaiter, never()).waitUntil(any());
    }

    @Test
    void shouldSkipEarlyLadderStepsWhenStartedLate() throws InterruptedException {
        List<BookingSlot> threeStepLadder = List.of(
                slot("2026-08-13T18:00:00", "2026-08-13T19:00:00"),
                slot("2026-08-13T18:30:00", "2026-08-13T19:30:00"),
                slot("2026-08-13T19:00:00", "2026-08-13T20:00:00")
        );
        when(bookingLadderPlanner.buildLadder(TARGET_START, DURATION, STEP)).thenReturn(threeStepLadder);
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T19:00:00+03:00"));
        when(bookingSchedulePlanner.bookingWindowOpensAt(
                threeStepLadder.get(0).start(), catalogEntry, Duration.ofSeconds(30)
        )).thenReturn(LocalDateTime.parse("2026-07-30T18:00:00"));
        when(bookingSchedulePlanner.bookingWindowOpensAt(
                threeStepLadder.get(1).start(), catalogEntry, Duration.ofSeconds(30)
        )).thenReturn(LocalDateTime.parse("2026-07-30T18:30:00"));
        when(bookingSchedulePlanner.bookingWindowOpensAt(
                threeStepLadder.get(2).start(), catalogEntry, Duration.ofSeconds(30)
        )).thenReturn(LocalDateTime.parse("2026-07-30T19:00:00"));
        when(bookMeetingRoomUseCase.execute(request(threeStepLadder.getLast())))
                .thenReturn(created("event-late"));

        CreatedEvent actual = useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry);

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId("event-late")
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        verify(bookMeetingRoomUseCase, times(1)).execute(request(threeStepLadder.getLast()));
        verify(bookMeetingRoomUseCase, never()).execute(request(threeStepLadder.get(0)));
        verify(bookMeetingRoomUseCase, never()).execute(request(threeStepLadder.get(1)));
        verify(calendarEventGateway, never()).updateEventTime(any(), any(), any(), any());
    }

    @Test
    void shouldFailWhenNoSlotCouldBeCreated() {
        when(bookMeetingRoomUseCase.execute(any())).thenThrow(new RuntimeException("busy"));

        assertThatThrownBy(() -> useCase.execute(MEETING, ROOM, TARGET_START, DURATION, TZ, catalogEntry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not create any ladder slot");
    }

    private static BookingRequest request(BookingSlot slot) {
        return BookingRequest.builder()
                .meetingName(MEETING)
                .roomEmail(ROOM)
                .start(slot.start())
                .end(slot.end())
                .timeZone(TZ)
                .build();
    }

    private static CreatedEvent created(String eventId) {
        return CreatedEvent.builder().eventId(eventId).summary(MEETING).build();
    }

    private static BookingSlot slot(String start, String end) {
        return BookingSlot.builder()
                .start(LocalDateTime.parse(start))
                .end(LocalDateTime.parse(end))
                .build();
    }
}
