package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class RoomBookerPropertiesTest {

    @Test
    void computesEndFromStartAndDuration() {
        ApiRoomBookerProperties properties = sampleApi();

        assertThat(properties.bookingStart()).isEqualTo(LocalDateTime.parse("2026-06-22T14:00:00"));
        assertThat(properties.bookingEnd()).isEqualTo(LocalDateTime.parse("2026-06-22T15:30:00"));
        assertThat(properties.bookingMode()).isEqualTo("api");
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
        properties.setStart("2026-06-22T14:00:00");
        properties.setDuration("1h30m");
        properties.setOauthToken("token");
        properties.setBookingOpenBuffer("30s");
        properties.setBookingMaxRetries(2);
        properties.setBookingRetryBackoff("1s");
        properties.setBookingRetryMultiplier(2);
        return properties;
    }
}
