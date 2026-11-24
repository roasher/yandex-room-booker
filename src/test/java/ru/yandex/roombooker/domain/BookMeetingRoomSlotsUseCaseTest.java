package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;
import ru.yandex.roombooker.domain.model.CreatedEvent;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class BookMeetingRoomSlotsUseCaseTest {

    private static final String TZ = "Europe/Moscow";
    private static final Duration STEP = Duration.ofMinutes(30);
    private static final MeetingRoomEntry ENTRY = new MeetingRoomEntry("Room", "conf_test", "Office", "3h", "2d");
    private static final RoomResolver.ResolvedRoom ROOM = new RoomResolver.ResolvedRoom(
            "room", "Room", "conf_test", "conf_test@yandex-team.ru", ENTRY
    );
    private static final MeetingRoomEntry OTHER_ENTRY = new MeetingRoomEntry(
            "Other room", "conf_other", "Office", "2h", "1d"
    );
    private static final RoomResolver.ResolvedRoom OTHER_ROOM = new RoomResolver.ResolvedRoom(
            "other-room", "Other room", "conf_other", "conf_other@yandex-team.ru", OTHER_ENTRY
    );
    private static final BookingSlot FIRST = slot("2026-10-05T14:00", "2026-10-05T14:30");
    private static final BookingSlot SECOND = slot("2026-10-05T14:30", "2026-10-05T16:00");
    private static final BookingSlot THIRD = slot("2026-10-06T18:00", "2026-10-06T19:00");

    @Mock
    private RoomResolver roomResolver;
    @Mock
    private BookingLadderPlanner bookingLadderPlanner;
    @Mock
    private BookingSchedulePlanner bookingSchedulePlanner;
    @Mock
    private ProgressiveBookMeetingRoomUseCase progressiveBookMeetingRoomUseCase;
    @Mock
    private RoomBookerProperties properties;
    @InjectMocks
    private BookMeetingRoomSlotsUseCase useCase;

    @BeforeEach
    void setUp() {
        when(roomResolver.resolve("room")).thenReturn(ROOM);
        lenient().when(properties.resolvedSlotShiftStep()).thenReturn(STEP);
    }

    @Test
    void shouldRunOnlyEachPlansOwnRoomAndSlotsConcurrently() throws InterruptedException {
        when(roomResolver.resolve("other-room")).thenReturn(OTHER_ROOM);
        CountDownLatch started = new CountDownLatch(3);
        when(progressiveBookMeetingRoomUseCase.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                    return new CreatedEvent("event", invocation.getArgument(0), invocation.getArgument(1));
                });

        useCase.execute(List.of(
                new BookingPlan("Yoga", "room", List.of(FIRST, SECOND)),
                new BookingPlan("Music", "other-room", List.of(THIRD))
        ), TZ);

        verify(progressiveBookMeetingRoomUseCase).execute(
                "Yoga", ROOM.exchange(), FIRST.start(), Duration.ofMinutes(30), TZ, ENTRY, List.of(SECOND)
        );
        verify(progressiveBookMeetingRoomUseCase).execute(
                "Yoga", ROOM.exchange(), SECOND.start(), Duration.ofMinutes(90), TZ, ENTRY, List.of(FIRST)
        );
        verify(progressiveBookMeetingRoomUseCase).execute(
                "Music", OTHER_ROOM.exchange(), THIRD.start(), Duration.ofHours(1), TZ, OTHER_ENTRY, List.of()
        );
        verifyNoMoreInteractions(progressiveBookMeetingRoomUseCase);
    }

    @Test
    void shouldAttemptOtherPlansWhenOneFails() throws InterruptedException {
        when(roomResolver.resolve("other-room")).thenReturn(OTHER_ROOM);
        when(progressiveBookMeetingRoomUseCase.execute(
                "Yoga", ROOM.exchange(), FIRST.start(), Duration.ofMinutes(30), TZ, ENTRY, List.of()
        )).thenThrow(new IllegalStateException("Room busy"));
        when(progressiveBookMeetingRoomUseCase.execute(
                "Music", OTHER_ROOM.exchange(), THIRD.start(), Duration.ofHours(1), TZ, OTHER_ENTRY, List.of()
        )).thenReturn(new CreatedEvent("event-music", "Music", OTHER_ROOM.email()));

        useCase.execute(List.of(
                new BookingPlan("Yoga", "room", List.of(FIRST)),
                new BookingPlan("Music", "other-room", List.of(THIRD))
        ), TZ);

        verify(progressiveBookMeetingRoomUseCase).execute(
                "Music", OTHER_ROOM.exchange(), THIRD.start(), Duration.ofHours(1), TZ, OTHER_ENTRY, List.of()
        );
    }

    @Test
    void shouldValidateEveryPlanBeforeAttemptingAnyBooking() {
        when(roomResolver.resolve("other-room")).thenReturn(OTHER_ROOM);
        doNothing().when(bookingSchedulePlanner).validateDuration(Duration.ofMinutes(30), ENTRY, ROOM.displayName());
        doThrow(new IllegalStateException("other room duration limit"))
                .when(bookingSchedulePlanner).validateDuration(
                        Duration.ofHours(1), OTHER_ENTRY, OTHER_ROOM.displayName()
                );

        assertThatThrownBy(() -> useCase.execute(List.of(
                new BookingPlan("Yoga", "room", List.of(FIRST)),
                new BookingPlan("Music", "other-room", List.of(THIRD))
        ), TZ)).isInstanceOf(IllegalStateException.class).hasMessageContaining("other room duration limit");
        verifyNoInteractions(progressiveBookMeetingRoomUseCase);
    }

    @Test
    void shouldRejectOverlapsAcrossEntriesUsingAliasesForTheSameRoom() {
        when(roomResolver.resolve("CONF_TEST@yandex-team.ru")).thenReturn(new RoomResolver.ResolvedRoom(
                null, "Room", "CONF_TEST", "CONF_TEST@yandex-team.ru", ENTRY
        ));

        assertThatThrownBy(() -> useCase.execute(List.of(
                new BookingPlan("Yoga", "room", List.of(FIRST)),
                new BookingPlan("Music", "CONF_TEST@yandex-team.ru", List.of(FIRST))
        ), TZ)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
        verifyNoInteractions(progressiveBookMeetingRoomUseCase);
    }

    @Test
    void shouldProtectTargetsAcrossDifferentEntriesInTheSameRoom() throws InterruptedException {
        when(progressiveBookMeetingRoomUseCase.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CreatedEvent("event", "Practice", ROOM.email()));

        useCase.execute(List.of(
                new BookingPlan("Yoga", "room", List.of(FIRST)),
                new BookingPlan("Music", "room", List.of(SECOND))
        ), TZ);

        verify(progressiveBookMeetingRoomUseCase).execute(
                "Yoga", ROOM.exchange(), FIRST.start(), Duration.ofMinutes(30), TZ, ENTRY, List.of(SECOND)
        );
        verify(progressiveBookMeetingRoomUseCase).execute(
                "Music", ROOM.exchange(), SECOND.start(), Duration.ofMinutes(90), TZ, ENTRY, List.of(FIRST)
        );
    }

    @Test
    void shouldCancelWaitingBookingsWhenInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch stopped = new CountDownLatch(2);
        when(progressiveBookMeetingRoomUseCase.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("Expected interruption");
                    } finally {
                        stopped.countDown();
                    }
                });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> {
                useCase.execute(List.of(new BookingPlan("Yoga", "room", List.of(FIRST, SECOND))), TZ);
                return null;
            });
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                result.cancel(true);
            }
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static BookingSlot slot(String start, String end) {
        return new BookingSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }
}
