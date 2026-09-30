package uk.gov.hmcts.reform.services.listassist.fixtures;

import com.azure.storage.blob.models.BlobItem;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks what the emulator actually delivers against the external manifest: exact Blob names per allowlisted
 * container, byte-for-byte content and the physical Parquet schema. Decoded rows keep their object provenance.
 */
public final class FixtureVerifier {

    private final ListAssistBlobReader reader;
    private final ListAssistSchemaInventory inventory;
    private final Path workDirectory;

    public FixtureVerifier(ListAssistBlobReader reader, ListAssistSchemaInventory inventory, Path workDirectory) {
        this.reader = reader;
        this.inventory = inventory;
        this.workDirectory = workDirectory;
    }

    /**
     * Verifies what storage holds after {@code phase}; merge successive reports to keep every delivered version.
     */
    public Report verify(FixtureManifest manifest, int phase) {
        List<FixtureManifest.ObjectEntry> stored = manifest.storedAfterPhase(phase);
        Map<String, String> etags = new HashMap<>();
        for (ListAssistContainer container : ListAssistContainer.values()) {
            Set<String> expectedNames = stored.stream()
                .filter(object -> object.container().equals(container.key()))
                .map(FixtureManifest.ObjectEntry::blobName)
                .collect(Collectors.toCollection(TreeSet::new));
            Set<String> listed = new TreeSet<>();
            for (BlobItem item : reader.list(container)) {
                listed.add(item.getName());
                etags.put(container.key() + "/" + item.getName(), item.getProperties().getETag());
            }
            if (!listed.equals(expectedNames)) {
                throw new AssertionError(container.key() + " listed " + listed + " but manifest expects "
                    + expectedNames);
            }
        }
        Map<ObjectKey, List<Map<String, String>>> observed = new LinkedHashMap<>();
        for (FixtureManifest.ObjectEntry object : stored) {
            observed.put(new ObjectKey(object.container(), object.blobName(), object.sha256()),
                downloadAndDecode(object, etags.get(object.container() + "/" + object.blobName())).rows());
        }
        return new Report(observed);
    }

