package ru.yandex.roombooker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings for automatic room booking on startup.
 */
@ConfigurationProperties(prefix = "room-booker")
public record RoomBookerProperties(
        boolean enabled,
        String apiBaseUrl,
        String timeZone,
        String meetingName,
        String roomEmail,
        String start,
        String end,
        String oauthToken
) {
}
