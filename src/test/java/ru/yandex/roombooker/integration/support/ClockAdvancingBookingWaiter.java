package ru.yandex.roombooker.integration.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

import ru.yandex.roombooker.domain.BookingWaiter;

/**
 * Test waiter that advances {@link MutableClock} to the target instead of sleeping.
 */
public final class ClockAdvancingBookingWaiter extends BookingWaiter {

    private final MutableClock mutableClock;
    private final ZoneId zoneId;

    public ClockAdvancingBookingWaiter(MutableClock mutableClock) {
        super(mutableClock);
        this.mutableClock = mutableClock;
        this.zoneId = mutableClock.getZone();
    }

    @Override
    public void waitUntil(LocalDateTime target) {
        mutableClock.setInstant(target.atZone(zoneId).toInstant());
    }
}