    private ParquetFixtureFiles.Decoded downloadAndDecode(FixtureManifest.ObjectEntry object, String etag) {
        String blob = object.container() + "/" + object.blobName();
        ListAssistContainer container = Arrays.stream(ListAssistContainer.values())
            .filter(candidate -> candidate.key().equals(object.container()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Manifest container is not allowlisted: " + blob));
        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        reader.download(container, object.blobName(), etag, downloaded);
        byte[] bytes = downloaded.toByteArray();
        if (bytes.length != object.contentLength() || !FixtureGenerator.sha256(bytes).equals(object.sha256())) {
            throw new AssertionError("Downloaded content differs from manifest for " + blob);
        }
        ParquetFixtureFiles.Decoded decoded = ParquetFixtureFiles.read(writeTemp(bytes));
        List<String> columns = inventory.observed(object.container()).columns();
        if (!decoded.columnNames().equals(columns)) {
            throw new AssertionError("Decoded columns differ from inventory for " + blob);
        }
        if (!decoded.allLeavesAreOptionalStrings()) {
            throw new AssertionError("Decoded schema is not nullable UTF-8 strings for " + blob);
        }
        if (object.rowCount() > 0 && !decoded.codecs().equals(Set.of(CompressionCodecName.SNAPPY))) {
            throw new AssertionError("Decoded column chunks are not Snappy for " + blob + ": " + decoded.codecs());
        }
        if (decoded.rows().size() != object.rowCount()) {
            throw new AssertionError("Decoded " + decoded.rows().size() + " rows from " + blob + ", manifest says "
                + object.rowCount());
        }
        return decoded;
    }

    private Path writeTemp(byte[] bytes) {
        try {
            Files.createDirectories(workDirectory);
            Path file = Files.createTempFile(workDirectory, "download-", ".parquet");
            Files.write(file, bytes);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record ObjectKey(String container, String blobName, String sha256) {
    }

    /**
     * Decoded rows keyed by the exact object version they came from. Evidence and latest state are rebuilt from raw
     * source column names; nothing is rejected merely because it disagrees with an earlier observation.
     */
    public record Report(Map<ObjectKey, List<Map<String, String>>> observed) {

        public Report merge(Report later) {
            Map<ObjectKey, List<Map<String, String>>> merged = new LinkedHashMap<>(observed);
            merged.putAll(later.observed());
            return new Report(merged);
        }

        public List<Map<String, String>> rows(String container) {
            return observed.entrySet().stream()
                .filter(entry -> entry.getKey().container().equals(container))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        }

        public FixtureManifest.Evidence evidence() {
            ExpectedBuilder evidence = new ExpectedBuilder();
            for (Map<String, String> row : rows(BaselineScenario.HEARINGS)) {
                evidence.hearing(row.get("ID_Hearing"), row.get("Case_No"), row.get("ID_Session"),
                    row.get("CD_Locality"), row.get("CD_Location"));
            }
            for (Map<String, String> row : rows(BaselineScenario.SESSIONS)) {
                evidence.session(row.get("ID_Session"), row.get("CD_Court"), row.get("CD_Room"));
            }
            rows(BaselineScenario.SESSION_OFFICERS).forEach(row ->
                evidence.sessionOfficer(row.get("ID_Session"), row.get("ID_JO")));
            rows(BaselineScenario.USER).forEach(row -> evidence.user(row.get("ID_User"), row.get("External_User_ID")));
            return evidence.evidence();
        }

        public Map<String, Map<String, String>> latestHearingLifecycle() {
            return latestHearings().states();
        }

        public Map<String, Map<String, String>> latestSessionLifecycle() {
            return latestSessions().states();
        }

        public List<String> lifecycleConflicts() {
            return Stream.concat(latestHearings().conflicts().stream(), latestSessions().conflicts().stream())
                .sorted().toList();
        }

        private LatestState latestHearings() {
            return latest(BaselineScenario.HEARINGS,
                row -> ExpectedBuilder.hearingKey(row.get("ID_Hearing"), row.get("ID_Case")),
                "Last_Modified_Date", SyntheticRows.HEARING_LIFECYCLE);
        }

        private LatestState latestSessions() {
            return latest(BaselineScenario.SESSIONS, row -> row.get("ID_Session"), "Last_Modified_date",
                SyntheticRows.SESSION_LIFECYCLE);
        }

        /**
         * Reference reading of {@link FixtureManifest.Expected#LATEST_POLICY}. Rows are grouped by identity and
         * timestamp first, so conflicts and the chosen state never depend on delivery or listing order. The
         * fixed-width source timestamp orders correctly as text.
         */
        private LatestState latest(String container, Function<Map<String, String>, String> key,
                                   String modifiedColumn, List<String> fields) {
            Map<String, TreeMap<String, Set<Map<String, String>>>> versions = new TreeMap<>();
            for (Map<String, String> row : rows(container)) {
                String modified = row.get(modifiedColumn);
                if (modified == null) {
                    throw new AssertionError(container + " row without " + modifiedColumn + " for " + key.apply(row));
                }
                versions.computeIfAbsent(key.apply(row), k -> new TreeMap<>())
                    .computeIfAbsent(modified, m -> new HashSet<>())
                    .add(project(row, fields));
            }
            Map<String, Map<String, String>> states = new LinkedHashMap<>();
            List<String> conflicts = new ArrayList<>();
            versions.forEach((rowKey, byModified) -> {
                byModified.forEach((modified, values) -> {
                    if (values.size() > 1) {
                        conflicts.add(FixtureManifest.Expected.conflict(container, rowKey, modified));
                    }
                });
                Set<Map<String, String>> newest = byModified.lastEntry().getValue();
                if (newest.size() == 1) {
                    states.put(rowKey, newest.iterator().next());
                }
            });
            return new LatestState(states, conflicts);
        }

        private static Map<String, String> project(Map<String, String> row, List<String> fields) {
            Map<String, String> projected = new LinkedHashMap<>();
            fields.forEach(field -> projected.put(field, row.get(field)));
            return projected;
        }

        private record LatestState(Map<String, Map<String, String>> states, List<String> conflicts) {
        }
    }
}
