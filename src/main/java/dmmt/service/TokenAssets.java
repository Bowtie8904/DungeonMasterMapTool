package dmmt.service;

import dmmt.model.DmProject.TokenDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Converts runtime artwork into immutable, package-relative PNG assets and back.
 */
public final class TokenAssets
{
    private static Path sessionDirectory;

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
        if (value == null)
            return value;
        if (value.startsWith("file:"))
            return snapshot(Path.of(URI.create(value)));
        if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
        {
            try
            {
                return store(Base64.getDecoder().decode(value));
            }
            catch (IllegalArgumentException ex)
            {
                throw new IOException("Invalid embedded token image.", ex);
            }
        }
        Path root = directory.toAbsolutePath().normalize();
        Path file = root.resolve(value.replace('/', '\\')).normalize();
        if (!file.startsWith(root))
            throw new IOException("Token image is outside its package: " + value);
        return snapshot(file);
    }

    private static String write(String value, Path directory, String folder) throws IOException
    {
        if (value == null)
            return null;
        String reference;
        try
        {
            reference = value.startsWith("file:") ? value : store(Base64.getDecoder().decode(value));
        }
        catch (IllegalArgumentException ex)
        {
            throw new IOException("Invalid embedded token image.", ex);
        }
        Path source = Path.of(URI.create(reference));
        Path relative = Path.of(folder).resolve(source.getFileName());
        Path target = directory.resolve(relative);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target))
        {
            Path staging = Files.createTempFile(target.getParent(), "token-", ".tmp");
            try
            {
                Files.copy(source, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(staging);
            }
        }
        return relative.toString();
    }

    public static synchronized String store(byte[] png) throws IOException
    {
        validatePng(png);
        Path target = sessionDirectory().resolve(hash(png) + ".png");
        if (!Files.exists(target))
        {
            Path staging = Files.createTempFile(target.getParent(), "token-", ".tmp");
            try
            {
                Files.write(staging, png);
                Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(staging);
            }
        }
        return target.toUri().toString();
    }

    private static String hash(byte[] bytes)
    {
        return HexFormat.of().formatHex(digest().digest(bytes));
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

    private static synchronized String snapshot(Path source) throws IOException
    {
        if (!Files.isRegularFile(source))
            throw new IOException("Token image is missing: " + source);
        String name = source.getFileName().toString();
        if (!name.matches("[0-9a-f]{64}\\.png"))
        {
            MessageDigest digest = digest();
            try (InputStream input = Files.newInputStream(source))
            {
                byte[] header = input.readNBytes(8);
                validatePng(header);
                digest.update(header);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1)
                    digest.update(buffer, 0, count);
            }
            name = HexFormat.of().formatHex(digest.digest()) + ".png";
        }
        Path target = sessionDirectory().resolve(name);
        if (!Files.exists(target))
        {
            try
            {
                Files.createLink(target, source.toAbsolutePath());
            }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException ex)
            {
                Files.copy(source, target);
            }
        }
        return target.toUri().toString();
    }

    private static void validatePng(byte[] bytes) throws IOException
    {
        if (bytes.length < 8 || bytes[0] != (byte)0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
                || bytes[4] != 13 || bytes[5] != 10 || bytes[6] != 26 || bytes[7] != 10)
            throw new IOException("Token artwork must be a PNG image.");
    }

    private static Path sessionDirectory() throws IOException
    {
        if (sessionDirectory == null)
        {
            Path directory = Files.createTempDirectory("dmmt-token-art-");
            sessionDirectory = directory;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try
                {
                    java.util.List<Path> entries;
                    try (var files = Files.list(directory))
                    {
                        entries = files.toList();
                    }
                    for (Path file : entries)
                        Files.deleteIfExists(file);
                    Files.deleteIfExists(directory);
                }
                catch (IOException ex)
                {
                    java.util.logging.Logger.getLogger(TokenAssets.class.getName())
                                            .log(java.util.logging.Level.WARNING, "Could not remove session token artwork", ex);
                }
            }, "token-art-cleanup"));
        }
        return sessionDirectory;
    }
}
