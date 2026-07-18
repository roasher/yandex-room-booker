package ru.yandex.roombooker.adapter.out.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses Calendar web {@code /api/models} JSON responses and turns nested UI errors into readable messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "browser")
public class CalendarWebResponseParser {

    private final ObjectMapper objectMapper;

    public JsonNode parseModelResponse(String modelName, @Nullable String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new CalendarApiException("Calendar web API returned empty response for " + modelName);
        }

        JsonNode root = readTree(responseBody);
        JsonNode model = findModel(root, modelName);
        if (model == null) {
            log.error("Calendar web API response missing model '{}': {}", modelName, responseBody);
            throw new CalendarApiException(
                    "Calendar web API response missing model '%s': %s".formatted(modelName, responseBody)
            );
        }

        String modelStatus = textOrDefault(model, "status", "");
        JsonNode data = model.path("data");
        if ("error".equalsIgnoreCase(modelStatus) || isErrorPayload(data)) {
            String details = describeError(model, data);
            log.error("Calendar web API model '{}' failed: {}", modelName, details);
            log.error("Calendar web API full response: {}", responseBody);
            throw new CalendarApiException(
                    "Calendar web API %s failed: %s".formatted(modelName, details)
            );
        }
        if (data.isMissingNode() || data.isNull()) {
            return model;
        }
        return data;
    }

    public JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new CalendarApiException("Calendar web API returned non-JSON body: " + json, exception);
        }
    }

    public @Nullable JsonNode findModel(JsonNode root, String modelName) {
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            return null;
        }
        for (JsonNode model : models) {
            if (modelName.equals(model.path("name").asText())) {
                return model;
            }
        }
        return models.isEmpty() ? null : models.get(0);
    }

    public @Nullable String findText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = textField(node, fieldName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public @Nullable String textOrNumber(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        if (field.isNumber()) {
            return Long.toString(field.asLong());
        }
        return field.asText();
    }

    private boolean isErrorPayload(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return false;
        }
        String status = textOrDefault(data, "status", "");
        return !status.isBlank()
                && !"ok".equalsIgnoreCase(status)
                && !"modified".equalsIgnoreCase(status);
    }

    private String describeError(@Nullable JsonNode model, @Nullable JsonNode data) {
        StringBuilder details = new StringBuilder();
        if (model != null) {
            appendField(details, "modelStatus", textField(model, "status"));
            JsonNode error = model.get("error");
            if (error != null && error.isObject()) {
                appendField(details, "error", textField(error, "name"));
                appendField(details, "code", textField(error, "code"));
                JsonNode readable = error.get("readable");
                if (readable != null && readable.isObject()) {
                    appendField(details, "ru", textField(readable, "ru"));
                    appendField(details, "en", textField(readable, "en"));
                } else if (readable != null && readable.isTextual()) {
                    appendField(details, "readable", readable.asText());
                }
            } else {
                appendField(details, "error", textField(model, "error"));
                appendField(details, "message", textField(model, "message"));
            }
        }
        if (data != null && !data.isMissingNode() && !data.isNull()) {
            appendField(details, "status", textField(data, "status"));
            appendField(details, "resourceEmail", textField(data, "resourceEmail"));
            appendField(details, "instanceStart", textField(data, "instanceStart"));
            appendField(details, "overlapStart", textField(data, "overlapStart"));
            appendField(details, "startTs", textField(data, "startTs"));
            appendField(details, "description", textField(data, "description"));
            appendField(details, "message", textField(data, "message"));
        }
        return details.isEmpty() ? "unknown error" : details.toString();
    }

    private void appendField(StringBuilder target, String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(name).append('=').append(value);
    }

    private String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        String value = textField(node, fieldName);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private @Nullable String textField(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }
}
