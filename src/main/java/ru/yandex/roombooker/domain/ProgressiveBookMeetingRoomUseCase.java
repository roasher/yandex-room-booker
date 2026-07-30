package ru.yandex.roombooker.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.BookingSlot;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.domain.model.ExistingEvent;

/**
 * Books the earliest overlapping slot when it opens, then walks the held event toward the target
 * window in {@code slot-shift-step} increments. Resumes after restart by discovering an existing
 * ladder event in the calendar (no local state).
 *
 * <p>If the process starts after earlier ladder windows have already opened, those steps are skipped
 * and booking begins at the latest already-open ladder slot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressiveBookMeetingRoomUseCase {

    private final BookingLadderPlanner bookingLadderPlanner;
    private final BookingSchedulePlanner bookingSchedulePlanner;
    private final BookingWaiter bookingWaiter;
    private final BookMeetingRoomUseCase bookMeetingRoomUseCase;
    private final CalendarEventGateway calendarEventGateway;
    private final RoomBookerProperties properties;
    private final Clock clock;

    public CreatedEvent execute(
            String meetingName,
            String roomExchange,
            LocalDateTime targetStart,
            Duration duration,
            String timeZone,
            @Nullable MeetingRoomEntry catalogEntry
    ) throws InterruptedException {
        Duration step = properties.resolvedSlotShiftStep();
        List<BookingSlot> ladder = bookingLadderPlanner.buildLadder(targetStart, duration, step);
        BookingSlot target = ladder.getLast();

        HeldBooking held = discoverHeldBooking(meetingName, roomExchange, ladder, timeZone);
        if (held != null && held.slot().equals(target)) {
            log.info(
                    "Existing booking already at target {}–{} (eventId={})",
                    target.start(),
                    target.end(),
                    held.eventId()
            );
            return CreatedEvent.builder()
                    .eventId(held.eventId())
                    .summary(meetingName)
                    .roomEmail(RoomResolver.toEmail(roomExchange))
                    .build();
        }

        int startIndex = firstDueLadderIndex(ladder, catalogEntry);
        if (startIndex > 0) {
            log.info(
                    "Starting late on ladder: skipping {} earlier slot(s), first attempt is {}–{}",
                    startIndex,
                    ladder.get(startIndex).start(),
                    ladder.get(startIndex).end()
            );
        }

        for (int index = startIndex; index < ladder.size(); index++) {
            BookingSlot slot = ladder.get(index);
            if (held != null && !slot.start().isAfter(held.slot().start())) {
                continue;
            }

            LocalDateTime attemptAt = bookingSchedulePlanner.firstAttemptAt(
                    slot.start(),
                    catalogEntry,
                    properties.resolvedBookingOpenBuffer()
            );
            bookingWaiter.waitUntil(attemptAt);

            if (held == null) {
                held = tryCreate(meetingName, roomExchange, slot, timeZone);
            } else {
                held = tryShift(held, slot, timeZone);
            }
        }

        if (held == null) {
            throw new IllegalStateException(
                    "Progressive booking failed: could not create any ladder slot for '%s' toward %s–%s"
                            .formatted(meetingName, target.start(), target.end())
            );
        }
        if (!held.slot().equals(target)) {
            throw new IllegalStateException(
                    "Progressive booking stopped at %s–%s without reaching target %s–%s (eventId=%s)"
                            .formatted(
                                    held.slot().start(),
                                    held.slot().end(),
                                    target.start(),
                                    target.end(),
                                    held.eventId()
                            )
            );
        }

        log.info(
                "Progressive booking reached target {}–{} (eventId={})",
                target.start(),
                target.end(),
                held.eventId()
        );
        return CreatedEvent.builder()
                .eventId(held.eventId())
                .summary(meetingName)
                .roomEmail(RoomResolver.toEmail(roomExchange))
                .build();
    }

    /**
     * Latest ladder index whose booking window has already opened; {@code 0} if none have opened yet.
     */
    private int firstDueLadderIndex(List<BookingSlot> ladder, @Nullable MeetingRoomEntry catalogEntry) {
        LocalDateTime now = LocalDateTime.now(clock);
        Duration openBuffer = properties.resolvedBookingOpenBuffer();
        int dueIndex = 0;
        boolean foundOpen = false;
        for (int index = 0; index < ladder.size(); index++) {
            LocalDateTime windowOpens = bookingSchedulePlanner.bookingWindowOpensAt(
                    ladder.get(index).start(),
                    catalogEntry,
                    openBuffer
            );
            if (windowOpens == null) {
                return 0;
            }
            if (!windowOpens.isAfter(now)) {
                dueIndex = index;
                foundOpen = true;
            } else if (foundOpen) {
                break;
            }
        }
        return dueIndex;
    }

    private @Nullable HeldBooking discoverHeldBooking(
            String meetingName,
            String roomExchange,
            List<BookingSlot> ladder,
            String timeZone
    ) {
        BookingSlot earliest = ladder.getFirst();
        BookingSlot target = ladder.getLast();
        List<ExistingEvent> events = calendarEventGateway.findEvents(
                earliest.start(),
                target.end(),
                timeZone
        );

        return events.stream()
                .filter(event -> meetingName.equals(event.summary()))
                .filter(event -> event.hasRoom(roomExchange) || event.roomReferences().isEmpty())
                .filter(event -> matchesLadderSlot(event, ladder))
                .max(Comparator.comparing(ExistingEvent::start))
                .map(event -> new HeldBooking(
                        event.eventId(),
                        BookingSlot.builder().start(event.start()).end(event.end()).build()
                ))
                .orElse(null);
    }

    private static boolean matchesLadderSlot(ExistingEvent event, List<BookingSlot> ladder) {
        return ladder.stream().anyMatch(slot ->
                slot.start().equals(event.start()) && slot.end().equals(event.end())
        );
    }

    private @Nullable HeldBooking tryCreate(
            String meetingName,
            String roomExchange,
            BookingSlot slot,
            String timeZone
    ) {
        BookingRequest request = BookingRequest.builder()
                .meetingName(meetingName)
                .roomEmail(roomExchange)
                .start(slot.start())
                .end(slot.end())
                .timeZone(timeZone)
                .build();
        try {
            log.info("Ladder create attempt for {}–{}", slot.start(), slot.end());
            CreatedEvent created = bookMeetingRoomUseCase.execute(request);
            return new HeldBooking(created.eventId(), slot);
        } catch (RuntimeException failure) {
            log.warn(
                    "Ladder create failed for {}–{}: {}; skipping to next attempt",
                    slot.start(),
                    slot.end(),
                    failure.getMessage()
            );
            return null;
        }
    }

    private HeldBooking tryShift(HeldBooking held, BookingSlot slot, String timeZone) {
        try {
            log.info(
                    "Ladder shift attempt: event {} {}–{} -> {}–{}",
                    held.eventId(),
                    held.slot().start(),
                    held.slot().end(),
                    slot.start(),
                    slot.end()
            );
            calendarEventGateway.updateEventTime(held.eventId(), slot.start(), slot.end(), timeZone);
            return new HeldBooking(held.eventId(), slot);
        } catch (RuntimeException failure) {
            log.warn(
                    "Ladder shift failed for event {} to {}–{}: {}; keeping {}–{}",
                    held.eventId(),
                    slot.start(),
                    slot.end(),
                    failure.getMessage(),
                    held.slot().start(),
                    held.slot().end()
            );
            return held;
        }
    }

    private record HeldBooking(String eventId, BookingSlot slot) {
    }
}
