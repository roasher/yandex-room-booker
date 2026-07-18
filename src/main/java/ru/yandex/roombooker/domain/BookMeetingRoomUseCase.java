package ru.yandex.roombooker.domain;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Creates a calendar event and books a meeting room for the given time slot.
 *
 * <p>Retries transient failures with exponential backoff, since contested rooms can return
 * transient {@code 5xx} errors right as the booking window opens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMeetingRoomUseCase {

    private final CalendarEventGateway calendarEventGateway;
    private final RoomBookerProperties properties;

    public CreatedEvent execute(BookingRequest request) {
        if (calendarEventGateway.booksRoomDuringCreate()) {
            return executeWithRoomOnCreate(request);
        }
        return executeCreateThenAttach(request);
    }

    private CreatedEvent executeWithRoomOnCreate(BookingRequest request) {
        int maxAttempts = properties.getBookingMaxRetries() + 1;
        Duration backoff = properties.resolvedBookingRetryBackoff();
        double multiplier = properties.getBookingRetryMultiplier();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info(
                        "Booking attempt {}/{} via create-with-room: meeting '{}' room {} ({}–{})",
                        attempt,
                        maxAttempts,
                        request.meetingName(),
                        request.roomEmail(),
                        request.start(),
                        request.end()
                );
                CreatedEvent created = calendarEventGateway.createEvent(request);
                calendarEventGateway.attachRoom(created.eventId(), request.roomEmail());
                return CreatedEvent.builder()
                        .eventId(created.eventId())
                        .summary(created.summary())
                        .roomEmail(RoomResolver.toEmail(request.roomEmail()))
                        .build();
            } catch (RuntimeException failure) {
                if (attempt == maxAttempts) {
                    throw failure;
                }
                log.warn(
                        "Booking attempt {}/{} failed: {}; retrying in {}",
                        attempt,
                        maxAttempts,
                        failure.getMessage(),
                        backoff
                );
                sleep(backoff);
                backoff = scaleBackoff(backoff, multiplier);
            }
        }
        throw new IllegalStateException("Booking aborted: booking-max-retries must be >= 0");
    }

    private CreatedEvent executeCreateThenAttach(BookingRequest request) {
        CreatedEvent created = calendarEventGateway.createEvent(request);

        int maxAttempts = properties.getBookingMaxRetries() + 1;
        Duration backoff = properties.resolvedBookingRetryBackoff();
        double multiplier = properties.getBookingRetryMultiplier();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info(
                        "Room attach attempt {}/{}: room {} to event {} for meeting '{}' ({}–{})",
                        attempt,
                        maxAttempts,
                        request.roomEmail(),
                        created.eventId(),
                        request.meetingName(),
                        request.start(),
                        request.end()
                );
                calendarEventGateway.attachRoom(created.eventId(), request.roomEmail());
                return CreatedEvent.builder()
                        .eventId(created.eventId())
                        .summary(created.summary())
                        .roomEmail(RoomResolver.toEmail(request.roomEmail()))
                        .build();
            } catch (RuntimeException failure) {
                if (attempt == maxAttempts) {
                    throw failure;
                }
                log.warn(
                        "Room attach attempt {}/{} failed: {}; retrying in {}",
                        attempt,
                        maxAttempts,
                        failure.getMessage(),
                        backoff
                );
                sleep(backoff);
                backoff = scaleBackoff(backoff, multiplier);
            }
        }
        throw new IllegalStateException("Booking aborted: booking-max-retries must be >= 0");
    }

    private static Duration scaleBackoff(Duration backoff, double multiplier) {
        return Duration.ofMillis(Math.round(backoff.toMillis() * multiplier));
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry booking", interrupted);
        }
    }
}
