package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.ListAssistSchemaInventory;
import uk.gov.hmcts.reform.services.listassist.fixtures.ReassignmentScenario;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Candidate;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Diagnostic;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.SessionOfficer;
import uk.gov.hmcts.reform.services.listassist.query.ListAssistCandidateRepository;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Progressive changes: moves, cancellation and reinstatement, officer changes, a personal-code change, equal-timestamp
 * conflicts and a corrected file overwriting an earlier Blob name. Expectations are written out by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class ReassignmentIngestionTest {

    private static final IngestionEnvironment ENV = IngestionEnvironment.start();
    private static final LocalDate DAY_ONE = LocalDate.of(2040, 3, 12);
    private static final LocalDate DAY_TWO = LocalDate.of(2040, 3, 13);

    @Autowired
    private ListAssistIngestionJob job;
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

    private static String key(String hearing, String caseId, String session) {
        return "[\"" + hearing + "\", \"" + caseId + "\", \"" + session + "\"]";
    }

    @Test
    void currentStateFollowsSourceVersionsAndExposesConflicts() {
        GeneratedScenario generated = FixtureGenerator.generate(
            ReassignmentScenario.build(ListAssistSchemaInventory.load(), 23), FixtureGenerator.defaultOutputRoot()
                .resolve("ingestion"));
        Map<String, String> s = generated.manifest().symbols();
        final String oldCode = s.get("officer_old_personal_code");
        final String newCode = s.get("officer_old_personal_code_replacement");

        ENV.seed(generated, 1);
        job.runOnce();
        ENV.seed(generated, 2);
        job.runOnce();

        // Phase 2: hearing_cancel is cancelled, so only the original hearing_move association remains for officer_old.
        CandidateHearings afterCancel = candidates.findCandidates(oldCode, DAY_ONE);
        assertThat(afterCancel.candidates()).extracting(Candidate::idHearing, Candidate::idSession)
            .containsExactly(tuple(s.get("hearing_move"), s.get("session_r1")));

        ENV.seed(generated, 3);
        job.runOnce();

        // I08: the old personal code no longer matches; the replacement code finds the reinstated hearing (L02, D03).
        assertThat(candidates.findCandidates(oldCode, DAY_ONE).diagnostics()).extracting(Diagnostic::code)
            .containsExactly("PERSONAL_CODE_UNMATCHED");
        CandidateHearings old = candidates.findCandidates(newCode, DAY_ONE);
        assertThat(old.diagnostics()).isEmpty();
        assertThat(old.candidates()).extracting(Candidate::idHearing, Candidate::idSession, Candidate::basis)
            .containsExactlyInAnyOrder(
                tuple(s.get("hearing_move"), s.get("session_r1"), "both"),
                tuple(s.get("hearing_cancel"), s.get("session_r1"), "both"));

        // I02: officer_new's later non-presiding seat on session_r1 is a candidate, alongside every earlier seat.
        CandidateHearings fresh = candidates.findCandidates(s.get("officer_new_personal_code"), DAY_ONE);
        assertThat(fresh.candidates()).extracting(Candidate::idHearing, Candidate::idSession, Candidate::basis)
            .containsExactlyInAnyOrder(
                tuple(s.get("hearing_move"), s.get("session_r1"), "session"),
                tuple(s.get("hearing_cancel"), s.get("session_r1"), "session"),
                tuple(s.get("hearing_heard"), s.get("session_r2"), "both"));
        Candidate onR1 = fresh.candidates().stream().filter(c -> c.idSession().equals(s.get("session_r1")))
            .findFirst().orElseThrow();
        assertThat(onR1.sessionOfficers()).extracting(SessionOfficer::idJo, SessionOfficer::presiding)
            .containsExactlyInAnyOrder(tuple(s.get("officer_old"), "True"),
                tuple(s.get("officer_placeholder"), "False"), tuple(s.get("officer_new"), "False"));
        // L04: heard stays visible and is not cancelled.
        assertThat(fresh.candidates()).filteredOn(c -> c.idHearing().equals(s.get("hearing_heard")))
            .extracting(Candidate::heardFlag).containsExactly("Yes");
        // D04 and the corrected overwrite (same Last_Modified, different duration) are conflicts, not candidates.
        assertThat(fresh.diagnostics()).extracting(Diagnostic::code, Diagnostic::count)
            .containsExactly(tuple("HEARING_CONFLICT", 2));
        assertThat(fresh.diagnostics().getFirst().sampleIds()).containsExactlyInAnyOrder(
            key(s.get("hearing_conflict"), s.get("case_conflict"), s.get("session_r2")),
            key(s.get("hearing_move"), s.get("case_move"), s.get("session_r2")));

        // L03: a cancelled session hides its hearing from candidates but not from storage.
        assertThat(candidates.findCandidates(newCode, DAY_TWO).candidates()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from listassist.current_hearing_association"
            + " where id_hearing = ?", Integer.class, s.get("hearing_session_cancelled"))).isOne();

        // B08: both versions of the overwritten Blob are in the ledger as separate ingested versions.
        assertThat(jdbc.queryForObject("select count(distinct etag) from listassist.source_file"
            + " where status = 'ingested' and blob_name like '%Incr_vhmcts_Hearings-20400311110000%'",
            Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select dataset from listassist.observation_conflict", String.class))
            .containsOnly("hearing").hasSize(2);
    }
}
