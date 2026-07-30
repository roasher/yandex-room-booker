package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import ru.yandex.roombooker.domain.model.BookingSlot;

class BookingLadderPlannerTest {

    private final BookingLadderPlanner planner = new BookingLadderPlanner();

    @Test
    void shouldBuildLadderFromEarliestOverlapToTarget() {
        List<BookingSlot> actual = planner.buildLadder(
                LocalDateTime.parse("2026-08-13T19:00:00"),
                Duration.ofHours(3),
                Duration.ofMinutes(30)
        );

        assertThat(actual).isEqualTo(List.of(
                slot("2026-08-13T16:30:00", "2026-08-13T19:30:00"),
                slot("2026-08-13T17:00:00", "2026-08-13T20:00:00"),
                slot("2026-08-13T17:30:00", "2026-08-13T20:30:00"),
                slot("2026-08-13T18:00:00", "2026-08-13T21:00:00"),
                slot("2026-08-13T18:30:00", "2026-08-13T21:30:00"),
                slot("2026-08-13T19:00:00", "2026-08-13T22:00:00")
        ));
    }

    @Test
    void shouldBuildSingleSlotWhenDurationEqualsStep() {
        List<BookingSlot> actual = planner.buildLadder(
                LocalDateTime.parse("2026-08-13T19:00:00"),
                Duration.ofMinutes(30),
                Duration.ofMinutes(30)
        );

        assertThat(actual).isEqualTo(List.of(
                slot("2026-08-13T19:00:00", "2026-08-13T19:30:00")
        ));
    }

    @Test
    void shouldRejectStepLongerThanDuration() {
        assertThatThrownBy(() -> planner.buildLadder(
                LocalDateTime.parse("2026-08-13T19:00:00"),
                Duration.ofHours(1),
                Duration.ofHours(2)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slot-shift-step");
    }

    private static BookingSlot slot(String start, String end) {
        return BookingSlot.builder()
                .start(LocalDateTime.parse(start))
                .end(LocalDateTime.parse(end))
                .build();
    }
}
