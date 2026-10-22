package dmmt.remote;

import dmmt.ui.HandoutWindow;
import dmmt.ui.RecentHandouts;
import javafx.scene.image.Image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Confined to the same serial worker as the standalone recent-handout UI.
 */
public final class RemoteHandoutLibrary
{
    public static final class Conflict extends IOException
    {
        public Conflict()
        {
            super("Host recent handouts changed; synchronize before retrying.");
        }
    }

    private final Supplier<RecentHandouts> open;
    private final RemoteAssetStore store;
    private final Path cache;
    private final RemoteHandouts.Artwork artwork;
    private final Map<String, RemoteHandouts.Recent> published = new HashMap<>();
    private volatile RecentHandouts recents;

    public RemoteHandoutLibrary(Supplier<RecentHandouts> open, RemoteAssetStore store, Path cache)
    {
        this.open = Objects.requireNonNull(open);
        this.store = Objects.requireNonNull(store);
        this.cache = Objects.requireNonNull(cache);
        artwork = new RemoteHandouts.Artwork(store, cache);
    }

    public long version()
    {
        return recents == null ? -1 : recents.version();
    }

    public RecentHandouts store()
    {
        return recents;
    }

    public RemoteHandouts.Library publish(int max) throws IOException
    {
        if (recents == null)
            recents = Objects.requireNonNull(open.get());
        if (recents.ids().size() > max)
            recents.trim(max);
        var entries = new ArrayList<RemoteHandouts.Recent>();
        var ids = recents.ids();
        for (var id : ids)
        {
            var entry = published.get(id);
            if (entry == null)
            {
                var original = store.publishRequiredAsset(recents.file(id));
                // Validate full-size decoded bounds before generating a small preview.
                artwork.load(new RemoteHandouts.Board(List.of(original), 0, false, false, null));
                Image thumbnail;
                try (var input = Files.newInputStream(recents.file(id)))
                {
                    thumbnail = new Image(input, RemoteHandouts.THUMBNAIL_WIDTH, RemoteHandouts.THUMBNAIL_HEIGHT, true, true);
                }
                var descriptor = artwork.publish(new HandoutWindow.Snapshot(
                        List.of(thumbnail), 0, false, false, null)).board().images().getFirst();
                entry = new RemoteHandouts.Recent(id, original, descriptor);
                published.put(id, entry);
            }
            entries.add(entry);
        }
        published.keySet().retainAll(ids);
        return new RemoteHandouts.Library(recents.version(), max, entries);
    }

    public void persist(RemoteHandouts.Request request, RemoteHandouts.Library expected,
                        HandoutWindow.Snapshot before, HandoutWindow.Snapshot after, int max) throws IOException
    {
        if (recents == null || recents.version() != expected.version() || max != expected.max())
            throw new Conflict();
        if (request.operation() == RemoteHandouts.Operation.REMOVE_RECENT)
        {
            if (!recents.ids().contains(request.recentId()))
                throw new Conflict();
            recents.remove(request.recentId());
        }
        else if (request.operation() == RemoteHandouts.Operation.ADD_RECENT)
        {
            if (!recents.ids().contains(request.recentId()))
                throw new Conflict();
            recents.moveToTop(request.recentId());
        }
        else
        {
            // Only appended images are new pastes; rotation/show/delete must not reorder the recent list.
            var remaining = new ArrayList<>(before.images());
            for (var image : after.images())
            {
                if (!remaining.remove(image))
                    recents.add(image, max);
            }
        }
    }
}
