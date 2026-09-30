package uk.gov.hmcts.reform.services.listassist.fixtures;

import uk.gov.hmcts.reform.services.listassist.fixtures.ListAssistSchemaInventory.DatasetSchema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One source extract file: which dataset, how it is named, when it arrives and the authored rows it contains.
 */
public record Delivery(DatasetSchema dataset, ExtractKind kind, LocalDate pathDate, LocalDateTime fileTimestamp,
                       int phase, UploadAction upload, List<Map<String, String>> rows) {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public Delivery {
        for (Map<String, String> row : rows) {
            for (String column : row.keySet()) {
                if (!dataset.columns().contains(column)) {
                    throw new IllegalArgumentException(dataset.container() + " has no column " + column);
                }
            }
        }
        rows = rows.stream().map(row -> complete(dataset.columns(), row)).toList();
    }

    public String container() {
        return dataset.container();
    }

    /**
     * Observed layout {@code YYYY-MM/YYYY-MM-DD-dbo-<Full|Incr>_<view-token>-YYYYMMDDhhmmss-data.parquet}.
     */
    public String blobName() {
        return pathDate.format(MONTH) + "/" + pathDate + "-dbo-" + kind.token() + "_" + dataset.viewToken()
            + "-" + fileTimestamp.format(STAMP) + "-data.parquet";
    }

    private static Map<String, String> complete(List<String> columns, Map<String, String> row) {
        Map<String, String> complete = new LinkedHashMap<>();
        columns.forEach(column -> complete.put(column, row.get(column)));
        return Collections.unmodifiableMap(complete);
    }

    /**
     * {@code CREATE} must not replace an existing Blob; {@code OVERWRITE} replaces a Blob name delivered earlier.
     */
    public enum UploadAction {
        CREATE,
        OVERWRITE;

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ExtractKind {
        FULL("Full"),
        INCR("Incr");

        private final String token;

        ExtractKind(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }
}
