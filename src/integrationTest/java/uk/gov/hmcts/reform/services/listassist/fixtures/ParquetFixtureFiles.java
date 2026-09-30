package uk.gov.hmcts.reform.services.listassist.fixtures;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes and decodes fixture Parquet in the observed physical shape: a flat schema of OPTIONAL BYTE_ARRAY UTF-8
 * string leaves with Snappy column chunks. Every column is declared up front, so all-null columns keep their type.
 */
public final class ParquetFixtureFiles {

    private static final String MESSAGE_NAME = "listassist_fixture";

    private ParquetFixtureFiles() {
    }

    public static MessageType stringSchema(List<String> columns) {
        Types.MessageTypeBuilder builder = Types.buildMessage();
        for (String column : columns) {
            builder.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(column);
        }
        return builder.named(MESSAGE_NAME);
    }

    public static void write(Path target, List<String> columns, List<Map<String, String>> rows, Layout layout) {
        MessageType schema = stringSchema(columns);
        try {
            Files.createDirectories(target.getParent());
            SimpleGroupFactory groups = new SimpleGroupFactory(schema);
            try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(target))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withRowGroupSize(layout.rowGroupBytes())
                .withPageSize(layout.pageBytes())
                .build()) {
                for (Map<String, String> row : rows) {
                    Group group = groups.newGroup();
                    row.forEach((column, value) -> {
                        if (value != null) {
                            group.add(column, value);
                        }
                    });
                    writer.write(group);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Decoded read(Path file) {
        ParquetReadOptions options = ParquetReadOptions.builder(new PlainParquetConfiguration()).build();
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), options)) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            Set<CompressionCodecName> codecs = EnumSet.noneOf(CompressionCodecName.class);
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                for (ColumnChunkMetaData chunk : block.getColumns()) {
                    codecs.add(chunk.getCodec());
                }
            }
            List<Map<String, String>> rows = new ArrayList<>();
            int rowGroups = 0;
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                rowGroups++;
                RecordReader<Group> records = new ColumnIOFactory().getColumnIO(schema)
                    .getRecordReader(pages, new GroupRecordConverter(schema));
                for (long i = 0; i < pages.getRowCount(); i++) {
                    rows.add(toRow(schema, records.read()));
                }
            }
            return new Decoded(schema, Collections.unmodifiableSet(codecs), rowGroups, rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> toRow(MessageType schema, Group group) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < schema.getFieldCount(); i++) {
            row.put(schema.getFieldName(i), group.getFieldRepetitionCount(i) == 0 ? null : group.getString(i, 0));
        }
        return row;
    }

    /**
     * Writer layout knobs. The baseline uses parquet-java defaults; robustness scenarios can force many row groups.
     */
    public record Layout(int rowGroupBytes, int pageBytes) {
        public static final Layout DEFAULT =
            new Layout(ParquetWriter.DEFAULT_BLOCK_SIZE, ParquetWriter.DEFAULT_PAGE_SIZE);
    }

    public record Decoded(MessageType schema, Set<CompressionCodecName> codecs, int rowGroups,
                          List<Map<String, String>> rows) {

        public List<String> columnNames() {
            return schema.getFields().stream().map(Type::getName).toList();
        }

        public boolean allLeavesAreOptionalStrings() {
            return schema.getFields().stream().allMatch(field -> field.isPrimitive()
                && field.isRepetition(Type.Repetition.OPTIONAL)
                && field.asPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.BINARY
                && LogicalTypeAnnotation.stringType().equals(field.getLogicalTypeAnnotation()));
        }
    }
}
