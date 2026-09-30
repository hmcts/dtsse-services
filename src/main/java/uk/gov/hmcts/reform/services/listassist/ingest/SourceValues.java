package uk.gov.hmcts.reform.services.listassist.ingest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Strict interpretation of the observed source timestamp format, e.g. {@code 2040-01-30 09:12:13.1234567}.
 * Impossible calendar values such as 30 February are rejected, not adjusted.
 */
final class SourceValues {

    private static final DateTimeFormatter SOURCE_DATE_TIME =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSS").withResolverStyle(ResolverStyle.STRICT);

    private SourceValues() {
    }

    static boolean isValidTimestamp(String value) {
        return parse(value) != null;
    }

    static LocalDate dateOf(String value) {
        LocalDateTime parsed = parse(value);
        return parsed == null ? null : parsed.toLocalDate();
    }

    private static LocalDateTime parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, SOURCE_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
