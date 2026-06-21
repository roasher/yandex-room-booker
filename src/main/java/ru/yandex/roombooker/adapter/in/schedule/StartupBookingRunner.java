package ru.yandex.roombooker.adapter.in.schedule;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.BookMeetingRoomUseCase;
import ru.yandex.roombooker.domain.RoomResolver;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Runs automatic room booking once when the application starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBookingRunner implements ApplicationRunner {

    private final RoomBookerProperties properties;
    private final RoomResolver roomResolver;
    private final BookMeetingRoomUseCase bookMeetingRoomUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Room booker is disabled (room-booker.enabled=false)");
            return;
        }

        validateProperties();
        RoomResolver.ResolvedRoom room = roomResolver.resolve(properties.effectiveRoomReference());
        LocalDateTime start = properties.bookingStart();
        LocalDateTime end = properties.bookingEnd();

        log.info(
                "Using room {} ({}) -> {}, slot {}–{} ({} min)",
                room.displayName(),
                room.exchange(),
                room.email(),
                start,
                end,
                properties.bookingDuration().toMinutes()
        );

        BookingRequest request = BookingRequest.builder()
                .meetingName(properties.meetingName())
                .roomEmail(room.email())
                .start(start)
                .end(end)
                .timeZone(properties.timeZone())
                .build();

        CreatedEvent created = bookMeetingRoomUseCase.execute(request);
        log.info(
                "Booked meeting room: eventId={}, summary={}, room={}",
                created.eventId(),
                created.summary(),
                created.roomEmail()
        );
    }

    private void validateProperties() {
        requireNonBlank(properties.meetingName(), "room-booker.meeting-name");
        requireNonBlank(properties.effectiveRoomReference(), "room-booker.room (or room-booker.room-email)");
        requireNonBlank(properties.start(), "room-booker.start");
        if (properties.duration() == null || properties.duration().isBlank()) {
            throw new IllegalStateException(
                    "Missing or invalid property: room-booker.duration (e.g. 90m, 1h, 1h30m)"
            );
        }
        properties.bookingDuration();
        requireNonBlank(properties.oauthToken(), "YANDEX_CALENDAR_OAUTH_TOKEN");
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }

}
