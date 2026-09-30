package uk.gov.hmcts.reform.services.listassist.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code source_file} ledger and {@code container_bootstrap} record. An {@code ingested} row is never overwritten,
 * so a concurrent or replayed run cannot demote or duplicate a completed file.
 */
@Component
class SourceFileLedger {

    static final String INGESTED = "ingested";
    static final String FAILED = "failed";
    static final String SUPERSEDED = "superseded";
    static final String SKIPPED_PRE_BOOTSTRAP = "skipped_pre_bootstrap";

    private final JdbcTemplate jdbc;

    SourceFileLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<VersionKey, String> statuses(String container) {
        Map<VersionKey, String> statuses = new HashMap<>();
        jdbc.query("select blob_name, etag, status from listassist.source_file where container = ?",
            rs -> {
                statuses.put(new VersionKey(rs.getString(1), rs.getString(2)), rs.getString(3));
            }, container);
        return statuses;
    }

    boolean isBootstrapped(String container) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from listassist.container_bootstrap where container = ?)", Boolean.class,
            container));
    }

    /**
     * Within the file transaction: marks the version ingested and returns its id, or empty if another run already
     * ingested it.
     */
    Optional<Long> claimIngested(String container, ListedVersion version, String sha256) {
        List<Long> ids = jdbc.queryForList("""
            insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp, status,
                                                attempts, sha256, size_bytes, last_attempt_at, ingested_at)
            values (?, ?, ?, ?, ?, 'ingested', 1, ?, ?, now(), now())
            on conflict (container, blob_name, etag) do update
               set status = 'ingested', error_code = null, attempts = source_file.attempts + 1,
                   sha256 = excluded.sha256, size_bytes = excluded.size_bytes, last_attempt_at = now(),
                   ingested_at = now()
             where source_file.status <> 'ingested'
            returning id
            """, Long.class, container, version.name().blobName(), version.etag(), version.name().kind(),
            version.name().fileTimestamp(), sha256, version.sizeBytes());
        return ids.stream().findFirst();
    }

    void recordCounts(long sourceFileId, long rows, long unusableRows) {
        jdbc.update("update listassist.source_file set row_count = ?, unusable_row_count = ? where id = ?",
            rows, unusableRows, sourceFileId);
    }

    /**
     * Outside the rolled-back file transaction. Only a new or already failed version is updated.
     */
    void markFailed(String container, ListedVersion version, String errorCode) {
        jdbc.update("""
            insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp, status,
                                                error_code, attempts, last_attempt_at)
            values (?, ?, ?, ?, ?, 'failed', ?, 1, now())
            on conflict (container, blob_name, etag) do update
               set error_code = excluded.error_code, attempts = source_file.attempts + 1, last_attempt_at = now()
             where source_file.status = 'failed'
            """, container, version.name().blobName(), version.etag(), version.name().kind(),
            version.name().fileTimestamp(), errorCode);
    }

    /**
     * The Blob changed after listing. This ETag can never be downloaded again; the next listing finds the new one.
     */
    void markSuperseded(String container, ListedVersion version) {
        upsertTerminal(container, version, SUPERSEDED);
    }

    /**
     * A failed version that is no longer listed.
     */
    void markSuperseded(String container, VersionKey key) {
        jdbc.update("update listassist.source_file set status = 'superseded'"
            + " where container = ? and blob_name = ? and etag = ? and status = 'failed'",
            container, key.blobName(), key.etag());
    }

    /**
     * Within the bootstrap Full's transaction: records the chosen Full and skips every older listed version.
     */
    void markBootstrapped(String container, long fullSourceFileId, List<ListedVersion> olderVersions) {
        jdbc.update("insert into listassist.container_bootstrap (container, source_file_id) values (?, ?)",
            container, fullSourceFileId);
        olderVersions.forEach(version -> upsertTerminal(container, version, SKIPPED_PRE_BOOTSTRAP));
    }

    private void upsertTerminal(String container, ListedVersion version, String status) {
        jdbc.update("""
            insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp, status)
            values (?, ?, ?, ?, ?, ?)
            on conflict (container, blob_name, etag) do update
               set status = excluded.status
             where source_file.status <> 'ingested'
            """, container, version.name().blobName(), version.etag(), version.name().kind(),
            version.name().fileTimestamp(), status);
    }
}
