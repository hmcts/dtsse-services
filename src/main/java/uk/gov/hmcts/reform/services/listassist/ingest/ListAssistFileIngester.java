package uk.gov.hmcts.reform.services.listassist.ingest;

import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Ingests one Blob version: a conditional download to a temporary file, then its ledger row and every selected row in
 * a single transaction. Failures are recorded in the ledger outside that transaction. Logs never contain row values.
 */
@Component
@ConditionalOnProperty("listassist.blob.endpoint")
class ListAssistFileIngester {

    private static final Logger log = LoggerFactory.getLogger(ListAssistFileIngester.class);
    private static final int BATCH_SIZE = 500;
    private static final int PRECONDITION_FAILED = 412;

    private final ListAssistBlobReader reader;
    private final SourceFileLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final long maxFileBytes;

    ListAssistFileIngester(ListAssistBlobReader reader, SourceFileLedger ledger, JdbcTemplate jdbc,
                           TransactionTemplate transaction,
                           @Value("${listassist.ingest.max-file-bytes}") long maxFileBytes) {
        this.reader = reader;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * Returns true if the version is ingested (now or by an earlier run). For a bootstrap Full, {@code olderVersions}
     * are skipped in the same transaction; otherwise pass {@code null}.
     */
    boolean ingest(ListAssistDataset dataset, ListedVersion version, List<ListedVersion> olderVersions) {
        String container = dataset.container().key();
        if (version.sizeBytes() > maxFileBytes) {
            fail(container, version, "file_too_large", null);
            return false;
        }
        Path file = null;
        try {
            file = Files.createTempFile("listassist-", ".parquet");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), sha256)) {
                reader.download(dataset.container(), version.name().blobName(), version.etag(), out);
            }
            Path downloaded = file;
            String digest = HexFormat.of().formatHex(sha256.digest());
            Boolean ingested = transaction.execute(status ->
                store(dataset, version, downloaded, digest, olderVersions));
            return Boolean.TRUE.equals(ingested);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == PRECONDITION_FAILED) {
                log.info("ListAssist Blob changed after listing container={} blob={}", container,
                    version.name().blobName());
                ledger.markSuperseded(container, version);
            } else {
                fail(container, version, "download_failed", e);
            }
        } catch (SourceFileException e) {
            fail(container, version, e.errorCode(), e);
        } catch (IOException | UncheckedIOException e) {
            fail(container, version, "download_failed", e);
        } catch (DataAccessException | TransactionException e) {
            fail(container, version, "database_failed", e);
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            fail(container, version, "unexpected", e);
        } finally {
            deleteQuietly(file);
        }
        return false;
    }

    private boolean store(ListAssistDataset dataset, ListedVersion version, Path file, String sha256,
                          List<ListedVersion> olderVersions) {
        String container = dataset.container().key();
        Optional<Long> claimed = ledger.claimIngested(container, version, sha256);
        if (claimed.isEmpty()) {
            return true;
        }
        long sourceFileId = claimed.get();
        Rows rows = new Rows(dataset, sourceFileId);
        long count;
        try {
            count = ParquetRows.read(file, dataset.sourceColumns(), rows::add);
        } catch (SourceFileException | DataAccessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // parquet-java reports unreadable files with IOException or plain runtime exceptions.
            throw new SourceFileException(SourceFileException.DECODE_FAILED, e);
        }
        rows.flush();
        ledger.recordCounts(sourceFileId, count, rows.unusable);
        if (olderVersions != null) {
            ledger.markBootstrapped(container, sourceFileId, olderVersions);
        }
        log.info("Ingested ListAssist file container={} blob={} rows={} unusableRows={}", container,
            version.name().blobName(), count, rows.unusable);
        return true;
    }

    private void fail(String container, ListedVersion version, String errorCode, Exception e) {
        // Only the exception type is logged: messages from decoders or the database can echo source values.
        log.error("ListAssist file failed container={} blob={} errorCode={} exception={}", container,
            version.name().blobName(), errorCode, e == null ? null : e.getClass().getName());
        ledger.markFailed(container, version, errorCode);
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete temporary ListAssist file {}", file);
            }
        }
    }

    /**
     * Buffers one batch of rows and classifies each row's problem before insert.
     */
    private final class Rows {

        private final long sourceFileId;
        private final String sql;
        private final List<Integer> identityIndexes = new ArrayList<>();
        private final int lastModifiedIndex;
        private final List<Integer> parsedDateIndexes = new ArrayList<>();
        private final List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        private int rowNo;
        private long unusable;

        Rows(ListAssistDataset dataset, long sourceFileId) {
            this.sourceFileId = sourceFileId;
            List<String> source = dataset.sourceColumns();
            dataset.identityColumns().forEach(column -> identityIndexes.add(source.indexOf(column)));
            this.lastModifiedIndex = source.indexOf(dataset.lastModifiedColumn());
            dataset.parsedDates().keySet().forEach(column -> parsedDateIndexes.add(source.indexOf(column)));
            List<String> columns = new ArrayList<>(List.of("source_file_id", "row_no", "row_problem"));
            columns.addAll(dataset.tableColumns());
            dataset.parsedDates().keySet().forEach(column -> columns.add(dataset.parsedDates().get(column)));
            this.sql = "insert into listassist." + dataset.table() + " (" + String.join(", ", columns)
                + ") values (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        }

        void add(String[] values) {
            String problem = problem(values);
            if (problem != null) {
                unusable++;
            }
            Object[] args = new Object[3 + values.length + parsedDateIndexes.size()];
            args[0] = sourceFileId;
            args[1] = rowNo++;
            args[2] = problem;
            System.arraycopy(values, 0, args, 3, values.length);
            for (int i = 0; i < parsedDateIndexes.size(); i++) {
                args[3 + values.length + i] = SourceValues.dateOf(values[parsedDateIndexes.get(i)]);
            }
            batch.add(args);
            if (batch.size() == BATCH_SIZE) {
                flush();
            }
        }

        void flush() {
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }

        private String problem(String[] values) {
            for (int index : identityIndexes) {
                if (values[index] == null) {
                    return "missing_identity";
                }
            }
            return SourceValues.isValidTimestamp(values[lastModifiedIndex]) ? null : "invalid_last_modified";
        }
    }
}
