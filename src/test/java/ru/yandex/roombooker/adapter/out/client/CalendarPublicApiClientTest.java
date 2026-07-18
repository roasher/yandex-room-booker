package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CalendarPublicApiClientTest {

    @Test
    void shouldAppendCorpDomainWhenRoomIdHasNoAtSign() {
        assertThat(CalendarPublicApiClient.normalizeRoomEmail("cr_000004170"))
                .isEqualTo("cr_000004170@yandex-team.ru");
    }

    @Test
    void shouldKeepFullRoomEmailUntouched() {
        assertThat(CalendarPublicApiClient.normalizeRoomEmail("conf_rr_3_1@yandex-team.ru"))
                .isEqualTo("conf_rr_3_1@yandex-team.ru");
    }

    @Test
    void shouldExtractRoomIdFromEmail() {
        assertThat(CalendarPublicApiClient.normalizeRoomId("conf_st_yoga@yandex-team.ru"))
                .isEqualTo("conf_st_yoga");
    }

    @Test
    void shouldKeepBareExchangeAsRoomId() {
        assertThat(CalendarPublicApiClient.normalizeRoomId("conf_st_yoga"))
                .isEqualTo("conf_st_yoga");
    }
}
