package dmmt.service;

import dmmt.model.MultiLevelManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Two detached neighbouring-floor preparations; never loads a level through the state-mutating loadLevel API.
 */
public final class AdjacentLevelPrefetch<T> implements AutoCloseable
{
    @FunctionalInterface
    public interface Loader<T>
    {
        T load(Path file, BooleanSupplier cancelled) throws IOException;
    }

    private static final class Request<T>
    {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile T result;
        Future<T> future;
    }

    private final Loader<T> loader;
    private final Function<Callable<T>, Future<T>> submit;
    private final BiConsumer<Path, Exception> onFailure;
    private final Map<Path, Request<T>> requests = new LinkedHashMap<>();
    private boolean closed;

    public AdjacentLevelPrefetch(Loader<T> loader, BiConsumer<Path, Exception> onFailure)
    {
        this(loader, work -> WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, false, work), onFailure);
    }

    AdjacentLevelPrefetch(Loader<T> loader, Function<Callable<T>, Future<T>> submit,
                          BiConsumer<Path, Exception> onFailure)
    {
        this.loader = loader;
        this.submit = submit;
        this.onFailure = onFailure;
    }

    public static List<Path> adjacentFiles(Path manifestFile, MultiLevelManifest manifest, String currentId)
    {
        int index = manifest.indexOf(currentId);
        if (index < 0)
        {
            return List.of();
        }
        List<Path> neighbours = new ArrayList<>(2);
        for (int adjacent : new int[] { index - 1, index + 1 })
        {
            if (adjacent >= 0 && adjacent < manifest.getLevels().size())
            {
                neighbours.add(MultiLevelService.levelFile(manifestFile, manifest.getLevels().get(adjacent)));
            }
        }
        return neighbours;
    }

    public synchronized void update(List<Path> neighbours)
    {
        clear();
        if (closed)
        {
            return;
        }
        neighbours.stream().map(path -> path.toAbsolutePath().normalize()).distinct().limit(2).forEach(file -> {
            Request<T> request = new Request<>();
            requests.put(file, request);
            try {
                request.future = submit.apply(() -> {
                try
                {
                    T result = loader.load(file, request.cancelled::get);
                    synchronized (this)
                    {
                        if (!request.cancelled.get() && requests.get(file) == request)
                        {
                            request.result = result;
                        }
                    }
                    return result;
                }
                catch (IOException | RuntimeException ex)
                {
                    synchronized (this)
                    {
                        if (!request.cancelled.get() && requests.get(file) == request && onFailure != null)
                        {
                            onFailure.accept(file, ex);
                        }
                    }
                    throw ex;
                }
                });
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                requests.remove(file);
                request.cancelled.set(true);
                if (onFailure != null) {
                    onFailure.accept(file, ex);
                }
            }
        });
    }

    /**
     * Nonblocking handoff: incomplete work is cancelled, then visible preparation runs in the foreground lane.
     */
    public synchronized T take(Path file)
    {
        Request<T> request = requests.remove(file.toAbsolutePath().normalize());
        if (request == null)
        {
            return null;
        }
        T result = request.result;
        request.cancelled.set(true);
        if (request.future != null)
        {
            request.future.cancel(true);
        }
        return result;
    }

    public synchronized void clear()
    {
        for (Request<T> request : requests.values())
        {
            request.cancelled.set(true);
            if (request.future != null)
            {
                request.future.cancel(true);
            }
        }
        requests.clear();
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        clear();
    }
}
