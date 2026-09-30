package uk.gov.hmcts.reform.services.listassist.ingest;

/**
 * One Blob version seen in a listing: its parsed extract name, and the ETag and size it had at that moment. Downloads
 * are conditional on the ETag, so the size is exactly the size of the bytes that can be downloaded.
 */
record ListedVersion(ExtractName name, String etag, long sizeBytes) {

    VersionKey key() {
        return new VersionKey(name.blobName(), etag);
    }
}
