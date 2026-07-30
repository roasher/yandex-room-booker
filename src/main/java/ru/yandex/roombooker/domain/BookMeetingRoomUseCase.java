package ru.yandex.roombooker.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.adapter.out.client.CalendarApiException;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Creates a calendar event and books a meeting room for the given time slot.
 *
 * <p>Retries contested attach failures with exponential backoff, including a false {@code busy}
 * that the Calendar API often returns right as the booking window opens. {@code not bookable} /
 * {@code too-far-event} are retried until {@code booking-max-retries} or an optional deadline
 * (next ladder step), whichever comes first.
 *
 * <p>For Public API create-then-attach, failure to attach the room deletes the created event
 * so a booking never leaves an orphan meeting without the room.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMeetingRoomUseCase {

    private final CalendarEventGateway calendarEventGateway;
    private final RoomBookerProperties properties;
    private final Clock clock;

    public CreatedEvent execute(BookingRequest request) {
        return execute(request, null);
    }

    /**
     * @param retryUntil when non-null, stop retrying {@code not bookable} once this instant is reached
     *                   so the progressive ladder can move to the next step
     */
    public CreatedEvent execute(BookingRequest request, @Nullable Instant retryUntil) {
        if (calendarEventGateway.booksRoomDuringCreate()) {
            return executeWithRoomOnCreate(request, retryUntil);
        }
        return executeCreateThenAttach(request, retryUntil);
    }

    private CreatedEvent executeWithRoomOnCreate(BookingRequest request, @Nullable Instant retryUntil) {
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
                if (shouldStopRetrying(failure, attempt, maxAttempts, retryUntil)) {
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

    private CreatedEvent executeCreateThenAttach(BookingRequest request, @Nullable Instant retryUntil) {
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
                if (shouldStopRetrying(failure, attempt, maxAttempts, retryUntil)) {
                    rollbackCreatedEvent(created.eventId(), failure);
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

    private boolean shouldStopRetrying(
            RuntimeException failure,
            int attempt,
            int maxAttempts,
            @Nullable Instant retryUntil
    ) {
        if (!isRetryable(failure) || attempt == maxAttempts) {
            return true;
        }
        if (retryUntil != null
                && isNotYetBookable(failure)
                && !Instant.now(clock).isBefore(retryUntil)) {
            log.warn(
                    "Stopping not-bookable retries: next ladder step is due at {} (attempt {}/{})",
                    retryUntil,
                    attempt,
                    maxAttempts
            );
            return true;
        }
        return false;
    }

    private void rollbackCreatedEvent(String eventId, RuntimeException attachFailure) {
        try {
            log.warn(
                    "Room attach failed for event {}; deleting event to keep booking atomic: {}",
                    eventId,
                    attachFailure.getMessage()
            );
            calendarEventGateway.deleteEvent(eventId);
        } catch (RuntimeException deleteFailure) {
            log.error(
                    "Failed to delete orphan event {} after room attach failure ({}): {}",
                    eventId,
                    attachFailure.getMessage(),
                    deleteFailure.getMessage()
            );
        }
    }

    private static boolean isRetryable(RuntimeException failure) {
        if (failure instanceof CalendarApiException calendarApiException) {
            return calendarApiException.isRetryable();
        }
        return true;
    }

    private static boolean isNotYetBookable(RuntimeException failure) {
        return failure instanceof CalendarApiException calendarApiException
                && CalendarApiException.looksLikeNotYetBookable(calendarApiException.getMessage());
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
