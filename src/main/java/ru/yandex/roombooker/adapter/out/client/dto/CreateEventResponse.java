package ru.yandex.roombooker.adapter.out.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateEventResponse(@JsonProperty("event_id") String eventId) {
}
