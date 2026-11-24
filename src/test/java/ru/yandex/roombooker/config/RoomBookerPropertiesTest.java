package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoomBookerPropertiesTest {

    @Test
    void shouldResolveFullCookieHeaderForBrowserMode() {
        BrowserRoomBookerProperties properties = new BrowserRoomBookerProperties();
        properties.setCookies(" gdpr=0; Session_id=abc; yandexuid=1 ");

        assertThat(properties.resolvedCookieHeader()).isEqualTo("gdpr=0; Session_id=abc; yandexuid=1");
        properties.validateAuth();
    }

    @Test
    void shouldRequireApiAuthentication() {
        ApiRoomBookerProperties properties = new ApiRoomBookerProperties();

        assertThatThrownBy(properties::validateAuth).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YANDEX_CALENDAR_OAUTH_TOKEN");
    }
}
