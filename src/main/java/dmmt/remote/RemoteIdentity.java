package dmmt.remote;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

/** One hosting session's identity. Creation belongs on a worker; no keys or passwords are persisted. */
public final class RemoteIdentity {
    private final SSLContext context;
    private final X509Certificate certificate;
    private final String fingerprint;

    private RemoteIdentity(SSLContext context, X509Certificate certificate) throws GeneralSecurityException {
        this.context = context;
        this.certificate = certificate;
        fingerprint = RemoteTls.fingerprint(certificate);
    }

    public static RemoteIdentity create() throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        var pair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=Dungeon Master Map Tool remote session");
        var builder = new JcaX509v3CertificateBuilder(name,
                new BigInteger(159, random).add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(30))),
                name, pair.getPublic());
        X509Certificate certificate;
        try {
            var signed = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()));
            certificate = new JcaX509CertificateConverter().getCertificate(signed);
        } catch (OperatorCreationException failure) {
            throw new GeneralSecurityException("Could not create the remote hosting identity.", failure);
        }
        certificate.checkValidity();
        certificate.verify(pair.getPublic());
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        char[] password = java.util.HexFormat.of().formatHex(secret).toCharArray();
        Arrays.fill(secret, (byte) 0);
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, password);
            keys.setKeyEntry("host", pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});
            return new RemoteIdentity(RemoteTls.hostContext(keys, password), certificate);
        } catch (java.io.IOException failure) {
            throw new GeneralSecurityException("Could not initialize the remote hosting identity.", failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public SSLContext context() {
        return context;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public X509Certificate certificate() {
        return certificate;
    }
}
