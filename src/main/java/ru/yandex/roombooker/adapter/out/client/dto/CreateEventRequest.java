package ru.yandex.roombooker.adapter.out.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateEventRequest(
        String summary,
        EventDateTime start,
        EventDateTime end,
        List<EventParticipantRequest> participants,
        EventRules rules
) {
    public record EventDateTime(
            @JsonProperty("date_time") String dateTime,
            @JsonProperty("time_zone") String timeZone
    ) {
    }

    public record EventRules(
            String visibility,
            @JsonProperty("participant_can_invite") boolean participantCanInvite,
            @JsonProperty("participant_can_edit") boolean participantCanEdit
    ) {
        public static EventRules participantsOnly() {
            return new EventRules("PARTICIPANTS", true, true);
        }
    }
}
