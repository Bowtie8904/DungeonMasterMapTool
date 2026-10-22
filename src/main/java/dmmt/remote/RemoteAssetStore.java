package dmmt.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, content-addressed storage for assets exchanged by an authenticated remote session.
 */
public final class RemoteAssetStore
{
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final String EXTENSION_PATTERN = "[a-z0-9]{1,12}";

    private final Path root;
    private final Path objects;
    private final Path staging;
    private final long maxAssetBytes;
    private final long maxStoredBytes;
    private final long maxStagingBytes;
    private long storedBytes;
    private long reservedStoredBytes;
    private long reservedStagingBytes;

    public RemoteAssetStore(Path root, long maxAssetBytes, long maxStoredBytes, long maxStagingBytes) throws IOException
    {
        if (maxAssetBytes < 0 || maxStoredBytes < 0 || maxStagingBytes < 0 || maxAssetBytes > maxStoredBytes)
            throw new IllegalArgumentException("Invalid remote asset quotas.");
        Files.createDirectories(root.toAbsolutePath().normalize());
        this.root = root.toAbsolutePath().normalize().toRealPath();
        this.objects = this.root.resolve("objects");
        this.staging = this.root.resolve("staging");
        this.maxAssetBytes = maxAssetBytes;
        this.maxStoredBytes = maxStoredBytes;
        this.maxStagingBytes = maxStagingBytes;
        ensureManagedDirectory(objects);
        ensureManagedDirectory(staging);
        cleanInterruptedStaging();
        storedBytes = scanStoredBytes();
    }

    public long maxAssetBytes()
    {
        return maxAssetBytes;
    }

    public long maxStoredBytes()
    {
        return maxStoredBytes;
    }

    public long maxStagingBytes()
    {
        return maxStagingBytes;
    }

    /**
     * Ingests a single bounded stream. The caller must supply the advertised length and SHA-256;
     * neither is trusted until verified against the streamed bytes.
     */
    public AssetDescriptor ingest(InputStream input, long expectedSize, String expectedSha256) throws IOException
    {
        Objects.requireNonNull(input, "input");
        requireHash(expectedSha256);
        if (expectedSize < 0 || expectedSize > maxAssetBytes)
            throw new IOException("Asset exceeds the per-file transfer limit.");
        synchronized (this)
        {
            Path existing = objectPath(expectedSha256, false);
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
            {
                verifyFile(existing, expectedSha256, expectedSize);
                return new AssetDescriptor(expectedSha256, expectedSize);
            }
            if (storedBytes + reservedStoredBytes > maxStoredBytes - expectedSize)
                throw new IOException("Remote asset storage quota exceeded.");
            if (reservedStagingBytes > maxStagingBytes - expectedSize)
                throw new IOException("Remote asset staging quota exceeded.");
            reservedStoredBytes += expectedSize;
            reservedStagingBytes += expectedSize;
        }

        Path temporary = staging.resolve(UUID.randomUUID() + ".part");
        try
        {
            ensureManagedDirectory(staging);
            MessageDigest digest = sha256();
            long actualSize = 0;
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                                                              StandardOpenOption.WRITE))
            {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1)
                {
                    if (actualSize > expectedSize - read)
                        throw new IOException("Asset stream exceeds its advertised size.");
                    actualSize += read;
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (actualSize != expectedSize)
                throw new IOException("Asset stream size does not match its manifest.");
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!actualHash.equals(expectedSha256))
                throw new IOException("Asset content does not match its SHA-256 identifier.");

