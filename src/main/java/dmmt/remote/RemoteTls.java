package dmmt.remote;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** TLS contexts for a host identity and an exact certificate pin, verified manually or selected through discovery. */
public final class RemoteTls {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteTls.class);

    private RemoteTls() {
    }

    public static String fingerprint(X509Certificate certificate) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    public static SSLContext hostContext(KeyStore identity, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, password);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    /** A discovery pin provides channel continuity, not independently verified host identity. Never accept any certificate. */
    public static SSLContext clientContext(String verifiedFingerprint) throws GeneralSecurityException {
        if (verifiedFingerprint == null || !verifiedFingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("A verified SHA-256 host certificate fingerprint is required.");
        }
        byte[] pin = HexFormat.of().parseHex(verifiedFingerprint);
        X509TrustManager trust = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("This trust context only authenticates the remote host.");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    LOG.warn("TLS: the remote host supplied no certificate");
                    throw new CertificateException("The remote host supplied no certificate.");
                }
                try {
                    chain[0].checkValidity();
                } catch (CertificateException failure) {
                    LOG.warn("TLS: host certificate is not currently valid: {}", failure.getMessage());
                    throw failure;
                }
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(pin, actual)) {
                        LOG.warn("TLS: host certificate does not match the pinned fingerprint ({}... expected)",
                                verifiedFingerprint.substring(0, 8));
                        throw new CertificateException("The host certificate does not match the verified fingerprint.");
                    }
                } catch (GeneralSecurityException failure) {
                    LOG.warn("TLS: could not verify the remote host certificate", failure);
                    throw new CertificateException("Could not verify the remote host certificate.", failure);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, new TrustManager[]{trust}, null);
        return context;
    }
}
