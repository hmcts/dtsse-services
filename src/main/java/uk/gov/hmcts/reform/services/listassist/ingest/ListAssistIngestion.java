package uk.gov.hmcts.reform.services.listassist.ingest;

import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.services.listassist.ListAssistBlobReader;
import uk.gov.hmcts.reform.services.listassist.ingest.PostgresAdvisoryLock.HeldLock;
import uk.gov.hmcts.reform.services.listassist.ingest.PostgresAdvisoryLock.LockLostException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One pass over the four core containers. A container is bootstrapped from its latest Full; after that every unseen
 * or failed Blob version is ingested in filename-timestamp order. There is no watermark, so late arrivals are found.
 */
@Component
@ConditionalOnProperty("listassist.blob.endpoint")
public class ListAssistIngestion {

    private static final Logger log = LoggerFactory.getLogger(ListAssistIngestion.class);
    private static final Set<String> DONE = Set.of(SourceFileLedger.INGESTED, SourceFileLedger.SUPERSEDED,
        SourceFileLedger.SKIPPED_PRE_BOOTSTRAP);

    private final ListAssistBlobReader reader;
    private final SourceFileLedger ledger;
    private final ListAssistFileIngester files;

    ListAssistIngestion(ListAssistBlobReader reader, SourceFileLedger ledger, ListAssistFileIngester files) {
        this.reader = reader;
        this.ledger = ledger;
        this.files = files;
    }

    /**
     * Containers progress independently: a failure in one is logged and the others continue.
     */
    public void ingestAll(HeldLock lock) {
        long started = System.nanoTime();
        log.info("ListAssist ingestion started");
        for (ListAssistDataset dataset : ListAssistDataset.values()) {
            try {
                ingest(dataset, lock);
            } catch (LockLostException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("ListAssist ingestion failed container={} exception={} storageError={}",
                    dataset.container().key(), e.getClass().getName(),
                    e instanceof BlobStorageException storage ? storage.getErrorCode() : null);
            }
        }
        log.info("ListAssist ingestion finished durationMs={}", (System.nanoTime() - started) / 1_000_000);
    }

    private void ingest(ListAssistDataset dataset, HeldLock lock) {
        String container = dataset.container().key();
        List<ListedVersion> listed = list(dataset);
        // A failed version whose ETag is no longer listed was replaced or deleted; it will never be retried.
        Set<VersionKey> listedKeys = listed.stream().map(ListedVersion::key).collect(Collectors.toSet());
        ledger.statuses(container).forEach((key, status) -> {
            if (SourceFileLedger.FAILED.equals(status) && !listedKeys.contains(key)) {
                ledger.markSuperseded(container, key);
            }
        });
        if (!ledger.isBootstrapped(container) && !bootstrap(dataset, listed, lock)) {
            return;
        }
        Map<VersionKey, String> statuses = ledger.statuses(container);
        List<ListedVersion> pending = listed.stream()
            .filter(version -> !DONE.contains(statuses.getOrDefault(version.key(), "")))
            .sorted((left, right) -> ExtractName.DELIVERY_ORDER.compare(left.name(), right.name()))
            .toList();
        for (ListedVersion version : pending) {
            lock.checkHeld();
            files.ingest(dataset, version, null);
        }
    }

    /**
     * Ingests the latest listed Full and, in the same transaction, records the bootstrap and skips older versions.
     * Without a successful Full the container stays unbootstrapped and no increments are ingested.
     */
    private boolean bootstrap(ListAssistDataset dataset, List<ListedVersion> listed, HeldLock lock) {
        Optional<ListedVersion> full = listed.stream()
            .filter(version -> version.name().isFull())
            .max((left, right) -> ExtractName.DELIVERY_ORDER.compare(left.name(), right.name()));
        if (full.isEmpty()) {
            log.warn("ListAssist container has no Full extract and stays unbootstrapped container={}",
                dataset.container().key());
            return false;
        }
        String boundary = full.get().name().fileTimestamp();
        List<ListedVersion> older = listed.stream()
            .filter(version -> version.name().fileTimestamp().compareTo(boundary) < 0)
            .toList();
        lock.checkHeld();
        return files.ingest(dataset, full.get(), older);
    }

    private List<ListedVersion> list(ListAssistDataset dataset) {
        List<ListedVersion> listed = new ArrayList<>();
        for (BlobItem item : reader.list(dataset.container())) {
            Optional<ExtractName> name = ExtractName.parse(item.getName(), dataset.viewToken());
            if (name.isPresent()) {
                listed.add(new ListedVersion(name.get(), item.getProperties().getETag(),
                    item.getProperties().getContentLength()));
            } else if (item.getName().endsWith("-data.parquet")) {
                // Schema files, folder markers and metadata sit alongside the extracts and are skipped silently.
                log.warn("Ignoring ListAssist Blob that is not a recognised extract container={} blob={}",
                    dataset.container().key(), item.getName());
            }
        }
        return listed;
    }
}
