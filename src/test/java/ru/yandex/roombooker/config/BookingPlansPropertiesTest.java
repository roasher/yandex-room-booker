package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;

class BookingPlansPropertiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldKeepEachMeetingsRoomDurationAndSlotsTogetherAndSkipDisabledEntries() {
        BookingPlanProperties yoga = plan("Yoga", "yoga-room", "1h", 1, "14:00");
        BookingPlanProperties music = plan("Music", "music-room", "1h30m", 3, "18:00");
        music.setSlots(List.of(
                new BookingSlotProperties(null, 3, "18:00", null),
                new BookingSlotProperties("2026-10-09", null, "12:00", "30m")
        ));
        BookingPlansProperties configuration = new BookingPlansProperties(List.of(
                yoga, new BookingPlanProperties(), music
        ));

        assertThat(configuration.resolve(CLOCK, "Europe/Moscow")).containsExactly(
                new BookingPlan("Yoga", "yoga-room", List.of(slot("2026-10-05T14:00", "2026-10-05T15:00"))),
                new BookingPlan("Music", "music-room", List.of(
                        slot("2026-10-07T18:00", "2026-10-07T19:30"),
                        slot("2026-10-09T12:00", "2026-10-09T12:30")
                ))
        );
    }

    @Test
    void shouldIdentifyAnEnabledEntryWithMissingRoom() {
        BookingPlanProperties invalid = plan("Music", null, "1h", 1, "14:00");
        BookingPlansProperties configuration = new BookingPlansProperties(
                List.of(new BookingPlanProperties(), invalid)
        );

        assertThatThrownBy(() -> configuration.resolve(CLOCK, "Europe/Moscow"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("room-booker[1].room");
    }

    @Test
    void shouldRequireSlotsForEveryEnabledEntry() {
        BookingPlanProperties invalid = plan("Music", "room", "1h", 1, "14:00");
        invalid.setSlots(List.of());

        assertThatThrownBy(() -> new BookingPlansProperties(List.of(invalid)).resolve(CLOCK, "Europe/Moscow"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("room-booker[0].slots");
    }

    private static BookingPlanProperties plan(String name, String room, String duration, int day, String time) {
        BookingPlanProperties plan = new BookingPlanProperties();
        plan.setEnabled(true);
        plan.setMeetingName(name);
        plan.setRoom(room);
        plan.setDuration(duration);
        plan.setSlots(List.of(new BookingSlotProperties(null, day, time, null)));
        return plan;
    }

    private static BookingSlot slot(String start, String end) {
        return new BookingSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }
}
