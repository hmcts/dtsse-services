package uk.gov.hmcts.reform.services.listassist.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for new ListAssist extracts. Only the replica holding the advisory lock runs; the others skip that tick.
 */
@Component
@ConditionalOnProperty("listassist.blob.endpoint")
public class ListAssistIngestionJob {

    static final String LOCK_NAME = "ingestion";

    private static final Logger log = LoggerFactory.getLogger(ListAssistIngestionJob.class);

    private final PostgresAdvisoryLock lock;
    private final ListAssistIngestion ingestion;
    private final boolean enabled;

    public ListAssistIngestionJob(PostgresAdvisoryLock lock, ListAssistIngestion ingestion,
                                  @Value("${listassist.ingest.enabled}") boolean enabled) {
        this.lock = lock;
        this.ingestion = ingestion;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${listassist.ingest.cron}", zone = "${listassist.ingest.zone}")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /**
     * Returns false if another run holds the lock.
     */
    public boolean runOnce() {
        boolean ran = lock.runIfAcquired(LOCK_NAME, ingestion::ingestAll);
        if (!ran) {
            log.info("ListAssist ingestion already running elsewhere; skipping this run");
        }
        return ran;
    }
}
