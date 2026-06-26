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
        int maxAttempts = properties.bookingMaxRetries() + 1;
        Duration backoff = properties.resolvedBookingRetryBackoff();
        double multiplier = properties.bookingRetryMultiplier();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info(
                        "Booking attempt {}/{}: room {} for meeting '{}' from {} to {}",
                        attempt,
                        maxAttempts,
                        request.roomEmail(),
                        request.meetingName(),
                        request.start(),
                        request.end()
                );
                return calendarEventGateway.createMeetingWithRoom(request);
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
