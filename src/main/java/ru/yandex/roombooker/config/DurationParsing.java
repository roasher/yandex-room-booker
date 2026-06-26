package ru.yandex.roombooker.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses duration strings from YAML config (e.g. {@code 90m}, {@code 1h30m}, {@code 3d}).
 */
public final class DurationParsing {

    private static final Pattern COMPOUND_DURATION = Pattern.compile(
            "^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$",
            Pattern.CASE_INSENSITIVE
    );

    private DurationParsing() {
    }

    public static Duration parseRequired(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + propertyName);
        }
        return parse(value);
    }

    public static Duration parse(String value) {
        try {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(value);
        } catch (IllegalArgumentException ignored) {
            Matcher matcher = COMPOUND_DURATION.matcher(value.trim());
            if (matcher.matches()) {
                long days = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
                long hours = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
                long minutes = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
                long seconds = matcher.group(4) == null ? 0 : Long.parseLong(matcher.group(4));
                if (days + hours + minutes + seconds > 0) {
                    return Duration.ofSeconds(days * 86400 + hours * 3600 + minutes * 60 + seconds);
                }
            }
            throw new IllegalArgumentException("'%s' is not a valid duration".formatted(value));
        }
    }
}
