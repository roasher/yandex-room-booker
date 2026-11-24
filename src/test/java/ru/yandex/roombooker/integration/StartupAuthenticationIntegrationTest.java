package ru.yandex.roombooker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import ru.yandex.roombooker.YandexRoomBookerApplication;

class StartupAuthenticationIntegrationTest {

    @Test
    void shouldFailStartupOnceWhenTheOAuthTokenIsRejected() throws Exception {
        Queue<String> requests = new ConcurrentLinkedQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    requests.add(request.getMethod() + " " + request.getRequestUrl().encodedPath());
                    return new MockResponse().setResponseCode(401)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"error\":\"UnauthorizedError\",\"description\":\"Unauthorized\"}");
                }
            });
            server.start();

            assertThatThrownBy(() -> startApplication(server))
                    .hasStackTraceContaining("OAuth token was rejected")
                    .hasStackTraceContaining("YANDEX_CALENDAR_OAUTH_TOKEN");
            assertThat(requests).containsExactly("GET /v1/calendar/events");
        }
    }

    @Test
    void shouldReportRoomAccessSeparatelyAfterAcceptingTheToken() throws Exception {
        Queue<String> requests = new ConcurrentLinkedQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getRequestUrl().encodedPath();
                    requests.add(request.getMethod() + " " + path);
                    return new MockResponse()
                            .setResponseCode(path.equals("/v1/calendar/events") ? 200 : 401)
                            .addHeader("Content-Type", "application/json")
                            .setBody(path.equals("/v1/calendar/events")
                                    ? "{\"items\":[]}" : "{\"error\":\"UnauthorizedError\"}");
                }
            });
            server.start();

            assertThatThrownBy(() -> startApplication(server))
                    .hasStackTraceContaining("OAuth token was accepted")
                    .hasStackTraceContaining("room-directory access was rejected")
                    .hasStackTraceContaining("Startup stopped before any booking attempts");
            assertThat(requests).containsExactly("GET /v1/calendar/events", "GET /v1/calendar/rooms");
        }
    }

    private void startApplication(MockWebServer server) {
        try (var context = new SpringApplicationBuilder(YandexRoomBookerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--room-booker-settings.booking-mode=api",
                        "--room-booker-settings.oauth-token=rejected-test-token",
                        "--room-booker-settings.api-base-url=" + server.url("/"),
                        "--room-booker-settings.time-zone=Europe/Moscow",
                        "--room-booker[0].enabled=true",
                        "--room-booker[0].meeting-name=Auth test",
                        "--room-booker[0].room=conf_test",
                        "--room-booker[0].duration=1h",
                        "--room-booker[0].slots[0].date=2099-01-01",
                        "--room-booker[0].slots[0].time=12:00",
                        "--room-booker[0].slots[1].date=2099-01-02",
                        "--room-booker[0].slots[1].time=12:00",
                        "--logging.level.root=OFF"
                )) {
            // Closing a successful startup keeps the failing regression test from leaking the context.
        }
    }
}
