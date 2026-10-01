package uk.gov.hmcts.reform.services.listassist.ingest;

import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.reform.services.listassist.fixtures.ParquetFixtureFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParquetRowsTest {

    @TempDir
    Path work;

    @Test
    void rejectsOversizedMetadataBeforeEmittingAnyRows() throws IOException {
        for (Consumer<FileMetaData> corrupt : List.<Consumer<FileMetaData>>of(
            metadata -> metadata.getRow_groups().getFirst().setNum_rows(ParquetLimits.MAX_ROWS + 1),
            metadata -> metadata.getRow_groups().getFirst().getColumns().getFirst().getMeta_data()
                .setTotal_uncompressed_size(ParquetLimits.MAX_ROW_GROUP_BYTES + 1L),
            metadata -> metadata.getRow_groups().getFirst().getColumns().getFirst().getMeta_data()
                .setTotal_compressed_size(ParquetLimits.MAX_ROW_GROUP_BYTES + 1L))) {
            Path file = parquet("ok");
            rewriteFooter(file, corrupt);
            assertThatThrownBy(() -> ParquetRows.read(file, List.of("value"), row -> {
                throw new AssertionError("Must reject before decoding rows");
            })).isInstanceOf(SourceFileException.class);
        }
    }

    @Test
    void rejectsAnOversizedFieldAndAcceptsTheBoundary() throws IOException {
        Path file = parquet("a".repeat(ParquetLimits.MAX_FIELD_BYTES));
        List<String[]> rows = new ArrayList<>();
        assertThat(ParquetRows.read(file, List.of("value"), rows::add)).isEqualTo(1);
        assertThat(rows.getFirst()[0]).hasSize(ParquetLimits.MAX_FIELD_BYTES);
        Path oversized = parquet("a".repeat(ParquetLimits.MAX_FIELD_BYTES + 1));
        assertThatThrownBy(() -> ParquetRows.read(oversized, List.of("value"), rows::add))
            .isInstanceOf(SourceFileException.class)
            .hasMessage("Parquet input exceeds the field_bytes limit");
        assertThat(rows).hasSize(1);
    }

    @Test
    void rejectsForgedFooterLengthWithoutAllocatingIt() throws IOException {
        Path file = parquet("ok");
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.length - 8, Integer.MAX_VALUE);
        Files.write(file, bytes);
        assertThatThrownBy(() -> ParquetRows.read(file, List.of("value"), row -> { }))
            .isInstanceOf(SourceFileException.class);
    }

    private Path parquet(String value) {
        Path file = work.resolve(System.nanoTime() + ".parquet");
        ParquetFixtureFiles.write(file, List.of("value"), List.of(Map.of("value", value)),
            ParquetFixtureFiles.Layout.DEFAULT);
        return file;
    }

    private static void rewriteFooter(Path file, Consumer<FileMetaData> change) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.length - 8);
        int offset = bytes.length - length - 8;
        FileMetaData metadata = Util.readFileMetaData(new ByteArrayInputStream(bytes, offset, length));
        change.accept(metadata);
        ByteArrayOutputStream footer = new ByteArrayOutputStream();
        Util.writeFileMetaData(metadata, footer);
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        rewritten.write(bytes, 0, offset);
        footer.writeTo(rewritten);
        rewritten.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(footer.size()).putInt(0x31524150).array());
        Files.write(file, rewritten.toByteArray());
    }
}
