package ru.yandex.roombooker.adapter.in.schedule;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.yandex.roombooker.config.BookingPlansProperties;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.BookMeetingRoomSlotsUseCase;
import ru.yandex.roombooker.domain.model.BookingPlan;

/**
 * Resolves the configured slots once at startup and attempts every booking.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBookingRunner implements ApplicationRunner {

    private final RoomBookerProperties properties;
    private final BookingPlansProperties bookingPlansProperties;
    private final BookMeetingRoomSlotsUseCase bookMeetingRoomSlotsUseCase;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        List<BookingPlan> plans = bookingPlansProperties.resolve(clock, properties.getTimeZone());
        if (plans.isEmpty()) {
            log.info("No enabled room-booker entries; nothing to book");
            return;
        }

        requireNonBlank(properties.getTimeZone(), "room-booker-settings.time-zone");
        ZoneId.of(properties.getTimeZone());
        properties.validateAuth();
        log.info("Booking mode: {}, time zone: {}", properties.bookingMode(), properties.getTimeZone());
        bookMeetingRoomSlotsUseCase.execute(plans, properties.getTimeZone());
    }

    private static void requireNonBlank(@Nullable String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }
}
