package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CalendarWebResponseParserTest {

    private CalendarWebResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new CalendarWebResponseParser(new ObjectMapper());
    }

    @Test
    void shouldReturnModelDataOnSuccess() {
        JsonNode data = parser.parseModelResponse("create-event", """
                {
                  "models": [{
                    "name": "create-event",
                    "status": "ok",
                    "data": {"status": "ok", "showEventId": 42}
                  }]
                }
                """);

        assertThat(parser.textOrNumber(data, "showEventId")).isEqualTo("42");
    }

    @Test
    void shouldSurfaceNestedReadableError() {
        assertThatThrownBy(() -> parser.parseModelResponse("create-event", """
                {
                  "models": [{
                    "name": "create-event",
                    "status": "error",
                    "error": {
                      "name": "too-far-event",
                      "readable": {
                        "ru": "Переговорка «2A.Йога» бронируема до 2026-07-21 15:37",
                        "en": "Room «2A.Yoga» can be booked up to 2026-07-21 15:37"
                      },
                      "code": "CALENDAR_ERROR"
                    }
                  }]
                }
                """))
                .isInstanceOf(CalendarApiException.class)
                .hasMessageContaining("too-far-event")
                .hasMessageContaining("Переговорка «2A.Йога» бронируема до 2026-07-21 15:37")
                .hasMessageContaining("Room «2A.Yoga» can be booked up to 2026-07-21 15:37");
    }
}
