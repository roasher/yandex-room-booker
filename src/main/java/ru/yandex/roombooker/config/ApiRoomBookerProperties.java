package ru.yandex.roombooker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public Calendar API booking settings ({@code cloud-api.yandex.net}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "room-booker")
public class ApiRoomBookerProperties extends RoomBookerProperties {

    private String apiBaseUrl;
    private String oauthToken;

    @Override
    public String bookingMode() {
        return "api";
    }

    @Override
    public String calendarBaseUrl() {
        return apiBaseUrl;
    }

    @Override
    public void validateAuth() {
        requireNonBlank(oauthToken, "YANDEX_CALENDAR_OAUTH_TOKEN");
    }
}
