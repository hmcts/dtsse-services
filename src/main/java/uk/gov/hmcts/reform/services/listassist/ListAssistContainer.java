package uk.gov.hmcts.reform.services.listassist;

/**
 * The four core ListAssist containers. {@link #key()} is the stable name used in the ledger, diagnostics and
 * configuration; the physical Blob container names are configured under {@code listassist.blob.containers}.
 */
public enum ListAssistContainer {
    HEARINGS("hearings"),
    SESSIONS("sessions"),
    SESSION_OFFICERS("session-officers"),
    USERS("users");

    private final String key;

    ListAssistContainer(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
