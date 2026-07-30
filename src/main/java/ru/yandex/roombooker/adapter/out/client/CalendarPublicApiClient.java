package ru.yandex.roombooker.adapter.out.client;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.adapter.out.client.dto.AddRoomsToEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventResponse;
import ru.yandex.roombooker.config.ApiRoomBookerProperties;
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
@ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "api", matchIfMissing = true)
public class CalendarPublicApiClient implements CalendarEventGateway {

    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient calendarRestClient;
    private final ApiRoomBookerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public CreatedEvent createEvent(BookingRequest request) {
        CreateEventResponse created = postJson(
                resolveOAuthToken(),
                "/v1/calendar/events",
                buildCreateEventRequest(request),
                CreateEventResponse.class
        );

        if (created.eventId() == null || created.eventId().isBlank()) {
            throw new CalendarApiException("Calendar API returned empty event_id");
        }

        log.info("Created calendar event {} for meeting '{}'", created.eventId(), request.meetingName());
        return CreatedEvent.builder()
                .eventId(created.eventId())
                .summary(request.meetingName())
                .build();
    }

    @Override
    public void attachRoom(String eventId, String roomId) {
        String normalizedRoomId = normalizeRoomId(roomId);
        patchJson(
                resolveOAuthToken(),
                "/v1/calendar/events/" + eventId + "/rooms",
                new AddRoomsToEventRequest(List.of(normalizedRoomId))
        );
        log.info("Attached room {} to event {}", normalizedRoomId, eventId);
    }

    private CreateEventRequest buildCreateEventRequest(BookingRequest request) {
        return new CreateEventRequest(
                request.meetingName(),
                toEventDateTime(request.start(), request.timeZone()),
                toEventDateTime(request.end(), request.timeZone()),
                null
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

    /**
     * Writes JSON via streaming body so Content-Type stays {@code application/json}.
     * Avoids String→text/plain and byte[]→octet-stream converter defaults.
     */
    private RestClient.RequestBodySpec jsonRequest(RestClient.RequestBodySpec request, Object body) {
        byte[] jsonBytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        return request
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(jsonBytes.length)
                .body(outputStream -> outputStream.write(jsonBytes));
    }

    private <T> T postJson(String oauthToken, String uri, Object body, Class<T> responseType) {
        log.debug("Calendar API request: POST {} body={}", uri, toJson(body));

        T response = jsonRequest(
                calendarRestClient.post()
                        .uri(uri)
                        .header("Authorization", "OAuth " + oauthToken),
                body
        )
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .body(responseType);

        if (response == null) {
            throw new CalendarApiException("Calendar API returned empty response for " + uri);
        }
        return response;
    }

    private void patchJson(String oauthToken, String uri, Object body) {
        log.debug("Calendar API request: PATCH {} body={}", uri, toJson(body));

        jsonRequest(
                calendarRestClient.patch()
                        .uri(uri)
                        .header("Authorization", "OAuth " + oauthToken),
                body
        )
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .toBodilessEntity();
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

    private String textField(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }

    private String resolveOAuthToken() {
        if (properties.getOauthToken() != null && !properties.getOauthToken().isBlank()) {
            return properties.getOauthToken();
        }
        throw new CalendarApiException(
                "OAuth token is not configured. Set YANDEX_CALENDAR_OAUTH_TOKEN or room-booker.oauth-token"
        );
    }

    String normalizeRoomId(String roomReference) {
        int atIndex = roomReference.indexOf('@');
        if (atIndex >= 0) {
            return roomReference.substring(0, atIndex);
        }
        return roomReference;
    }

    String normalizeRoomEmail(String roomId) {
        if (roomId.contains("@")) {
            return roomId;
        }
        return roomId + "@yandex-team.ru";
    }
}
