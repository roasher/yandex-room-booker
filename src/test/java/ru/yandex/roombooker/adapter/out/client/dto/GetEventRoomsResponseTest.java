package ru.yandex.roombooker.adapter.out.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GetEventRoomsResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseRoomObjectsFromApi() throws Exception {
        String json = """
                {
                  "limit": 1,
                  "items": [{
                    "room_id": "conf_st_yoga",
                    "name": "2A.Йога",
                    "email": "conf_st_yoga@yandex-team.ru"
                  }]
                }
                """;

        GetEventRoomsResponse response = objectMapper.readValue(json, GetEventRoomsResponse.class);

        assertThat(response.roomReferences()).containsExactly("conf_st_yoga");
    }

    @Test
    void shouldFallBackToEmailWhenRoomIdMissing() throws Exception {
        String json = """
                {"items":[{"email":"conf_st_yoga@yandex-team.ru"}]}
                """;

        GetEventRoomsResponse response = objectMapper.readValue(json, GetEventRoomsResponse.class);

        assertThat(response.roomReferences()).containsExactly("conf_st_yoga@yandex-team.ru");
    }
}
