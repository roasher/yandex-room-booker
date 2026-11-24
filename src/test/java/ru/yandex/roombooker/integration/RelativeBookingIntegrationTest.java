package ru.yandex.roombooker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.yandex.roombooker.domain.BookingWaiter;
import ru.yandex.roombooker.integration.support.ClockAdvancingBookingWaiter;
import ru.yandex.roombooker.integration.support.MutableClock;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "room-booker[0].enabled=true",
        "room-booker-settings.booking-mode=api",
        "room-booker-settings.oauth-token=test-token",
        "room-booker[0].meeting-name=Next Monday meeting",
        "room-booker[0].room=test-room",
        "room-booker-settings.time-zone=Europe/Moscow",
        "room-booker[0].slots[0].time=18:30",
        "room-booker[0].slots[0].day-of-week=1",
        "room-booker[0].duration=1h",
        "room-booker-settings.slot-shift-step=30m",
        "room-booker-settings.booking-open-buffer=0s",
        "room-catalog.rooms.test-room.display-name=Test room",
        "room-catalog.rooms.test-room.exchange=conf_test",
        "room-catalog.rooms.test-room.max-duration=1h",
        "room-catalog.rooms.test-room.bookable-ahead=2d"
})
class RelativeBookingIntegrationTest {

    private static final MutableClock CLOCK = new MutableClock(
            Instant.parse("2026-10-02T10:00:00+03:00"), ZoneId.of("Europe/Moscow")
    );
    private static final List<BookingAttempt> ATTEMPTS = new ArrayList<>();
    private static final MockWebServer SERVER = startServer();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("room-booker-settings.api-base-url", () -> SERVER.url("/").toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @Test
    void shouldBookNextMondayWhenTwoDayWindowOpens() {
        assertThat(ATTEMPTS).containsExactly(
                new BookingAttempt(
                        "POST", "2026-10-05T18:00:00", "2026-10-05T19:00:00", "Europe/Moscow",
                        Instant.parse("2026-10-03T18:00:00+03:00")
                ),
                new BookingAttempt(
                        "PATCH", "2026-10-05T18:30:00", "2026-10-05T19:30:00", "Europe/Moscow",
                        Instant.parse("2026-10-03T18:30:00+03:00")
                )
        );
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        ObjectMapper mapper = new ObjectMapper();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("GET".equals(request.getMethod()) && path.equals("/v1/calendar/events")) {
                    return json("{\"items\":[],\"limit\":0}");
                }
                if ("PATCH".equals(request.getMethod()) && path.equals("/v1/calendar/events/event-1/rooms")) {
                    return new MockResponse().setResponseCode(204);
                }
                if (("POST".equals(request.getMethod()) && path.equals("/v1/calendar/events"))
                        || ("PATCH".equals(request.getMethod()) && path.equals("/v1/calendar/events/event-1"))) {
                    try {
                        JsonNode body = mapper.readTree(request.getBody().readUtf8());
                        ATTEMPTS.add(new BookingAttempt(
                                request.getMethod(),
                                body.path("start").path("date_time").asText(),
                                body.path("end").path("date_time").asText(),
                                body.path("start").path("time_zone").asText(),
                                CLOCK.instant()
                        ));
                        return json("{\"event_id\":\"event-1\"}");
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }

    private record BookingAttempt(String method, String start, String end, String timeZone, Instant attemptedAt) {
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        Clock clock() {
            return CLOCK;
        }

        @Bean
        @Primary
        BookingWaiter bookingWaiter() {
            return new ClockAdvancingBookingWaiter(CLOCK);
        }
    }
}
