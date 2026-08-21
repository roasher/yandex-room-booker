package ru.yandex.roombooker.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import ru.yandex.roombooker.domain.model.BookingSlot;

/**
 * Builds the progressive booking ladder from a target window and shift step.
 *
 * <p>Earliest start is the first step-aligned time where a duration-length slot still overlaps
 * the target: {@code targetStart - (duration - step)}.
 */
@Service
public class BookingLadderPlanner {

    public List<BookingSlot> buildLadder(LocalDateTime targetStart, Duration duration, Duration step) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (step.isZero() || step.isNegative()) {
            throw new IllegalArgumentException("slot-shift-step must be positive");
        }
        if (step.compareTo(duration) > 0) {
            throw new IllegalArgumentException(
                    "slot-shift-step %s must not exceed duration %s".formatted(step, duration)
            );
        }

        Duration lead = duration.minus(step);
        LocalDateTime earliestStart = targetStart.minus(lead);
        LocalDateTime targetEnd = targetStart.plus(duration);

        List<BookingSlot> slots = new ArrayList<>();
        for (LocalDateTime start = earliestStart; !start.isAfter(targetStart); start = start.plus(step)) {
            slots.add(BookingSlot.builder().start(start).end(start.plus(duration)).build());
        }

        BookingSlot last = slots.getLast();
        if (!last.start().equals(targetStart) || !last.end().equals(targetEnd)) {
            throw new IllegalStateException(
                    "Ladder did not reach target %s–%s (last was %s–%s); check duration/step alignment"
                            .formatted(targetStart, targetEnd, last.start(), last.end())
            );
        }
        return List.copyOf(slots);
    }
}
