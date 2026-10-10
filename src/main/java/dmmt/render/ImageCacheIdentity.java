package dmmt.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Move-stable asset version. The content fallback is only used by off-thread image preparation.
 */
final class ImageCacheIdentity
{
    private ImageCacheIdentity()
    {
    }

    static String key(Path file, String options) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String identity;
        if (attributes.fileKey() != null)
        {
            identity = attributes.fileKey().toString();
        }
        else
        {
            MessageDigest digest = digest();
            try (InputStream input = Files.newInputStream(file))
            {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1)
                {
                    if (Thread.currentThread().isInterrupted())
                    {
                        throw new java.io.InterruptedIOException("Image preparation cancelled");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            identity = HexFormat.of().formatHex(digest.digest());
        }
        return hash(identity + "|" + attributes.size() + "|" + attributes.lastModifiedTime() + options);
    }

    static String hash(String text)
    {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)), 0, 12);
    }

    private static MessageDigest digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException(ex);
        }
    }
}
