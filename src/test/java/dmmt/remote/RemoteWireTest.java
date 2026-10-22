package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWireTest {
    @Test
    void envelopesAndTypedPayloadsRoundTripWithMessageBoundaries() throws Exception {
        var first = RemoteWire.message(RemoteWire.Type.HELLO, new RemoteWire.Hello(1, "build-123"));
        var second = RemoteWire.message(RemoteWire.Type.ERROR, Map.of("message", "Try again."));
        var out = new ByteArrayOutputStream();
        RemoteWire.write(out, first);
        RemoteWire.write(out, second);
        var in = new ByteArrayInputStream(out.toByteArray());
        assertEquals(first, RemoteWire.read(in));
        assertEquals(second, RemoteWire.read(in));
        assertEquals(new RemoteWire.Hello(1, "build-123"), RemoteWire.payload(first, RemoteWire.Hello.class));
        assertThrows(EOFException.class, () -> RemoteWire.read(in));
        assertFalse(RemoteWire.message(RemoteWire.Type.PAIR, Map.of("code", "12345678"))
                .toString().contains("12345678"));
    }

    @Test
    void rejectsOversizedEmptyNegativeAndTruncatedFrames() throws Exception {
        for (int length : new int[]{0, -1, Integer.MAX_VALUE, RemoteWire.MAX_MESSAGE_BYTES + 1}) {
            var out = new ByteArrayOutputStream();
            new DataOutputStream(out).writeInt(length);
            assertThrows(IOException.class, () -> RemoteWire.read(new ByteArrayInputStream(out.toByteArray())));
        }
        var out = new ByteArrayOutputStream();
        new DataOutputStream(out).writeInt(100);
        out.write("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(EOFException.class, () -> RemoteWire.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsUnknownFieldsDuplicateKeysTrailingValuesAndInvalidEnvelopes() {
        String id = UUID.randomUUID().toString();
        for (String json : new String[]{
                "{\"id\":\"" + id + "\",\"type\":\"UNKNOWN\",\"payload\":{}}",
                "{\"id\":\"" + id + "\",\"type\":1,\"payload\":{}}",
                "{\"id\":\"" + id + "\",\"type\":\"EDIT\",\"payload\":{},\"extra\":1}",
                "{\"id\":\"" + id + "\",\"type\":\"EDIT\",\"type\":\"STATE\",\"payload\":{}}",
                "{\"id\":\"" + id + "\",\"type\":\"EDIT\",\"payload\":{}} {}",
                "{\"id\":null,\"type\":\"EDIT\",\"payload\":{}}",
                "{\"id\":\"" + id + "\",\"type\":\"EDIT\",\"payload\":[]}",
                "{\"id\":\"" + id + "\",\"type\":\"EDIT\",\"payload\":null}",
                "null", "[]", "{broken"
        }) {
            assertThrows(IOException.class, () -> RemoteWire.read(frame(json)), json);
        }
    }

    @Test
    void rejectsDeeplyNestedPayloadsAndOversizedOutboundMessagesWithoutPartialOutput() {
        String deep = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"EDIT\",\"payload\":"
                + "{\"nested\":".repeat(40) + "{}" + "}".repeat(40) + "}";
        assertThrows(IOException.class, () -> RemoteWire.read(frame(deep)));
        var message = RemoteWire.message(RemoteWire.Type.EDIT, Map.of("text", "x".repeat(RemoteWire.MAX_MESSAGE_BYTES)));
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> RemoteWire.write(out, message));
        assertEquals(0, out.size());
    }

    @Test
    void validatesHelloAndDoesNotRetainTheCallersMutablePayload() {
        var message = RemoteWire.message(RemoteWire.Type.STATE, Map.of("revision", 1));
        var copy = new RemoteWire.Message(message.id(), message.type(), message.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) message.payload()).put("revision", 2);
        assertEquals(1, message.payload().get("revision").intValue());
        assertEquals(1, copy.payload().get("revision").intValue());
        assertThrows(IllegalArgumentException.class, () -> new RemoteWire.Hello(1, ""));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWire.Hello(1, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWire.Hello(1, "x".repeat(129)));
    }

    @Test
    void payloadsRejectFractionalIntegersNullPrimitivesAndOrdinalEnums() {
        assertThrows(IOException.class, () -> RemoteWire.payload(RemoteWire.message(RemoteWire.Type.HELLO,
                Map.of("protocolVersion", 1.9, "build", "test")), RemoteWire.Hello.class));
        assertThrows(IOException.class, () -> RemoteWire.payload(RemoteWire.message(RemoteWire.Type.HELLO,
                java.util.Collections.singletonMap("protocolVersion", null)), RemoteWire.Hello.class));
        assertThrows(IOException.class, () -> RemoteWire.payload(RemoteWire.message(RemoteWire.Type.ERROR,
                Map.of("code", 0, "message", "Error")), RemoteProtocol.Failure.class));
    }

    @Test
    void responseCorrelationAndSecretRedactionAreExplicit() {
        var request = RemoteWire.message(RemoteWire.Type.STATE, new RemoteProtocol.Empty());
        assertEquals(request.id(), RemoteWire.reply(request, RemoteWire.Type.STATE, Map.of("revision", 1)).id());
        assertFalse(new RemoteProtocol.Pair("12345678", "Desktop B").toString().contains("12345678"));
        assertFalse(new RemoteProtocol.Connect(UUID.randomUUID(), "A".repeat(43)).toString().contains("A".repeat(43)));
    }

    private static ByteArrayInputStream frame(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream();
        new DataOutputStream(out).writeInt(bytes.length);
        out.write(bytes);
        return new ByteArrayInputStream(out.toByteArray());
    }
}
