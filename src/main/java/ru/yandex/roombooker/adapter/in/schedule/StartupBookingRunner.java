package ru.yandex.roombooker.adapter.in.schedule;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.BookMeetingRoomUseCase;
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
    private final BookMeetingRoomUseCase bookMeetingRoomUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Room booker is disabled (room-booker.enabled=false)");
            return;
        }

        validateProperties();
        BookingRequest request = BookingRequest.builder()
                .meetingName(properties.meetingName())
                .roomEmail(properties.roomEmail())
                .start(parseDateTime(properties.start()))
                .end(parseDateTime(properties.end()))
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
        requireNonBlank(properties.roomEmail(), "room-booker.room-email");
        requireNonBlank(properties.start(), "room-booker.start");
        requireNonBlank(properties.end(), "room-booker.end");
        requireNonBlank(properties.oauthToken(), "YANDEX_CALENDAR_OAUTH_TOKEN");
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Invalid datetime '%s', expected ISO-8601 like 2026-06-22T14:00:00".formatted(value),
                    exception
            );
        }
    }
}
