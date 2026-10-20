package dmmt.service;

import dmmt.model.DmProject.TokenDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Converts runtime artwork into immutable, package-relative PNG assets and back.
 */
final class TokenAssets
{
    private TokenAssets()
    {
    }

    static void read(TokenDefinition token, Path directory) throws IOException
    {
        token.setSourceImage(read(token.getSourceImage(), directory));
        token.setImage(read(token.getImage(), directory));
    }

    static void write(TokenDefinition token, Path directory, String folder) throws IOException
    {
        token.setSourceImage(write(token.getSourceImage(), directory, folder));
        token.setImage(write(token.getImage(), directory, folder));
    }

    private static String read(String value, Path directory) throws IOException
    {
        if (value == null || !value.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
            return value;
        Path root = directory.toAbsolutePath().normalize();
        Path file = root.resolve(value.replace('/', '\\')).normalize();
        if (!file.startsWith(root))
            throw new IOException("Token image is outside its package: " + value);
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }

    private static String write(String value, Path directory, String folder) throws IOException
    {
        if (value == null)
            return null;
        byte[] bytes;
        try
        {
            bytes = Base64.getDecoder().decode(value);
        }
        catch (IllegalArgumentException ex)
        {
            throw new IOException("Invalid embedded token image.", ex);
        }
        if (bytes.length < 8 || bytes[0] != (byte)0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
                || bytes[4] != 13 || bytes[5] != 10 || bytes[6] != 26 || bytes[7] != 10)
            throw new IOException("Token artwork must be a PNG image.");
        try
        {
            String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".png";
            Path relative = Path.of(folder).resolve(name);
            Path target = directory.resolve(relative);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target))
            {
                Path staging = Files.createTempFile(target.getParent(), "token-", ".tmp");
                try
                {
                    Files.write(staging, bytes);
                    Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                finally
                {
                    Files.deleteIfExists(staging);
                }
            }
            return relative.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException(ex);
        }
    }
}
