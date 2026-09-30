package uk.gov.hmcts.reform.services.listassist.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Private storage owned exclusively by this application instance. */
@Component
@ConditionalOnProperty("listassist.blob.endpoint")
class PrivateDownloads {

    private final Path directory;

    PrivateDownloads(@Value("${listassist.ingest.temp-directory}") Path directory) throws IOException {
        this.directory = directory;
        var permissions = PosixFilePermissions.fromString("rwx------");
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(permissions));
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
                throw new IOException("ListAssist download directory must be a private directory", e);
            }
        }
        // Remove extracts left by a killed JVM when its filesystem is reused, for example during local development.
        try (var files = Files.newDirectoryStream(directory, "listassist-*.parquet")) {
            for (Path file : files) {
                Files.delete(file);
            }
        }
    }

    Path create() throws IOException {
        return Files.createTempFile(directory, "listassist-", ".parquet",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    static OutputStream bounded(OutputStream destination, long limit) {
        return new FilterOutputStream(destination) {
            private long written;

            @Override
            public void write(int value) throws IOException {
                check(1);
                out.write(value);
                written++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                check(length);
                out.write(bytes, offset, length);
                written += length;
            }

            private void check(int length) throws DownloadTooLargeException {
                if (length > limit - written) {
                    // Azure's output-stream subscriber handles IOException, but can drop runtime exceptions.
                    throw new DownloadTooLargeException();
                }
            }
        };
    }

    static final class DownloadTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        DownloadTooLargeException() {
            super("Download exceeds the file size limit");
        }
    }
}
