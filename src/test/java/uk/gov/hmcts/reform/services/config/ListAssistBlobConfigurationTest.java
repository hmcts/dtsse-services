package uk.gov.hmcts.reform.services.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListAssistBlobConfigurationTest {

    private final ListAssistBlobConfiguration configuration = new ListAssistBlobConfiguration();

    @Test
    void emulatorModeAcceptsOnlyAzuriteDevelopmentAccountEndpoints() {
        assertThatCode(() -> configuration.listAssistBlobServiceClient("http://127.0.0.1:10000/devstoreaccount1",
            "emulator")).doesNotThrowAnyException();
        assertThatCode(() -> configuration.listAssistBlobServiceClient("http://azurite:10000/devstoreaccount1/",
            "emulator")).doesNotThrowAnyException();
        for (String endpoint : new String[] {
            "https://example.blob.core.windows.net/",
            "http://example.blob.core.windows.net/devstoreaccount1",
            "https://127.0.0.1:10000/devstoreaccount1",
            "http://127.0.0.1:10000/otheraccount"}) {
            assertThatThrownBy(() -> configuration.listAssistBlobServiceClient(endpoint, "emulator"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void unknownAuthModeFailsInsteadOfFallingBack() {
        assertThatThrownBy(() -> configuration.listAssistBlobServiceClient("http://127.0.0.1:10000/devstoreaccount1",
            "shared-key")).isInstanceOf(IllegalArgumentException.class);
    }
}
