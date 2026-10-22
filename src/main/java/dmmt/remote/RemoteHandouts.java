package dmmt.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import dmmt.log.Log;
import dmmt.ui.HandoutWindow;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable handout protocol and bounded PNG preparation. No filesystem paths cross the wire.
 */
public final class RemoteHandouts
{
    private static final Log LOG = Log.of(RemoteHandouts.class);
    public static final int MAX_IMAGES = 64;
    public static final long MAX_IMAGE_PIXELS = 16_777_216;
    public static final long MAX_BOARD_PIXELS = 33_554_432;
    public static final int THUMBNAIL_WIDTH = 96;
    public static final int THUMBNAIL_HEIGHT = 144;

    public enum Operation
    {
        BOARD,
        ADD_RECENT,
        REMOVE_RECENT
    }

    public record Recent(String id, RemoteAssetStore.AssetDescriptor original,
                         RemoteAssetStore.AssetDescriptor thumbnail)
    {
        public Recent
        {
            if (id == null || !id.matches("[0-9a-f]{16,64}"))
                throw new IllegalArgumentException("Invalid recent handout ID.");
            Objects.requireNonNull(original);
            Objects.requireNonNull(thumbnail);
        }
    }

    public record Library(@JsonProperty(required = true) long version, @JsonProperty(required = true) int max,
                          List<Recent> entries)
    {
        public Library
        {
            entries = List.copyOf(entries);
            if (version < 0 || max < 0 || max > 1000 || entries.size() > max
                    || entries.stream().map(Recent::id).distinct().count() != entries.size())
            {
                throw new IllegalArgumentException("Invalid recent handout library.");
            }
        }

        public static Library empty()
        {
            return new Library(0, 0, List.of());
        }
    }

    public record Board(List<RemoteAssetStore.AssetDescriptor> images, @JsonProperty(required = true) int rotation,
                        @JsonProperty(required = true) boolean mirrored,
                        @JsonProperty(required = true) boolean shown, List<Integer> showOnly)
    {
        public Board
        {
            images = List.copyOf(images);
            showOnly = showOnly == null ? null : List.copyOf(showOnly);
            int imageCount = images.size();
            if (images.size() > MAX_IMAGES || rotation < 0 || rotation >= 360 || rotation % 90 != 0
                    || shown && images.isEmpty() || showOnly != null && (showOnly.isEmpty()
                    || showOnly.size() > images.size() || new java.util.HashSet<>(showOnly).size() != showOnly.size()
                    || showOnly.stream().anyMatch(i -> i < 0 || i >= imageCount)))
            {
                throw new IllegalArgumentException("Invalid handout board.");
            }
        }

        public static Board empty()
        {
            return new Board(List.of(), 0, false, false, null);
        }
    }

    public record State(UUID sessionId, @JsonProperty(required = true) long revision,
                        @JsonProperty(required = true) long nextSequence, Board board,
                        @JsonProperty(required = true) boolean ready, String message, Library library)
    {
        public State
        {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(board);
            Objects.requireNonNull(library);
            if (revision < 0 || nextSequence < 1 || message == null || message.isBlank() || message.length() > 500)
            {
                throw new IllegalArgumentException("Invalid handout state.");
            }
        }

        public State(UUID sessionId, long revision, long nextSequence, Board board, boolean ready, String message)
        {
            this(sessionId, revision, nextSequence, board, ready, message, Library.empty());
        }
    }

    public record Request(UUID sessionId, UUID commandId, @JsonProperty(required = true) long sequence,
                          @JsonProperty(required = true) long expectedRevision, Board board,
                          Operation operation, String recentId)
    {
        public Request
        {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(commandId);
            Objects.requireNonNull(board);
            Objects.requireNonNull(operation);
            if (operation == Operation.BOARD ? recentId != null
                                             : recentId == null || !recentId.matches("[0-9a-f]{16,64}"))
            {
                throw new IllegalArgumentException("Invalid recent handout command.");
            }
            if (sequence < 1 || expectedRevision < 0)
                throw new IllegalArgumentException("Invalid handout command.");
        }

        public Request(UUID sessionId, UUID commandId, long sequence, long expectedRevision, Board board)
        {
            this(sessionId, commandId, sequence, expectedRevision, board, Operation.BOARD, null);
        }
    }

    public record Stamp(UUID sessionId, @JsonProperty(required = true) long revision,
                        @JsonProperty(required = true) long nextSequence,
                        @JsonProperty(required = true) boolean ready, String message)
    {
        public Stamp
        {
            Objects.requireNonNull(sessionId);
            if (revision < 0 || nextSequence < 1 || message == null || message.length() > 500)
                throw new IllegalArgumentException("Invalid handout state stamp.");
        }

        public Stamp(State state)
        {
            this(state.sessionId(), state.revision(), state.nextSequence(), state.ready(), state.message());
        }
    }

