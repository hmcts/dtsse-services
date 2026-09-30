package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SourceValuesTest {

    @Test
    void acceptsOnlyRealSevenDigitTimestamps() {
        assertThat(SourceValues.isValidTimestamp("2040-01-30 09:12:13.1234567")).isTrue();
        assertThat(SourceValues.isValidTimestamp("2040-02-30 09:12:13.1234567")).isFalse();
        assertThat(SourceValues.isValidTimestamp("2040-01-30 24:00:00.0000000")).isFalse();
        assertThat(SourceValues.isValidTimestamp("2040-01-30 09:12:13.123456")).isFalse();
        assertThat(SourceValues.isValidTimestamp("2040-01-30T09:12:13.1234567")).isFalse();
        assertThat(SourceValues.isValidTimestamp(" 2040-01-30 09:12:13.1234567")).isFalse();
        assertThat(SourceValues.isValidTimestamp("null")).isFalse();
        assertThat(SourceValues.isValidTimestamp(null)).isFalse();
    }

    @Test
    void takesTheCalendarDateWithoutTimezoneConversion() {
        assertThat(SourceValues.dateOf("2040-03-29 23:59:59.9999999")).isEqualTo(LocalDate.of(2040, 3, 29));
        assertThat(SourceValues.dateOf("2040-02-30 00:00:00.0000000")).isNull();
    }
}
