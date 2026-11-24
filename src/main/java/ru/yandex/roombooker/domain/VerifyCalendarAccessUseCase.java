package ru.yandex.roombooker.domain;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.RoomBookerProperties;

/** Checks remote authentication once before any booking is scheduled or attempted. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyCalendarAccessUseCase {

    private final CalendarEventGateway calendarEventGateway;
    private final RoomBookerProperties properties;
    private final Clock clock;

    /** Fails startup if the configured credentials or required read endpoints cannot be verified. */
    public void execute() {
        String timeZone = properties.getTimeZone();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(timeZone));
        log.info("Checking Calendar credentials and read access before starting booking workers");
        calendarEventGateway.verifyAccess(now, now.plusMinutes(1), timeZone);
        log.info("Calendar startup access check passed");
    }
}
