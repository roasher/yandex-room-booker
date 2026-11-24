package ru.yandex.roombooker.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Attempts each meeting configuration in its own room and slots, after validating the entire batch.
 * A contested room or slot does not cancel the remaining bookings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMeetingRoomSlotsUseCase {

    private final RoomResolver roomResolver;
    private final BookingLadderPlanner bookingLadderPlanner;
    private final BookingSchedulePlanner bookingSchedulePlanner;
    private final ProgressiveBookMeetingRoomUseCase progressiveBookMeetingRoomUseCase;
    private final RoomBookerProperties properties;

    /**
     * Waits for all configured slots to finish; interruption cancels outstanding attempts.
     * Every plan keeps its own meeting name and single room.
     */
    public void execute(List<BookingPlan> plans, String timeZone) throws InterruptedException {
        List<PlannedBooking> bookings = new ArrayList<>();
        for (BookingPlan plan : plans) {
            RoomResolver.ResolvedRoom room = roomResolver.resolve(plan.roomReference());
            validateSlots(plan.slots(), room);
            for (BookingSlot slot : plan.slots()) {
                bookings.add(new PlannedBooking(plan.meetingName(), room, slot));
            }
        }
        // Entries may refer to the same physical room using different catalog names or addresses.
        var byRoom = bookings.stream().collect(Collectors.groupingBy(PlannedBooking::roomKey));
        for (List<PlannedBooking> roomBookings : byRoom.values()) {
            validateOverlaps(roomBookings.stream().map(PlannedBooking::slot).toList(),
                    roomBookings.getFirst().room().displayName());
        }
        List<Callable<Boolean>> tasks = bookings.stream()
                .<Callable<Boolean>>map(booking -> () -> bookSlot(
                        booking.meetingName(), booking.room(), booking.slot(),
                        byRoom.get(booking.roomKey()).stream().map(PlannedBooking::slot).toList(), timeZone
                ))
                .toList();
        log.info("Attempting {} bookings from {} enabled room-booker entries", tasks.size(), plans.size());
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("booking-", 0).factory())) {
            try {
                int successful = 0;
                for (Future<Boolean> result : executor.invokeAll(tasks)) {
                    if (result.get()) {
                        successful++;
                    }
                }
                log.info("Booking finished: {} of {} requested room/slot bookings reached their targets",
                        successful, tasks.size());
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                throw interrupted;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                throw new IllegalStateException("Booking worker failed", failure.getCause());
            }
        }
    }

    private void validateSlots(List<BookingSlot> slots, RoomResolver.ResolvedRoom room) {
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("At least one booking slot is required");
        }
        for (BookingSlot slot : slots) {
            Duration duration = Duration.between(slot.start(), slot.end());
            bookingSchedulePlanner.validateDuration(duration, room.catalogEntry(), room.displayName());
            bookingLadderPlanner.buildLadder(slot.start(), duration, properties.resolvedSlotShiftStep());
        }
    }

    private void validateOverlaps(List<BookingSlot> slots, String roomName) {
        List<BookingSlot> ordered = slots.stream().sorted(Comparator.comparing(BookingSlot::start)).toList();
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).overlaps(ordered.get(index))) {
                throw new IllegalArgumentException(
                        "Requested booking slots overlap in room '%s': %s and %s"
                                .formatted(roomName, ordered.get(index - 1), ordered.get(index))
                );
            }
        }
    }

    private boolean bookSlot(
            String meetingName,
            RoomResolver.ResolvedRoom room,
            BookingSlot slot,
            List<BookingSlot> slots,
            String timeZone
    ) throws InterruptedException {
        List<BookingSlot> otherTargets = slots.stream().filter(other -> !other.equals(slot)).toList();
        try {
            CreatedEvent created = progressiveBookMeetingRoomUseCase.execute(
                    meetingName,
                    room.exchange(),
                    slot.start(),
                    Duration.between(slot.start(), slot.end()),
                    timeZone,
                    room.catalogEntry(),
                    otherTargets
            );
            log.info(
                    "Booking successful: {}–{} booked for '{}' in {} ({}) (eventId={})",
                    slot.start(), slot.end(), created.summary(), room.displayName(), created.roomEmail(),
                    created.eventId()
            );
            return true;
        } catch (RuntimeException failure) {
            // Progressive booking can retain an earlier window even when the target was not reached.
            log.warn(
                    "Could not reach target {}–{} in {} ({}): {}. Other requested slots continue independently.",
                    slot.start(), slot.end(), room.displayName(), room.exchange(), failure.getMessage()
            );
            return false;
        }
    }

    private record PlannedBooking(String meetingName, RoomResolver.ResolvedRoom room, BookingSlot slot) {
        String roomKey() {
            return room.exchange().strip().toLowerCase(Locale.ROOT);
        }
    }
}
