package ru.yandex.roombooker.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Blocks until a target time is reached, waking periodically to log progress.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingWaiter {

    private static final Duration MAX_SLEEP = Duration.ofMinutes(1);
    private static final Duration LOG_THRESHOLD = Duration.ofSeconds(1);

    private final Clock clock;

    public void waitUntil(LocalDateTime target) throws InterruptedException {
        while (true) {
            LocalDateTime now = LocalDateTime.now(clock);
            if (!now.isBefore(target)) {
                return;
            }
            Duration remaining = Duration.between(now, target);
            if (remaining.compareTo(LOG_THRESHOLD) >= 0) {
                log.info("Booking window opens at {}; waiting {} more", target, formatRemaining(remaining));
            }
            long sleepMs = Math.max(1L, Math.min(remaining.toMillis(), MAX_SLEEP.toMillis()));
            Thread.sleep(sleepMs);
        }
    }

    private static String formatRemaining(Duration remaining) {
        long hours = remaining.toHours();
        long minutes = remaining.toMinutesPart();
        long seconds = remaining.toSecondsPart();
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, minutes, seconds);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, seconds);
        }
        return "%ds".formatted(seconds);
    }
}
