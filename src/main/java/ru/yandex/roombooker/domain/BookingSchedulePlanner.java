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

    public LocalDateTime firstAttemptAt(LocalDateTime slotStart, @Nullable MeetingRoomEntry room, Duration openBuffer) {
        if (room == null || room.bookableAhead() == null || room.bookableAhead().isBlank()) {
            return LocalDateTime.now(clock);
        }
        Duration bookableAhead = DurationParsing.parse(room.bookableAhead());
        LocalDateTime windowOpens = slotStart.minus(bookableAhead).plus(openBuffer);
        LocalDateTime now = LocalDateTime.now(clock);
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
