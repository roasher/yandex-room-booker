package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CalendarApiExceptionTest {

    @Test
    void shouldRetryNotYetBookableEvenOnHttp400() {
        CalendarApiException exception = new CalendarApiException(
                "Calendar API request failed: HTTP 400 Some rooms are not bookable",
                400
        );
        assertThat(exception.isRetryable()).isTrue();
    }

    @Test
    void shouldRetryRoomBusyOnHttp400() {
        CalendarApiException exception = new CalendarApiException(
                "Calendar API request failed: HTTP 400 rooms are busy",
                400
        );
        assertThat(exception.isRetryable()).isTrue();
    }

    @Test
    void shouldRetryTooFarEventWithoutHttpStatus() {
        CalendarApiException exception = new CalendarApiException("too-far-event");
        assertThat(exception.isRetryable()).isTrue();
    }
}
