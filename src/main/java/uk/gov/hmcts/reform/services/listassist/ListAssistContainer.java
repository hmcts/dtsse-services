package uk.gov.hmcts.reform.services.listassist;

public enum ListAssistContainer {
    HEARINGS("v3-sl-hearings"),
    SESSIONS("v3-sl-sessions"),
    SESSIONS_JOFFICER("v3-sl-sessions-jofficer"),
    USER("v3-sl-user");

    private final String name;

    ListAssistContainer(String name) {
        this.name = name;
    }

    public String blobName() {
        return name;
    }
}
