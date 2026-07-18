package ru.yandex.roombooker.adapter.out.client;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.config.BrowserRoomBookerProperties;

/**
 * Resolves and caches Calendar web session from cookies (ckey/uid scraped or fetched) and applies auth headers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "browser")
public class CalendarWebSessionResolver {

    private static final String MODELS_PATH = "/api/models";
    private static final Pattern CKEY_JSON = Pattern.compile("\"ckey\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CKEY_HEADER = Pattern.compile(
            "x-yandex-maya-ckey[\"'\\s:=]+([A-Za-z0-9+/=!._-]+)"
    );

    private final RestClient calendarRestClient;
    private final BrowserRoomBookerProperties properties;
    private final ObjectMapper objectMapper;
    private final CalendarWebResponseParser responseParser;

    private volatile @Nullable CalendarWebSession cachedSession;

    public CalendarWebSession ensureSession() {
        CalendarWebSession cached = cachedSession;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedSession != null) {
                return cachedSession;
            }
            cachedSession = resolveSession();
            return cachedSession;
        }
    }

    public void applyAuthHeaders(RestClient.RequestBodySpec request) {
        CalendarWebSession session = ensureSession();
        String cookieHeader = properties.resolvedCookieHeader();
        if (cookieHeader.isBlank()) {
            throw new CalendarApiException(
                    "Browser mode requires YANDEX_CALENDAR_COOKIES (full Cookie header from DevTools)"
            );
        }
        request.header("Cookie", cookieHeader);
        if (session.ckey() != null) {
            request.header("x-yandex-maya-ckey", session.ckey());
        }
        if (session.uid() != null) {
            request.header("x-yandex-maya-uid", session.uid());
        }
    }

    private CalendarWebSession resolveSession() {
        if (!properties.hasBrowserCookie()) {
            throw new CalendarApiException(
                    "Browser mode requires YANDEX_CALENDAR_COOKIES (full Cookie header from DevTools)"
            );
        }

        String cookieHeader = properties.resolvedCookieHeader();
        log.info("Resolving calendar web session (ckey/uid) via boot-session-config");
        String bootResponse = postModelsRaw("boot-session-config", Map.of(), cookieHeader, null);
        JsonNode bootRoot = responseParser.readTree(bootResponse);
        String uid = responseParser.textOrNumber(bootRoot, "uid");

        JsonNode bootModel = responseParser.findModel(bootRoot, "boot-session-config");
        JsonNode bootData = bootModel == null ? null : bootModel.path("data");
        String ckey = firstNonBlank(
                bootData == null ? null : responseParser.findText(bootData, "ckey", "csrfToken", "csrf"),
                bootModel == null ? null : responseParser.findText(bootModel, "ckey")
        );
        String email = firstNonBlank(
                bootData == null ? null : responseParser.findText(bootData, "email", "defaultEmail"),
                bootData == null ? null : responseParser.findText(bootData.path("account"), "email"),
                bootData == null ? null : responseParser.findText(bootData.path("user"), "email")
        );

        if (ckey == null) {
            ckey = fetchCkeyFromCalendarPage(cookieHeader);
        }

        if (ckey == null) {
            throw new CalendarApiException(
                    "Could not resolve CSRF ckey from cookies. "
                            + "Ensure YANDEX_CALENDAR_COOKIES is a full Cookie header from an authenticated calendar tab. "
                            + "boot response=" + bootResponse
            );
        }

        log.info("Resolved calendar web session uid={}", uid);
        return new CalendarWebSession(ckey, uid, email);
    }

    private @Nullable String fetchCkeyFromCalendarPage(String cookieHeader) {
        try {
            log.info("Trying to extract ckey from calendar home page");
            String html = calendarRestClient.get()
                    .uri("/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Cookie", cookieHeader)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::throwHttpException)
                    .body(String.class);
            if (html == null || html.isBlank()) {
                return null;
            }
            Matcher matcher = CKEY_JSON.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
            matcher = CKEY_HEADER.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to extract ckey from calendar page: {}", exception.getMessage());
        }
        return null;
    }

    private String postModelsRaw(
            String modelName,
            Map<String, Object> params,
            String cookieHeader,
            @Nullable String ckey
    ) {
        Map<String, Object> body = Map.of(
                "models", List.of(Map.of("name", modelName, "params", params))
        );
        RestClient.RequestBodySpec request = calendarRestClient.post()
                .uri(MODELS_PATH + "?_models=" + modelName)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json")
                .header("Origin", properties.getWebBaseUrl())
                .header("Referer", properties.getWebBaseUrl() + "/")
                .header("Cookie", cookieHeader)
                .header("x-yandex-maya-timezone", properties.getTimeZone())
                .header("x-yandex-maya-locale", "ru")
                .header("x-yandex-maya-user-agent", "maya-frontend");
        if (ckey != null && !ckey.isBlank()) {
            request.header("x-yandex-maya-ckey", ckey);
        }
        String responseBody = request
                .body(toJson(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwHttpException)
                .body(String.class);
        if (responseBody == null || responseBody.isBlank()) {
            throw new CalendarApiException("Calendar web API returned empty response for " + modelName);
        }
        return responseBody;
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

    private @Nullable String firstNonBlank(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
