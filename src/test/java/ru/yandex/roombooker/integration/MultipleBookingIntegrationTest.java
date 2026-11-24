package ru.yandex.roombooker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "room-booker-settings.booking-mode=api",
        "room-booker-settings.oauth-token=test-token",
        "room-booker-settings.time-zone=Europe/Moscow",
        "room-booker-settings.slot-shift-step=30m",
        "room-booker-settings.booking-open-buffer=0s",
        "room-booker[0].enabled=true",
        "room-booker[0].meeting-name=Yoga",
        "room-booker[0].room=test-room",
        "room-booker[0].duration=1h",
        "room-booker[0].slots[0].day-of-week=1",
        "room-booker[0].slots[0].time=14:00",
        "room-booker[0].slots[1].date=2026-10-05",
        "room-booker[0].slots[1].time=16:00",
        "room-booker[0].slots[1].duration=30m",
        "room-booker[1].enabled=false",
        "room-booker[2].enabled=true",
        "room-booker[2].meeting-name=Music",
        "room-booker[2].room=other-room",
        "room-booker[2].duration=1h30m",
        "room-booker[2].slots[0].day-of-week=3",
        "room-booker[2].slots[0].time=18:00",
        "room-catalog.rooms.test-room.display-name=Test room",
        "room-catalog.rooms.test-room.exchange=conf_test",
        "room-catalog.rooms.test-room.max-duration=3h",
        "room-catalog.rooms.test-room.bookable-ahead=2d",
        "room-catalog.rooms.other-room.display-name=Other room",
        "room-catalog.rooms.other-room.exchange=conf_other",
        "room-catalog.rooms.other-room.max-duration=2h",
        "room-catalog.rooms.other-room.bookable-ahead=1d"
})
class MultipleBookingIntegrationTest {

    private static final Queue<BookingAttempt> ATTEMPTS = new ConcurrentLinkedQueue<>();
    private static final Map<String, String> MEETINGS = new ConcurrentHashMap<>();
    private static final Map<String, String> ATTACHED = new ConcurrentHashMap<>();
    private static final Queue<LocalDateTime> WAITED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger NEXT_EVENT_ID = new AtomicInteger();
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
    void shouldBookEachEnabledEntryOnlyInItsOwnRoomAndSlotsWithItsOwnMeetingName() {
        assertThat(ATTEMPTS.stream().map(attempt -> new RoomBookingAttempt(
                attempt.method(), MEETINGS.get(attempt.eventId()), ATTACHED.get(attempt.eventId()),
                attempt.start(), attempt.end(), attempt.timeZone()
        )).toList()).containsExactlyInAnyOrder(
                attempt("POST", "Yoga", "conf_test", "2026-10-05", "13:30", "14:30"),
                attempt("PATCH", "Yoga", "conf_test", "2026-10-05", "14:00", "15:00"),
                attempt("POST", "Yoga", "conf_test", "2026-10-05", "16:00", "16:30"),
                attempt("POST", "Music", "conf_other", "2026-10-07", "17:00", "18:30"),
                attempt("PATCH", "Music", "conf_other", "2026-10-07", "17:30", "19:00"),
                attempt("PATCH", "Music", "conf_other", "2026-10-07", "18:00", "19:30")
        );
        assertThat(ATTACHED.values()).containsExactlyInAnyOrder("conf_test", "conf_test", "conf_other");
        assertThat(WAITED).containsExactlyInAnyOrder(
                LocalDateTime.parse("2026-10-03T13:30"),
                LocalDateTime.parse("2026-10-03T14:00"),
                LocalDateTime.parse("2026-10-03T16:00"),
                LocalDateTime.parse("2026-10-06T17:00"),
                LocalDateTime.parse("2026-10-06T17:30"),
                LocalDateTime.parse("2026-10-06T18:00")
        );
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        ObjectMapper mapper = new ObjectMapper();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("GET".equals(request.getMethod()) && path.equals("/v1/calendar/rooms")) {
                    return json("""
                            {"items":[
                              {"room_id":"00000000-0000-0000-0000-000000000001","email":"conf_test@yandex-team.ru"},
                              {"room_id":"00000000-0000-0000-0000-000000000002","email":"conf_other@yandex-team.ru"}
                            ]}
                            """);
                }
                if ("POST".equals(request.getMethod()) && path.equals("/v1/calendar/free-busy/rooms/search")) {
                    return json("{\"items\":[]}");
                }
                if ("GET".equals(request.getMethod()) && path.equals("/v1/calendar/events")) {
                    return json("{\"items\":[],\"limit\":0}");
                }
                if ("PATCH".equals(request.getMethod()) && path.endsWith("/rooms")) {
                    try {
                        JsonNode body = mapper.readTree(request.getBody().readUtf8());
                        ATTACHED.put(path.split("/")[4], body.path("items").get(0).asText());
                        return new MockResponse().setResponseCode(204);
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                }
                if (("POST".equals(request.getMethod()) && path.equals("/v1/calendar/events"))
                        || ("PATCH".equals(request.getMethod()) && path.startsWith("/v1/calendar/events/event-"))) {
                    try {
                        JsonNode body = mapper.readTree(request.getBody().readUtf8());
                        String start = body.path("start").path("date_time").asText();
                        String eventId = "POST".equals(request.getMethod())
                                ? "event-" + NEXT_EVENT_ID.incrementAndGet() : path.split("/")[4];
                        if ("POST".equals(request.getMethod())) {
                            MEETINGS.put(eventId, body.path("summary").asText());
                        }
                        ATTEMPTS.add(new BookingAttempt(
                                request.getMethod(),
                                eventId,
                                start,
                                body.path("end").path("date_time").asText(),
                                body.path("start").path("time_zone").asText()
                        ));
                        return json("{\"event_id\":\"" + eventId + "\"}");
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

    private static RoomBookingAttempt attempt(
            String method, String meeting, String room, String date, String start, String end
    ) {
        return new RoomBookingAttempt(method, meeting, room, date + "T" + start + ":00", date + "T" + end + ":00",
                "Europe/Moscow");
    }

    private record BookingAttempt(String method, String eventId, String start, String end, String timeZone) {
    }

    private record RoomBookingAttempt(
            String method, String meetingName, String room, String start, String end, String timeZone
    ) {
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneId.of("Europe/Moscow"));
        }

        @Bean
        @Primary
        BookingWaiter bookingWaiter(Clock clock) {
            return new BookingWaiter(clock) {
                @Override
                public void waitUntil(LocalDateTime target) {
                    // Record the planned opening time instead of waiting for real days to pass.
                    WAITED.add(target);
                }
            };
        }
    }
}
