package ru.yandex.roombooker.domain.model;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;

/**
 * An existing calendar event discovered for resume after restart.
 */
@Accessors(chain = true)
@Builder
public record ExistingEvent(
        String eventId,
        String summary,
        LocalDateTime start,
        LocalDateTime end,
        List<String> roomReferences
) {
    public ExistingEvent {
        roomReferences = roomReferences == null ? List.of() : List.copyOf(roomReferences);
    }

    public boolean hasRoom(@Nullable String roomReference) {
        if (roomReference == null || roomReference.isBlank()) {
            return false;
        }
        String normalized = normalize(roomReference);
        return roomReferences.stream().map(ExistingEvent::normalize).anyMatch(normalized::equals);
    }

    private static String normalize(String roomReference) {
        String trimmed = roomReference.trim().toLowerCase();
        int atIndex = trimmed.indexOf('@');
        if (atIndex >= 0) {
            return trimmed.substring(0, atIndex);
        }
        return trimmed;
    }
}
