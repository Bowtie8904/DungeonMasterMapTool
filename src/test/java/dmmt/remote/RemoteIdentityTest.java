package dmmt.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteIdentityTest {
    @Test
    void createsVerifiableSessionOnlyIdentitiesWithoutKeytoolOrGlobalProviderChanges() throws Exception {
        var providers = java.security.Security.getProviders();
        var first = RemoteIdentity.create();
        var second = RemoteIdentity.create();
        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals(RemoteTls.fingerprint(first.certificate()), first.fingerprint());
        assertEquals(64, first.fingerprint().length());
        assertEquals("EC", first.certificate().getPublicKey().getAlgorithm());
        first.certificate().checkValidity();
        first.certificate().verify(first.certificate().getPublicKey());
        assertArrayEquals(providers, java.security.Security.getProviders());
        assertEquals("TLSv1.3", first.context().getProtocol());
    }
}
