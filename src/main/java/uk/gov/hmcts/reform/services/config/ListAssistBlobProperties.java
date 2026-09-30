package uk.gov.hmcts.reform.services.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;

import java.util.Map;

/**
 * Physical Blob container names, keyed by {@link ListAssistContainer#key()}, e.g.
 * {@code listassist.blob.containers.hearings}. Deployments mount them from Key Vault with the endpoint.
 */
@ConfigurationProperties("listassist.blob")
public record ListAssistBlobProperties(Map<ListAssistContainer, String> containers) {
}
