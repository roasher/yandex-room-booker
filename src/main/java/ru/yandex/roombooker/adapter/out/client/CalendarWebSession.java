package ru.yandex.roombooker.adapter.out.client;

import org.jspecify.annotations.Nullable;

/**
 * Resolved Calendar web UI session (CSRF ckey + optional uid/email).
 */
public record CalendarWebSession(
        @Nullable String ckey,
        @Nullable String uid,
        @Nullable String email
) {
}