    public record Query(Request request, Stamp stamp)
    {
        public Query(Request request)
        {
            this(request, null);
        }

        public boolean unchanged(State state)
        {
            return request == null && new Stamp(state).equals(stamp);
        }
    }

    public record Update(State state, RemotePlayerSession.Receipt receipt)
    {
        public Update
        {
            Objects.requireNonNull(state);
        }
    }

    public record Prepared(Board board, HandoutWindow.Snapshot snapshot)
    {
    }

    /**
     * Preparation is serialized; a failed board must not retain its decoded images.
     */
    public static final class Artwork
    {
        private final RemoteAssetStore store;
        private final Path cache;
        private final IdentityHashMap<Image, RemoteAssetStore.AssetDescriptor> encoded = new IdentityHashMap<>();
        private final Map<String, Image> decoded = new LinkedHashMap<>();

        public Artwork(RemoteAssetStore store, Path cache)
        {
            this.store = Objects.requireNonNull(store);
            this.cache = Objects.requireNonNull(cache);
        }

        public synchronized Prepared publish(HandoutWindow.Snapshot snapshot) throws IOException
        {
            validatePixels(snapshot.images());
            Files.createDirectories(cache);
            var assets = new ArrayList<RemoteAssetStore.AssetDescriptor>();
            var nextEncoded = new IdentityHashMap<Image, RemoteAssetStore.AssetDescriptor>();
            var nextDecoded = new LinkedHashMap<String, Image>();
            for (var image : snapshot.images())
            {
                var asset = encoded.get(image);
                if (asset == null)
                {
                    Path temporary = Files.createTempFile(cache, "handout-", ".png");
                    try
                    {
                        int width = (int)image.getWidth(), height = (int)image.getHeight();
                        var bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        var reader = image.getPixelReader();
                        if (reader == null)
                            throw new IOException("Handout pixels are not readable.");
                        int[] row = new int[width];
                        for (int y = 0; y < height; y++)
                        {
                            reader.getPixels(0, y, width, 1, PixelFormat.getIntArgbInstance(), row, 0, width);
                            bitmap.setRGB(0, y, width, 1, row, 0, width);
                        }
                        if (!ImageIO.write(bitmap, "png", temporary.toFile()))
                            throw new IOException("PNG encoder unavailable.");
                        asset = store.publishRequiredAsset(temporary);
                    }
                    finally
                    {
                        Files.deleteIfExists(temporary);
                    }
                }
                assets.add(asset);
                nextEncoded.put(image, asset);
                nextDecoded.put(asset.sha256(), image);
            }
            var board = new Board(assets, snapshot.rotation(), snapshot.mirrored(), snapshot.shown(), snapshot.showOnly());
            encoded.clear();
            encoded.putAll(nextEncoded);
            decoded.clear();
            decoded.putAll(nextDecoded);
            LOG.debug("Published handout board with {} image(s)", assets.size());
            return new Prepared(board, snapshot);
        }

        public synchronized Prepared load(Board board) throws IOException
        {
            var images = new ArrayList<Image>();
            var nextEncoded = new IdentityHashMap<Image, RemoteAssetStore.AssetDescriptor>();
            var nextDecoded = new LinkedHashMap<String, Image>();
            long total = 0;
            for (var asset : board.images())
            {
                if (!store.descriptor(asset.sha256()).equals(asset))
                    throw new IOException("Handout asset size changed.");
                Image image = nextDecoded.getOrDefault(asset.sha256(), decoded.get(asset.sha256()));
                if (image == null)
                {
                    Path file = store.materialize(asset.sha256(), cache, "png");
                    try (var input = ImageIO.createImageInputStream(file.toFile()))
                    {
                        var readers = ImageIO.getImageReaders(input);
                        if (!readers.hasNext())
                            throw new IOException("Handout is not a supported PNG.");
                        var reader = readers.next();
                        try
                        {
                            reader.setInput(input);
                            if (!"png".equalsIgnoreCase(reader.getFormatName()))
                                throw new IOException("Handout must be PNG.");
                            long pixels = checkPixels(reader.getWidth(0), reader.getHeight(0));
                            if (total + pixels > MAX_BOARD_PIXELS)
                            {
                                throw new IOException("Handout exceeds the decoded board pixel limit.");
                            }

                        }
                        finally
                        {
                            reader.dispose();
                        }
                    }
                    try (var input = Files.newInputStream(file))
                    {
                        image = new Image(input);
                    }
                    if (image.isError())
                        throw new IOException("Handout image could not be decoded.");
                }
                total += checkPixels(image.getWidth(), image.getHeight());
                if (total > MAX_BOARD_PIXELS)
                    throw new IOException("Handout exceeds the decoded board pixel limit.");
                images.add(image);
                nextDecoded.put(asset.sha256(), image);
                nextEncoded.put(image, asset);
            }
            encoded.clear();
            encoded.putAll(nextEncoded);
            decoded.clear();
            decoded.putAll(nextDecoded);
            LOG.debug("Loaded handout board with {} image(s)", images.size());
            return new Prepared(board, new HandoutWindow.Snapshot(images, board.rotation(),
                                                                  board.mirrored(), board.shown(), board.showOnly()));
        }

