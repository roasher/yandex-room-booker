package ru.yandex.roombooker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.domain.BookingWaiter;
import ru.yandex.roombooker.domain.ProgressiveBookMeetingRoomUseCase;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.integration.support.ClockAdvancingBookingWaiter;
import ru.yandex.roombooker.integration.support.MutableClock;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class ProgressiveBookingIntegrationTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDateTime TARGET_START = LocalDateTime.parse("2026-08-13T19:00:00");
    private static final Duration DURATION = Duration.ofHours(1);
    private static final String MEETING = "Music practice";
    private static final String ROOM = "cr_000004198";
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    private static final MockWebServer mockWebServer = startServer();
    private static final MutableClock mutableClock = new MutableClock(
            Instant.parse("2026-07-30T10:00:00+03:00"),
            MOSCOW
    );

    private final List<String> requestLog = new ArrayList<>();
    private final AtomicBoolean failFirstCreateAttach = new AtomicBoolean(false);
    private final AtomicBoolean failFirstShift = new AtomicBoolean(false);
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger shiftCount = new AtomicInteger();
    private volatile String listEventsJson = """
            {"items":[],"limit":0}
            """;

    @Autowired
    private ProgressiveBookMeetingRoomUseCase progressiveBookMeetingRoomUseCase;

    private static MockWebServer startServer() {
        try {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockWebServer.shutdown();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("room-booker.enabled", () -> "false");
        registry.add("room-booker.booking-mode", () -> "api");
        registry.add("room-booker.api-base-url", () -> mockWebServer.url("/").toString().replaceAll("/$", ""));
        registry.add("room-booker.oauth-token", () -> "test-token");
        registry.add("room-booker.time-zone", () -> "Europe/Moscow");
        registry.add("room-booker.slot-shift-step", () -> "30m");
        registry.add("room-booker.booking-open-buffer", () -> "0s");
        registry.add("room-booker.booking-max-retries", () -> "0");
        registry.add("room-booker.booking-retry-backoff", () -> "0s");
        registry.add("room-booker.booking-retry-multiplier", () -> "1");
    }

    @BeforeEach
    void setUp() {
        requestLog.clear();
        failFirstCreateAttach.set(false);
        failFirstShift.set(false);
        createCount.set(0);
        shiftCount.set(0);
        listEventsJson = """
                {"items":[],"limit":0}
                """;
        mutableClock.setInstant(Instant.parse("2026-07-30T10:00:00+03:00"));
        mockWebServer.setDispatcher(new CalendarApiDispatcher());
    }

    @Test
    void shouldSkipEarlyLadderStepsWhenStartedLate() throws Exception {
        // Target 19:00 with 14d bookable-ahead opens at 2026-07-30T19:00.
        // Earliest 18:30 already opened at 18:30 — must be skipped.
        mutableClock.setInstant(Instant.parse("2026-07-30T19:00:00+03:00"));

        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                DURATION,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId(EVENT_ID)
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        assertThat(createCount.get()).isEqualTo(1);
        assertThat(shiftCount.get()).isEqualTo(0);
        assertThat(requestLog).isEqualTo(List.of(
                "GET /v1/calendar/events",
                "POST /v1/calendar/events",
                "PATCH /v1/calendar/events/" + EVENT_ID + "/rooms"
        ));
    }

    @Test
    void shouldCreateThenShiftAlongClearLadder() throws Exception {
        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                DURATION,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId(EVENT_ID)
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        assertThat(createCount.get()).isEqualTo(1);
        assertThat(shiftCount.get()).isEqualTo(1);
        assertThat(requestLog).containsExactly(
                "GET /v1/calendar/events",
                "POST /v1/calendar/events",
                "PATCH /v1/calendar/events/" + EVENT_ID + "/rooms",
                "PATCH /v1/calendar/events/" + EVENT_ID
        );
    }

    @Test
    void shouldSkipContestedCreateThenBookTargetSlot() throws Exception {
        failFirstCreateAttach.set(true);

        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                DURATION,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual.eventId()).isEqualTo(EVENT_ID);
        assertThat(createCount.get()).isEqualTo(2);
        assertThat(shiftCount.get()).isEqualTo(0);
        assertThat(requestLog).isEqualTo(List.of(
                "GET /v1/calendar/events",
                "POST /v1/calendar/events",
                "PATCH /v1/calendar/events/" + EVENT_ID + "/rooms",
                "DELETE /v1/calendar/events/" + EVENT_ID,
                "POST /v1/calendar/events",
                "PATCH /v1/calendar/events/" + EVENT_ID + "/rooms"
        ));
    }

    @Test
    void shouldSkipContestedShiftAndReachTargetOnNextStep() throws Exception {
        failFirstShift.set(true);
        Duration longer = Duration.ofMinutes(90);

        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                longer,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual.eventId()).isEqualTo(EVENT_ID);
        assertThat(createCount.get()).isEqualTo(1);
        assertThat(shiftCount.get()).isEqualTo(2);
        assertThat(requestLog.stream()
                .filter(entry -> entry.equals("PATCH /v1/calendar/events/" + EVENT_ID))
                .count()).isEqualTo(2);
    }

    @Test
    void shouldResumeFromMidLadderWithoutCreating() throws Exception {
        listEventsJson = """
                {
                  "limit": 1,
                  "items": [{
                    "ical_uid": "x",
                    "event_id": "%s",
                    "start": {"date_time": "2026-08-13T18:30:00", "time_zone": "Europe/Moscow"},
                    "end": {"date_time": "2026-08-13T19:30:00", "time_zone": "Europe/Moscow"},
                    "summary": "%s",
                    "relation_type": "ORGANIZER",
                    "rules": {},
                    "created_at": "2026-07-30T10:00:00Z",
                    "updated_at": "2026-07-30T10:00:00Z"
                  }]
                }
                """.formatted(EVENT_ID, MEETING);

        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                DURATION,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual).isEqualTo(CreatedEvent.builder()
                .eventId(EVENT_ID)
                .summary(MEETING)
                .roomEmail(ROOM + "@yandex-team.ru")
                .build());
        assertThat(createCount.get()).isEqualTo(0);
        assertThat(shiftCount.get()).isEqualTo(1);
        assertThat(requestLog).containsExactly(
                "GET /v1/calendar/events",
                "GET /v1/calendar/events/" + EVENT_ID + "/rooms",
                "PATCH /v1/calendar/events/" + EVENT_ID
        );
    }

    @Test
    void shouldExitImmediatelyWhenResumingAtTarget() throws Exception {
        listEventsJson = """
                {
                  "limit": 1,
                  "items": [{
                    "ical_uid": "x",
                    "event_id": "%s",
                    "start": {"date_time": "2026-08-13T19:00:00", "time_zone": "Europe/Moscow"},
                    "end": {"date_time": "2026-08-13T20:00:00", "time_zone": "Europe/Moscow"},
                    "summary": "%s",
                    "relation_type": "ORGANIZER",
                    "rules": {},
                    "created_at": "2026-07-30T10:00:00Z",
                    "updated_at": "2026-07-30T10:00:00Z"
                  }]
                }
                """.formatted(EVENT_ID, MEETING);

        CreatedEvent actual = progressiveBookMeetingRoomUseCase.execute(
                MEETING,
                ROOM,
                TARGET_START,
                DURATION,
                "Europe/Moscow",
                catalogEntry()
        );

        assertThat(actual.eventId()).isEqualTo(EVENT_ID);
        assertThat(createCount.get()).isEqualTo(0);
        assertThat(shiftCount.get()).isEqualTo(0);
        assertThat(requestLog).containsExactly(
                "GET /v1/calendar/events",
                "GET /v1/calendar/events/" + EVENT_ID + "/rooms"
        );
    }

    private static MeetingRoomEntry catalogEntry() {
        return new MeetingRoomEntry(
                "Лотте - музыкальная комната",
                ROOM,
                "БЦ Лотте",
                "3h",
                "14d"
        );
    }

    private final class CalendarApiDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String method = request.getMethod();
            String path = request.getPath() == null ? "" : request.getPath().split("\\?", 2)[0];
            String key = method + " " + path;
            if (path.startsWith("/v1/calendar/events") && "GET".equals(method) && path.equals("/v1/calendar/events")) {
                requestLog.add("GET /v1/calendar/events");
                return json(200, listEventsJson);
            }
            if (path.matches("/v1/calendar/events/.+/rooms") && "GET".equals(method)) {
                requestLog.add(key);
                return json(200, "{\"limit\":1,\"items\":[{\"room_id\":\"" + ROOM
                        + "\",\"name\":\"Room\",\"email\":\"" + ROOM + "@yandex-team.ru\"}]}");
            }
            if (path.equals("/v1/calendar/events") && "POST".equals(method)) {
                requestLog.add(key);
                createCount.incrementAndGet();
                return json(200, "{\"event_id\":\"" + EVENT_ID + "\"}");
            }
            if (path.equals("/v1/calendar/events/" + EVENT_ID + "/rooms") && "PATCH".equals(method)) {
                requestLog.add(key);
                if (failFirstCreateAttach.compareAndSet(true, false)) {
                    return json(409, "{\"error\":\"busy\",\"message\":\"room busy\"}");
                }
                return new MockResponse().setResponseCode(204);
            }
            if (path.equals("/v1/calendar/events/" + EVENT_ID) && "DELETE".equals(method)) {
                requestLog.add(key);
                return new MockResponse().setResponseCode(204);
            }
            if (path.equals("/v1/calendar/events/" + EVENT_ID) && "PATCH".equals(method)) {
                requestLog.add(key);
                shiftCount.incrementAndGet();
                if (failFirstShift.compareAndSet(true, false)) {
                    return json(409, "{\"error\":\"busy\",\"message\":\"room busy\"}");
                }
                return json(200, """
                        {
                          "ical_uid":"x",
                          "event_id":"%s",
                          "start":{"date_time":"2026-08-13T19:00:00","time_zone":"Europe/Moscow"},
                          "end":{"date_time":"2026-08-13T20:00:00","time_zone":"Europe/Moscow"},
                          "summary":"%s",
                          "relation_type":"ORGANIZER",
                          "rules":{},
                          "created_at":"2026-07-30T10:00:00Z",
                          "updated_at":"2026-07-30T10:00:00Z"
                        }
                        """.formatted(EVENT_ID, MEETING));
            }
            return json(404, "{\"error\":\"unexpected\",\"message\":\"" + key + "\"}");
        }

        private static MockResponse json(int code, String body) {
            return new MockResponse()
                    .setResponseCode(code)
                    .addHeader("Content-Type", "application/json")
                    .setBody(body);
        }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        Clock clock() {
            return mutableClock;
        }

        @Bean
        @Primary
        BookingWaiter bookingWaiter() {
            return new ClockAdvancingBookingWaiter(mutableClock);
        }
    }
}
