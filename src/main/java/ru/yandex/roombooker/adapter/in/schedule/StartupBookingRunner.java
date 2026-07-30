package ru.yandex.roombooker.adapter.in.schedule;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.BookingLadderPlanner;
import ru.yandex.roombooker.domain.BookingSchedulePlanner;
import ru.yandex.roombooker.domain.ProgressiveBookMeetingRoomUseCase;
import ru.yandex.roombooker.domain.RoomResolver;
import ru.yandex.roombooker.domain.model.BookingSlot;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * On startup books the configured room: direct target booking when there is no bookable-ahead
 * policy, otherwise progressive ladder toward the target.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBookingRunner implements ApplicationRunner {

    private final RoomBookerProperties properties;
    private final RoomResolver roomResolver;
    private final BookingLadderPlanner bookingLadderPlanner;
    private final BookingSchedulePlanner bookingSchedulePlanner;
    private final ProgressiveBookMeetingRoomUseCase progressiveBookMeetingRoomUseCase;

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        if (!properties.isEnabled()) {
            log.info("Room booker is disabled (room-booker.enabled=false)");
            return;
        }

        validateProperties();
        log.info("Booking mode: {}", properties.bookingMode());
        RoomResolver.ResolvedRoom room = roomResolver.resolve(properties.effectiveRoomReference());
        LocalDateTime targetStart = properties.bookingStart();
        LocalDateTime targetEnd = properties.bookingEnd();

        bookingSchedulePlanner.validateDuration(
                properties.bookingDuration(),
                room.catalogEntry(),
                room.displayName()
        );

        boolean progressive = room.catalogEntry() != null && room.catalogEntry().hasBookableAheadPolicy();
        if (progressive) {
            List<BookingSlot> ladder = bookingLadderPlanner.buildLadder(
                    targetStart,
                    properties.bookingDuration(),
                    properties.resolvedSlotShiftStep()
            );
            BookingSlot earliest = ladder.getFirst();
            log.info(
                    "Using room {} ({}) -> {}, target {}–{} ({} min), ladder {} slots from {} step {}",
                    room.displayName(),
                    room.exchange(),
                    room.email(),
                    targetStart,
                    targetEnd,
                    properties.bookingDuration().toMinutes(),
                    ladder.size(),
                    earliest.start(),
                    properties.resolvedSlotShiftStep()
            );
            LocalDateTime firstAttemptAt = bookingSchedulePlanner.firstAttemptAt(
                    earliest.start(),
                    room.catalogEntry(),
                    properties.resolvedBookingOpenBuffer()
            );
            log.info(
                    "Earliest ladder slot {} is bookable from {} (bookable-ahead={})",
                    earliest.start(),
                    firstAttemptAt,
                    room.catalogEntry().bookableAhead()
            );
        } else {
            log.info(
                    "Using room {} ({}) -> {}, target {}–{} ({} min), direct booking (no bookable-ahead)",
                    room.displayName(),
                    room.exchange(),
                    room.email(),
                    targetStart,
                    targetEnd,
                    properties.bookingDuration().toMinutes()
            );
        }

        try {
            CreatedEvent created = progressiveBookMeetingRoomUseCase.execute(
                    properties.getMeetingName(),
                    room.exchange(),
                    targetStart,
                    properties.bookingDuration(),
                    properties.getTimeZone(),
                    room.catalogEntry()
            );
            log.info(
                    "Booking successful: {}–{} booked for '{}' in {} ({}) (eventId={})",
                    targetStart,
                    targetEnd,
                    created.summary(),
                    room.displayName(),
                    created.roomEmail(),
                    created.eventId()
            );
        } catch (RuntimeException failure) {
            // Room busy / contested slot is an expected outcome for a one-shot booker: we tried.
            log.warn(
                    "Could not book {} ({}) for {}–{}: {}. Exiting without a booking.",
                    room.displayName(),
                    room.exchange(),
                    targetStart,
                    targetEnd,
                    failure.getMessage()
            );
        }
    }

    private void validateProperties() {
        requireNonBlank(properties.getMeetingName(), "room-booker.meeting-name");
        requireNonBlank(properties.effectiveRoomReference(), "room-booker.room (or room-booker.room-email)");
        requireNonBlank(properties.getStart(), "room-booker.start");
        if (properties.getDuration() == null || properties.getDuration().isBlank()) {
            throw new IllegalStateException(
                    "Missing or invalid property: room-booker.duration (e.g. 90m, 1h, 1h30m)"
            );
        }
        properties.bookingDuration();
        properties.resolvedSlotShiftStep();
        properties.validateAuth();
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
    }

}
