package uk.gov.hmcts.reform.services.listassist.ingest;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParquetLimitsTest {

    @Test
    void oversizedOrNegativePagesAreRejectedBeforeCallingTheCodec() {
        CompressionCodecFactory factory = mock(CompressionCodecFactory.class);
        var decoder = mock(CompressionCodecFactory.BytesInputDecompressor.class);
        when(factory.getDecompressor(any())).thenReturn(decoder);
        var bounded = new ParquetLimits(factory).getDecompressor(CompressionCodecName.SNAPPY);

        assertThatThrownBy(() -> bounded.decompress(BytesInput.empty(), ParquetLimits.MAX_PAGE_BYTES + 1))
            .isInstanceOf(SourceFileException.class);
        assertThatThrownBy(() -> bounded.decompress(BytesInput.empty(), -1))
            .isInstanceOf(SourceFileException.class);
        assertThatThrownBy(() -> bounded.decompress(ByteBuffer.allocate(0), 0, ByteBuffer.allocate(0),
            ParquetLimits.MAX_PAGE_BYTES + 1)).isInstanceOf(SourceFileException.class);
        verifyNoInteractions(decoder);
    }

    @Test
    void cumulativeDecodedBytesAreBoundedEvenWhenFooterSizesLie() throws IOException {
        CompressionCodecFactory factory = mock(CompressionCodecFactory.class);
        var decoder = mock(CompressionCodecFactory.BytesInputDecompressor.class);
        when(factory.getDecompressor(any())).thenReturn(decoder);
        ParquetLimits limits = new ParquetLimits(factory);
        var bounded = limits.getDecompressor(CompressionCodecName.SNAPPY);
        for (int i = 0; i < 8; i++) {
            bounded.decompress(BytesInput.empty(), ParquetLimits.MAX_PAGE_BYTES);
        }
        assertThatThrownBy(() -> bounded.decompress(BytesInput.empty(), 1))
            .isInstanceOf(SourceFileException.class);
        limits.nextRowGroup();
        bounded.decompress(BytesInput.empty(), ParquetLimits.MAX_PAGE_BYTES);
        verify(decoder, times(9)).decompress(BytesInput.empty(), ParquetLimits.MAX_PAGE_BYTES);
    }
}
