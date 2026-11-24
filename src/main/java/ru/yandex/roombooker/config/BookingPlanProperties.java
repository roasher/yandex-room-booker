package ru.yandex.roombooker.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;

/**
 * One element of room-booker: an enabled meeting, a single room, and a list of slots.
 */
@Getter
@Setter
public class BookingPlanProperties {

    private boolean enabled;
    private @Nullable String meetingName;
    private @Nullable String room;
    private @Nullable String duration;
    private List<BookingSlotProperties> slots = List.of();

    /** Resolves and validates an enabled entry, using an indexed path in configuration errors. */
    public BookingPlan resolve(Clock clock, String timeZone, String prefix) {
        RoomBookerProperties.requireNonBlank(meetingName, prefix + ".meeting-name");
        RoomBookerProperties.requireNonBlank(room, prefix + ".room");
        if (slots.isEmpty()) {
            throw new IllegalStateException(prefix + ".slots must contain at least one slot");
        }
        List<BookingSlot> resolved = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            BookingSlotProperties slot = slots.get(index);
            String slotPrefix = prefix + ".slots[" + index + "]";
            if (slot == null) {
                throw new IllegalStateException("Missing required property: " + slotPrefix);
            }
            resolved.add(slot.resolve(clock, timeZone, duration, slotPrefix));
        }
        return new BookingPlan(meetingName, room.strip(), resolved);
    }
}
