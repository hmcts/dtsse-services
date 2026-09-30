package uk.gov.hmcts.reform.services.listassist.fixtures;

import org.apache.parquet.Version;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a scenario to {@code <root>/<scenario>/blobs/phase-<n>/<container>/<blob name>} plus an external
 * {@code manifest.json}, decoding every file locally before it can be seeded.
 */
public final class FixtureGenerator {

    public static final String SCHEMA_PROFILE = "observed-baseline";

    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private FixtureGenerator() {
    }

    public static Path defaultOutputRoot() {
        return Path.of(System.getProperty("listassist.fixtures.dir", "build/listassist-fixtures"));
    }

    public static GeneratedScenario generate(Scenario scenario, Path outputRoot) {
        Path directory = outputRoot.resolve(scenario.id());
        deleteRecursively(directory);
        validateUploadPlan(scenario.deliveries());
        List<FixtureManifest.ObjectEntry> objects = new ArrayList<>();
        for (Delivery delivery : scenario.deliveries()) {
            Path file = GeneratedScenario.blobFile(directory, delivery.phase(), delivery.container(),
                delivery.blobName());
            ParquetFixtureFiles.write(file, delivery.dataset().columns(), delivery.rows(),
                ParquetFixtureFiles.Layout.DEFAULT);
            validate(delivery, ParquetFixtureFiles.read(file));
            byte[] bytes = readAllBytes(file);
            objects.add(new FixtureManifest.ObjectEntry(
                delivery.container(), delivery.blobName(), delivery.kind().token(), SCHEMA_PROFILE,
                delivery.phase(), delivery.upload().token(), delivery.rows().size(), bytes.length, sha256(bytes)));
        }
        FixtureManifest manifest = new FixtureManifest(
            FixtureManifest.FORMAT_VERSION, scenario.id(), scenario.seed(), scenario.businessClock().toString(),
            scenario.evidence(), AzuriteEmulator.IMAGE,
            Map.of("parquet-java", Version.VERSION_NUMBER, "fixtures", "dtsse-services listassist fixtures"),
            scenario.symbols(), List.copyOf(objects), scenario.expected(), scenario.privacySentinels());
        JSON.writeValue(directory.resolve("manifest.json").toFile(), manifest);
        return new GeneratedScenario(directory, manifest);
    }

    /**
     * A created name must be new; a planned overwrite must replace a name created in an earlier phase.
     */
    private static void validateUploadPlan(List<Delivery> deliveries) {
        Map<String, Integer> firstPhase = new HashMap<>();
        Set<String> perPhase = new HashSet<>();
        for (Delivery delivery : deliveries) {
            String name = delivery.container() + "/" + delivery.blobName();
            if (!perPhase.add(delivery.phase() + ":" + name)) {
                throw new IllegalStateException("Blob delivered twice in phase " + delivery.phase() + ": " + name);
            }
            Integer earlier = firstPhase.putIfAbsent(name, delivery.phase());
            if (delivery.upload() == Delivery.UploadAction.CREATE && earlier != null) {
                throw new IllegalStateException("Blob name reused without a planned overwrite: " + name);
            }
            if (delivery.upload() == Delivery.UploadAction.OVERWRITE
                && (earlier == null || earlier >= delivery.phase())) {
                throw new IllegalStateException("Planned overwrite has no earlier Blob to replace: " + name);
            }
        }
    }

    private static void validate(Delivery delivery, ParquetFixtureFiles.Decoded decoded) {
        String blob = delivery.container() + "/" + delivery.blobName();
        if (!decoded.columnNames().equals(delivery.dataset().columns())) {
            throw new IllegalStateException("Generated column list differs from inventory for " + blob);
        }
        if (!decoded.allLeavesAreOptionalStrings()) {
            throw new IllegalStateException("Generated schema is not nullable UTF-8 strings for " + blob);
        }
        if (!delivery.rows().isEmpty() && !decoded.codecs().equals(Set.of(CompressionCodecName.SNAPPY))) {
            throw new IllegalStateException("Generated file is not Snappy compressed: " + blob);
        }
        if (!decoded.rows().equals(delivery.rows())) {
            throw new IllegalStateException("Generated rows do not decode to the authored rows for " + blob);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record GeneratedScenario(Path directory, FixtureManifest manifest) {

        static Path blobFile(Path directory, int phase, String container, String blobName) {
            return directory.resolve("blobs").resolve("phase-" + phase).resolve(container).resolve(blobName);
        }

        public Path blobFile(FixtureManifest.ObjectEntry object) {
            return blobFile(directory, object.phase(), object.container(), object.blobName());
        }
    }
}
