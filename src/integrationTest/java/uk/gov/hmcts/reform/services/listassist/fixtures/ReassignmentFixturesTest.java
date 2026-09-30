package uk.gov.hmcts.reform.services.listassist.fixtures;

import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureSeeder.SeedResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against its own emulator so the real container names never collide with the baseline scenario.
 */
class ReassignmentFixturesTest {

    private static final long SEED = 23;

    private static final ListAssistSchemaInventory INVENTORY = ListAssistSchemaInventory.load();

    private static AzuriteEmulator emulator;

    @TempDir
    Path workDirectory;

    @BeforeAll
    static void startEmulator() {
        emulator = AzuriteEmulator.start();
    }

    @AfterAll
    static void stopEmulator() {
        if (emulator != null) {
            emulator.close();
        }
    }

    @Test
    void progressiveChangesPreserveEvidenceAndExposeLatestStateAndOverwrites() {
        GeneratedScenario generated = FixtureGenerator.generate(ReassignmentScenario.build(INVENTORY, SEED),
            FixtureGenerator.defaultOutputRoot());
        FixtureManifest manifest = generated.manifest();
        Map<String, String> symbols = manifest.symbols();
        String cancelKey = ExpectedBuilder.hearingKey(symbols.get("hearing_cancel"), symbols.get("case_cancel"));
        final String correctedName = manifest.objects().stream()
            .filter(object -> "overwrite".equals(object.upload()))
            .map(FixtureManifest.ObjectEntry::blobName).findFirst().orElseThrow();
        BlobServiceClient client = emulator.client();
        FixtureSeeder seeder = new FixtureSeeder(client);
        FixtureVerifier verifier = new FixtureVerifier(
            new ListAssistBlobReader(client, AzuriteEmulator.CONTAINER_NAMES), INVENTORY,
            workDirectory.resolve("downloads"));

        seeder.seed(generated, 1);
        FixtureVerifier.Report history = verifier.verify(manifest, 1);
        assertThat(history.latestHearingLifecycle().get(cancelKey)).isEqualTo(SyntheticRows.listedHearing());

        final List<SeedResult> phaseTwo = seeder.seed(generated, 2);
        history = history.merge(verifier.verify(manifest, 2));
        assertThat(history.latestHearingLifecycle().get(cancelKey))
            .isEqualTo(SyntheticRows.cancelledHearing(ReassignmentScenario.CANCELLED_AT));

        final List<SeedResult> phaseThree = seeder.seed(generated, 3);
        history = history.merge(verifier.verify(manifest, 3));

        // L02 and D03: the reinstatement's later null is not masked, and the stale cancelled row delivered last
        // does not regress it.
        FixtureManifest.Expected expected = manifest.expected();
        assertThat(history.latestHearingLifecycle()).isEqualTo(expected.latestHearingLifecycle());
        assertThat(history.latestHearingLifecycle().get(cancelKey).get("Listing_Cancelled_Date")).isNull();
        assertThat(history.latestSessionLifecycle()).isEqualTo(expected.latestSessionLifecycle());

        // L04: heard is its own evidence, not cancellation.
        String heardKey = ExpectedBuilder.hearingKey(symbols.get("hearing_heard"), symbols.get("case_heard"));
        assertThat(history.latestHearingLifecycle().get(heardKey))
            .containsEntry("Heard_Flag", "Yes").containsEntry("Listing_Cancelled_Flag", "0");
        assertThat(history.rows(BaselineScenario.HEARINGS)).extracting(row -> row.get("Heard_Flag"))
            .containsOnly("No", "Yes").contains("No", "Yes");

        // D04: disagreeing rows with one timestamp are recorded, and no latest state is invented for them.
        String conflictKey = ExpectedBuilder.hearingKey(symbols.get("hearing_conflict"), symbols.get("case_conflict"));
        assertThat(history.lifecycleConflicts()).isEqualTo(expected.lifecycleConflicts()).containsExactly(
            FixtureManifest.Expected.conflict(BaselineScenario.HEARINGS, conflictKey,
                ReassignmentScenario.CONFLICT_MODIFIED));
        assertThat(history.latestHearingLifecycle()).doesNotContainKey(conflictKey);

        // I02, I04, I08, I10: every observation survives; nothing is collapsed to a guessed current value.
        FixtureManifest.Evidence evidence = expected.evidence();
        assertThat(history.evidence()).isEqualTo(evidence);
        assertThat(evidence.hearingSessions().get(symbols.get("hearing_move")))
            .containsExactlyInAnyOrder(symbols.get("session_r1"), symbols.get("session_r2"));
        assertThat(evidence.hearingSessions().get(symbols.get("hearing_multi_day")))
            .containsExactlyInAnyOrder(symbols.get("session_m1"), symbols.get("session_m2"));
        assertThat(evidence.sessionOfficers().get(symbols.get("session_r1"))).containsExactlyInAnyOrder(
            symbols.get("officer_old"), symbols.get("officer_placeholder"), symbols.get("officer_new"));
        assertThat(evidence.userPersonalCodes().get(symbols.get("officer_old"))).hasSize(2);
        assertThat(history.rows(BaselineScenario.HEARINGS))
            .filteredOn(row -> symbols.get("hearing_cancel").equals(row.get("ID_Hearing")))
            .extracting(row -> row.get("ID_JO_1")).containsOnly(symbols.get("officer_old"));
        assertThat(expected.unresolved()).extracting(FixtureManifest.Unresolved::matrixId)
            .containsExactlyInAnyOrder("D04", "I02", "I04", "I08", "I10", "L03");

        // B08: the corrected file replaced the same Blob name with a new ETag; both versions stay in history.
        SeedResult original = phaseTwo.stream().filter(result -> result.blobName().equals(correctedName))
            .findFirst().orElseThrow();
        SeedResult corrected = phaseThree.stream().filter(result -> result.blobName().equals(correctedName))
            .findFirst().orElseThrow();
        assertThat(corrected.action()).isEqualTo("overwritten");
        assertThat(corrected.eTag()).isNotEqualTo(original.eTag());
        assertThat(history.rows(BaselineScenario.HEARINGS))
            .filteredOn(row -> symbols.get("hearing_move").equals(row.get("ID_Hearing")))
            .extracting(row -> row.get("Hearing_Duration")).contains("-30", "30");

        // Reseeding an earlier phase cannot silently roll back the corrected Blob.
        assertThatThrownBy(() -> seeder.seed(generated, 2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no overwrite is planned");
    }
}
