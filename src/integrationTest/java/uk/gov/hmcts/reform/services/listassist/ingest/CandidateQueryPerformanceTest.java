package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings;
import uk.gov.hmcts.reform.services.listassist.query.ListAssistCandidateRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual: {@code ./gradlew performance -Dlistassist.performance.scale=1.0}. Scale 1.0 generates a large synthetic data
 * set of hearing, session, officer and user rows directly in SQL, with several versions per identity, skewed sessions,
 * duplicate observations, changed personal codes and conflicts, then times the real repository query and prints the
 * plans of its main statements.
 */
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class CandidateQueryPerformanceTest {

    private static final IngestionEnvironment ENV = IngestionEnvironment.start();
    private static final LocalDate EPOCH = LocalDate.of(2039, 1, 1);

    @Autowired
    private ListAssistCandidateRepository candidates;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.register(registry);
    }

    @AfterAll
    static void stop() {
        ENV.close();
    }

    @Test
    void candidateQueryAtLargeVolume() {
        double scale = Double.parseDouble(System.getProperty("listassist.performance.scale", "0.1"));
        int users = 25_000;
        int sessions = (int) (2_000_000 * scale);
        int hearings = (int) (5_000_000 * scale);
        long start = System.nanoTime();
        generate(users, sessions, hearings);
        System.out.printf(Locale.ROOT, "Generated scale %.2f in %s: %s%n", scale, since(start), jdbc.queryForList(
            "select relname, n_live_tup from pg_stat_user_tables where schemaname = 'listassist' order by 1"));

        // Officer U(n) presides over sessions s where s % 31000 + 1 = n; session s is on EPOCH + (s % 730) days. Hot
        // sessions 1, 101, ..., 901 share 1% of all hearings; U102 presides over 101. U20's personal code changed.
        time("ordinary", "0000123", EPOCH.plusDays(122));
        CandidateHearings hot = time("high fan-out", "0000102", EPOCH.plusDays(101));
        time("changed code (new)", "C20", EPOCH.plusDays(19));
        CandidateHearings oldCode = time("changed code (old)", "0000020", EPOCH.plusDays(19));
        assertThat(hot.candidates()).hasSizeGreaterThan(100);
        assertThat(oldCode.candidates()).isEmpty();
        // Every seventh association's newest version moved a day later; it must not match its old date.
        assertThat(hot.candidates()).noneMatch(candidate -> Integer.parseInt(candidate.idHearing()) % 7 == 0);

        explain("select * from listassist.current_hearing_association"
            + " where (id_session = any(?) or id_hearing = any(?)) and (hearing_date = ? or hearing_date is null)",
            new String[] {"101"}, new String[] {"100"}, EPOCH.plusDays(101));
        explain("select id_user from listassist.current_user_account where id_user = any(array("
            + "select id_user from listassist.user_row where personal_code = ?)) and personal_code = ?", "C20", "C20");
        explain("select distinct id_hearing, id_case, id_session from listassist.hearing_row"
            + " where (id_jo_1 = any(?) or id_jo_2 = any(?) or id_jo_3 = any(?))",
            new String[] {"U123"}, new String[] {"U123"}, new String[] {"U123"});
    }

    private CandidateHearings time(String label, String personalCode, LocalDate date) {
        candidates.findCandidates(personalCode, date);
        long start = System.nanoTime();
        CandidateHearings result = candidates.findCandidates(personalCode, date);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf(Locale.ROOT, "findCandidates %s: %d candidates, %s in %s%n", label,
            result.candidates().size(), result.diagnostics().stream().map(d -> d.code()).toList(), elapsed);
        assertThat(elapsed).as(label).isLessThan(Duration.ofSeconds(5));
        return result;
    }

    private void generate(int users, int sessions, int hearings) {
        jdbc.update("insert into listassist.source_file (container, blob_name, etag, extract_kind, file_timestamp,"
            + " status) values ('synthetic', 'synthetic', 'synthetic', 'Full', '20390101000000', 'ingested')");
        long file = jdbc.queryForObject("select id from listassist.source_file where container = 'synthetic'",
            Long.class);
        // Users: one version each; every 20th user's personal code later changes.
        jdbc.update("""
            insert into listassist.user_row (source_file_id, row_no, last_modified, id_user, personal_code)
            select ?, u, '2039-01-01 00:00:00.0000000', 'U' || u, lpad(u::text, 7, '0') from generate_series(1, ?) u
            union all
            select ?, ? + u, '2039-06-01 00:00:00.0000000', 'U' || u, 'C' || u
              from generate_series(20, ?, 20) u
            """, file, users, file, users, users);
        // Sessions: three versions each, the last cancelling every 50th session.
        jdbc.update("""
            insert into listassist.session_row (source_file_id, row_no, last_modified, id_session, session_date_raw,
                                                session_date, cd_court, cd_room, session_status, cancelled_date)
            select ?, 1000000 + s * 3 + v, format('2039-01-01 00:00:0%s.0000000', v), s::text,
                   to_char(date '2039-01-01' + s % 730, 'YYYY-MM-DD') || ' 00:00:00.0000000',
                   date '2039-01-01' + s % 730, 'C' || s % 400, 'R' || s % 4000, 'v' || v,
                   case when v = 2 and s % 50 = 0 then '2039-01-01 00:00:00.0000000' end
              from generate_series(1, ?) s, generate_series(0, 2) v
            """, file, sessions);
        // Officers: one per session, a second on every tenth (panels).
        jdbc.update("""
            insert into listassist.session_officer_row (source_file_id, row_no, last_modified, id_session, id_jo,
                                                        jo_presiding)
            select ?, 20000000 + s, '2039-01-01 00:00:00.0000000', s::text, 'U' || (s % ? + 1), 'True'
              from generate_series(1, ?) s
            union all
            select ?, 30000000 + s, '2039-01-01 00:00:00.0000000', s::text, 'U' || ((s + 7) % ? + 1), 'False'
              from generate_series(10, ?, 10) s
            """, file, users, sessions, file, users, sessions);
        // Hearings: three versions per association; every 100th hearing lands on a hot session; every seventh moves a
        // day later in its newest version; every 50th newest version is delivered twice; every 1000th has a
        // conflicting duplicate.
        jdbc.update("""
            insert into listassist.hearing_row (source_file_id, row_no, last_modified, id_hearing, id_case, case_no,
                                                id_session, id_jo_1, hearing_date_raw, hearing_date, start_time_24h,
                                                duration, listing_cancelled_flag, heard_flag)
            select ?, 40000000 + h * 5 + v, format('2039-01-01 00:00:0%s.0000000', least(v, 2)), h::text,
                   'C' || h, 'CASE-' || h, sid::text, 'U' || (sid % ? + 1),
                   to_char(date '2039-01-01' + day, 'YYYY-MM-DD') || ' 00:00:00.0000000',
                   date '2039-01-01' + day, lpad((9 + h % 8)::text, 2, '0') || ':00',
                   case v when 3 then '32' when 4 then '999' else (30 + v)::text end, '0', 'No'
              from (select h, case when h % 100 = 0 then 1 + h % 1000 else 1 + h % ? end as sid
                      from generate_series(1, ?) h) g,
                   generate_series(0, 4) v,
                   lateral (select sid % 730 + case when v >= 2 and h % 7 = 0 then 1 else 0 end as day) d
             where v < 3 or (v = 3 and h % 50 = 0) or (v = 4 and h % 1000 = 0)
            """, file, users, sessions, hearings);
        jdbc.execute("analyze");
    }

    private void explain(String sql, Object... args) {
        System.out.println("EXPLAIN " + sql);
        jdbc.queryForList("explain (analyze, buffers) " + sql, String.class, args).forEach(System.out::println);
    }

    private static String since(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toString();
    }
}
