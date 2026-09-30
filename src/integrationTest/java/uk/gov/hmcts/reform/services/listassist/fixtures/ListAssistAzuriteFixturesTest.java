package uk.gov.hmcts.reform.services.listassist.fixtures;

import com.azure.core.http.rest.PagedResponse;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureSeeder.SeedResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListAssistAzuriteFixturesTest {

    private static final long SEED = 17;

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
    void inventoryHasTheSpecifiedObservedColumnProfiles() {
        assertThat(INVENTORY.observed("hearings").columns()).hasSize(31)
            .contains("ID_JO_1", "ID_JO_2", "ID_JO_3", SyntheticRows.UNSELECTED_SENTINEL, "extraction_date");
        assertThat(INVENTORY.observed("sessions").columns()).hasSize(19)
            .contains("Last_Modified_date", "INACTIVE_DATE", "cancelled_date");
        assertThat(INVENTORY.observed("session-officers").columns()).hasSize(10)
            .contains("ID_Booking", "inactive_date");
        assertThat(INVENTORY.observed("users").columns()).hasSize(9)
            .contains("External_User_ID", "Active_to_Date");
        assertThat(INVENTORY.observed("hearings").viewToken()).isEqualTo("vhmcts_Hearings");
        assertThat(INVENTORY.observed("session-officers").viewToken()).isEqualTo("vhmcts_Sessions_JOfficer");
    }

    @Test
    void generationIsByteForByteDeterministicForASeed() {
        GeneratedScenario first = FixtureGenerator.generate(BaselineScenario.build(INVENTORY, SEED),
            workDirectory.resolve("first"));
        GeneratedScenario second = FixtureGenerator.generate(BaselineScenario.build(INVENTORY, SEED),
            workDirectory.resolve("second"));
        GeneratedScenario otherSeed = FixtureGenerator.generate(BaselineScenario.build(INVENTORY, SEED + 1),
            workDirectory.resolve("other"));

        assertThat(second.manifest()).isEqualTo(first.manifest());
        assertThat(otherSeed.manifest().symbols()).isNotEqualTo(first.manifest().symbols());
    }

    @Test
    void baselineUsesObservedBlobLayoutAndPhysicalShape() {
        GeneratedScenario generated = FixtureGenerator.generate(BaselineScenario.build(INVENTORY, SEED),
            workDirectory);

        assertThat(generated.manifest().objects())
            .extracting(object -> object.container() + "/" + object.blobName())
            .contains(
                "hearings/2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet",
                "hearings/2040-01/2040-01-30-dbo-Incr_vhmcts_Hearings-20400130110000-data.parquet",
                "session-officers/2040-01/2040-01-30-dbo-Full_vhmcts_Sessions_JOfficer-20400130030205-data"
                    + ".parquet",
                "users/2040-02/2040-02-01-dbo-Incr_vhmcts_user-20400201023000-data.parquet");

        ParquetFixtureFiles.Decoded decoded = ParquetFixtureFiles.read(
            generated.blobFile(generated.manifest().objects().stream()
                .filter(object -> object.blobName().contains("Full_vhmcts_Hearings")).findFirst().orElseThrow()));
        assertThat(decoded.allLeavesAreOptionalStrings()).isTrue();
        // All-null columns keep their declared nullable string type.
        assertThat(decoded.rows()).allSatisfy(row -> assertThat(row.get(SyntheticRows.UNSELECTED_NULL)).isNull());
        assertThat(decoded.columnNames()).contains(SyntheticRows.UNSELECTED_NULL);
        assertThat(decoded.rows()).extracting(row -> row.get("Hearing_DateTime"))
            .contains("2040-01-31 10:00:00.0000000");
        assertThat(decoded.rows()).extracting(row -> row.get("Last_Modified_Date"))
            .allMatch(value -> value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{7}"));
    }

    @Test
    void seedsPhasesProgressivelyAndTheApplicationReaderSeesExactlyTheManifest() {
        GeneratedScenario generated = FixtureGenerator.generate(BaselineScenario.build(INVENTORY, SEED),
            FixtureGenerator.defaultOutputRoot());
        FixtureManifest manifest = generated.manifest();
        BlobServiceClient client = emulator.client();
        FixtureSeeder seeder = new FixtureSeeder(client);
        FixtureVerifier verifier = new FixtureVerifier(
            new ListAssistBlobReader(client, AzuriteEmulator.CONTAINER_NAMES), INVENTORY,
            workDirectory.resolve("downloads"));

        seeder.seed(generated, 1);
        FixtureVerifier.Report history = verifier.verify(manifest, 1);
        assertThat(history.rows(BaselineScenario.USER)).extracting(row -> row.get("ID_User"))
            .doesNotContain(manifest.symbols().get("officer_d"));
        seeder.seed(generated, 2);
        history = history.merge(verifier.verify(manifest, 2));
        final List<SeedResult> phaseThree = seeder.seed(generated, 3);
        history = history.merge(verifier.verify(manifest, 3));

        FixtureManifest.Expected expected = manifest.expected();
        assertThat(history.evidence()).isEqualTo(expected.evidence());
        assertThat(history.latestHearingLifecycle()).isEqualTo(expected.latestHearingLifecycle());
        assertThat(history.latestSessionLifecycle()).isEqualTo(expected.latestSessionLifecycle());
        assertThat(history.lifecycleConflicts()).isEqualTo(expected.lifecycleConflicts()).isEmpty();
        assertThat(expected.unresolved()).isEmpty();

        // Source flag encodings.
        assertThat(history.rows(BaselineScenario.SESSION_OFFICERS)).extracting(row -> row.get("JO_Presiding"))
            .containsOnly("True", "False").contains("True", "False");
        assertThat(history.rows(BaselineScenario.HEARINGS)).extracting(row -> row.get("Heard_Flag"))
            .containsOnly("No");

        Map<String, String> symbols = manifest.symbols();
        FixtureManifest.Evidence evidence = expected.evidence();
        Map<String, List<String>> hearingCases = evidence.hearingCaseReferences();
        assertThat(hearingCases.get(symbols.get("hearing_a"))).containsExactly("TEST-CASE-A", "TEST-CASE-B");
        assertThat(hearingCases.get(symbols.get("hearing_b"))).containsExactly("TEST-000123/2040");
        assertThat(hearingCases.get(symbols.get("hearing_reading_time"))).isEmpty();
        assertThat(hearingCases.values().stream().filter(cases -> cases.contains("TEST-CASE-A"))).hasSize(2);
        assertThat(evidence.sessionOfficers().get(symbols.get("session_b"))).hasSize(2);
        assertThat(evidence.hearingSessions().get(symbols.get("hearing_unassigned"))).isEmpty();
        assertThat(evidence.userPersonalCodes().get(symbols.get("officer_a"))).singleElement()
            .asString().startsWith("0");
        assertThat(evidence.userPersonalCodes().get(symbols.get("officer_placeholder"))).isEmpty();

        // Hearing Locality/Location join to the session's Court/Room, not the court twice.
        evidence.hearingSessions().forEach((hearing, sessions) -> assertThat(evidence.hearingLocations().get(hearing))
            .containsExactlyInAnyOrderElementsOf(sessions.stream()
                .flatMap(session -> evidence.sessionLocations().get(session).stream()).distinct().toList()));
        assertThat(evidence.hearingLocations().get(symbols.get("hearing_a")))
            .containsExactly(symbols.get("venue_north") + ":" + symbols.get("room_north_1"));

        // Privacy sentinels really are delivered, so later ingestion tests can prove they are dropped.
        List<String> delivered = history.observed().values().stream().flatMap(List::stream)
            .flatMap(row -> row.values().stream()).filter(Objects::nonNull).toList();
        assertThat(manifest.privacySentinels()).isNotEmpty().allSatisfy(sentinel ->
            assertThat(delivered).contains(sentinel));

        // Reseeding identical content leaves existing Blobs untouched.
        Map<String, String> etags = etagsByBlob(phaseThree);
        List<SeedResult> reseeded = seeder.seed(generated, 3);
        assertThat(reseeded).extracting(SeedResult::action).containsOnly("unchanged");
        assertThat(etagsByBlob(reseeded)).isEqualTo(etags);

        // B04: an explicitly small page size forces continuation tokens; every object is still listed once.
        List<String> paged = new ArrayList<>();
        int pages = 0;
        for (PagedResponse<BlobItem> page : client.getBlobContainerClient(BaselineScenario.HEARINGS)
            .listBlobs(new ListBlobsOptions().setMaxResultsPerPage(1), null).iterableByPage()) {
            pages++;
            page.getValue().forEach(item -> paged.add(item.getName()));
        }
        assertThat(pages).isEqualTo(3);
        assertThat(paged).containsExactlyInAnyOrderElementsOf(manifest.storedAfterPhase(3).stream()
            .filter(object -> object.container().equals(BaselineScenario.HEARINGS))
            .map(FixtureManifest.ObjectEntry::blobName).toList());
    }

    @Test
    void emulatorClientRejectsNonEmulatorEndpointsAndWrongKeys() {
        Set<String> hosts = Set.of("127.0.0.1", "localhost");
        assertThatThrownBy(() -> AzuriteEmulator.requireEmulatorEndpoint(
            "https://example.blob.core.windows.net/", hosts)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AzuriteEmulator.requireEmulatorEndpoint(
            "http://127.0.0.1:10000/someotheraccount", hosts)).isInstanceOf(IllegalStateException.class);

        String wrongKey = "d3Jvbmcta2V5LW9ubHktZm9yLWEtbmVnYXRpdmUtYXV0aGVudGljYXRpb24tdGVzdA==";
        ListAssistBlobReader reader = new ListAssistBlobReader(emulator.client(wrongKey),
            AzuriteEmulator.CONTAINER_NAMES);
        assertThatThrownBy(() -> reader.list(ListAssistContainer.HEARINGS).iterator().hasNext())
            .isInstanceOf(BlobStorageException.class)
            .satisfies(e -> assertThat(((BlobStorageException) e).getStatusCode()).isEqualTo(403));
    }

    private static Map<String, String> etagsByBlob(List<SeedResult> results) {
        return results.stream().collect(Collectors.toMap(SeedResult::blobName, SeedResult::eTag,
            (left, right) -> left, TreeMap::new));
    }
}
