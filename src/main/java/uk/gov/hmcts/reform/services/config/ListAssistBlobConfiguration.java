package uk.gov.hmcts.reform.services.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;

import java.net.URI;
import java.util.Locale;

/**
 * Builds the ListAssist Blob client for exactly one explicit authentication mode. There is no fallback between modes.
 * Without {@code listassist.blob.endpoint} there is no Blob access and no ingestion, so the application still starts
 * where the ListAssist settings are not provided.
 */
@Configuration
@ConditionalOnProperty("listassist.blob.endpoint")
@EnableConfigurationProperties(ListAssistBlobProperties.class)
public class ListAssistBlobConfiguration {

    static final String EMULATOR_ACCOUNT = "devstoreaccount1";
    /**
     * Azurite's published development account key. It only works against a local emulator and is not a secret.
     */
    static final String EMULATOR_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    public enum AuthMode {
        ENTRA,
        EMULATOR
    }

    @Bean
    public BlobServiceClient listAssistBlobServiceClient(@Value("${listassist.blob.endpoint}") String endpoint,
                                                         @Value("${listassist.blob.auth}") String auth) {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder().endpoint(endpoint);
        return switch (AuthMode.valueOf(auth.trim().toUpperCase(Locale.ROOT))) {
            case ENTRA -> builder.credential(new DefaultAzureCredentialBuilder().build()).buildClient();
            case EMULATOR -> {
                requireEmulatorEndpoint(endpoint);
                yield builder.credential(new StorageSharedKeyCredential(EMULATOR_ACCOUNT, EMULATOR_KEY)).buildClient();
            }
        };
    }

    @Bean
    public ListAssistBlobReader listAssistBlobReader(BlobServiceClient listAssistBlobServiceClient,
                                                     ListAssistBlobProperties properties) {
        return new ListAssistBlobReader(listAssistBlobServiceClient, properties.containers());
    }

    /**
     * The emulator key is only ever sent to a plain-HTTP Azurite endpoint for the development account.
     */
    static void requireEmulatorEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        boolean emulator = "http".equals(uri.getScheme())
            && uri.getHost() != null
            && !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".windows.net")
            && ("/" + EMULATOR_ACCOUNT).equals(path);
        if (!emulator) {
            throw new IllegalStateException("listassist.blob.auth=emulator requires an Azurite endpoint ending in /"
                + EMULATOR_ACCOUNT + ", not " + endpoint);
        }
    }
}
