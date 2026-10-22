package dmmt.audio;

import dmmt.service.WorkScheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/** Library-owned staged queue; resource workers never wait for work in another resource lane. */
public final class AudioPreparationQueue implements AutoCloseable {
    public enum Stage { QUEUED, COPYING, ANALYZING, PLAYBACK, READY, FAILED, CANCELLED }

    public static final class Item {
        private final Path source;
        private final AudioKind kind;
        private final String categoryId;
        private final String folderCategory;
        private volatile String trackId;
        private volatile Stage stage = Stage.QUEUED;
        private volatile double progress;
        private volatile String error;
        private volatile boolean cancelled;
        private volatile boolean explicitlyCancelled;
        private volatile boolean executing;
        private Future<?> future;
        private String copyFailureId = java.util.UUID.randomUUID().toString();

        private Item(Path source, AudioKind kind, String categoryId, String folderCategory, String trackId) {
            this.source = source.toAbsolutePath().normalize();
            this.kind = kind == null ? AudioKind.MUSIC : kind;
            this.categoryId = categoryId;
            this.folderCategory = folderCategory;
            this.trackId = trackId;
        }

        public Path source() { return source; }
        public String trackId() { return trackId; }
        public Stage stage() { return stage; }
        public double progress() { return progress; }
        public String error() { return error; }
    }

    private final AudioLibraryService library;
    private final List<Item> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private int running;
    private AudioRemoteBackend remote;

    public record RemoteItem(String name, String trackId, Stage stage, double progress, String error) {
        public RemoteItem {
            if (name == null || name.length() > 240 || name.contains("/") || name.contains("\\")
                    || !Double.isFinite(progress) || progress < 0 || progress > 1 || stage == null) {
                throw new IllegalArgumentException("Invalid remote preparation item.");
            }
        }
    }

    AudioPreparationQueue(AudioLibraryService library, AudioRemoteBackend remote) {
        this.library = library;
        this.remote = remote;
    }

    public synchronized void acceptRemote(List<RemoteItem> snapshot) {
        if (remote == null) return;
        items.clear();
        for (RemoteItem value : snapshot) {
            Item item = new Item(Path.of(value.name().isBlank() ? "Audio" : value.name()),
                    AudioKind.MUSIC, null, null, value.trackId());
            item.stage = value.stage();
            item.progress = value.progress();
            item.error = value.error();
            items.add(item);
        }
        changed();
    }