        public Image loadThumbnail(RemoteAssetStore.AssetDescriptor asset) throws IOException
        {
            if (!store.descriptor(asset.sha256()).equals(asset))
                throw new IOException("Thumbnail size changed.");
            Path file = store.materialize(asset.sha256(), cache, "png");
            try (var input = ImageIO.createImageInputStream(file.toFile()))
            {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext())
                    throw new IOException("Invalid recent thumbnail.");
                var reader = readers.next();
                try
                {
                    reader.setInput(input);
                    if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) < 1
                            || reader.getHeight(0) < 1 || reader.getWidth(0) > THUMBNAIL_WIDTH
                            || reader.getHeight(0) > THUMBNAIL_HEIGHT)
                        throw new IOException("Recent thumbnail exceeds its pixel limit.");
                }
                finally
                {
                    reader.dispose();
                }
            }
            try (var input = Files.newInputStream(file))
            {
                var image = new Image(input);
                if (image.isError())
                    throw new IOException("Recent thumbnail could not be decoded.");
                return image;
            }
        }

        public HandoutWindow.Snapshot appendFiles(HandoutWindow.Snapshot base, List<Path> files) throws IOException
        {
            if (files.isEmpty() || files.size() > MAX_IMAGES - base.images().size())
            {
                throw new IOException("Too many handout images.");
            }
            var images = new ArrayList<>(base.images());
            long total = 0;
            for (var image : images)
                total += checkPixels(image.getWidth(), image.getHeight());
            for (var file : files)
            {
                if (Files.size(file) > store.maxAssetBytes())
                    throw new IOException("Handout source file is too large.");
                try (var input = ImageIO.createImageInputStream(file.toFile()))
                {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext())
                        throw new IOException("Handout source format is not supported.");
                    var reader = readers.next();
                    try
                    {
                        reader.setInput(input);
                        total += checkPixels(reader.getWidth(0), reader.getHeight(0));
                        if (total > MAX_BOARD_PIXELS)
                            throw new IOException("Handout exceeds the decoded board pixel limit.");
                        // Read only the first frame; animated source files must not allocate unbounded frame lists.
                        var bitmap = reader.read(0);
                        var image = new WritableImage(bitmap.getWidth(), bitmap.getHeight());
                        int[] row = new int[bitmap.getWidth()];
                        for (int y = 0; y < bitmap.getHeight(); y++)
                        {
                            bitmap.getRGB(0, y, row.length, 1, row, 0, row.length);
                            image.getPixelWriter().setPixels(0, y, row.length, 1,
                                                             PixelFormat.getIntArgbInstance(), row, 0, row.length);
                        }
                        images.add(image);
                    }
                    finally
                    {
                        reader.dispose();
                    }
                }
            }
            return new HandoutWindow.Snapshot(images, base.rotation(), base.mirrored(), base.shown(), base.showOnly());
        }

    }

    private static void validatePixels(List<Image> images) throws IOException
    {
        if (images.size() > MAX_IMAGES)
            throw new IOException("Too many handout images.");
        long total = 0;
        for (var image : images)
            total += checkPixels(image.getWidth(), image.getHeight());
        if (total > MAX_BOARD_PIXELS)
            throw new IOException("Handout exceeds the decoded board pixel limit.");
    }

    private static long checkPixels(double width, double height) throws IOException
    {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1
                || width > 16384 || height > 16384 || width * height > MAX_IMAGE_PIXELS)
        {
            LOG.warn("Handout image rejected: {}x{} exceeds the pixel limit", width, height);
            throw new IOException("Handout exceeds the decoded image pixel limit.");
        }
        return (long)(width * height);
    }

    private RemoteHandouts()
    {
    }
}
