package ru.yandex.roombooker.adapter.out.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EventParticipantRequest(
        String email,
        @JsonProperty("participation_type") String participationType
) {
    public static EventParticipantRequest attendee(String email) {
        return new EventParticipantRequest(email, "ATTENDEE");
    }
}
