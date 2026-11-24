package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import ru.yandex.roombooker.domain.model.BookingPlan;
import ru.yandex.roombooker.domain.model.BookingSlot;

class ApplicationConfigTest {

    private final ApplicationConfig config = new ApplicationConfig();

    @ParameterizedTest
    @ValueSource(strings = {"api", "browser"})
    void shouldSelectTransportFromSharedSettings(String mode) {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(
                        "room-booker-settings.booking-mode=" + mode,
                        "room-booker-settings.time-zone=Europe/Moscow",
                        "room-booker-settings.api-base-url=http://localhost",
                        "room-booker-settings.web-base-url=http://localhost",
                        "room-booker-settings.oauth-token=test-token",
                        "room-booker-settings.cookies=Session_id=test"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoomBookerProperties settings = context.getBean(RoomBookerProperties.class);
                    assertThat(settings.bookingMode()).isEqualTo(mode);
                    settings.validateAuth();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Moscow", "Asia/Bangkok"})
    void shouldUseConfiguredTimeZoneForTheClockSharedByLadderAndWaiter(String timeZone) {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(
                        "room-booker-settings.time-zone=" + timeZone,
                        "room-booker-settings.api-base-url=http://localhost"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of(timeZone));
                });
    }

    @Test
    void shouldBindRoomBookerAsAnArray() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("room-booker[0].enabled", "true")
                .withProperty("room-booker[0].meeting-name", "Yoga")
                .withProperty("room-booker[0].room", "yoga-room")
                .withProperty("room-booker[0].duration", "1h")
                .withProperty("room-booker[0].slots[0].day-of-week", "1")
                .withProperty("room-booker[0].slots[0].time", "14:00")
                .withProperty("room-booker[1].enabled", "false");
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

        assertThat(config.bookingPlansProperties(environment).resolve(clock, "Europe/Moscow")).containsExactly(
                new BookingPlan("Yoga", "yoga-room", List.of(new BookingSlot(
                        LocalDateTime.parse("2026-10-05T14:00"), LocalDateTime.parse("2026-10-05T15:00")
                )))
        );
    }

    @Test
    void shouldRejectOldObjectConfigurationInsteadOfSilentlyIgnoringIt() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("room-booker.enabled", "true")
                .withProperty("room-booker.room", "room");

        assertThatThrownBy(() -> config.bookingPlansProperties(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void shouldRejectRoomsArrayInsideAnEntry() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("room-booker[0].enabled", "true")
                .withProperty("room-booker[0].rooms[0]", "room");

        assertThatThrownBy(() -> config.bookingPlansProperties(environment)).isInstanceOf(BindException.class);
    }
}
