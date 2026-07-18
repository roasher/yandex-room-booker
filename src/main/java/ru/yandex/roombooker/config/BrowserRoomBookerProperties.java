package ru.yandex.roombooker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calendar web UI API booking settings ({@code calendar.yandex-team.ru/api/models}).
 *
 * <p>Auth is cookie-only: pass the full browser {@code Cookie} header via
 * {@code YANDEX_CALENDAR_COOKIES}. CSRF ckey/uid are resolved at runtime.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "room-booker")
public class BrowserRoomBookerProperties extends RoomBookerProperties {

    private String webBaseUrl;
    private String cookies;

    @Override
    public String bookingMode() {
        return "browser";
    }

    @Override
    public String calendarBaseUrl() {
        return webBaseUrl;
    }

    @Override
    public void validateAuth() {
        if (!hasBrowserCookie()) {
            throw new IllegalStateException(
                    "Browser mode requires YANDEX_CALENDAR_COOKIES (full Cookie header from DevTools)"
            );
        }
    }

    /**
     * Full Cookie header (same idea as events-consumer {@code EVENTS_API_COOKIES}).
     */
    public String resolvedCookieHeader() {
        return cookies == null ? "" : cookies.trim();
    }

    public boolean hasBrowserCookie() {
        return !resolvedCookieHeader().isBlank();
    }
}
