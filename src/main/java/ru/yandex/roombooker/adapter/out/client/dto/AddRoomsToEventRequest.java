package ru.yandex.roombooker.adapter.out.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AddRoomsToEventRequest(
        @JsonProperty("room_ids") List<String> roomIds
) {
}
