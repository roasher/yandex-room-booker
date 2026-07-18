package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.config.BrowserRoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

@ExtendWith(MockitoExtension.class)
class CalendarWebApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private RestClient calendarRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @Mock
    private CalendarWebSessionResolver sessionResolver;

    private CalendarWebApiClient client;

    @BeforeEach
    void setUp() {
        BrowserRoomBookerProperties properties = new BrowserRoomBookerProperties();
        properties.setWebBaseUrl("https://calendar.yandex-team.ru");
        properties.setTimeZone("Europe/Moscow");
        properties.setCookies("Session_id=session-id-value; yandexuid=1");

        lenient().when(sessionResolver.ensureSession())
                .thenReturn(new CalendarWebSession("ckey-value", "1120000000763395", "pavelyurkin@yandex-team.ru"));

        client = new CalendarWebApiClient(
                calendarRestClient,
                properties,
                OBJECT_MAPPER,
                new CalendarWebResponseParser(OBJECT_MAPPER),
                sessionResolver
        );
        stubPostChain();
    }

    @Test
    void shouldCreateEventWithRoomLikeBrowser() {
        when(responseSpec.body(String.class)).thenReturn("""
                {
                  "models": [{
                    "name": "create-event",
                    "status": "ok",
                    "data": {"status": "ok", "showEventId": 42, "sequence": 0}
                  }]
                }
                """);

        CreatedEvent created = client.createEvent(bookingRequest());

        assertThat(created).isEqualTo(CreatedEvent.builder()
                .eventId("42")
                .summary("Focus")
                .roomEmail("conf_st_yoga@yandex-team.ru")
                .build());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("\"start\":\"2026-06-22T14:00:00\"");
        assertThat(bodyCaptor.getValue()).contains("conf_st_yoga@yandex-team.ru");
        assertThat(bodyCaptor.getValue()).contains("\"eventType\":\"user\"");
        assertThat(bodyCaptor.getValue()).contains("pavelyurkin@yandex-team.ru");
    }

    @Test
    void shouldSurfaceTooFarEventReadableMessage() {
        when(responseSpec.body(String.class)).thenReturn("""
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
                      "status": null,
                      "code": "CALENDAR_ERROR"
                    }
                  }]
                }
                """);

        assertThatThrownBy(() -> client.createEvent(bookingRequest()))
                .isInstanceOf(CalendarApiException.class)
                .hasMessageContaining("too-far-event")
                .hasMessageContaining("Переговорка «2A.Йога» бронируема до 2026-07-21 15:37")
                .hasMessageContaining("Room «2A.Yoga» can be booked up to 2026-07-21 15:37");
    }

    @Test
    void shouldBooksRoomDuringCreate() {
        assertThat(client.booksRoomDuringCreate()).isTrue();
    }

    private BookingRequest bookingRequest() {
        return BookingRequest.builder()
                .meetingName("Focus")
                .roomEmail("conf_st_yoga")
                .start(LocalDateTime.parse("2026-06-22T14:00:00"))
                .end(LocalDateTime.parse("2026-06-22T15:30:00"))
                .timeZone("Europe/Moscow")
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubPostChain() {
        lenient().when(calendarRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.onStatus(any(Predicate.class), any())).thenReturn(responseSpec);
    }
}
