package ru.yandex.roombooker.adapter.out.client;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventResponse;
import ru.yandex.roombooker.adapter.out.client.dto.EventParticipantRequest;
import ru.yandex.roombooker.config.RoomBookerProperties;
import ru.yandex.roombooker.domain.CalendarEventGateway;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

/**
 * Client for Yandex Calendar Public API (cloud-api.yandex.net).
 *
 * @see <a href="https://docs.yandex-team.ru/calendar-api">API Календаря</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarPublicApiClient implements CalendarEventGateway {

    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient calendarRestClient;
    private final RoomBookerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public CreatedEvent createMeetingWithRoom(BookingRequest request) {
        String oauthToken = resolveOAuthToken();
        String roomEmail = normalizeRoomEmail(request.roomEmail());

        CreateEventResponse created = postJson(
                oauthToken,
                "/v1/calendar/events",
                toJson(buildCreateEventRequest(request, roomEmail)),
                CreateEventResponse.class
        );

        if (created.eventId() == null || created.eventId().isBlank()) {
            throw new CalendarApiException("Calendar API returned empty event_id");
        }

        log.info("Created calendar event {} with room {}", created.eventId(), roomEmail);
        return CreatedEvent.builder()
                .eventId(created.eventId())
                .summary(request.meetingName())
                .roomEmail(roomEmail)
                .build();
    }

    private CreateEventRequest buildCreateEventRequest(BookingRequest request, String roomEmail) {
        return new CreateEventRequest(
                request.meetingName(),
                toEventDateTime(request.start(), request.timeZone()),
                toEventDateTime(request.end(), request.timeZone()),
                List.of(EventParticipantRequest.attendee(roomEmail))
        );
    }

    private CreateEventRequest.EventDateTime toEventDateTime(LocalDateTime dateTime, String timeZone) {
        return new CreateEventRequest.EventDateTime(dateTime.format(API_DATE_TIME), timeZone);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new CalendarApiException("Failed to serialize Calendar API request", exception);
        }
    }

    private <T> T postJson(String oauthToken, String uri, String jsonBody, Class<T> responseType) {
        log.debug("Calendar API request: POST {} body={}", uri, jsonBody);

        T response = calendarRestClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "OAuth " + oauthToken)
                .body(jsonBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .body(responseType);

        if (response == null) {
            throw new CalendarApiException("Calendar API returned empty response for " + uri);
        }
        return response;
    }

    private void throwApiException(org.springframework.http.HttpRequest httpRequest,
                                   org.springframework.http.client.ClientHttpResponse response) {
        try {
            int status = response.getStatusCode().value();
            String body = new String(response.getBody().readAllBytes());
            logApiError(httpRequest, status, response.getHeaders(), body);
            throw new CalendarApiException(
                    "Calendar API request failed: HTTP %s %s".formatted(status, body)
            );
        } catch (java.io.IOException exception) {
            log.error(
                    "Calendar API request failed: {} {} (could not read response body)",
                    httpRequest.getMethod(),
                    httpRequest.getURI(),
                    exception
            );
            throw new CalendarApiException("Calendar API request failed", exception);
        }
    }

    private void logApiError(org.springframework.http.HttpRequest httpRequest,
                             int status,
                             org.springframework.http.HttpHeaders responseHeaders,
                             String body) {
        log.error(
                "Calendar API error: {} {} -> HTTP {}, response headers={}, body={}",
                httpRequest.getMethod(),
                httpRequest.getURI(),
                status,
                responseHeaders,
                body
        );
        if (body == null || body.isBlank()) {
            log.error("Calendar API returned empty error body for HTTP {}", status);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            log.error(
                    "Calendar API error details: error={}, description={}, message={}",
                    textField(root, "error"),
                    textField(root, "description"),
                    textField(root, "message")
            );
        } catch (JsonProcessingException exception) {
            log.error("Calendar API error body is not JSON: {}", body);
        }
    }

    private static String textField(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }

    private String resolveOAuthToken() {
        if (properties.oauthToken() != null && !properties.oauthToken().isBlank()) {
            return properties.oauthToken();
        }
        throw new CalendarApiException(
                "OAuth token is not configured. Set YANDEX_CALENDAR_OAUTH_TOKEN or room-booker.oauth-token"
        );
    }

    static String normalizeRoomEmail(String roomEmail) {
        if (roomEmail.contains("@")) {
            return roomEmail;
        }
        return roomEmail + "@yandex-team.ru";
    }
}
