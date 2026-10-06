package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RoomBookerPropertiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldComputeEndFromExplicitStartAndDuration() {
        ApiRoomBookerProperties properties = sampleApi();

        assertThat(List.of(properties.bookingStart(CLOCK), properties.bookingEnd(CLOCK)))
                .containsExactly(
                        LocalDateTime.parse("2026-06-22T14:00:00"),
                        LocalDateTime.parse("2026-06-22T15:30:00")
                );
        assertThat(properties.bookingMode()).isEqualTo("api");
    }

    @ParameterizedTest
    @CsvSource({
            "1, 18:30, 2026-10-05T18:30:00",
            "2, 18:30, 2026-10-06T18:30:00",
            "3, 18:30, 2026-10-07T18:30:00",
            "4, 18:30, 2026-10-08T18:30:00",
            "5, 18:30, 2026-10-09T18:30:00",
            "5, 09:00, 2026-10-09T09:00:00",
            "6, 18:30, 2026-10-03T18:30:00",
            "7, 18:30, 2026-10-04T18:30:00",
            "1, '  18:30:15  ', 2026-10-05T18:30:15"
    })
    void shouldResolveNextWeekday(int dayOfWeek, String time, LocalDateTime expected) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(null);
        properties.setDayOfWeek(dayOfWeek);
        properties.setTime(time);

        assertThat(properties.bookingStart(CLOCK)).isEqualTo(expected);
    }

    @Test
    void shouldUseBookingTimeZoneToDetermineToday() {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(null);
        properties.setDayOfWeek(6);
        properties.setTime("18:30");
        // Friday in UTC, but already Saturday in the booking time zone (Europe/Moscow).
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T21:30:00Z"), ZoneOffset.UTC);

        assertThat(properties.bookingStart(clock)).isEqualTo(LocalDateTime.parse("2026-10-10T18:30:00"));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-12-31T10:00:00Z, 5, 2027-01-01T14:00:00",
            "2028-02-28T10:00:00Z, 2, 2028-02-29T14:00:00"
    })
    void shouldResolveAcrossCalendarBoundaries(Instant now, int dayOfWeek, LocalDateTime expected) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(null);
        properties.setDayOfWeek(dayOfWeek);

        assertThat(properties.bookingStart(Clock.fixed(now, ZoneOffset.UTC))).isEqualTo(expected);
    }

    @Test
    void shouldComputeRelativeBookingEndAcrossMidnightInBrowserMode() {
        BrowserRoomBookerProperties properties = new BrowserRoomBookerProperties();
        properties.setTimeZone("Europe/Moscow");
        properties.setDayOfWeek(1);
        properties.setTime("23:30");
        properties.setDuration("1h30m");

        assertThat(properties.bookingEnd(CLOCK)).isEqualTo(LocalDateTime.parse("2026-10-06T01:00:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1", "0", "8", "-1", "1.5", "monday", "next monday", "2026-02-30", "2026-10-05T18:30:00"
    })
    void shouldRejectInvalidDateWithSupportedFormats(String date) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(date);
        properties.setDayOfWeek(1);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("room-booker.date", "YYYY-MM-DD");
    }

    @ParameterizedTest
    @CsvSource({
            ", 0", ", 8", ", -1",
            "2026-10-05, 0", "2026-10-05, 8", "2026-10-05, -1"
    })
    void shouldRejectInvalidDayOfWeekEvenWithExplicitDate(String date, int dayOfWeek) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(date);
        properties.setDayOfWeek(dayOfWeek);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("room-booker.day-of-week", "1 (Monday)", "7 (Sunday)");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12, 1", "2026-10-13, 2", "2026-10-14, 3", "2026-10-15, 4",
            "2026-10-16, 5", "2026-10-17, 6", "2026-10-18, 7", "2026-10-02, 5"
    })
    void shouldUseExplicitDateWhenDayOfWeekMatches(String date, int dayOfWeek) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(date);
        properties.setDayOfWeek(dayOfWeek);

        assertThat(properties.bookingStart(CLOCK)).isEqualTo(LocalDateTime.parse(date + "T14:00:00"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7})
    void shouldRejectDateThatDoesNotMatchDayOfWeek(int dayOfWeek) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate("2026-10-05");
        properties.setDayOfWeek(dayOfWeek);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("room-booker.date '2026-10-05' is MONDAY (1), but room-booker.day-of-week is " + dayOfWeek);
    }

    @ParameterizedTest
    @ValueSource(strings = {"25:00", "14:60", "14:00 extra", "2026-10-05T18:30:00"})
    void shouldRejectInvalidTime(String time) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setTime(time);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("room-booker.time", "HH:mm");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRequireDateOrDayOfWeek(String date) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(date);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one of room-booker.date or room-booker.day-of-week is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldAllowMissingDateWhenDayOfWeekIsPresent(String date) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setDate(date);
        properties.setDayOfWeek(1);

        assertThat(properties.bookingStart(CLOCK)).isEqualTo(LocalDateTime.parse("2026-10-05T14:00:00"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRejectMissingTime(String time) {
        ApiRoomBookerProperties properties = sampleApi();
        properties.setTime(time);

        assertThatThrownBy(() -> properties.bookingStart(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing required property: room-booker.time");
    }

    @Test
    void shouldResolveFullCookieHeaderForBrowserMode() {
        BrowserRoomBookerProperties properties = new BrowserRoomBookerProperties();
        properties.setCookies("gdpr=0; Session_id=abc; yandexuid=1");

        assertThat(properties.bookingMode()).isEqualTo("browser");
        assertThat(properties.resolvedCookieHeader()).isEqualTo("gdpr=0; Session_id=abc; yandexuid=1");
        assertThat(properties.hasBrowserCookie()).isTrue();
    }

    private static ApiRoomBookerProperties sampleApi() {
        ApiRoomBookerProperties properties = new ApiRoomBookerProperties();
        properties.setEnabled(true);
        properties.setApiBaseUrl("https://cloud-api.yandex.net");
        properties.setTimeZone("Europe/Moscow");
        properties.setMeetingName("Test");
        properties.setRoom("stroganov-yoga");
        properties.setDate("2026-06-22");
        properties.setTime("14:00");
        properties.setDuration("1h30m");
        properties.setOauthToken("token");
        properties.setBookingOpenBuffer("30s");
        properties.setSlotShiftStep("30m");
        properties.setBookingMaxRetries(2);
        properties.setBookingRetryBackoff("1s");
        properties.setBookingRetryMultiplier(2);
        return properties;
    }
}
