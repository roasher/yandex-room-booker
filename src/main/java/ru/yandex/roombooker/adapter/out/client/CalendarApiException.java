package ru.yandex.roombooker.adapter.out.client;

/**
 * Failure while calling Yandex Calendar API.
 */
public class CalendarApiException extends RuntimeException {

    public CalendarApiException(String message) {
        super(message);
    }

    public CalendarApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
