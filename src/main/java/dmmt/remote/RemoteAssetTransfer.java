package dmmt.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongConsumer;

/** Hash-only bulk protocol. Each bounded chunk is independently framed; publication verifies the complete stream. */
public final class RemoteAssetTransfer {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteAssetTransfer.class);
    public static final int CHUNK_BYTES = 64 * 1024;
    public enum Direction { UPLOAD, DOWNLOAD }

    public record Open(RemoteProtocol.Connect access, Direction direction, RemoteAssetStore.AssetDescriptor asset) {
        public Open {
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(asset, "asset");
        }

        @Override
        public String toString() {
            return "AssetTransfer[direction=" + direction + ", asset=" + asset + ", access=redacted]";
        }
    }

    public record Chunk(long offset, String data) {
        public Chunk {
            if (offset < 0 || data == null || data.length() > ((CHUNK_BYTES + 2) / 3) * 4 || data.isEmpty()) {
                throw new IllegalArgumentException("Invalid asset chunk.");
            }
        }
    }

    public record End(long size) {
        public End {
            if (size < 0) {
                throw new IllegalArgumentException("Invalid asset transfer length.");
            }
        }
    }

    private RemoteAssetTransfer() {
    }

    static InputStream input(RemoteTlsChannel channel, UUID id, long expected, Runnable authorize,
                             LongConsumer progress) {
        return new InputStream() {
            private byte[] chunk = new byte[0];
            private int cursor;
            private long offset;
            private boolean ended;

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
            }

            @Override
            public int read(byte[] bytes, int from, int length) throws IOException {
                Objects.checkFromIndexSize(from, length, bytes.length);
                if (length == 0) {
                    return 0;
                }
                if (cursor == chunk.length && !ended) {
                    authorize.run();
                    var message = channel.receive();
                    requireId(message, id);
                    if (message.type() == RemoteWire.Type.TRANSFER_END) {
                        var end = RemoteWire.payload(message, End.class);
                        if (offset != expected || end.size() != expected) {
                            LOG.warn("Asset transfer ended at offset {} (expected {})", offset, expected);
                            throw new IOException("Asset transfer ended at an unexpected offset.");
                        }
                        ended = true;
                    } else {
                        requireType(message, RemoteWire.Type.TRANSFER_CHUNK);
                        var part = RemoteWire.payload(message, Chunk.class);
                        if (part.offset() != offset) {
                            LOG.warn("Asset chunk offset {} does not match expected {}", part.offset(), offset);
                            throw new IOException("Asset chunk offset does not match.");
                        }
                        try {
                            chunk = Base64.getDecoder().decode(part.data());
                        } catch (IllegalArgumentException malformed) {
                            LOG.warn("Asset chunk at offset {} is not valid Base64", offset);
                            throw new IOException("Asset chunk is not valid Base64.", malformed);
                        }
                        if (chunk.length < 1 || chunk.length > CHUNK_BYTES || offset > expected - chunk.length) {
                            LOG.warn("Asset chunk of {} bytes at offset {} exceeds the advertised {} bytes",
                                    chunk.length, offset, expected);
                            throw new IOException("Asset chunk exceeds the advertised transfer.");
                        }
                        cursor = 0;
                        offset += chunk.length;
                        progress.accept(offset);
                    }
                }
                if (ended) {
                    return -1;
                }
                int count = Math.min(length, chunk.length - cursor);
                System.arraycopy(chunk, cursor, bytes, from, count);
                cursor += count;
                return count;
            }
        };
    }

    static OutputStream output(RemoteTlsChannel channel, UUID id, long expected, Runnable authorize,
                               LongConsumer progress) {
        return new OutputStream() {
            private long offset;
            private boolean ended;

            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value});
            }

            @Override
            public void write(byte[] bytes, int from, int length) throws IOException {
                Objects.checkFromIndexSize(from, length, bytes.length);
                if (ended || offset > expected - length) {
                    throw new IOException("Asset stream exceeds its advertised size or is closed.");
                }
                for (int cursor = from; cursor < from + length;) {
                    authorize.run();
                    int size = Math.min(CHUNK_BYTES, from + length - cursor);
                    String encoded = Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, cursor, cursor + size));
                    channel.send(new RemoteWire.Message(id, RemoteWire.Type.TRANSFER_CHUNK,
                            RemoteWire.message(RemoteWire.Type.TRANSFER_CHUNK, new Chunk(offset, encoded)).payload()));
                    offset += size;
                    cursor += size;
                    progress.accept(offset);
                }
            }

            @Override
            public void close() throws IOException {
                if (!ended) {
                    authorize.run();
                    if (offset != expected) {
                        throw new IOException("Asset stream ended before its advertised size.");
                    }
                    channel.send(new RemoteWire.Message(id, RemoteWire.Type.TRANSFER_END,
                            RemoteWire.message(RemoteWire.Type.TRANSFER_END, new End(offset)).payload()));
                    ended = true;
                }
            }
        };
    }

    static void requireId(RemoteWire.Message message, UUID id) throws IOException {
        if (!id.equals(message.id())) {
            LOG.warn("Asset transfer reply id does not match its request");
            throw new IOException("Asset transfer identity does not match its request.");
        }
    }

    static void requireType(RemoteWire.Message message, RemoteWire.Type type) throws IOException {
        if (message.type() == RemoteWire.Type.ERROR) {
            LOG.warn("The host rejected the asset transfer");
            throw new IOException("The host rejected the asset transfer; verify access and quota availability.");
        }
        if (message.type() != type) {
            LOG.warn("Unexpected asset transfer message {} (expected {})", message.type(), type);
            throw new IOException("Unexpected asset transfer message.");
        }
    }
}
