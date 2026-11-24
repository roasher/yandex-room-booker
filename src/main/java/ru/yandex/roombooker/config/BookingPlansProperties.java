package ru.yandex.roombooker.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import ru.yandex.roombooker.domain.model.BookingPlan;

/**
 * The top-level room-booker array. Disabled entries are ignored without resolving their slots.
 */
public record BookingPlansProperties(List<BookingPlanProperties> entries) {

    public BookingPlansProperties {
        entries = List.copyOf(entries);
    }

    /** Resolves all enabled entries against the same startup instant. */
    public List<BookingPlan> resolve(Clock clock, String timeZone) {
        Clock startupClock = Clock.fixed(clock.instant(), clock.getZone());
        List<BookingPlan> plans = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            BookingPlanProperties entry = entries.get(index);
            if (entry.isEnabled()) {
                plans.add(entry.resolve(startupClock, timeZone, "room-booker[" + index + "]"));
            }
        }
        return List.copyOf(plans);
    }
}
