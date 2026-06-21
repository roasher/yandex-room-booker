package ru.yandex.roombooker.domain;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yandex.roombooker.config.MeetingRoomEntry;
import ru.yandex.roombooker.config.RoomCatalogProperties;

/**
 * Resolves a room identifier from config to a calendar email address.
 */
@Service
@RequiredArgsConstructor
public class RoomResolver {

    private final RoomCatalogProperties roomCatalogProperties;

    public ResolvedRoom resolve(String roomReference) {
        if (roomReference == null || roomReference.isBlank()) {
            throw new IllegalArgumentException("Room reference must not be blank");
        }

        String trimmed = roomReference.trim();
        MeetingRoomEntry catalogEntry = findCatalogEntry(trimmed);
        if (catalogEntry != null) {
            return new ResolvedRoom(
                    trimmed,
                    catalogEntry.displayName(),
                    catalogEntry.exchange(),
                    toEmail(catalogEntry.exchange()),
                    catalogEntry
            );
        }

        String exchange = extractExchange(trimmed);
        for (MeetingRoomEntry room : roomCatalogProperties.rooms().values()) {
            if (room.exchange() != null && room.exchange().equalsIgnoreCase(exchange)) {
                return new ResolvedRoom(
                        null,
                        room.displayName(),
                        room.exchange(),
                        toEmail(room.exchange()),
                        room
                );
            }
        }

        return new ResolvedRoom(null, exchange, exchange, toEmail(exchange), null);
    }

    private MeetingRoomEntry findCatalogEntry(String roomReference) {
        Map<String, MeetingRoomEntry> rooms = roomCatalogProperties.rooms();
        MeetingRoomEntry byId = rooms.get(roomReference);
        if (byId != null) {
            return byId;
        }

        for (Map.Entry<String, MeetingRoomEntry> entry : rooms.entrySet()) {
            MeetingRoomEntry room = entry.getValue();
            if (room.displayName() != null
                    && room.displayName().equalsIgnoreCase(roomReference)) {
                return room;
            }
        }
        return null;
    }

    private static String extractExchange(String roomReference) {
        int atIndex = roomReference.indexOf('@');
        if (atIndex >= 0) {
            return roomReference.substring(0, atIndex);
        }
        return roomReference;
    }

    public static String toEmail(String exchange) {
        if (exchange.contains("@")) {
            return exchange;
        }
        return exchange + "@yandex-team.ru";
    }

    public record ResolvedRoom(
            String catalogId,
            String displayName,
            String exchange,
            String email,
            MeetingRoomEntry catalogEntry
    ) {
    }
}
