package ru.yandex.roombooker.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.DurationParsing;
import ru.yandex.roombooker.config.MeetingRoomEntry;

/**
 * Computes when a room slot becomes bookable based on catalog policy.
 */
@Service
@RequiredArgsConstructor
public class BookingSchedulePlanner {

    private final Clock clock;

    /**
     * Absolute time when the booking window for {@code slotStart} opens (ignores "already open").
     * Returns {@code null} when the room has no bookable-ahead policy.
     */
    public @Nullable LocalDateTime bookingWindowOpensAt(
            LocalDateTime slotStart,
            @Nullable MeetingRoomEntry room,
            Duration openBuffer
    ) {
        if (room == null || room.bookableAhead() == null || room.bookableAhead().isBlank()) {
            return null;
        }
        Duration bookableAhead = DurationParsing.parse(room.bookableAhead());
        return slotStart.minus(bookableAhead).plus(openBuffer);
    }

    public LocalDateTime firstAttemptAt(LocalDateTime slotStart, @Nullable MeetingRoomEntry room, Duration openBuffer) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowOpens = bookingWindowOpensAt(slotStart, room, openBuffer);
        if (windowOpens == null) {
            return now;
        }
        if (!windowOpens.isAfter(now)) {
            return now;
        }
        return windowOpens;
    }

    public void validateDuration(Duration requestedDuration, @Nullable MeetingRoomEntry room, String roomLabel) {
        if (room == null || room.maxDuration() == null || room.maxDuration().isBlank()) {
            return;
        }
        Duration maxDuration = DurationParsing.parse(room.maxDuration());
        if (requestedDuration.compareTo(maxDuration) > 0) {
            throw new IllegalStateException(
                    "Requested duration %s exceeds max-duration %s for room '%s'"
                            .formatted(requestedDuration, maxDuration, roomLabel)
            );
        }
    }
}
