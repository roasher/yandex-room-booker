package ru.yandex.roombooker.adapter.out.client.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetEventRoomsResponse(
        @JsonProperty("items") @Nullable List<RoomItem> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomItem(
            @JsonProperty("room_id") @Nullable String roomId,
            @JsonProperty("email") @Nullable String email,
            @JsonProperty("name") @Nullable String name
    ) {
    }

    public List<String> roomReferences() {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (RoomItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.roomId() != null && !item.roomId().isBlank()) {
                refs.add(item.roomId());
            } else if (item.email() != null && !item.email().isBlank()) {
                refs.add(item.email());
            }
        }
        return List.copyOf(refs);
    }
}
