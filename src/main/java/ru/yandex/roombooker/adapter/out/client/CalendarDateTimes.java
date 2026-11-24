package ru.yandex.roombooker.adapter.out.client;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

import com.fasterxml.jackson.databind.JsonNode;

/** Converts calendar times to the booking timezone without discarding explicit offsets. */
final class CalendarDateTimes {

    private CalendarDateTimes() {
    }

    static LocalDateTime parse(String value, String sourceZone, String targetZone) {
        try {
            if (value.length() == 10) {
                // All-day dates are floating calendar dates, not instants in a source timezone.
                return LocalDate.parse(value).atStartOfDay();
            }
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    value, ZonedDateTime::from, LocalDateTime::from
            );
            ZonedDateTime zoned = parsed instanceof ZonedDateTime dateTime
                    ? dateTime : ((LocalDateTime) parsed).atZone(ZoneId.of(sourceZone));
            return zoned.withZoneSameInstant(ZoneId.of(targetZone)).toLocalDateTime();
        } catch (RuntimeException failure) {
            throw new CalendarApiException("Invalid calendar availability time: " + value, failure);
        }
    }

    static LocalDateTime parse(JsonNode value, String timeZone) {
        if (value.hasNonNull("date")) {
            return parse(value.path("date").asText(), timeZone, timeZone);
        }
        return parse(value.path("date_time").asText(), value.path("time_zone").asText(timeZone), timeZone);
    }
}
