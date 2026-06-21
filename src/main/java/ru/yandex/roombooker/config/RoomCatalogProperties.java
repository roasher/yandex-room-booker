package ru.yandex.roombooker.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local catalog of frequently used meeting rooms.
 *
 * @see rooms.yml
 */
@ConfigurationProperties(prefix = "room-catalog")
public class RoomCatalogProperties {

    private Map<String, MeetingRoomEntry> rooms = new LinkedHashMap<>();

    public Map<String, MeetingRoomEntry> rooms() {
        return rooms;
    }

    public void setRooms(Map<String, MeetingRoomEntry> rooms) {
        this.rooms = rooms == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rooms);
    }
}
