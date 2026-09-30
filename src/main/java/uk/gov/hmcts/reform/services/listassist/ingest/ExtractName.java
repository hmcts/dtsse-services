package uk.gov.hmcts.reform.services.listassist.ingest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed persisted extract name {@code YYYY-MM/YYYY-MM-DD-dbo-<Full|Incr>_<view-token>-YYYYMMDDhhmmss-data.parquet}.
 * The folder month, the date and the timestamp must be real calendar values, so an impossible timestamp cannot sort
 * above every valid extract and be chosen for bootstrap.
 */
public record ExtractName(String blobName, String kind, String fileTimestamp) {

    public static final String FULL = "Full";

    /**
     * Upstream filename timestamp first; the name only breaks ties so ordering is deterministic.
     */
    public static final Comparator<ExtractName> DELIVERY_ORDER =
        Comparator.comparing(ExtractName::fileTimestamp).thenComparing(ExtractName::blobName);

    private static final Pattern NAME = Pattern.compile(
        "(\\d{4}-\\d{2})/(\\d{4}-\\d{2}-\\d{2})-dbo-(Full|Incr)_(.+)-(\\d{14})-data\\.parquet");
    private static final DateTimeFormatter MONTH = strict("uuuu-MM");
    private static final DateTimeFormatter DATE = strict("uuuu-MM-dd");
    private static final DateTimeFormatter TIMESTAMP = strict("uuuuMMddHHmmss");

    public static Optional<ExtractName> parse(String blobName, String viewToken) {
        Matcher matcher = NAME.matcher(blobName);
        if (!matcher.matches() || !matcher.group(4).equals(viewToken)) {
            return Optional.empty();
        }
        try {
            YearMonth.parse(matcher.group(1), MONTH);
            LocalDate.parse(matcher.group(2), DATE);
            LocalDateTime.parse(matcher.group(5), TIMESTAMP);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return Optional.of(new ExtractName(blobName, matcher.group(3), matcher.group(5)));
    }

    public boolean isFull() {
        return FULL.equals(kind);
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }
}
