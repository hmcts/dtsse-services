package uk.gov.hmcts.reform.services.listassist.ingest;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.bytes.HeapByteBufferAllocator;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static uk.gov.hmcts.reform.services.listassist.ingest.ParquetLimits.requireWithin;

/**
 * Streams the selected string columns of a local Parquet file one row at a time. Only the projected columns are
 * decoded, so unselected personal data is never materialised.
 */
final class ParquetRows {

    private ParquetRows() {
    }

    @FunctionalInterface
    interface RowSink {
        void accept(String[] row);
    }

    /**
     * Returns the number of rows read. Throws {@link SourceFileException} {@code schema_mismatch} if a selected column
     * is missing or is not a flat nullable string. Decoder failures surface as {@link IOException} or unchecked
     * exceptions from parquet-java.
     */
    static long read(Path file, List<String> columns, RowSink sink) throws IOException {
        checkFooter(file);
        var builder = ParquetReadOptions.builder(new PlainParquetConfiguration());
        ParquetLimits limits = new ParquetLimits(builder.build().getCodecFactory());
        ParquetReadOptions options = builder.withCodecFactory(limits)
            .withAllocator(new HeapByteBufferAllocator() {
                @Override
                public ByteBuffer allocate(int size) {
                    requireWithin(size, ParquetLimits.MAX_PAGE_BYTES);
                    return super.allocate(size);
                }
            }).build();
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), options)) {
            MessageType fileSchema = reader.getFooter().getFileMetaData().getSchema();
            MessageType projection = new MessageType(fileSchema.getName(), columns.stream()
                .map(column -> requireString(fileSchema, column)).toList());
            reader.setRequestedSchema(projection);
            long declaredRows = 0;
            for (var block : reader.getFooter().getBlocks()) {
                requireWithin(block.getRowCount(), ParquetLimits.MAX_ROWS - declaredRows);
                declaredRows += block.getRowCount();
                long compressed = 0;
                long uncompressed = 0;
                for (var chunk : block.getColumns()) {
                    if (columns.contains(chunk.getPath().toDotString())) {
                        requireWithin(chunk.getTotalSize(), ParquetLimits.MAX_ROW_GROUP_BYTES - compressed);
                        requireWithin(chunk.getTotalUncompressedSize(),
                            ParquetLimits.MAX_ROW_GROUP_BYTES - uncompressed);
                        compressed += chunk.getTotalSize();
                        uncompressed += chunk.getTotalUncompressedSize();
                    }
                }
            }
            long rows = 0;
            while (true) {
                limits.nextRowGroup();
                PageReadStore pages = reader.readNextRowGroup();
                if (pages == null) {
                    break;
                }
                try (pages) {
                    RecordReader<Group> records = new ColumnIOFactory().getColumnIO(projection, fileSchema)
                        .getRecordReader(pages, new GroupRecordConverter(projection));
                    requireWithin(pages.getRowCount(), ParquetLimits.MAX_ROWS - rows);
                    for (long i = 0; i < pages.getRowCount(); i++) {
                        Group group = records.read();
                        String[] row = new String[columns.size()];
                        for (int field = 0; field < row.length; field++) {
                            if (group.getFieldRepetitionCount(field) != 0) {
                                var binary = group.getBinary(field, 0);
                                requireWithin(binary.length(), ParquetLimits.MAX_FIELD_BYTES);
                                row[field] = binary.toStringUsingUTF8();
                            }
                        }
                        sink.accept(row);
                        rows++;
                    }
                }
            }
            return rows;
        } finally {
            limits.release();
        }
    }

    private static void checkFooter(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < 12) {
                throw new IOException("Parquet file is too short");
            }
            ByteBuffer tail = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(channel.size() - 8);
            while (tail.hasRemaining()) {
                if (channel.read(tail) < 0) {
                    throw new IOException("Truncated Parquet footer");
                }
            }
            tail.flip();
            int size = tail.getInt();
            if (tail.getInt() != 0x31524150) {
                throw new IOException("Expected an unencrypted Parquet file");
            }
            requireWithin(size, Math.min(ParquetLimits.MAX_FOOTER_BYTES, channel.size() - 12));
        }
    }

    private static Type requireString(MessageType schema, String column) {
        if (!schema.containsField(column)) {
            throw new SourceFileException(SourceFileException.SCHEMA_MISMATCH, "Selected column is missing: " + column);
        }
        Type type = schema.getType(column);
        boolean string = type.isPrimitive()
            && !type.isRepetition(Type.Repetition.REPEATED)
            && type.asPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.BINARY
            && LogicalTypeAnnotation.stringType().equals(type.getLogicalTypeAnnotation());
        if (!string) {
            throw new SourceFileException(SourceFileException.SCHEMA_MISMATCH,
                "Selected column is not a nullable string: " + column);
        }
        return type;
    }
}
