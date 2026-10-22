package dmmt.remote;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;

/** Bounded control messages, not bulk asset transport. No polymorphic Java type names on the wire. */
public final class RemoteWire {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteWire.class);
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final int PROTOCOL_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxStringLength(MAX_MESSAGE_BYTES).maxNumberLength(64).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public enum Type {
        HELLO, PAIR, REQUEST_CONTROL, APPROVAL, GRANT, CONNECT, STATE, UNCHANGED, EDIT, RESULT, ERROR, DISCONNECT,
        WORKSPACE_STATE, WORKSPACE_EDIT, WORKSPACE_RESULT, WORKSPACE_WAIT,
        PLAYER_QUERY, PLAYER_STATE, HANDOUT_QUERY, HANDOUT_STATE,
        DIRECTORY_QUERY, DIRECTORY_LIST,
        SAVE_QUERY, SAVE_STATE,
        MAP_CATALOG_QUERY, MAP_CATALOG,
        OPERATION_QUERY, OPERATION_REPLY,
        TRANSFER_OPEN, TRANSFER_READY, TRANSFER_PRESENT, TRANSFER_CHUNK, TRANSFER_END, TRANSFER_RESULT,
        PAYLOAD_BEGIN
    }

    public record Message(UUID id, Type type, JsonNode payload) {
        public Message {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Remote message payload must be a JSON object.");
            }
            payload = payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }

        @Override
        public String toString() {
            return "Message[id=" + id + ", type=" + type + ", payload=redacted]";
        }
    }

    /** The legacy wire field "build" now carries RemoteCompatibility.VERSION, not a content hash. */
    public record Hello(int protocolVersion, String build) {
        public Hello {
            if (build == null || build.isBlank() || build.length() > 128
                    || build.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A valid remote compatibility version is required.");
            }
        }
    }

    private RemoteWire() {
    }

    public static Message message(Type type, Object payload) {
        return new Message(UUID.randomUUID(), type, JSON.valueToTree(payload));
    }

    public static Message reply(Message request, Type type, Object payload) {
        return new Message(request.id(), type, JSON.valueToTree(payload));
    }

    public static <T> T payload(Message message, Class<T> type) throws IOException {
        return JSON.treeToValue(message.payload(), type);
    }

    public static Message read(InputStream input) throws IOException {
        DataInputStream stream = new DataInputStream(input);
        int length = stream.readInt();
        if (length < 1 || length > MAX_MESSAGE_BYTES) {
            LOG.warn("Rejected remote message with invalid length {}", length);
            throw new IOException("Remote message length is outside the control-message limit.");
        }
        byte[] bytes = new byte[length];
        stream.readFully(bytes);
        Message message = JSON.readValue(bytes, Message.class);
        if (message == null) {
            throw new IOException("A remote message must not be null.");
        }
        return message;
    }

    static byte[] encode(Message message, int limit) throws IOException {
        Objects.requireNonNull(message, "message");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JSON.writeValue(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                requireSpace(1);
                bytes.write(value);
            }

            @Override
            public void write(byte[] source, int offset, int length) throws IOException {
                requireSpace(length);
                bytes.write(source, offset, length);
            }

            private void requireSpace(int length) throws IOException {
                if (length > limit - bytes.size()) {
                    throw new IOException("Remote message exceeds its bounded payload limit.");
                }
            }
        }, message);
        return bytes.toByteArray();
    }

    static Message decode(byte[] bytes) throws IOException {
        Message message = JSON.readValue(bytes, Message.class);
        if (message == null) {
            throw new IOException("A remote message must not be null.");
        }
        return message;
    }

    public static void write(OutputStream output, Message message) throws IOException {
        byte[] bytes = encode(message, MAX_MESSAGE_BYTES);
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(bytes.length);
        stream.write(bytes);
        stream.flush();
    }
}
