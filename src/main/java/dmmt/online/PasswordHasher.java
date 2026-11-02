package dmmt.online;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Argon2id password hashing (Bouncy Castle) with a per-password salt; parameters are stored with the hash. */
public final class PasswordHasher {
    private static final int MEMORY_KB = 19456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return hash(password, MEMORY_KB, ITERATIONS, PARALLELISM);
    }

    static String hash(String password, int memoryKb, int iterations, int parallelism) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, memoryKb, iterations, parallelism, HASH_BYTES);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return "argon2id$" + memoryKb + "$" + iterations + "$" + parallelism + "$" + enc.encodeToString(salt) + "$"
                + enc.encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || stored.isBlank()) {
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 6 || !"argon2id".equals(parts[0])) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            byte[] actual = derive(password, salt, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int memoryKb, int iterations, int parallelism, int length) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withMemoryAsKB(memoryKb).withIterations(iterations).withParallelism(parallelism).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