    AudioPreparationQueue(AudioLibraryService library) {
        this.library = library;
        for (AudioLibrary.CopyFailure failure : library.copyFailures()) {
            try {
                Item item = new Item(Path.of(failure.source()), failure.kind(), failure.categoryId(),
                        failure.folderCategory(), null);
                item.copyFailureId = failure.id();
                item.stage = failure.cancelled() ? Stage.CANCELLED : Stage.FAILED;
                item.error = failure.error();
                items.add(item);
            } catch (RuntimeException e) {
                System.err.println("Could not recover an audio copy failure: " + e.getMessage());
            }
        }
        for (AudioTrack track : library.tracks()) {
            if (!track.isReady()) {
                Item item = new Item(library.fileOf(track), track.getKind(), track.getCategoryId(), null, track.getId());
                items.add(item);
                if (track.getPreparationState() == AudioTrack.PreparationState.PENDING) {
                    schedulePreparation(item, false);
                } else {
                    item.stage = track.getPreparationState() == AudioTrack.PreparationState.CANCELLED
                            ? Stage.CANCELLED : Stage.FAILED;
                    item.error = track.getPreparationError();
                }
            }
        }
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    public synchronized List<Item> items() { return List.copyOf(items); }

    public synchronized Item enqueue(Path source, AudioKind kind, String categoryId, String folderCategory) {
        if (remote != null) {
            if (closed) throw new IllegalStateException("The audio preparation queue is stopped.");
            remote.upload(source, kind, categoryId, folderCategory);
            return new Item(source, kind, categoryId, folderCategory, null);
        }
        if (closed) {
            throw new IllegalStateException("The audio preparation queue is stopped.");
        }
        Item item = new Item(source, kind, categoryId, folderCategory, null);
        items.add(item);
        try {
            item.future = WorkScheduler.shared().submit(WorkScheduler.Kind.COPY, false, () -> {
                try {
                    synchronized (this) {
                        if (item.cancelled || closed) {
                            return null;
                        }
                        item.executing = true;
                        running++;
                        stage(item, Stage.COPYING);
                    }
                    String category = item.folderCategory == null || item.kind != AudioKind.MUSIC ? item.categoryId
                            : library.categoryForName(item.folderCategory).getId();
                    AudioTrack copied = library.copyPending(item.source, item.kind, category,
                            value -> progress(item, value));
                    synchronized (this) {
                        item.trackId = copied.getId();
                        library.forgetCopyFailure(item.copyFailureId);
                        if (item.explicitlyCancelled) {
                            cancel(item);
                        } else if (closed) {
                            item.stage = Stage.CANCELLED;
                        } else if (item.cancelled) {
                            cancel(item);
                        } else {
                            schedulePreparation(item, false);
                        }
                    }
                } catch (Exception e) {
                    failed(item, e);
                } finally {
                    finished(item);
                }
                return null;
            });
        } catch (RuntimeException e) {
            failed(item, e);
        }
        changed();
        return item;
    }

    private synchronized void schedulePreparation(Item item, boolean foreground) {
        stage(item, Stage.QUEUED);
        try {
            item.future = WorkScheduler.shared().submit(WorkScheduler.Kind.AUDIO, foreground, () -> {
                try {
                    synchronized (this) {
                        if (item.cancelled || closed) {
                            return null;
                        }
                        item.executing = true;
                        running++;
                        stage(item, Stage.ANALYZING);
                    }
                    library.preparePending(item.trackId, value -> progress(item, value),
                            () -> stage(item, Stage.PLAYBACK));
                    stage(item, Stage.READY);
                } catch (Exception e) {
                    failed(item, e);
                } finally {
                    finished(item);
                }
                return null;
            });
        } catch (RuntimeException e) {
            failed(item, e);
        }
    }

    private synchronized void finished(Item item) {
        if (item.executing) {
            item.executing = false;
            running--;
            notifyAll();
        }
    }

    /**
     * Waits until no copy or preparation job is still running, so their source files are released. Jobs that had
     * not started when the queue was closed return without opening any file.
     */
    public synchronized boolean awaitStopped(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (running > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    public synchronized void prioritize(String trackId) {
        if (remote != null) { remote.command("prioritize", trackId); return; }
        items.stream().filter(item -> trackId.equals(item.trackId) && item.stage == Stage.QUEUED)
                .findFirst().ifPresent(item -> WorkScheduler.shared().prioritize(item.future));
        changed();
    }

    public synchronized void retry(String trackId) throws IOException {
        if (remote != null) { remote.command("retry", trackId); return; }
        if (closed) {
            return;
        }
        AudioTrack track = library.track(trackId).orElseThrow();
        if (track.isReady() || items.stream().anyMatch(item -> trackId.equals(item.trackId)
                && !terminal(item.stage))) {
            return;
        }
        library.preparationState(trackId, AudioTrack.PreparationState.PENDING, null);
        Item item = new Item(library.fileOf(track), track.getKind(), track.getCategoryId(), null, trackId);
        items.removeIf(previous -> trackId.equals(previous.trackId));
        items.add(item);
        schedulePreparation(item, true);
        changed();
    }

    public synchronized void retryFailed() throws IOException {
        if (remote != null) { remote.command("retryFailed"); return; }
        for (Item item : List.copyOf(items)) {
            if (item.stage != Stage.FAILED && item.stage != Stage.CANCELLED) {
                continue;
            }
            if (item.trackId != null) {
                retry(item.trackId);
            } else {
                // Future.cancel completes immediately, but the copy worker may still be publishing its source.
                if (item.executing) {
                    continue;
                }
                library.forgetCopyFailure(item.copyFailureId);
                items.remove(item);
                enqueue(item.source, item.kind, item.categoryId, item.folderCategory);
            }
        }
    }

    public synchronized void cancelRemaining() {
        if (remote != null) { remote.command("cancelPreparation"); return; }
        for (Item item : items) {
            if (!terminal(item.stage)) {
                cancel(item);
            }
        }
        changed();
    }

    private static boolean terminal(Stage stage) {
        return stage == Stage.READY || stage == Stage.FAILED || stage == Stage.CANCELLED;
    }

    private void cancel(Item item) {
        item.cancelled = true;
        item.explicitlyCancelled = true;
        if (item.future != null) {
            item.future.cancel(true);
        }
        item.stage = Stage.CANCELLED;
        item.error = "Cancelled; retry to prepare this track.";
        persistFailure(item, AudioTrack.PreparationState.CANCELLED, item.error);
    }

    private synchronized void failed(Item item, Exception failure) {
        if (closed) {
            item.stage = Stage.CANCELLED;
        } else if (item.cancelled) {
            // Explicit cancellation was already persisted; do not overwrite a newer retry's state.
            item.stage = Stage.CANCELLED;
        } else {
            item.error = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            persistFailure(item, AudioTrack.PreparationState.FAILED, item.error);
            item.stage = Stage.FAILED;
        }
        changed();
    }

    private void persistFailure(Item item, AudioTrack.PreparationState state, String error) {
        try {
            if (item.trackId == null) {
                library.copyFailure(new AudioLibrary.CopyFailure(item.copyFailureId, item.source.toString(),
                        item.kind, item.categoryId, item.folderCategory,
                        state == AudioTrack.PreparationState.CANCELLED, error));
            } else if (library.track(item.trackId).filter(track -> !track.isReady()).isPresent()) {
                library.preparationState(item.trackId, state, error);
            }
        } catch (IOException e) {
            item.error = error + " (could not save state: " + e.getMessage() + ")";
            System.err.println(item.error);
        }
    }

    private synchronized void stage(Item item, Stage stage) {
        if ((item.cancelled || closed) && stage != Stage.READY) {
            return;
        }
        item.stage = stage;
        item.progress = stage == Stage.READY ? 1 : 0;
        changed();
    }

    private void progress(Item item, double value) {
        if (item.cancelled || closed) {
            throw new java.util.concurrent.CancellationException("Audio preparation was cancelled.");
        }
        item.progress = value;
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                System.err.println("Audio preparation listener failed: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Item item : items) {
            if (!terminal(item.stage)) {
                item.cancelled = true;
                item.stage = Stage.CANCELLED;
                if (item.future != null) {
                    item.future.cancel(true);
                }
            }
        }
        // Pending entries deliberately remain persisted as pending for automatic recovery on next launch.
        listeners.clear();
    }
}
