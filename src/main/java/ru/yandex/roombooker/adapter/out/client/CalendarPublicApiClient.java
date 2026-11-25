package ru.yandex.roombooker.adapter.out.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import ru.yandex.roombooker.adapter.out.client.dto.AddRoomsToEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventResponse;
import ru.yandex.roombooker.adapter.out.client.dto.GetEventRoomsResponse;
import ru.yandex.roombooker.adapter.out.client.dto.GetEventsResponse;
import ru.yandex.roombooker.adapter.out.client.dto.PatchEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.PatchEventResponse;
import ru.yandex.roombooker.config.ApiRoomBookerProperties;
import ru.yandex.roombooker.domain.CalendarEventGateway;
import ru.yandex.roombooker.domain.RoomResolver;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;
import ru.yandex.roombooker.domain.model.ExistingEvent;

/**
 * Client for Yandex Calendar Public API (cloud-api.yandex.net).
 *
 * @see <a href="https://docs.yandex-team.ru/calendar-api">API Календаря</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "room-booker-settings", name = "booking-mode", havingValue = "api", matchIfMissing = true
)
public class CalendarPublicApiClient implements CalendarEventGateway {

    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient calendarRestClient;
    private final ApiRoomBookerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void verifyAccess(LocalDateTime from, LocalDateTime to, String timeZone) {
        String eventsUri = UriComponentsBuilder.fromPath("/v1/calendar/events")
                .queryParam("from", toQueryDateTime(from, ZoneId.of(timeZone)))
                .queryParam("to", toQueryDateTime(to, ZoneId.of(timeZone)))
                .queryParam("time_zone", timeZone)
                .queryParam("limit", 1)
                .build().encode().toUriString();
        verifyReadAccess(eventsUri, false);
        log.info("OAuth token accepted by Calendar events API; checking room-directory access");
        verifyReadAccess("/v1/calendar/rooms?limit=1", true);
    }

    private void verifyReadAccess(String uri, boolean tokenAccepted) {
        try {
            pageItems(getJson(resolveOAuthToken(), uri, JsonNode.class));
        } catch (CalendarApiException failure) {
            Integer status = failure.httpStatus();
            String message;
            if (Integer.valueOf(401).equals(status) && !tokenAccepted) {
                message = "OAuth token was rejected (HTTP 401). It may be expired, revoked or invalid. "
                        + "Update YANDEX_CALENDAR_OAUTH_TOKEN and restart.";
            } else if (tokenAccepted && Integer.valueOf(401).equals(status)) {
                message = "OAuth token was accepted by Calendar events API, but room-directory access was rejected "
                        + "(HTTP 401). The public room directory excludes yandex-team accounts at the API gateway; "
                        + "adding OAuth scopes or regenerating the token cannot remove that restriction. "
                        + "For corporate rooms, set ROOM_BOOKER_BOOKING_MODE=browser and configure "
                        + "YANDEX_CALENDAR_COOKIES from an authenticated calendar.yandex-team.ru session. "
                        + "For other account types, check room-directory endpoint access. "
                        + "This does not establish that the token has expired.";
            } else if (tokenAccepted && Integer.valueOf(403).equals(status)) {
                message = "OAuth token was accepted by Calendar events API, but room-directory access was rejected "
                        + "(HTTP " + status + "). Check endpoint availability and calendar:resources.read "
                        + "or calendar:read_all permissions; this does not establish that the token has expired.";
            } else if (Integer.valueOf(403).equals(status)) {
                message = "Calendar read access was denied (HTTP 403). Check the OAuth token's Calendar permissions.";
            } else {
                message = "Calendar startup access check failed" + (status == null ? "" : " (HTTP " + status + ")")
                        + ". Token validity could not be confirmed; check Calendar connectivity and configuration.";
            }
            throw new IllegalStateException(message + " Startup stopped before any booking attempts.", failure);
        } catch (RestClientException failure) {
            throw new IllegalStateException(
                    "Could not contact Calendar to verify access. Token validity could not be confirmed. "
                            + "Startup stopped before any booking attempts.", failure
            );
        }
    }

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

    @Override
    public void deleteEvent(String eventId) {
        log.debug("Calendar API request: DELETE /v1/calendar/events/{}", eventId);
        calendarRestClient.delete()
                .uri("/v1/calendar/events/" + eventId)
                .header("Authorization", "OAuth " + resolveOAuthToken())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .toBodilessEntity();
        log.info("Deleted calendar event {}", eventId);
    }

    @Override
    public CreatedEvent updateEventTime(String eventId, LocalDateTime start, LocalDateTime end, String timeZone) {
        PatchEventResponse updated = patchJson(
                resolveOAuthToken(),
                "/v1/calendar/events/" + eventId,
                new PatchEventRequest(toEventDateTime(start, timeZone), toEventDateTime(end, timeZone)),
                PatchEventResponse.class
        );
        log.info("Updated calendar event {} time to {}–{}", eventId, start, end);
        return CreatedEvent.builder()
                .eventId(updated != null && updated.eventId() != null ? updated.eventId() : eventId)
                .summary(updated != null ? updated.summary() : null)
                .build();
    }

    @Override
    public List<ExistingEvent> findEvents(LocalDateTime from, LocalDateTime to, String timeZone) {
        ZoneId zoneId = ZoneId.of(timeZone);
        String fromParam = toQueryDateTime(from, zoneId);
        String toParam = toQueryDateTime(to, zoneId);
        log.debug(
                "Calendar API request: GET /v1/calendar/events?from={}&to={}&time_zone={}",
                fromParam,
                toParam,
                timeZone
        );

        GetEventsResponse response = calendarRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/calendar/events")
                        .queryParam("from", fromParam)
                        .queryParam("to", toParam)
                        .queryParam("time_zone", timeZone)
                        .build())
                .header("Authorization", "OAuth " + resolveOAuthToken())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .body(GetEventsResponse.class);
        if (response == null || response.items() == null) {
            return List.of();
        }

        List<ExistingEvent> events = new ArrayList<>();
        for (GetEventsResponse.EventItem item : response.items()) {
            if (item.eventId() == null || item.start() == null || item.end() == null) {
                continue;
            }
            List<String> rooms = listRoomIds(item.eventId());
            events.add(ExistingEvent.builder()
                    .eventId(item.eventId())
                    .summary(item.summary() == null ? "" : item.summary())
                    .start(parseApiDateTime(item.start().dateTime()))
                    .end(parseApiDateTime(item.end().dateTime()))
                    .roomReferences(rooms)
                    .build());
        }
        return List.copyOf(events);
    }

    private List<String> listRoomIds(String eventId) {
        try {
            GetEventRoomsResponse rooms = getJson(
                    resolveOAuthToken(),
                    "/v1/calendar/events/" + eventId + "/rooms",
                    GetEventRoomsResponse.class
            );
            if (rooms == null) {
                return List.of();
            }
            return rooms.roomReferences();
        } catch (RuntimeException exception) {
            log.warn("Could not load rooms for event {}: {}", eventId, exception.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ExistingEvent> findRoomEvents(
            String roomReference, LocalDateTime from, LocalDateTime to, String timeZone
    ) {
        String roomId = resolvePublicRoomId(roomReference);
        Map<String, String> request = Map.of(
                "room_id", roomId,
                "from", toQueryDateTime(from, ZoneId.of(timeZone)),
                "to", toQueryDateTime(to, ZoneId.of(timeZone))
        );
        List<ExistingEvent> events = new ArrayList<>();
        String cursor = "";
        Set<String> seenCursors = new HashSet<>();
        do {
            JsonNode page = postJson(resolveOAuthToken(),
                    pageUri("/v1/calendar/free-busy/rooms/search", cursor), request, JsonNode.class);
            for (JsonNode item : pageItems(page)) {
                String eventId = item.path("event_id").asText("");
                // Reservations without event_id are temporary holds, not confirmed meetings.
                if (eventId.isBlank()) {
                    continue;
                }
                events.add(ExistingEvent.builder()
                        .eventId(eventId)
                        .summary("")
                        .start(CalendarDateTimes.parse(item.path("start"), timeZone))
                        .end(CalendarDateTimes.parse(item.path("end"), timeZone))
                        .roomReferences(List.of(roomReference))
                        .build());
            }
            cursor = nextCursor(page, seenCursors);
        } while (!cursor.isEmpty());
        return List.copyOf(events);
    }

    private String resolvePublicRoomId(String roomReference) {
        try {
            return UUID.fromString(roomReference).toString();
        } catch (IllegalArgumentException notPublicId) {
            // Configuration normally contains an Exchange name; the availability API requires a public UUID.
        }
        String roomEmail = RoomResolver.toEmail(roomReference);
        String cursor = "";
        Set<String> seenCursors = new HashSet<>();
        do {
            JsonNode page = getJson(resolveOAuthToken(), pageUri("/v1/calendar/rooms", cursor), JsonNode.class);
            for (JsonNode room : pageItems(page)) {
                if (roomEmail.equalsIgnoreCase(room.path("email").asText()) && room.hasNonNull("room_id")) {
                    return room.path("room_id").asText();
                }
            }
            cursor = nextCursor(page, seenCursors);
        } while (!cursor.isEmpty());
        throw new CalendarApiException("Cannot check availability: no public room ID found for " + roomReference);
    }

    private static String pageUri(String path, String cursor) {
        return cursor.isEmpty() ? path : UriComponentsBuilder.fromPath(path)
                .queryParam("iteration_key", "{cursor}").encode().buildAndExpand(cursor).toUriString();
    }

    private static JsonNode pageItems(@Nullable JsonNode page) {
        if (page == null || !page.path("items").isArray()) {
            throw new CalendarApiException("Calendar API returned an invalid room availability/list response");
        }
        return page.path("items");
    }

    private static String nextCursor(JsonNode page, Set<String> seenCursors) {
        String cursor = page.path("iteration_key").asText("");
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            throw new CalendarApiException("Calendar API returned a repeated pagination cursor");
        }
        return cursor;
    }

    private CreateEventRequest buildCreateEventRequest(BookingRequest request) {
        return new CreateEventRequest(
                request.meetingName(),
                toEventDateTime(request.start(), request.timeZone()),
                toEventDateTime(request.end(), request.timeZone()),
                null,
                CreateEventRequest.EventRules.participantsOnly()
        );
    }

    private CreateEventRequest.EventDateTime toEventDateTime(LocalDateTime dateTime, String timeZone) {
        return new CreateEventRequest.EventDateTime(dateTime.format(API_DATE_TIME), timeZone);
    }

    private static String toQueryDateTime(LocalDateTime dateTime, ZoneId zoneId) {
        // Use UTC Instant (…Z). Offset form with '+' breaks in query strings (+ means space).
        return ZonedDateTime.of(dateTime, zoneId).toInstant().toString();
    }

    private static LocalDateTime parseApiDateTime(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            throw new CalendarApiException("Calendar API event is missing date_time");
        }
        if (dateTime.length() > 19) {
            return LocalDateTime.parse(dateTime.substring(0, 19), API_DATE_TIME);
        }
        return LocalDateTime.parse(dateTime, API_DATE_TIME);
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

    private <T> T getJson(String oauthToken, String uri, Class<T> responseType) {
        log.debug("Calendar API request: GET {}", uri);
        return calendarRestClient.get()
                .uri(URI.create(uri))
                .header("Authorization", "OAuth " + oauthToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException)
                .body(responseType);
    }

    private <T> T postJson(String oauthToken, String uri, Object body, Class<T> responseType) {
        log.debug("Calendar API request: POST {} body={}", uri, toJson(body));

        T response = jsonRequest(
                calendarRestClient.post()
                        .uri(URI.create(uri))
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
        patchJson(oauthToken, uri, body, Void.class);
    }

    private <T> T patchJson(String oauthToken, String uri, Object body, Class<T> responseType) {
        log.debug("Calendar API request: PATCH {} body={}", uri, toJson(body));

        RestClient.ResponseSpec responseSpec = jsonRequest(
                calendarRestClient.patch()
                        .uri(uri)
                        .header("Authorization", "OAuth " + oauthToken),
                body
        )
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwApiException);

        if (responseType == Void.class || responseType == void.class) {
            responseSpec.toBodilessEntity();
            return null;
        }
        return responseSpec.body(responseType);
    }

    private void throwApiException(org.springframework.http.HttpRequest httpRequest,
                                   org.springframework.http.client.ClientHttpResponse response) {
        try {
            int status = response.getStatusCode().value();
            String body = new String(response.getBody().readAllBytes());
            logApiError(httpRequest, status, response.getHeaders(), body);
            throw new CalendarApiException(
                    "Calendar API request failed: HTTP %s %s".formatted(status, body),
                    status
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
        boolean expectedConflict = CalendarApiException.looksLikeRoomBusy(body)
                || CalendarApiException.looksLikeNotYetBookable(body);
        if (expectedConflict) {
            log.warn(
                    "Calendar API: {} for {} {} (HTTP {}): {}",
                    CalendarApiException.looksLikeRoomBusy(body) ? "room busy" : "not yet bookable",
                    httpRequest.getMethod(),
                    httpRequest.getURI(),
                    status,
                    summarizeErrorBody(body)
            );
            return;
        }
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

    private String summarizeErrorBody(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode details = root.get("details");
            if (details != null && details.hasNonNull("upstream_message")) {
                return details.get("upstream_message").asText();
            }
            String message = textField(root, "message");
            return message != null ? message : body;
        } catch (JsonProcessingException exception) {
            return body;
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
                "OAuth token is not configured. Set YANDEX_CALENDAR_OAUTH_TOKEN or room-booker-settings.oauth-token"
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
