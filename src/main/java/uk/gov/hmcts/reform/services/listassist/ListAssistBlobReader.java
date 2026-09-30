package uk.gov.hmcts.reform.services.listassist;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRequestConditions;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the core containers by their configured physical names.
 */
public class ListAssistBlobReader {

    private final BlobServiceClient blobServiceClient;
    private final Map<ListAssistContainer, String> containerNames;

    public ListAssistBlobReader(BlobServiceClient blobServiceClient, Map<ListAssistContainer, String> containerNames) {
        List<String> missing = Arrays.stream(ListAssistContainer.values())
            .filter(container -> containerNames == null || isBlank(containerNames.get(container)))
            .map(ListAssistContainer::key).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("listassist.blob.containers is missing " + missing);
        }
        this.blobServiceClient = blobServiceClient;
        this.containerNames = new EnumMap<>(containerNames);
    }

    public Iterable<BlobItem> list(ListAssistContainer container) {
        return blobContainer(container).listBlobs();
    }

    /**
     * Downloads the Blob only if it still has the listed ETag, so newer bytes are never recorded under an older
     * version. A changed Blob fails with a 412 {@code BlobStorageException}. Listings may return the ETag without the
     * quotes an {@code If-Match} header expects, so it is quoted here if needed.
     */
    public void download(ListAssistContainer container, String blobName, String etag, OutputStream destination) {
        String ifMatch = etag.startsWith("\"") ? etag : "\"" + etag + "\"";
        blobContainer(container)
            .getBlobClient(blobName)
            .downloadStreamWithResponse(destination, null, null, new BlobRequestConditions().setIfMatch(ifMatch),
                false, null, Context.NONE);
    }

    private BlobContainerClient blobContainer(ListAssistContainer container) {
        return blobServiceClient.getBlobContainerClient(containerNames.get(container));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
