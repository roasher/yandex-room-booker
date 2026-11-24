package ru.yandex.roombooker.adapter.in.schedule;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import ru.yandex.roombooker.config.BookingPlansProperties;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.BookMeetingRoomSlotsUseCase;
import ru.yandex.roombooker.domain.VerifyCalendarAccessUseCase;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;

@ExtendWith(MockitoExtension.class)
class StartupBookingRunnerTest {

    @Mock
    private RoomBookerProperties properties;
    @Mock
    private BookingPlansProperties bookingPlansProperties;
    @Mock
    private BookMeetingRoomSlotsUseCase bookMeetingRoomSlotsUseCase;
    @Mock
    private VerifyCalendarAccessUseCase verifyCalendarAccessUseCase;
    @Mock
    private Clock clock;
    @InjectMocks
    private StartupBookingRunner runner;

    @Test
    void shouldVerifyCredentialsBeforeStartingBookingWorkers() throws InterruptedException {
        when(properties.getTimeZone()).thenReturn("Europe/Moscow");
        List<BookingPlan> plans = List.of(new BookingPlan("Meeting", "room", List.of(new BookingSlot(
                LocalDateTime.parse("2026-12-01T12:00"), LocalDateTime.parse("2026-12-01T13:00")
        ))));
        when(bookingPlansProperties.resolve(clock, "Europe/Moscow")).thenReturn(plans);

        runner.run(new DefaultApplicationArguments());

        var order = inOrder(properties, verifyCalendarAccessUseCase, bookMeetingRoomSlotsUseCase);
        order.verify(properties).validateAuth();
        order.verify(verifyCalendarAccessUseCase).execute();
        order.verify(bookMeetingRoomSlotsUseCase).execute(plans, "Europe/Moscow");
    }

    @Test
    void shouldSkipRemoteAuthenticationWhenNothingIsEnabled() throws InterruptedException {
        when(properties.getTimeZone()).thenReturn("Europe/Moscow");
        when(bookingPlansProperties.resolve(clock, "Europe/Moscow")).thenReturn(List.of());

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(verifyCalendarAccessUseCase, bookMeetingRoomSlotsUseCase);
    }
}
