package ru.yandex.roombooker.adapter.out.client;

import org.jspecify.annotations.Nullable;

/**
 * Failure while calling Yandex Calendar API.
 */
public class CalendarApiException extends RuntimeException {

    private final @Nullable Integer httpStatus;

    public CalendarApiException(String message) {
        this(message, null, null);
    }

    public CalendarApiException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public CalendarApiException(String message, @Nullable Integer httpStatus) {
        this(message, httpStatus, null);
    }

    public CalendarApiException(String message, @Nullable Integer httpStatus, @Nullable Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public @Nullable Integer httpStatus() {
        return httpStatus;
    }

    /**
     * Contested rooms often return a false {@code busy} right as the window opens, so busy is
     * retried like other transient failures. Window-not-open-yet ({@code not bookable} /
     * {@code too-far-event}), {@code 5xx}, network failures, and {@code 429} are also retryable.
     * Other client {@code 4xx} errors are not.
     */
    public boolean isRetryable() {
        String message = getMessage();
        if (looksLikeRoomBusy(message) || looksLikeNotYetBookable(message)) {
            return true;
        }
        if (httpStatus == null) {
            return true;
        }
        if (httpStatus == 429) {
            return true;
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            return false;
        }
        return true;
    }

    public static boolean looksLikeRoomBusy(@Nullable String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("rooms are busy")
                || lower.contains("room busy")
                || lower.contains("busy-overlap")
                || lower.contains("busy at the requested");
    }

    public static boolean looksLikeNotYetBookable(@Nullable String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("not bookable") || lower.contains("too-far-event");
    }
}
