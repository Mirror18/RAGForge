package com.ragforge.ingestion.connector;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class FetchedContent implements AutoCloseable {
    private final InputStream stream;
    private final SourceMetadata metadata;

    public FetchedContent(InputStream stream, SourceMetadata metadata, long maxBytes) {
        this.stream = new BoundedInputStream(stream, maxBytes);
        this.metadata = metadata;
    }

    public InputStream stream() {
        return stream;
    }

    public SourceMetadata metadata() {
        return metadata;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long consumed;

        private BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (consumed >= maxBytes) {
                int extra = super.read();
                if (extra < 0) {
                    return -1;
                }
                throw new ConnectorException(ConnectorFailure.CONTENT_TOO_LARGE, "content exceeds configured limit");
            }
            int value = super.read();
            if (value >= 0) {
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (consumed >= maxBytes) {
                int extra = super.read();
                if (extra < 0) {
                    return -1;
                }
                throw new ConnectorException(ConnectorFailure.CONTENT_TOO_LARGE, "content exceeds configured limit");
            }
            int allowed = (int) Math.min(length, maxBytes - consumed);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                consumed += count;
            }
            return count;
        }
    }
}
