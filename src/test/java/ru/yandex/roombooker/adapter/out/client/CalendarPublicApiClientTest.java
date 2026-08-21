package ru.yandex.roombooker.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.adapter.out.client.dto.CreateEventResponse;
import ru.yandex.roombooker.config.ApiRoomBookerProperties;
import ru.yandex.roombooker.domain.model.BookingRequest;
import ru.yandex.roombooker.domain.model.CreatedEvent;

@ExtendWith(MockitoExtension.class)
class CalendarPublicApiClientTest {

    @Mock
    private RestClient calendarRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private CalendarPublicApiClient client;

    @BeforeEach
    void setUp() {
        ApiRoomBookerProperties properties = new ApiRoomBookerProperties();
        properties.setOauthToken("token");
        properties.setApiBaseUrl("https://cloud-api.yandex.net");
        client = new CalendarPublicApiClient(calendarRestClient, properties, new ObjectMapper());
        stubRequestChain();
    }

    @Test
    void shouldAppendCorpDomainWhenRoomIdHasNoAtSign() {
        assertThat(client.normalizeRoomEmail("cr_000004170"))
                .isEqualTo("cr_000004170@yandex-team.ru");
    }

    @Test
    void shouldKeepFullRoomEmailUntouched() {
        assertThat(client.normalizeRoomEmail("conf_rr_3_1@yandex-team.ru"))
                .isEqualTo("conf_rr_3_1@yandex-team.ru");
    }

    @Test
    void shouldExtractRoomIdFromEmail() {
        assertThat(client.normalizeRoomId("conf_st_yoga@yandex-team.ru"))
                .isEqualTo("conf_st_yoga");
    }

    @Test
    void shouldKeepBareExchangeAsRoomId() {
        assertThat(client.normalizeRoomId("conf_st_yoga"))
                .isEqualTo("conf_st_yoga");
    }

    @Test
    void shouldAttachRoomAsStreamingJsonBodyWithApplicationJson() {
        client.attachRoom("event-1", "conf_st_yoga@yandex-team.ru");

        ArgumentCaptor<org.springframework.http.StreamingHttpOutputMessage.Body> bodyCaptor =
                ArgumentCaptor.forClass(org.springframework.http.StreamingHttpOutputMessage.Body.class);
        verify(requestBodySpec).contentType(MediaType.APPLICATION_JSON);
        verify(requestBodySpec).body(bodyCaptor.capture());

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            bodyCaptor.getValue().writeTo(buffer);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"items\":[\"conf_st_yoga\"]}");
    }

    @Test
    void shouldCreateEventWithParticipantsOnlyVisibility() {
        when(responseSpec.body(eq(CreateEventResponse.class)))
                .thenReturn(new CreateEventResponse("event-42"));

        CreatedEvent created = client.createEvent(BookingRequest.builder()
                .meetingName("Focus")
                .roomEmail("conf_st_yoga")
                .start(LocalDateTime.parse("2026-06-22T14:00:00"))
                .end(LocalDateTime.parse("2026-06-22T15:30:00"))
                .timeZone("Europe/Moscow")
                .build());

        assertThat(created).isEqualTo(CreatedEvent.builder()
                .eventId("event-42")
                .summary("Focus")
                .build());

        ArgumentCaptor<org.springframework.http.StreamingHttpOutputMessage.Body> bodyCaptor =
                ArgumentCaptor.forClass(org.springframework.http.StreamingHttpOutputMessage.Body.class);
        verify(calendarRestClient).post();
        verify(requestBodySpec).body(bodyCaptor.capture());

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            bodyCaptor.getValue().writeTo(buffer);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .contains("\"visibility\":\"PARTICIPANTS\"")
                .contains("\"participant_can_invite\":true")
                .contains("\"participant_can_edit\":true");
    }

    @SuppressWarnings("unchecked")
    private void stubRequestChain() {
        lenient().when(calendarRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(calendarRestClient.patch()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentLength(any(Long.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(org.springframework.http.StreamingHttpOutputMessage.Body.class)))
                .thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.onStatus(any(Predicate.class), any())).thenReturn(responseSpec);
        lenient().when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.noContent().build());
    }
}
