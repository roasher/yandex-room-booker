package ru.yandex.roombooker.adapter.out.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetEventsResponse(
        List<EventItem> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventItem(
            @JsonProperty("event_id") String eventId,
            String summary,
            CreateEventRequest.EventDateTime start,
            CreateEventRequest.EventDateTime end
    ) {
    }
}
