package uk.gov.hmcts.reform.services.listassist;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

@Component
public class ListAssistBlobReader {

    private final BlobServiceClient blobServiceClient;

    public ListAssistBlobReader(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    public Iterable<BlobItem> list(ListAssistContainer container) {
        return blobServiceClient.getBlobContainerClient(container.blobName()).listBlobs();
    }

    public void download(ListAssistContainer container, String blobName, OutputStream destination) {
        blobServiceClient.getBlobContainerClient(container.blobName())
            .getBlobClient(blobName)
            .downloadStream(destination);
    }
}
