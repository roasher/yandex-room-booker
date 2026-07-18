package ru.yandex.roombooker.adapter.out.client;

import java.time.format.DateTimeFormatter;
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
@ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "browser")
public class CalendarWebApiClient implements CalendarEventGateway {

    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String MODELS_PATH = "/api/models";

    private final RestClient calendarRestClient;
    private final BrowserRoomBookerProperties properties;
    private final ObjectMapper objectMapper;
    private final CalendarWebResponseParser responseParser;
    private final CalendarWebSessionResolver sessionResolver;

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
        params.put("visibility", "everyone");
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
                    "Calendar web API request failed: HTTP %s %s".formatted(status, body)
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
