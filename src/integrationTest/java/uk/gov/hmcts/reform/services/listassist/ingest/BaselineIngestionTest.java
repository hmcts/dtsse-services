package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.services.listassist.fixtures.BaselineScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.ListAssistSchemaInventory;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Candidate;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Diagnostic;
import uk.gov.hmcts.reform.services.listassist.query.ListAssistCandidateRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Runs the Spring application in emulator auth mode against the baseline fixture world, phase by phase.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class BaselineIngestionTest {

    private static final IngestionEnvironment ENV = IngestionEnvironment.start();
    private static final LocalDate DAY_ONE = LocalDate.of(2040, 1, 31);
    private static final LocalDate DAY_TWO = LocalDate.of(2040, 2, 1);

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

    @Test
    void ingestsPhasesAndAnswersCandidateQueries() {
        GeneratedScenario generated = FixtureGenerator.generate(
            BaselineScenario.build(ListAssistSchemaInventory.load(), 17), FixtureGenerator.defaultOutputRoot()
                .resolve("ingestion"));
        Map<String, String> symbols = generated.manifest().symbols();
        final String codeA = symbols.get("officer_a_personal_code");
        final String codeD = symbols.get("officer_d_personal_code");

        ENV.seed(generated, 1);
        assertThat(job.runOnce()).isTrue();
        assertThat(jdbc.queryForList("select container from listassist.container_bootstrap order by 1", String.class))
            .containsExactly("hearings", "session-officers", "sessions", "users");
        assertThat(jdbc.queryForObject("select count(*) from listassist.source_file where status = 'ingested'",
            Integer.class)).isEqualTo(5);

        CandidateHearings dayOneA = candidates.findCandidates(codeA, DAY_ONE);
        assertThat(dayOneA.diagnostics()).isEmpty();
        assertThat(dayOneA.candidates())
            .extracting(Candidate::idHearing, Candidate::caseNo, Candidate::basis, Candidate::sessionState)
            .containsExactlyInAnyOrder(
                tuple(symbols.get("hearing_a"), "TEST-CASE-A", "both", "known"),
                tuple(symbols.get("hearing_a"), "TEST-CASE-B", "both", "known"),
                tuple(symbols.get("hearing_b"), "TEST-000123/2040", "both", "known"),
                tuple(symbols.get("hearing_c"), "TEST-40000123", "both", "known"));
        // Same start time is not a key: ordering is deterministic by time, then source identity.
        assertThat(dayOneA.candidates()).extracting(Candidate::startTime24h)
            .containsExactly("10:00", "10:00", "10:00", "12:45");
        assertThat(dayOneA.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.matchedUserIds()).containsExactly(symbols.get("officer_a"));
            assertThat(candidate.locality()).isEqualTo("Synthetic Venue North");
            assertThat(candidate.location()).isEqualTo("Synthetic North Courtroom 1");
        });

        CandidateHearings panel = candidates.findCandidates(symbols.get("officer_b_personal_code"), DAY_ONE);
        assertThat(panel.candidates()).extracting(Candidate::idHearing, Candidate::caseNo)
            .containsExactlyInAnyOrder(tuple(symbols.get("hearing_reading_time"), null),
                tuple(symbols.get("hearing_e"), "TEST-CASE-A"));
        assertThat(panel.candidates().getFirst().sessionOfficers()).hasSize(2);

        // Phase 2 assigns officer_d before their personal-code mapping exists.
        ENV.seed(generated, 2);
        job.runOnce();
        assertThat(codes(candidates.findCandidates(codeD, DAY_TWO).diagnostics()))
            .containsExactly("PERSONAL_CODE_UNMATCHED");

        ENV.seed(generated, 3);
        job.runOnce();
        CandidateHearings dayTwoD = candidates.findCandidates(codeD, DAY_TWO);
        assertThat(dayTwoD.diagnostics()).isEmpty();
        assertThat(dayTwoD.candidates()).extracting(Candidate::idHearing, Candidate::basis)
            .containsExactly(tuple(symbols.get("hearing_h"), "both"));
        assertThat(dayTwoD.containers()).allSatisfy(status -> {
            assertThat(status.bootstrapped()).isTrue();
            assertThat(status.failedFiles()).isZero();
        });

        // Replaying a run finds nothing new and duplicates nothing.
        int rows = jdbc.queryForObject("select count(*) from listassist.hearing_row", Integer.class);
        job.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from listassist.hearing_row", Integer.class))
            .isEqualTo(rows);
        assertThat(jdbc.queryForObject("select count(*) from listassist.source_file", Integer.class))
            .isEqualTo(generated.manifest().objects().size());
        assertThat(jdbc.queryForObject("select count(*) from listassist.observation_conflict", Integer.class))
            .isZero();

        // Privacy sentinels in ignored source columns never reach the database.
        for (String table : List.of("hearing_row", "session_row", "session_officer_row", "user_row")) {
            assertThat(jdbc.queryForObject("select count(*) from listassist." + table + " t where t::text ~ ?",
                Integer.class, "SENTINEL|example\\.invalid|Synthetic Officer")).as(table).isZero();
        }
    }

    private static List<String> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::code).toList();
    }
}
