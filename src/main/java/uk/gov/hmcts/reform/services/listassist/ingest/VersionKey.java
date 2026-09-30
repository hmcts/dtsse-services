package uk.gov.hmcts.reform.services.listassist.ingest;

/**
 * Identity of one Blob version in a container's ledger.
 */
record VersionKey(String blobName, String etag) {
}