            synchronized (this)
            {
                Path target = objectPath(expectedSha256, true);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                {
                    verifyFile(target, expectedSha256, expectedSize);
                }
                else
                {
                    try
                    {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                    catch (AtomicMoveNotSupportedException ex)
                    {
                        throw new IOException("The asset store does not support atomic publication.", ex);
                    }
                    storedBytes += expectedSize;
                }
            }
            return new AssetDescriptor(expectedSha256, expectedSize);
        }
        finally
        {
            Files.deleteIfExists(temporary);
            synchronized (this)
            {
                reservedStoredBytes -= expectedSize;
                reservedStagingBytes -= expectedSize;
            }
        }
    }

    /**
     * Explicitly publishes a host-selected file. Callers should invoke this only for assets the
     * authenticated session is authorized to expose.
     */
    public AssetDescriptor publishRequiredAsset(Path source) throws IOException
    {
        Objects.requireNonNull(source, "source");
        Path normalized = source.toAbsolutePath().normalize();
        try
        {
            if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Required asset is not a regular, non-symbolic-link file.");
            long size = Files.size(normalized);
            if (size > maxAssetBytes)
                throw new IOException("Asset exceeds the per-file transfer limit.");
            String hash;
            try (InputStream input = Files.newInputStream(normalized))
            {
                hash = hash(input, maxAssetBytes);
            }
            try (InputStream input = Files.newInputStream(normalized))
            {
                return ingest(input, size, hash);
            }
        }
        catch (IOException failure)
        {
            throw new IOException("Could not publish required asset '" + normalized + "': " + failure.getMessage(),
                                  failure);
        }
    }

    public boolean contains(String sha256) throws IOException
    {
        requireHash(sha256);
        Path path = objectPath(sha256, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return false;
        ensureRegularManagedFile(path);
        return true;
    }

    public AssetDescriptor descriptor(String sha256) throws IOException
    {
        requireHash(sha256);
        Path path = objectPath(sha256, false);
        ensureRegularManagedFile(path);
        long size = Files.size(path);
        if (size > maxAssetBytes)
            throw new IOException("Stored asset exceeds the configured per-file limit.");
        verifyFile(path, sha256, size);
        return new AssetDescriptor(sha256, size);
    }

    /**
     * Streams an asset to the destination and verifies it while reading. A failed verification
     * may occur after bytes have been written; receivers must publish only after their own check.
     */
    public void transferTo(String sha256, OutputStream destination) throws IOException
    {
        Objects.requireNonNull(destination, "destination");
        AssetDescriptor descriptor = descriptor(sha256);
        Path path = objectPath(sha256, false);
        MessageDigest digest = sha256();
        long count = 0;
        try (InputStream input = Files.newInputStream(path))
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1)
            {
                count += read;
                if (count > maxAssetBytes)
                    throw new IOException("Stored asset exceeds the configured per-file limit.");
                digest.update(buffer, 0, read);
                destination.write(buffer, 0, read);
            }
        }
        if (count != descriptor.size() || !HexFormat.of().formatHex(digest.digest()).equals(sha256))
            throw new IOException("Stored asset failed integrity verification.");
    }

    /**
     * Materializes a verified immutable object under a caller-selected cache directory. The
     * filename is derived only from the hash and validated extension.
     */
    public synchronized Path materialize(String sha256, Path cacheDirectory, String extension) throws IOException
    {
        requireHash(sha256);
        String safeExtension = validateExtension(extension);
        AssetDescriptor descriptor = descriptor(sha256);
        Path cacheRoot = cacheDirectory.toAbsolutePath().normalize();
        rejectSymbolicPathComponents(cacheRoot);
        Files.createDirectories(cacheRoot);
        if (Files.isSymbolicLink(cacheRoot))
            throw new IOException("Asset cache directory must not be a symbolic link.");
        Path realCacheRoot = cacheRoot.toRealPath();
        if (!Files.isDirectory(realCacheRoot, LinkOption.NOFOLLOW_LINKS) || !realCacheRoot.equals(cacheRoot))
            throw new IOException("Asset cache directory is not a safe directory.");
        cacheRoot = realCacheRoot;
        Path target = cacheRoot.resolve(sha256 + "." + safeExtension);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        {
            ensureRegularCacheFile(target, cacheRoot);
            try (InputStream input = Files.newInputStream(target))
            {
                if (Files.size(target) == descriptor.size() && hash(input, maxAssetBytes).equals(sha256))
                    return target;
            }
            throw new IOException("Cached asset failed integrity verification.");
        }

        cleanCacheStagingFiles(cacheRoot);
        long cacheBytes = scanCacheBytes(cacheRoot);
        if (cacheBytes > maxStoredBytes - descriptor.size())
            throw new IOException("Remote asset cache quota exceeded.");
        Path temporary = Files.createTempFile(cacheRoot, ".remote-asset-", ".part");
        try
        {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING,
                                                              StandardOpenOption.WRITE))
            {
                transferTo(sha256, output);
            }
            try
            {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                throw new IOException("The asset cache does not support atomic publication.", ex);
            }
            return target;
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private Path objectPath(String hash, boolean createParent) throws IOException
    {
        requireHash(hash);
        Path prefix = objects.resolve(hash.substring(0, 2));
        if (createParent)
            ensureManagedDirectory(prefix);
        else if (Files.exists(prefix, LinkOption.NOFOLLOW_LINKS))
            ensureManagedDirectory(prefix);
        Path result = prefix.resolve(hash);
        if (!result.normalize().startsWith(objects))
            throw new IOException("Invalid remote asset identifier.");
        return result;
    }

    private void cleanInterruptedStaging() throws IOException
    {
        try (var entries = Files.list(staging))
        {
            for (Path entry : entries.toList())
            {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected entry in remote asset staging directory.");
                Files.delete(entry);
            }
        }
    }

    private long scanStoredBytes() throws IOException
    {
        long total = 0;
        try (var entries = Files.walk(objects))
        {
            for (Path entry : entries.toList())
            {
                if (entry.equals(objects))
                    continue;
                if (Files.isSymbolicLink(entry))
                    throw new IOException("Symbolic links are not permitted in remote asset storage.");
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                {
                    String name = entry.getFileName().toString();
                    if (!name.matches("[0-9a-f]{2}"))
                        throw new IOException("Unexpected directory in remote asset storage.");
                    continue;
                }
                String hash = entry.getFileName().toString();
                if (!hash.matches(HASH_PATTERN) || !hash.startsWith(entry.getParent().getFileName().toString())
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected file in remote asset storage.");
                long size = Files.size(entry);
                if (size > maxAssetBytes || total > maxStoredBytes - size)
                    throw new IOException("Stored assets exceed the configured quota.");
                total += size;
            }
        }
        return total;
    }

    private void verifyFile(Path file, String expectedHash, long expectedSize) throws IOException
    {
        ensureRegularManagedFile(file);
        if (Files.size(file) != expectedSize)
            throw new IOException("Stored asset size does not match its manifest.");
        try (InputStream input = Files.newInputStream(file))
        {
            if (!hash(input, maxAssetBytes).equals(expectedHash))
                throw new IOException("Stored asset failed SHA-256 integrity verification.");
        }
    }

    private void ensureManagedDirectory(Path directory) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized))
            throw new IOException("Symbolic links are not permitted in managed asset storage.");
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Managed asset path is not a directory.");
        Path real = normalized.toRealPath();
        if (!real.startsWith(root) && !real.equals(root))
            throw new IOException("Managed asset path escapes its root.");
    }

    private void ensureRegularManagedFile(Path file) throws IOException
    {
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Managed asset is missing or is not a regular file.");
        Path real = normalized.toRealPath();
        if (!real.startsWith(root))
            throw new IOException("Managed asset path escapes its root.");
    }

    private static void ensureRegularCacheFile(Path file, Path cacheRoot) throws IOException
    {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Cached asset is not a regular file.");
        if (!file.toRealPath().startsWith(cacheRoot))
            throw new IOException("Cached asset path escapes its cache directory.");
    }

    private long scanCacheBytes(Path cacheRoot) throws IOException
    {
        class CacheVisitor extends SimpleFileVisitor<Path>
        {
            private long total;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
            {
                if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(cacheRoot))
                    throw new IOException("Unsafe directory in remote asset cache: " + directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                ensureRegularCacheFile(file, cacheRoot);
                if (!file.getFileName().toString().matches("[0-9a-f]{64}\\.[a-z0-9]{1,12}"))
                    throw new IOException("Unexpected file in remote asset cache: " + file);
                long size = Files.size(file);
                if (size > maxStoredBytes || total > maxStoredBytes - size)
                    throw new IOException("Remote asset cache quota exceeded.");
                total += size;
                return FileVisitResult.CONTINUE;
            }
        }
        var visitor = new CacheVisitor();
        Files.walkFileTree(cacheRoot, visitor);
        return visitor.total;
    }

    private static void cleanCacheStagingFiles(Path cacheRoot) throws IOException
    {
        try (var entries = Files.list(cacheRoot))
        {
            for (Path entry : entries.toList())
            {
                String name = entry.getFileName().toString();
                if (name.startsWith(".remote-asset-") && name.endsWith(".part"))
                {
                    if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Unexpected entry in remote asset cache staging.");
                    Files.delete(entry);
                }
            }
        }
    }

    private static void rejectSymbolicPathComponents(Path path) throws IOException
    {
        Path current = path.getRoot();
        if (current == null)
            throw new IOException("Asset cache path must be absolute.");
        for (Path component : path)
        {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current))
                throw new IOException("Symbolic links are not permitted in the asset cache path.");
        }
    }

    private static String hash(InputStream input, long limit) throws IOException
    {
        MessageDigest digest = sha256();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1)
        {
            if (count > limit - read)
                throw new IOException("Asset exceeds the per-file transfer limit.");
            count += read;
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String validateExtension(String extension) throws IOException
    {
        if (extension == null || !extension.toLowerCase(java.util.Locale.ROOT).matches(EXTENSION_PATTERN))
            throw new IOException("Invalid asset extension.");
        return extension.toLowerCase(java.util.Locale.ROOT);
    }

    static String extensionOf(Path path) throws IOException
    {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1)
            return "bin";
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return validateExtension(extension);
    }

    static void requireHash(String hash) throws IOException
    {
        if (hash == null || !hash.matches(HASH_PATTERN))
            throw new IOException("Invalid remote asset SHA-256 identifier.");
    }

    private static MessageDigest sha256()
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

    public record AssetDescriptor(String sha256, long size)
    {
        public AssetDescriptor
        {
            if (sha256 == null || !sha256.matches(HASH_PATTERN) || size < 0)
                throw new IllegalArgumentException("Invalid remote asset descriptor.");
        }
    }
}
