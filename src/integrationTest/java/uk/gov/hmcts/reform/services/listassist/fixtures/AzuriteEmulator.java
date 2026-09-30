package uk.gov.hmcts.reform.services.listassist.fixtures;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.ListBlobContainersOptions;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Disposable Azurite Blob service on a dynamically mapped port. Each instance is fresh, so the real ListAssist
 * container names can be used without collisions between test workers.
 */
public final class AzuriteEmulator implements AutoCloseable {

    public static final String IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.37.0"
        + "@sha256:830430c1da1a2d537e08f3e6764dd1f5ae00cf0346bcaf625b968ec3f0971fd5";
    public static final String ACCOUNT_NAME = "devstoreaccount1";
    /**
     * Azurite's published development account key. Local emulator only; it is not a secret or a real credential.
     */
    public static final String ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    /**
     * Emulator containers are named after each container's key.
     */
    public static final Map<ListAssistContainer, String> CONTAINER_NAMES = Arrays.stream(ListAssistContainer.values())
        .collect(Collectors.toUnmodifiableMap(Function.identity(), ListAssistContainer::key));

    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "azurite");
    private static final int BLOB_PORT = 10000;
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(30);

    private final GenericContainer<?> container;

    private AzuriteEmulator(GenericContainer<?> container) {
        this.container = container;
    }

    public static AzuriteEmulator start() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException("Docker is required to run the ListAssist Azurite fixture tests");
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", String.valueOf(BLOB_PORT),
                "--location", "/data", "--disableProductStyleUrl", "--disableTelemetry")
            .withExposedPorts(BLOB_PORT)
            .waitingFor(Wait.forLogMessage(".*Azurite Blob service successfully listens.*", 1)
                .withStartupTimeout(Duration.ofSeconds(60)));
        container.start();
        AzuriteEmulator emulator = new AzuriteEmulator(container);
        emulator.awaitAuthenticatedReadiness();
        return emulator;
    }

    public String blobEndpoint() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(BLOB_PORT) + "/" + ACCOUNT_NAME;
    }

    public BlobServiceClient client() {
        return client(ACCOUNT_KEY);
    }

    public BlobServiceClient client(String accountKey) {
        String endpoint = blobEndpoint();
        Set<String> hosts = new HashSet<>(LOCAL_HOSTS);
        hosts.add(container.getHost());
        requireEmulatorEndpoint(endpoint, hosts);
        return new BlobServiceClientBuilder()
            .endpoint(endpoint)
            .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, accountKey))
            .buildClient();
    }

    /**
     * Refuses anything other than a plain-HTTP emulator endpoint carrying the development account path.
     */
    public static void requireEmulatorEndpoint(String endpoint, Set<String> emulatorHosts) {
        URI uri = URI.create(endpoint);
        boolean emulator = "http".equals(uri.getScheme())
            && uri.getHost() != null
            && emulatorHosts.contains(uri.getHost())
            && !uri.getHost().endsWith(".windows.net")
            && ("/" + ACCOUNT_NAME).equals(uri.getPath());
        if (!emulator) {
            throw new IllegalStateException("Refusing to use non-emulator Blob endpoint " + endpoint);
        }
    }

    private void awaitAuthenticatedReadiness() {
        BlobServiceClient client = client();
        Instant deadline = Instant.now().plus(READINESS_TIMEOUT);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                ListBlobContainersOptions onePage = new ListBlobContainersOptions().setMaxResultsPerPage(1);
                client.listBlobContainers(onePage, Duration.ofSeconds(5)).iterator().hasNext();
                return;
            } catch (RuntimeException e) {
                last = e;
                pause();
            }
        }
        throw new IllegalStateException("Azurite did not answer an authenticated list request within "
            + READINESS_TIMEOUT, last);
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
