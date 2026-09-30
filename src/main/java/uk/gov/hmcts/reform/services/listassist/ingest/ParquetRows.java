package uk.gov.hmcts.reform.services.listassist.ingest;

import org.apache.parquet.ParquetReadOptions;
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
import java.nio.file.Path;
import java.util.List;

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
        ParquetReadOptions options = ParquetReadOptions.builder(new PlainParquetConfiguration()).build();
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), options)) {
            MessageType fileSchema = reader.getFooter().getFileMetaData().getSchema();
            MessageType projection = new MessageType(fileSchema.getName(), columns.stream()
                .map(column -> requireString(fileSchema, column)).toList());
            reader.setRequestedSchema(projection);
            long rows = 0;
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                RecordReader<Group> records = new ColumnIOFactory().getColumnIO(projection, fileSchema)
                    .getRecordReader(pages, new GroupRecordConverter(projection));
                for (long i = 0; i < pages.getRowCount(); i++) {
                    Group group = records.read();
                    String[] row = new String[columns.size()];
                    for (int field = 0; field < row.length; field++) {
                        row[field] = group.getFieldRepetitionCount(field) == 0 ? null : group.getString(field, 0);
                    }
                    sink.accept(row);
                    rows++;
                }
            }
            return rows;
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
