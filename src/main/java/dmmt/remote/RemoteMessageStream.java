package dmmt.remote;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded, uncompressed workspace framing; even large fog/maps retain the ordinary per-frame cap. */
final class RemoteMessageStream {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteMessageStream.class);
    static final int MAX_PAYLOAD_BYTES = 32 * 1024 * 1024;
    private static final Set<RemoteWire.Type> LARGE_TYPES = Set.of(
            RemoteWire.Type.WORKSPACE_STATE, RemoteWire.Type.WORKSPACE_EDIT, RemoteWire.Type.WORKSPACE_RESULT,
            RemoteWire.Type.OPERATION_REPLY, RemoteWire.Type.OPERATION_QUERY);

    record Begin(RemoteWire.Type type, int size) {
        Begin {
            if (!LARGE_TYPES.contains(type) || size <= RemoteWire.MAX_MESSAGE_BYTES || size > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Invalid large workspace payload.");
            }
        }
    }

    private RemoteMessageStream() {
    }

    static void send(RemoteTlsChannel channel, RemoteWire.Message message) throws IOException {
        if (!LARGE_TYPES.contains(message.type())) {
            channel.send(message);
            return;
        }
        byte[] bytes = RemoteWire.encode(message, MAX_PAYLOAD_BYTES);
        if (bytes.length <= RemoteWire.MAX_MESSAGE_BYTES) {
            channel.send(message);
            return;
        }
        var opening = RemoteWire.reply(message, RemoteWire.Type.PAYLOAD_BEGIN, new Begin(message.type(), bytes.length));
        LOG.debug("Streaming large {} payload ({} bytes)", message.type(), bytes.length);
        channel.send(opening);
        try (var output = RemoteAssetTransfer.output(channel, message.id(), bytes.length, deadline(), ignored -> {})) {
            output.write(bytes);
        }
    }

    static RemoteWire.Message receive(RemoteTlsChannel channel) throws IOException {
        var message = channel.receive();
        if (message.type() != RemoteWire.Type.PAYLOAD_BEGIN) {
            return message;
        }
        try {
            var begin = RemoteWire.payload(message, Begin.class);
            byte[] bytes = RemoteAssetTransfer.input(channel, message.id(), begin.size(), deadline(), ignored -> {})
                    .readAllBytes();
            var decoded = RemoteWire.decode(bytes);
            if (decoded.type() != begin.type() || !decoded.id().equals(message.id())) {
                LOG.warn("Rejected large workspace payload: announced {} but received {}", begin.type(), decoded.type());
                throw new IOException("Large workspace payload identity does not match.");
            }
            LOG.debug("Received large {} payload ({} bytes)", decoded.type(), begin.size());
            return decoded;
        } catch (IllegalArgumentException malformed) {
            LOG.warn("Rejected malformed large workspace payload", malformed);
            throw new IOException("Invalid large workspace payload.", malformed);
        }
    }

    private static Runnable deadline() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        return () -> {
            if (System.nanoTime() >= deadline) {
                throw new IllegalArgumentException("The workspace payload deadline expired.");
            }
        };
    }
}
