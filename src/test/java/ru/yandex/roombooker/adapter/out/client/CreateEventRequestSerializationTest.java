package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventRequest;
import ru.yandex.roombooker.adapter.out.client.dto.EventParticipantRequest;

class CreateEventRequestSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeStartAndEndWithSnakeCaseFieldNames() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Test",
                new CreateEventRequest.EventDateTime("2026-06-22T00:00:00", "Europe/Moscow"),
                new CreateEventRequest.EventDateTime("2026-06-22T01:00:00", "Europe/Moscow"),
                List.of(EventParticipantRequest.attendee("cr_000004198@yandex-team.ru"))
        );

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"date_time\":\"2026-06-22T00:00:00\"");
        assertThat(json).contains("\"time_zone\":\"Europe/Moscow\"");
        assertThat(json).contains("\"participation_type\":\"ATTENDEE\"");
    }
}
