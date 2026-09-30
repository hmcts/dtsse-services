package uk.gov.hmcts.reform.services.listassist.ingest;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;
import uk.gov.hmcts.reform.services.listassist.fixtures.ListAssistSchemaInventory;
import uk.gov.hmcts.reform.services.listassist.fixtures.ParquetFixtureFiles;
import uk.gov.hmcts.reform.services.listassist.ingest.PostgresAdvisoryLock.LockLostException;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Candidate;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Diagnostic;
import uk.gov.hmcts.reform.services.listassist.query.ListAssistCandidateRepository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Failure paths and query edge cases. Every test starts from an empty emulator and database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "listassist.ingest.max-file-bytes=200000")
@DirtiesContext
class IngestionFailureTest {

    private static final IngestionEnvironment ENV = IngestionEnvironment.start();
    private static final ListAssistSchemaInventory INVENTORY = ListAssistSchemaInventory.load();
    private static final String MODIFIED = "2040-05-01 09:00:00.1234567";
    private static final String HEARINGS_FULL =
        "2040-05/2040-05-01-dbo-Full_vhmcts_Hearings-20400501030000-data.parquet";
    private static final String USERS_FULL = "2040-05/2040-05-01-dbo-Full_vhmcts_user-20400501030000-data.parquet";

    @Autowired
    private ListAssistIngestionJob job;
    @Autowired
    private ListAssistFileIngester fileIngester;
    @Autowired
    private ListAssistCandidateRepository candidates;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @TempDir
    Path workDirectory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.register(registry);
    }

    @AfterAll
    static void stop() {
        ENV.close();
    }

    @BeforeEach
    void reset() {
        BlobServiceClient client = ENV.azurite().client();
        for (ListAssistContainer container : ListAssistContainer.values()) {
            client.getBlobContainerClient(container.key()).deleteIfExists();
        }
        jdbc.execute("truncate listassist.source_file, listassist.container_bootstrap, listassist.hearing_row,"
            + " listassist.session_row, listassist.session_officer_row, listassist.user_row");
        jdbc.execute("drop trigger if exists injected on listassist.hearing_row");
        jdbc.execute("drop trigger if exists injected on listassist.user_row");
    }

    @Test
    void oversizedFieldRollsBackAlreadyFlushedRowsAndRemovesTheDownload() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(user("91" + i, "0100001", MODIFIED));
        }
        rows.add(user("bad", "x".repeat(ParquetLimits.MAX_FIELD_BYTES + 1), MODIFIED));
        upload("users", USERS_FULL, parquet(INVENTORY.observed("users").columns(), rows));

        job.runOnce();

        assertThat(jdbc.queryForObject("select count(*) from listassist.user_row", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select error_code from listassist.source_file", String.class))
            .isEqualTo("resource_limit");
        assertThat(jdbc.queryForObject("select count(*) from listassist.container_bootstrap", Integer.class)).isZero();
        try (var files = java.nio.file.Files.list(Path.of(System.getProperty("java.io.tmpdir"), "dtsse-listassist"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void badFilesFailWithoutBlockingOthersAndBadRowsAreKeptButNotCurrent() {
        List<String> columns = INVENTORY.observed("users").columns();
        upload("users", USERS_FULL, parquet(columns,
            user("9100001", "0100001", MODIFIED),
            user("9100002", "0100002", "2040-02-30 09:00:00.1234567"),
            user(null, "0100003", MODIFIED)));
        upload("users", "2040-05/2040-05-01-dbo-Incr_vhmcts_user-20400501110000-data.parquet",
            BinaryData.fromString("not a parquet file"));
        List<String> withoutCode = new ArrayList<>(columns);
        withoutCode.remove("External_User_ID");
        upload("users", "2040-05/2040-05-01-dbo-Incr_vhmcts_user-20400501120000-data.parquet",
            parquet(withoutCode, user("9100004", null, MODIFIED)));
        upload("users", "2040-05/2040-05-01-dbo-Incr_vhmcts_user-20400501130000-data.parquet",
            parquet(columns, IntStream.range(0, 20).mapToObj(i -> user("91" + i, randomHex(i, 20_000), MODIFIED))
                .toList()));
        upload("users", "2040-05/2040-05-02-dbo-Incr_vhmcts_user-20400502110000-data.parquet",
            parquet(columns, user("9100005", "0100005", MODIFIED)));
        upload("users", "2040-05/not-an-extract.json", BinaryData.fromString("{}"));

        job.runOnce();
        job.runOnce();

        assertThat(jdbc.queryForList("""
            select status, error_code, attempts, row_count, unusable_row_count
              from listassist.source_file where container = 'users' order by blob_name
            """)).extracting(row -> tuple(row.get("status"), row.get("error_code"), row.get("attempts"),
                row.get("row_count"), row.get("unusable_row_count")))
            .containsExactly(
                tuple("ingested", null, 1, 3L, 2L),
                tuple("failed", "decode_failed", 2, null, null),
                tuple("failed", "schema_mismatch", 2, null, null),
                tuple("failed", "file_too_large", 2, null, null),
                tuple("ingested", null, 1, 1L, 0L));
        assertThat(jdbc.queryForList("select row_problem from listassist.unusable_observation order by 1",
            String.class)).containsExactly("invalid_last_modified", "missing_identity");
        assertThat(jdbc.queryForList("select id_user from listassist.current_user_account order by 1", String.class))
            .containsExactly("9100001", "9100005");

        List<Diagnostic> ok = candidates.findCandidates("0100001", LocalDate.of(2040, 5, 1)).diagnostics();
        assertThat(ok).extracting(Diagnostic::code).contains("INGESTION_FAILED", "NOT_BOOTSTRAPPED");
        assertThat(ok).filteredOn(d -> d.code().equals("INGESTION_FAILED"))
            .flatExtracting(Diagnostic::sampleIds).containsExactly("users");
        // A user whose only observation is unorderable is reported, not silently unmatched.
        assertThat(candidates.findCandidates("0100002", LocalDate.of(2040, 5, 1)).diagnostics())
            .filteredOn(d -> d.code().equals("UNORDERABLE_OBSERVATIONS"))
            .flatExtracting(Diagnostic::sampleIds).containsExactly("user:9100002");
    }

    @Test
    void failureAfterPartialInsertRollsBackTheWholeBootstrapAndRetrySucceeds() {
        List<String> columns = INVENTORY.observed("hearings").columns();
        String olderIncr = "2040-04/2040-04-30-dbo-Incr_vhmcts_Hearings-20400430110000-data.parquet";
        upload("hearings", olderIncr, parquet(columns, hearing(1, "9300001")));
        upload("hearings", HEARINGS_FULL, parquet(columns,
            IntStream.range(0, 1200).mapToObj(i -> hearing(i, "9300001")).toList()));
        jdbc.execute("""
            create or replace function listassist.injected_failure() returns trigger language plpgsql as $$
            begin
              if new.row_no = 900 then raise exception 'injected failure'; end if;
              return new;
            end $$""");
        jdbc.execute("create trigger injected before insert on listassist.hearing_row for each row"
            + " execute function listassist.injected_failure()");

        job.runOnce();

        assertThat(jdbc.queryForObject("select count(*) from listassist.hearing_row", Integer.class)).isZero();
        assertThat(jdbc.queryForList("select blob_name, status, error_code from listassist.source_file"))
            .extracting(row -> tuple(row.get("blob_name"), row.get("status"), row.get("error_code")))
            .containsExactly(tuple(HEARINGS_FULL, "failed", "database_failed"));
        assertThat(jdbc.queryForObject("select count(*) from listassist.container_bootstrap", Integer.class))
            .isZero();

        jdbc.execute("drop trigger injected on listassist.hearing_row");
        job.runOnce();

        assertThat(jdbc.queryForObject("select count(*) from listassist.hearing_row", Integer.class))
            .isEqualTo(1200);
        assertThat(jdbc.queryForList("select blob_name, status, attempts from listassist.source_file order by 1"))
            .extracting(row -> tuple(row.get("blob_name"), row.get("status"), row.get("attempts")))
            .containsExactly(tuple(olderIncr, "skipped_pre_bootstrap", 0), tuple(HEARINGS_FULL, "ingested", 2));
        assertThat(jdbc.queryForList("select container from listassist.container_bootstrap", String.class))
            .containsExactly("hearings");
    }

    @Test
    void lostLockBetweenFilesStopsTheRunBeforeTheNextFile() {
        List<String> columns = INVENTORY.observed("users").columns();
        String incr = "2040-05/2040-05-01-dbo-Incr_vhmcts_user-20400501110000-data.parquet";
        upload("users", USERS_FULL, parquet(columns, user("9100001", "0100001", MODIFIED)));
        upload("users", incr, parquet(columns, user("9100002", "0100002", MODIFIED)));
        jdbc.execute("""
            create or replace function listassist.injected_lock_loss() returns trigger language plpgsql as $$
            begin
              perform pg_terminate_backend(pid) from pg_locks
               where locktype = 'advisory' and granted and pid <> pg_backend_pid();
              return new;
            end $$""");
        jdbc.execute("create trigger injected after insert on listassist.user_row for each row"
            + " execute function listassist.injected_lock_loss()");

        assertThatThrownBy(job::runOnce).isInstanceOf(LockLostException.class);

        assertThat(jdbc.queryForList("select blob_name, status from listassist.source_file"))
            .extracting(row -> tuple(row.get("blob_name"), row.get("status")))
            .containsExactly(tuple(USERS_FULL, "ingested"));
        jdbc.execute("drop trigger injected on listassist.user_row");
        assertThat(job.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from listassist.source_file where status = 'ingested'",
            Integer.class)).isEqualTo(2);
    }

    @Test
    void failedVersionReplacedByACorrectedUploadIsSupersededNotReportedForever() {
        List<String> columns = INVENTORY.observed("users").columns();
        String incr = "2040-05/2040-05-01-dbo-Incr_vhmcts_user-20400501110000-data.parquet";
        upload("users", USERS_FULL, parquet(columns, user("9100001", "0100001", MODIFIED)));
        upload("users", incr, BinaryData.fromString("not a parquet file"));
        job.runOnce();
        upload("users", incr, parquet(columns, user("9100002", "0100002", MODIFIED)));

        job.runOnce();

        assertThat(jdbc.queryForList("select status, error_code from listassist.source_file where blob_name = ?"
            + " order by first_seen_at", incr)).extracting(row -> tuple(row.get("status"), row.get("error_code")))
            .containsExactly(tuple("superseded", "decode_failed"), tuple("ingested", null));
        assertThat(candidates.findCandidates("0100002", LocalDate.of(2040, 5, 1)).diagnostics())
            .extracting(Diagnostic::code).doesNotContain("INGESTION_FAILED", "PERSONAL_CODE_UNMATCHED");
    }

    @Test
    void containerWithoutFullStaysUnbootstrapped() {
        upload("sessions", "2040-05/2040-05-01-dbo-Incr_vhmcts_Sessions-20400501110000-data.parquet",
            parquet(INVENTORY.observed("sessions").columns(),
                Map.of("ID_Session", "9200001", "Last_Modified_date", MODIFIED)));

        job.runOnce();

        assertThat(jdbc.queryForObject("select count(*) from listassist.source_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from listassist.container_bootstrap", Integer.class))
            .isZero();
    }

    @Test
    void fullWithAnImpossibleFilenameTimestampIsIgnoredAndCannotBecomeTheBootstrap() {
        List<String> columns = INVENTORY.observed("sessions").columns();
        upload("sessions", "2040-05/2040-05-01-dbo-Full_vhmcts_Sessions-20409999999999-data.parquet",
            parquet(columns, Map.of("ID_Session", "9200001", "Last_Modified_date", MODIFIED)));
        String valid = "2040-05/2040-05-01-dbo-Full_vhmcts_Sessions-20400501030000-data.parquet";
        upload("sessions", valid,
            parquet(columns, Map.of("ID_Session", "9200002", "Last_Modified_date", MODIFIED)));

        job.runOnce();

        assertThat(jdbc.queryForList("select f.blob_name from listassist.container_bootstrap b"
            + " join listassist.source_file f on f.id = b.source_file_id", String.class)).containsExactly(valid);
        assertThat(jdbc.queryForList("select blob_name from listassist.source_file", String.class))
            .containsExactly(valid);
    }

    @Test
    void blobChangedAfterListingIsSupersededAndItsNewVersionIngested() {
        String name = "2040-05/2040-05-01-dbo-Full_vhmcts_Sessions_JOfficer-20400501030000-data.parquet";
        List<String> columns = INVENTORY.observed("session-officers").columns();
        upload("session-officers", name, parquet(columns, officer("9300001", "9300002")));
        BlobProperties listed = container("session-officers").getBlobClient(name).getProperties();
        upload("session-officers", name, parquet(columns, officer("9300001", "9300003")));

        ListedVersion stale = new ListedVersion(ExtractName.parse(name, "vhmcts_Sessions_JOfficer").orElseThrow(),
            listed.getETag(), listed.getBlobSize());
        assertThat(fileIngester.ingest(ListAssistDataset.SESSION_OFFICERS, stale, null)).isFalse();
        assertThat(jdbc.queryForObject("select status from listassist.source_file where etag = ?", String.class,
            listed.getETag())).isEqualTo("superseded");

        job.runOnce();
        assertThat(jdbc.queryForList("select id_jo from listassist.session_officer_row", String.class))
            .containsExactly("9300003");
    }

    @Test
    void concurrentRunSkipsWhileAnotherHoldsTheLock() throws Exception {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            statement.execute("select pg_advisory_lock(hashtext('listassist'), hashtext('"
                + ListAssistIngestionJob.LOCK_NAME + "'))");
            assertThat(job.runOnce()).isFalse();
            statement.execute("select pg_advisory_unlock_all()");
        }
        assertThat(job.runOnce()).isTrue();
    }

    @Test
    void slotOnlyHearingWithoutSessionIsACandidateWithUnknownSession() {
        jdbc.update("insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp,"
            + " status) values ('hearings', 'direct', 'e', 'Full', '20400501030000', 'ingested')");
        long file = jdbc.queryForObject("select id from listassist.source_file", Long.class);
        jdbc.update("insert into listassist.user_row (source_file_id, row_no, last_modified, id_user, personal_code)"
            + " values (?, 0, ?, 'U1', '0000001')", file, MODIFIED);
        // The empty-case row also carries a blank inactive date, which must not hide it.
        jdbc.update("insert into listassist.hearing_row (source_file_id, row_no, last_modified, id_hearing, id_case,"
            + " id_session, id_jo_1, hearing_date_raw, hearing_date, inactive_date) values"
            + " (?, 0, ?, 'H1', null, null, 'U1', '2040-05-01 00:00:00.0000000', date '2040-05-01', null),"
            + " (?, 1, ?, 'H1', '', null, 'U1', '2040-05-01 00:00:00.0000000', date '2040-05-01', ''),"
            + " (?, 2, 'bad', 'H1', null, null, 'U1', '2040-05-01 00:00:00.0000000', date '2040-05-01', null)",
            file, MODIFIED, file, MODIFIED, file);
        jdbc.update("update listassist.hearing_row set row_problem = 'invalid_last_modified' where row_no = 2");

        CandidateHearings result = candidates.findCandidates("0000001", LocalDate.of(2040, 5, 1));

        // A null and an empty case ID are distinct associations, in candidates and in diagnostic counts.
        assertThat(result.candidates()).extracting(Candidate::idCase, Candidate::idSession, Candidate::basis,
                Candidate::sessionState)
            .containsExactly(tuple("", null, "hearing_slot", "unknown"), tuple(null, null, "hearing_slot", "unknown"));
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("SESSION_UNRESOLVED"))
            .extracting(Diagnostic::count, Diagnostic::sampleIds)
            .containsExactly(tuple(2, List.of("[\"H1\", \"\", null]", "[\"H1\", null, null]")));
        // A malformed row with a null session is reported, not a crash.
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("UNORDERABLE_OBSERVATIONS"))
            .flatExtracting(Diagnostic::sampleIds).containsExactly("hearing:[\"H1\", null, null]");
    }

    @Test
    void conflictedAssociationReportsItsUnorderableObservationsFromAnyDate() {
        jdbc.update("insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp,"
            + " status) values ('hearings', 'direct', 'e', 'Full', '20400501030000', 'ingested')");
        long file = jdbc.queryForObject("select id from listassist.source_file", Long.class);
        jdbc.update("insert into listassist.user_row (source_file_id, row_no, last_modified, id_user, personal_code)"
            + " values (?, 0, ?, 'U1', '0000001')", file, MODIFIED);
        jdbc.update("insert into listassist.hearing_row (source_file_id, row_no, last_modified, row_problem,"
            + " id_hearing, id_case, id_session, id_jo_1, duration, hearing_date) values"
            + " (?, 0, ?, null, 'H2', 'C2', 'S2', 'U1', '30', date '2040-05-01'),"
            + " (?, 1, ?, null, 'H2', 'C2', 'S2', 'U1', '45', date '2040-05-01'),"
            + " (?, 2, 'not a timestamp', 'invalid_last_modified', 'H2', 'C2', 'S2', 'U1', '30', date '2040-06-01')",
            file, MODIFIED, file, MODIFIED, file);

        List<Diagnostic> diagnostics = candidates.findCandidates("0000001", LocalDate.of(2040, 5, 1)).diagnostics();

        assertThat(diagnostics).filteredOn(d -> d.code().startsWith("HEARING_CONFLICT")
                || d.code().startsWith("UNORDERABLE"))
            .extracting(Diagnostic::code, Diagnostic::sampleIds)
            .containsExactly(tuple("HEARING_CONFLICT", List.of("[\"H2\", \"C2\", \"S2\"]")),
                tuple("UNORDERABLE_OBSERVATIONS", List.of("hearing:[\"H2\", \"C2\", \"S2\"]")));
    }

    @Test
    void rowsMissingTheirIdentityAreReportedByFileAndRowWhenRelevant() {
        jdbc.update("insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp,"
            + " status) values ('hearings', 'direct', 'e', 'Full', '20400501030000', 'ingested')");
        long file = jdbc.queryForObject("select id from listassist.source_file", Long.class);
        jdbc.update("insert into listassist.user_row (source_file_id, row_no, last_modified, row_problem, id_user,"
            + " personal_code) values (?, 0, ?, null, 'U1', '0000001'), (?, 1, ?, 'missing_identity', null,"
            + " '0000001'), (?, 2, ?, 'missing_identity', null, '0000009')", file, MODIFIED, file, MODIFIED, file,
            MODIFIED);
        jdbc.update("insert into listassist.session_officer_row (source_file_id, row_no, last_modified, row_problem,"
            + " id_session, id_jo) values (?, 0, ?, null, 'S1', 'U1'), (?, 1, ?, 'missing_identity', null, 'U1'),"
            + " (?, 2, ?, 'missing_identity', null, 'U9')", file, MODIFIED, file, MODIFIED, file, MODIFIED);
        // A valid judge, session and date, but no ID_Hearing; the other rows are off the date or unrelated.
        jdbc.update("insert into listassist.hearing_row (source_file_id, row_no, last_modified, row_problem,"
            + " id_hearing, id_case, id_session, id_jo_1, hearing_date) values"
            + " (?, 0, ?, 'missing_identity', null, 'C1', 'S1', null, date '2040-05-01'),"
            + " (?, 1, ?, 'missing_identity', null, 'C2', null, 'U1', null),"
            + " (?, 2, ?, 'missing_identity', null, 'C3', 'S1', null, date '2040-06-01'),"
            + " (?, 3, ?, 'missing_identity', null, 'C4', 'S9', 'U9', date '2040-05-01')",
            file, MODIFIED, file, MODIFIED, file, MODIFIED, file, MODIFIED);

        CandidateHearings result = candidates.findCandidates("0000001", LocalDate.of(2040, 5, 1));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("MISSING_IDENTITY"))
            .extracting(Diagnostic::count, Diagnostic::sampleIds)
            .containsExactly(tuple(4, List.of("hearing:direct#0", "hearing:direct#1", "session_officer:direct#1",
                "user:direct#1")));
    }

    @Test
    void blankOfficerInactivityKeepsTheAssignmentAndUnrelatedMalformedRowsAreNotReported() {
        jdbc.update("insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp,"
            + " status) values ('hearings', 'direct', 'e', 'Full', '20400501030000', 'ingested')");
        long file = jdbc.queryForObject("select id from listassist.source_file", Long.class);
        jdbc.update("insert into listassist.user_row (source_file_id, row_no, last_modified, id_user, personal_code)"
            + " values (?, 0, ?, 'U1', '0000001')", file, MODIFIED);
        jdbc.update("insert into listassist.session_officer_row (source_file_id, row_no, last_modified, id_session,"
            + " id_jo, inactive_date) values (?, 0, ?, 'S3', 'U1', ''), (?, 1, ?, 'S4', 'U1', ' ')",
            file, MODIFIED, file, MODIFIED);
        jdbc.update("insert into listassist.hearing_row (source_file_id, row_no, last_modified, row_problem,"
            + " id_hearing, id_case, id_session, id_jo_1, hearing_date) values"
            + " (?, 0, ?, null, 'H3', 'C3', 'S3', null, date '2040-05-01'),"
            + " (?, 1, ?, null, 'H4', 'C4', 'S4', null, date '2040-05-01'),"
            // H5 mentions U1 on (C1, S1) only; the malformed (C2, S2) association never does.
            + " (?, 2, ?, null, 'H5', 'C1', 'S1', 'U1', date '2040-06-01'),"
            + " (?, 3, 'bad', 'invalid_last_modified', 'H5', 'C2', 'S2', 'U9', date '2040-05-01')",
            file, MODIFIED, file, MODIFIED, file, MODIFIED, file);

        CandidateHearings result = candidates.findCandidates("0000001", LocalDate.of(2040, 5, 1));

        assertThat(result.candidates()).extracting(Candidate::idHearing, Candidate::basis)
            .containsExactly(tuple("H3", "session"), tuple("H4", "session"));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).doesNotContain("UNORDERABLE_OBSERVATIONS");
    }

    @Test
    void deletedFailedBootstrapFullIsSupersededWhileTheContainerStaysUnbootstrapped() {
        upload("users", USERS_FULL, BinaryData.fromString("not a parquet file"));
        job.runOnce();
        container("users").getBlobClient(USERS_FULL).delete();

        job.runOnce();

        assertThat(jdbc.queryForList("select status from listassist.source_file", String.class))
            .containsExactly("superseded");
        assertThat(jdbc.queryForObject("select count(*) from listassist.container_bootstrap", Integer.class))
            .isZero();
    }

    private static Map<String, String> user(String id, String code, String modified) {
        Map<String, String> row = new HashMap<>();
        row.put("ID_User", id);
        row.put("External_User_ID", code);
        row.put("Last_Modified_Date", modified);
        return row;
    }

    private static String randomHex(long seed, int length) {
        Random random = new Random(seed);
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(Character.forDigit(random.nextInt(16), 16));
        }
        return value.toString();
    }

    private static Map<String, String> hearing(int id, String session) {
        return Map.of("ID_Hearing", "93" + id, "ID_Session", session, "Last_Modified_Date", MODIFIED);
    }

    private static Map<String, String> officer(String session, String officer) {
        return Map.of("ID_Session", session, "ID_JO", officer, "Last_Modified_Date", MODIFIED);
    }

    @SafeVarargs
    private BinaryData parquet(List<String> columns, Map<String, String>... rows) {
        return parquet(columns, List.of(rows));
    }

    private BinaryData parquet(List<String> columns, List<Map<String, String>> rows) {
        Path file = workDirectory.resolve(System.nanoTime() + ".parquet");
        List<Map<String, String>> kept = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> known = new HashMap<>(row);
            known.keySet().retainAll(columns);
            kept.add(known);
        }
        ParquetFixtureFiles.write(file, columns, kept, ParquetFixtureFiles.Layout.DEFAULT);
        return BinaryData.fromFile(file);
    }

    private static void upload(String container, String name, BinaryData data) {
        container(container).getBlobClient(name).upload(data, true);
    }

    private static BlobContainerClient container(String name) {
        BlobContainerClient container = ENV.azurite().client().getBlobContainerClient(name);
        container.createIfNotExists();
        return container;
    }
}
