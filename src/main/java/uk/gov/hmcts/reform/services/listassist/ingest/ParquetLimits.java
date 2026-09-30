package uk.gov.hmcts.reform.services.listassist.ingest;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Bounds decoder input before the codec allocates its output, including forged page sizes. */
final class ParquetLimits implements CompressionCodecFactory {

    static final int MAX_FOOTER_BYTES = 8 * 1024 * 1024;
    static final int MAX_PAGE_BYTES = 16 * 1024 * 1024;
    static final int MAX_ROW_GROUP_BYTES = 128 * 1024 * 1024;
    static final int MAX_FIELD_BYTES = 16 * 1024;
    static final long MAX_ROWS = 5_000_000;

    private final CompressionCodecFactory delegate;
    private long decodedBytes;

    ParquetLimits(CompressionCodecFactory delegate) {
        this.delegate = delegate;
    }

    void nextRowGroup() {
        decodedBytes = 0;
    }

    static void requireWithin(long value, long maximum) {
        if (value < 0 || value > maximum) {
            throw new SourceFileException("resource_limit", "Parquet input exceeds a resource limit");
        }
    }

    private void checkPage(long compressedSize, int uncompressedSize) {
        requireWithin(compressedSize, MAX_PAGE_BYTES);
        requireWithin(uncompressedSize, MAX_PAGE_BYTES);
        requireWithin(uncompressedSize, MAX_ROW_GROUP_BYTES - decodedBytes);
        decodedBytes += uncompressedSize;
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codec) {
        BytesInputDecompressor decompressor = delegate.getDecompressor(codec);
        return new BytesInputDecompressor() {
            @Override
            public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
                checkPage(bytes.size(), uncompressedSize);
                return decompressor.decompress(bytes, uncompressedSize);
            }

            @Override
            public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
                throws IOException {
                checkPage(compressedSize, uncompressedSize);
                decompressor.decompress(input, compressedSize, output, uncompressedSize);
            }

            @Override
            public void release() {
                decompressor.release();
            }
        };
    }

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codec) {
        throw new UnsupportedOperationException("Ingestion only reads Parquet");
    }

    @Override
    public void release() {
        delegate.release();
    }
}
