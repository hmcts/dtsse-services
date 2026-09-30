package uk.gov.hmcts.reform.services.listassist.fixtures;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Uploads one delivery phase of a generated scenario as block Blobs. Identical existing objects are left untouched
 * so their ETags and timestamps stay stable. Differing content is replaced only when the manifest plans an overwrite,
 * and a planned overwrite must find the earlier version in place.
 */
public final class FixtureSeeder {

    public static final String CONTENT_TYPE = "application/octet-stream";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final BlobServiceClient client;

    public FixtureSeeder(BlobServiceClient client) {
        this.client = client;
    }

    public List<SeedResult> seed(GeneratedScenario generated, int phase) {
        FixtureManifest manifest = generated.manifest();
        manifest.objects().stream().map(FixtureManifest.ObjectEntry::container).distinct()
            .forEach(container -> client.getBlobContainerClient(container).createIfNotExists());

        List<SeedResult> results = new ArrayList<>();
        for (FixtureManifest.ObjectEntry object : manifest.objects()) {
            if (object.phase() != phase) {
                continue;
            }
            byte[] bytes = FixtureGenerator.readAllBytes(generated.blobFile(object));
            if (!FixtureGenerator.sha256(bytes).equals(object.sha256())) {
                throw new IllegalStateException("Generated file no longer matches manifest: " + object.blobName());
            }
            BlobClient blob = client.getBlobContainerClient(object.container()).getBlobClient(object.blobName());
            String name = object.container() + "/" + object.blobName();
            boolean overwrite = Delivery.UploadAction.OVERWRITE.token().equals(object.upload());
            String action;
            if (blob.exists()) {
                BlobProperties before = blob.getProperties();
                byte[] existing = blob.downloadContent().toBytes();
                if (FixtureGenerator.sha256(existing).equals(object.sha256())) {
                    action = "unchanged";
                } else if (overwrite) {
                    // Only replace the version that was inspected, never one that changed underneath us.
                    upload(blob, bytes, new BlobRequestConditions().setIfMatch(before.getETag()));
                    action = "overwritten";
                } else {
                    throw new IllegalStateException("Existing Blob differs and no overwrite is planned: " + name);
                }
            } else if (overwrite) {
                throw new IllegalStateException("Planned overwrite target is missing: " + name);
            } else {
                upload(blob, bytes, new BlobRequestConditions().setIfNoneMatch("*"));
                action = "uploaded";
            }
            BlobProperties properties = blob.getProperties();
            if (properties.getBlobSize() != object.contentLength()) {
                throw new IllegalStateException("Stored length differs from manifest: " + object.blobName());
            }
            results.add(new SeedResult(object.container(), object.blobName(), action, properties.getETag(),
                properties.getLastModified().toString(), properties.getBlobSize()));
        }
        writeRunOutput(generated.directory().resolve("seed-runs"), phase, results);
        return List.copyOf(results);
    }

    private static void upload(BlobClient blob, byte[] bytes, BlobRequestConditions conditions) {
        blob.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(bytes))
            .setHeaders(new BlobHttpHeaders().setContentType(CONTENT_TYPE))
            .setRequestConditions(conditions), TIMEOUT, Context.NONE);
    }

    private static void writeRunOutput(Path directory, int phase, List<SeedResult> results) {
        try {
            Files.createDirectories(directory);
            try (Stream<Path> previous = Files.list(directory)) {
                long run = previous.count() + 1;
                Path file = directory.resolve(String.format(Locale.ROOT, "%03d-phase-%d.json", run, phase));
                FixtureGenerator.JSON.writeValue(file.toFile(), results);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record SeedResult(String container, String blobName, String action, String eTag, String lastModified,
                             long contentLength) {
    }
}
