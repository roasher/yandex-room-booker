package ru.yandex.roombooker.adapter.out.client;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.config.BrowserRoomBookerProperties;
import ru.yandex.roombooker.domain.CalendarEventGateway;
import ru.yandex.roombooker.domain.RoomResolver;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.domain.model.ExistingEvent;

/**
 * Client for the Calendar web UI API ({@code calendar.yandex-team.ru/api/models}).
 *
 * <p>Mirrors browser requests (session cookie + maya headers, room in {@code attendees} on create)
 * so booking failures return the same detailed errors as the UI (e.g. {@code too-far-event}).
 *
 * @see <a href="https://wiki.yandex-team.ru/calendar/api/new-web/#create-event">create-event</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "room-booker-settings", name = "booking-mode", havingValue = "browser")
public class CalendarWebApiClient implements CalendarEventGateway {

    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String MODELS_PATH = "/api/models";

    private final RestClient calendarRestClient;
    private final BrowserRoomBookerProperties properties;
    private final ObjectMapper objectMapper;
    private final CalendarWebResponseParser responseParser;
    private final CalendarWebSessionResolver sessionResolver;

    @Override
    public void verifyAccess(LocalDateTime from, LocalDateTime to, String timeZone) {
        try {
            sessionResolver.ensureSession();
            JsonNode data = invokeModel("get-events", Map.of(
                    "from", from.format(API_DATE_TIME), "to", to.format(API_DATE_TIME), "tz", timeZone
            ));
            if (!data.isArray() && !data.path("events").isArray() && !data.path("items").isArray()) {
                throw new CalendarApiException("Calendar web API returned an invalid event-list response");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Could not verify Calendar browser access. Check YANDEX_CALENDAR_COOKIES "
                            + "and Calendar connectivity; refresh the cookies if the session has expired. "
                            + "Startup stopped before any booking attempts.", failure
            );
        }
    }

    @Override
    public boolean booksRoomDuringCreate() {
        return true;
    }

    @Override
    public CreatedEvent createEvent(BookingRequest request) {
        CalendarWebSession session = sessionResolver.ensureSession();
        String roomEmail = RoomResolver.toEmail(request.roomEmail());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", request.meetingName());
        params.put("eventType", "user");
        params.put("description", "");
        params.put("descriptionHtml", "");
        params.put("locationHtml", "");
        params.put("attendees", List.of(roomEmail));
        params.put("optionalAttendees", List.of());
        params.put("attachments", List.of());
        params.put("availability", "busy");
        params.put("participantsCanInvite", true);
        params.put("isAllDay", false);
        params.put("participantsCanEdit", true);
        params.put("visibility", "participants");
        params.put("start", request.start().format(API_DATE_TIME));
        params.put("end", request.end().format(API_DATE_TIME));
        params.put("tz", request.timeZone());
        params.put("externalId", UUID.randomUUID() + "@room-booker");
        params.put("isAutofitOn", false);
        params.put("eventData", Map.of(
                "enableSummarization", false,
                "disallowSummarizationLearning", true,
                "hasAutoBooking", false
        ));
        params.put("marks", Map.of());
        params.put("notifications", List.of(Map.of("channel", "email", "offset", "-15m")));
        if (session.email() != null && !session.email().isBlank()) {
            params.put("organizer", session.email());
        }

        JsonNode data = invokeModel("create-event", params);
        String eventId = responseParser.textOrNumber(data, "showEventId");
        if (eventId == null || eventId.isBlank()) {
            throw new CalendarApiException("Calendar web API returned empty showEventId: " + data);
        }

        log.info(
                "Created calendar event {} via web API for meeting '{}' with room {}",
                eventId,
                request.meetingName(),
                roomEmail
        );
        return CreatedEvent.builder()
                .eventId(eventId)
                .summary(request.meetingName())
                .roomEmail(roomEmail)
                .build();
    }

    @Override
    public void attachRoom(String eventId, String roomId) {
        // Room is already booked in create-event (same as the browser UI).
        log.debug("Skipping attachRoom for event {}: room was booked during create-event", eventId);
    }

    @Override
    public void deleteEvent(String eventId) {
        sessionResolver.ensureSession();
        invokeModel("delete-event", Map.of("id", eventId));
        log.info("Deleted calendar event {} via web API", eventId);
    }

    @Override
    public CreatedEvent updateEventTime(String eventId, LocalDateTime start, LocalDateTime end, String timeZone) {
        sessionResolver.ensureSession();
        JsonNode existing = invokeModel("get-event", Map.of("id", eventId));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = objectMapper.convertValue(existing, Map.class);
        if (params == null) {
            params = new LinkedHashMap<>();
        }
        params.put("id", eventId);
        params.put("start", start.format(API_DATE_TIME));
        params.put("end", end.format(API_DATE_TIME));
        params.put("tz", timeZone);
        invokeModel("update-event", params);
        log.info("Updated calendar event {} via web API to {}–{}", eventId, start, end);
        return CreatedEvent.builder()
                .eventId(eventId)
                .summary(responseParser.textOrNumber(existing, "name"))
                .build();
    }

    @Override
    public List<ExistingEvent> findEvents(LocalDateTime from, LocalDateTime to, String timeZone) {
        sessionResolver.ensureSession();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from.format(API_DATE_TIME));
        params.put("to", to.format(API_DATE_TIME));
        params.put("tz", timeZone);
        JsonNode data = invokeModel("get-events", params);
        JsonNode eventsNode = data.path("events");
        if (!eventsNode.isArray()) {
            eventsNode = data.isArray() ? data : data.path("items");
        }
        if (!eventsNode.isArray()) {
            return List.of();
        }

        List<ExistingEvent> events = new ArrayList<>();
        for (JsonNode event : eventsNode) {
            String eventId = firstNonBlank(
                    responseParser.textOrNumber(event, "showEventId"),
                    responseParser.textOrNumber(event, "id"),
                    responseParser.textOrNumber(event, "eventId")
            );
            String summary = firstNonBlank(
                    responseParser.textOrNumber(event, "name"),
                    responseParser.textOrNumber(event, "summary")
            );
            String startText = firstNonBlank(
                    responseParser.textOrNumber(event, "start"),
                    responseParser.textOrNumber(event.path("start"), "date_time")
            );
            String endText = firstNonBlank(
                    responseParser.textOrNumber(event, "end"),
                    responseParser.textOrNumber(event.path("end"), "date_time")
            );
            if (eventId == null || startText == null || endText == null) {
                continue;
            }
            events.add(ExistingEvent.builder()
                    .eventId(eventId)
                    .summary(summary == null ? "" : summary)
                    .start(LocalDateTime.parse(startText.substring(0, Math.min(19, startText.length()))))
                    .end(LocalDateTime.parse(endText.substring(0, Math.min(19, endText.length()))))
                    .roomReferences(extractAttendees(event))
                    .build());
        }
        return List.copyOf(events);
    }

    @Override
    public List<ExistingEvent> findRoomEvents(
            String roomReference, LocalDateTime from, LocalDateTime to, String timeZone
    ) {
        sessionResolver.ensureSession();
        String roomEmail = RoomResolver.toEmail(roomReference);
        // Maya's ids-only filter preserves event IDs; full shapes can replace them with organizer data.
        JsonNode data = invokeModel("get-availability-intervals", Map.of(
                "emails", List.of(roomEmail),
                "from", from.format(API_DATE_TIME),
                "to", to.format(API_DATE_TIME),
                "tz", timeZone,
                "shape", "ids-only"
        ));
        if (!data.isArray()) {
            throw new CalendarApiException("Calendar web API returned invalid room availability");
        }
        for (JsonNode subject : data) {
            if (!roomEmail.equalsIgnoreCase(subject.path("email").asText())) {
                continue;
            }
            JsonNode intervals = subject.path("intervals");
            if (!intervals.isArray()) {
                throw new CalendarApiException("Calendar web API returned no room availability intervals");
            }
            List<ExistingEvent> events = new ArrayList<>();
            for (JsonNode interval : intervals) {
                JsonNode eventIds = interval.path("eventIds");
                if (!eventIds.isArray()) {
                    throw new CalendarApiException("Calendar web API returned room availability without eventIds");
                }
                // Empty IDs denote temporary reservations, not a confirmed blocking meeting.
                for (JsonNode eventId : eventIds) {
                    if (!eventId.isValueNode() || eventId.isNull() || eventId.asText().isBlank()) {
                        throw new CalendarApiException("Calendar web API returned an invalid availability event ID");
                    }
                    events.add(ExistingEvent.builder()
                            .eventId(eventId.asText())
                            .summary("")
                            .start(CalendarDateTimes.parse(interval.path("start").asText(), timeZone, timeZone))
                            .end(CalendarDateTimes.parse(interval.path("end").asText(), timeZone, timeZone))
                            .roomReferences(List.of(roomEmail))
                            .build());
                }
            }
            if (events.isEmpty()) {
                verifyRoomAvailabilityReadable(roomEmail, from, to, timeZone);
            }
            return List.copyOf(events);
        }
        throw new CalendarApiException("Calendar web API returned no availability for room " + roomEmail);
    }

    private void verifyRoomAvailabilityReadable(
            String roomEmail, LocalDateTime from, LocalDateTime to, String timeZone
    ) {
        // Maya's interval filter replaces permission-denied/unknown-room results with empty intervals.
        JsonNode subjects = invokeModel("get-availabilities", Map.of(
                "emails", List.of(roomEmail),
                "start", from.format(API_DATE_TIME),
                "end", to.format(API_DATE_TIME),
                "tz", timeZone
        ));
        if (subjects.isArray()) {
            for (JsonNode subject : subjects) {
                if (roomEmail.equalsIgnoreCase(subject.path("email").asText())
                        && List.of("available", "busy", "maybe").contains(subject.path("availability").asText())) {
                    // Busy alone can mean a temporary reservation or a booking restriction, not a confirmed meeting.
                    return;
                }
            }
        }
        throw new CalendarApiException("Cannot read availability for room " + roomEmail);
    }

    private List<String> extractAttendees(JsonNode event) {
        JsonNode attendees = event.path("attendees");
        if (!attendees.isArray()) {
            attendees = event.path("resources");
        }
        if (!attendees.isArray()) {
            return List.of();
        }
        List<String> rooms = new ArrayList<>();
        for (JsonNode attendee : attendees) {
            if (attendee.isTextual()) {
                rooms.add(attendee.asText());
            } else {
                String email = firstNonBlank(
                        responseParser.textOrNumber(attendee, "email"),
                        responseParser.textOrNumber(attendee, "login")
                );
                if (email != null) {
                    rooms.add(email);
                }
            }
        }
        return List.copyOf(rooms);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private JsonNode invokeModel(String modelName, Map<String, Object> params) {
        Map<String, Object> body = Map.of(
                "models", List.of(Map.of("name", modelName, "params", params))
        );
        String jsonBody = toJson(body);
        String uri = MODELS_PATH + "?_models=" + modelName;
        log.debug("Calendar web API request: POST {} body={}", uri, jsonBody);

        RestClient.RequestBodySpec request = calendarRestClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json")
                .header("Origin", properties.getWebBaseUrl())
                .header("Referer", properties.getWebBaseUrl() + "/")
                .header("x-yandex-maya-timezone", properties.getTimeZone())
                .header("x-yandex-maya-locale", "ru")
                .header("x-yandex-maya-user-agent", "maya-frontend");

        sessionResolver.applyAuthHeaders(request);

        String responseBody = request
                .body(jsonBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwHttpException)
                .body(String.class);

        return responseParser.parseModelResponse(modelName, responseBody);
    }

    private void throwHttpException(HttpRequest httpRequest, ClientHttpResponse response) {
        try {
            int status = response.getStatusCode().value();
            String body = new String(response.getBody().readAllBytes());
            log.error(
                    "Calendar web API HTTP error: {} {} -> HTTP {}, body={}",
                    httpRequest.getMethod(),
                    httpRequest.getURI(),
                    status,
                    body
            );
            throw new CalendarApiException(
                    "Calendar web API request failed: HTTP %s %s".formatted(status, body),
                    status
            );
        } catch (java.io.IOException exception) {
            throw new CalendarApiException("Calendar web API request failed", exception);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new CalendarApiException("Failed to serialize Calendar web API request", exception);
        }
    }

}
