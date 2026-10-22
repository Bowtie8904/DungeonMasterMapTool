package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RemoteFailureTest {
    @Test
    void exceptionsWithoutMessagesStillProduceValidDiagnostics() {
        for (String message : new String[]{null, "", "   "}) {
            var failure = RemoteFailure.capture("", new IOException(message));
            assertEquals("IOException", failure.message().strip());
            assertTrue(failure.stackTrace().contains("java.io.IOException: IOException"));
        }
    }

    @Test
    void preservesOriginalCauseFramesAndBoundedCopyableDetailsAcrossTheWire() throws Exception {
        var missing = new NoSuchFileException("D:\\maps\\map-image.png");
        var captured = RemoteFailure.capture("Host artwork failed.", new CompletionException(
                new IOException("Could not publish map artwork.", missing)));
        assertTrue(captured.message().contains("Could not publish"));
        assertTrue(captured.stackTrace().contains("Caused by: java.nio.file.NoSuchFileException"));
        assertTrue(captured.stackTrace().contains("D:\\maps\\map-image.png"));
        assertTrue(captured.stackTrace().contains("RemoteFailureTest.java:"));
        var received = RemoteWire.payload(RemoteWire.message(RemoteWire.Type.WORKSPACE_WAIT,
                new RemoteProtocol.WorkspaceWait("Synchronization failed.", captured)), RemoteProtocol.WorkspaceWait.class);
        assertEquals(captured, received.failure());
        var exception = assertInstanceOf(RemoteFailure.HostException.class, received.failure().exception());
        assertSame(received.failure(), exception.details());
        var huge = RemoteFailure.capture("Artwork failed.", new IOException("x".repeat(100000)));
        assertTrue(huge.message().length() <= 1000);
        assertTrue(huge.stackTrace().length() <= RemoteFailure.MAX_TRACE);
    }

    @Test
    void parserPayloadsAndLabelledCredentialsAreNeverIncludedInDiagnostics() {
        String secret = "sensitive-credential-do-not-echo";
        var parser = new com.fasterxml.jackson.core.JsonParseException(null, "Payload " + secret);
        var failure = RemoteFailure.capture("Host query failed.", new IOException(parser.getMessage(), parser));
        assertFalse(failure.message().contains(secret));
        assertFalse(failure.stackTrace().contains(secret));
        assertTrue(failure.stackTrace().contains("payload omitted"));
        var labelled = RemoteFailure.capture("Host operation failed.",
                new IOException("credential=" + secret + " pairing code=12345678"));
        assertFalse(labelled.stackTrace().contains(secret));
        assertFalse(labelled.stackTrace().contains("12345678"));
    }
}
