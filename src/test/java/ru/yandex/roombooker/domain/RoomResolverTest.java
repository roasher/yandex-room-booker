package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.config.RoomCatalogProperties;

class RoomResolverTest {

    private RoomResolver roomResolver;

    @BeforeEach
    void setUp() {
        RoomCatalogProperties properties = new RoomCatalogProperties();
        properties.setRooms(Map.of(
                "cr-4198",
                new MeetingRoomEntry(
                        "Test room",
                        "cr_000004198",
                        "Aurora",
                        "90m",
                        "3d"
                )
        ));
        roomResolver = new RoomResolver(properties);
    }

    @Test
    void resolvesCatalogId() {
        RoomResolver.ResolvedRoom room = roomResolver.resolve("cr-4198");

        assertThat(room.catalogId()).isEqualTo("cr-4198");
        assertThat(room.email()).isEqualTo("cr_000004198@yandex-team.ru");
    }

    @Test
    void resolvesExchangeName() {
        RoomResolver.ResolvedRoom room = roomResolver.resolve("cr_000004198");

        assertThat(room.email()).isEqualTo("cr_000004198@yandex-team.ru");
        assertThat(room.catalogEntry()).isNotNull();
    }

    @Test
    void resolvesDisplayName() {
        RoomResolver.ResolvedRoom room = roomResolver.resolve("Test room");

        assertThat(room.email()).isEqualTo("cr_000004198@yandex-team.ru");
        assertThat(room.catalogEntry()).isNotNull();
    }

    @Test
    void resolvesFullEmailWithoutCatalogEntry() {
        RoomResolver.ResolvedRoom room = roomResolver.resolve("unknown@yandex-team.ru");

        assertThat(room.email()).isEqualTo("unknown@yandex-team.ru");
        assertThat(room.catalogEntry()).isNull();
    }

    @Test
    void rejectsBlankReference() {
        assertThatThrownBy(() -> roomResolver.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
