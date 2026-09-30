package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateDownloadsTest {

    @TempDir
    Path work;

    @Test
    void extractsArePrivateAndStaleDownloadsAreRemovedOnRestart() throws IOException {
        Path directory = work.resolve("private");
        PrivateDownloads downloads = new PrivateDownloads(directory);
        Path file = downloads.create();
        Files.writeString(file, "source extract");
        final Path unrelated = Files.writeString(directory.resolve("unrelated"), "keep");

        assertThat(Files.getPosixFilePermissions(directory))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        new PrivateDownloads(directory);
        assertThat(file).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void refusesSymlinksAndAccessibleDirectories() throws IOException {
        Path directory = Files.createDirectory(work.resolve("shared"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> new PrivateDownloads(directory)).isInstanceOf(IOException.class);
        Path link = Files.createSymbolicLink(work.resolve("link"), directory);
        assertThatThrownBy(() -> new PrivateDownloads(link)).isInstanceOf(IOException.class);
    }

    @Test
    void enforcesActualDownloadedBytesBeforeWritingPastTheLimit() throws IOException {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        try (var out = PrivateDownloads.bounded(destination, 4)) {
            out.write(new byte[] {1, 2, 3});
            out.write(4);
            assertThatThrownBy(() -> out.write(5)).isInstanceOf(PrivateDownloads.DownloadTooLargeException.class);
            assertThatThrownBy(() -> out.write(new byte[] {5, 6}))
                .isInstanceOf(PrivateDownloads.DownloadTooLargeException.class);
        }
        assertThat(destination.toByteArray()).containsExactly(1, 2, 3, 4);
    }
}
