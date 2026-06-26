package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class RoomBookerPropertiesTest {

    @Test
    void computesEndFromStartAndDuration() {
        RoomBookerProperties properties = new RoomBookerProperties(
                true,
                "https://cloud-api.yandex.net",
                "Europe/Moscow",
                "Test",
                "stroganov-yoga",
                null,
                "2026-06-22T14:00:00",
                "1h30m",
                "token",
                "30s"
        );

        assertThat(properties.bookingStart()).isEqualTo(LocalDateTime.parse("2026-06-22T14:00:00"));
        assertThat(properties.bookingEnd()).isEqualTo(LocalDateTime.parse("2026-06-22T15:30:00"));
    }
}
