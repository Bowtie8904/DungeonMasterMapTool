package dmmt.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.*;

class AudioPreparationQueueTest {
    @TempDir Path dir;

    @Test
    void pendingIsPersistedEditableAndExcludedUntilAtomicPublication() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        Path source = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.MUSIC, null, null);
            assertTrue(library.entered.await(5, TimeUnit.SECONDS));
            AudioTrack pending = library.track(item.trackId()).orElseThrow();
            assertFalse(pending.isReady());
            assertTrue(Files.exists(library.fileOf(pending)));
            assertEquals(AudioTrack.PreparationState.PENDING,
                    new AudioLibraryService(library.root()).track(pending.getId()).orElseThrow().getPreparationState());
            assertTrue(library.musicOf(pending.getCategoryId()).isEmpty());
            assertThrows(IllegalStateException.class, () -> library.playbackSourceOf(pending));
            AudioEngine engine = new AudioEngine(library, new FakeAudioOutput());
            assertThrows(IllegalArgumentException.class, () -> engine.playTrack(pending.getId()));
            AudioCategory category = library.createCategory("Edited category", null, null);
            library.renameTrack(pending.getId(), "Edited name");
            library.changeKind(pending.getId(), AudioKind.MUSIC, category.getId());
            library.styleTrack(pending.getId(), "#123456", "mdi2f-fire");
            engine.toggleEffect(pending.getId());
            assertTrue(engine.activeEffects().isEmpty());
            library.release.countDown();
            await(() -> item.stage() == AudioPreparationQueue.Stage.READY);
            AudioTrack ready = new AudioLibraryService(library.root()).track(pending.getId()).orElseThrow();
            assertTrue(ready.isReady());
            assertEquals("Edited name", ready.getName());
            assertEquals(AudioKind.MUSIC, ready.getKind());
            assertEquals(category.getId(), ready.getCategoryId());
            assertEquals("#123456", ready.getColor());
            assertEquals("mdi2f-fire", ready.getIcon());
            assertNotNull(ready.getLoudnessLufs());
            assertTrue(Files.exists(library.playbackFileOf(ready)));
        } finally {
            library.release.countDown();
            queue.close();
        }
    }

    @Test
    void failuresPersistAndRetryUsesManagedSourceAfterOriginalDisappears() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        library.fail = true;
        library.release.countDown();
        Path source = dir.resolve("retry.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.EFFECT, null, null);
            await(() -> item.stage() == AudioPreparationQueue.Stage.FAILED);
            AudioTrack failed = new AudioLibraryService(library.root()).track(item.trackId()).orElseThrow();
            assertEquals(AudioTrack.PreparationState.FAILED, failed.getPreparationState());
            assertTrue(failed.getPreparationError().contains("Deliberate failure"));
            assertTrue(library.visibleEffects().isEmpty());
            Files.delete(source);
            library.fail = false;
            queue.retry(item.trackId());
            await(() -> library.track(item.trackId()).orElseThrow().isReady());
            assertEquals(1, library.tracks().size());
        } finally {
            queue.close();
        }
    }

    @Test
    void cancelPersistsAndRetryIsNotOverwrittenByCancelledWorker() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        Path source = dir.resolve("cancel.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.MUSIC, null, null);
            assertTrue(library.entered.await(5, TimeUnit.SECONDS));
            queue.cancelRemaining();
            AudioTrack cancelled = new AudioLibraryService(library.root()).track(item.trackId()).orElseThrow();
            assertEquals(AudioTrack.PreparationState.CANCELLED, cancelled.getPreparationState());
            assertFalse(cancelled.isReady());
            library.release.countDown();
            queue.retry(item.trackId());
            await(() -> library.track(item.trackId()).orElseThrow().isReady());
            assertNull(library.track(item.trackId()).orElseThrow().getPreparationError());
        } finally {
            library.release.countDown();
            queue.close();
        }
    }

    @Test
    void shutdownPreservesPendingForStartupRecovery() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        Path source = dir.resolve("recover.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.EFFECT, null, null);
        assertTrue(library.entered.await(5, TimeUnit.SECONDS));
        queue.close();
        library.release.countDown();
        AudioLibraryService restarted = new AudioLibraryService(library.root());
        assertEquals(AudioTrack.PreparationState.PENDING, restarted.track(item.trackId()).orElseThrow().getPreparationState());
        AudioPreparationQueue recovered = restarted.preparationQueue();
        try {
            await(() -> restarted.track(item.trackId()).orElseThrow().isReady());
            assertEquals(1, restarted.tracks().size());
        } finally {
            recovered.close();
        }
    }

    @Test
    void prioritizingPendingTrackMovesItAheadOfBackgroundPreparations() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        Path source = dir.resolve("priority.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item first = queue.enqueue(source, AudioKind.MUSIC, null, null);
            assertTrue(library.entered.await(5, TimeUnit.SECONDS));
            AudioPreparationQueue.Item second = queue.enqueue(source, AudioKind.MUSIC, null, null);
            await(() -> second.trackId() != null && second.stage() == AudioPreparationQueue.Stage.QUEUED);
            AudioPreparationQueue.Item third = queue.enqueue(source, AudioKind.MUSIC, null, null);
            await(() -> third.trackId() != null && third.stage() == AudioPreparationQueue.Stage.QUEUED);
            queue.prioritize(third.trackId());
            library.release.countDown();
            await(() -> first.stage() == AudioPreparationQueue.Stage.READY
                    && second.stage() == AudioPreparationQueue.Stage.READY
                    && third.stage() == AudioPreparationQueue.Stage.READY);
            assertEquals(java.util.List.of(first.trackId(), third.trackId(), second.trackId()), library.started);
        } finally {
            library.release.countDown();
            queue.close();
        }
    }

    @Test
    void copyFailureSurvivesRestartAndCanBeRetriedWithoutInventingPlayableTrack() throws Exception {
        Path source = dir.resolve("missing.wav");
        AudioLibraryService library = new AudioLibraryService(dir.resolve("library"));
        AudioPreparationQueue original = library.preparationQueue();
        AudioPreparationQueue.Item failed = original.enqueue(source, AudioKind.MUSIC, null, null);
        await(() -> failed.stage() == AudioPreparationQueue.Stage.FAILED);
        assertTrue(library.tracks().isEmpty());
        original.close();

        AudioLibraryService restarted = new AudioLibraryService(library.root());
        AudioPreparationQueue recovered = restarted.preparationQueue();
        try {
            assertEquals(1, recovered.items().size());
            assertEquals(AudioPreparationQueue.Stage.FAILED, recovered.items().getFirst().stage());
            assertNotNull(recovered.items().getFirst().error());
            TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
            recovered.retryFailed();
            await(() -> restarted.tracks().size() == 1 && restarted.tracks().getFirst().isReady());
            assertTrue(new AudioLibraryService(library.root()).copyFailures().isEmpty());
        } finally {
            recovered.close();
        }
    }

    @Test
    void failedAtomicPublicationDoesNotExposePartialAnalysisOrLeavePlaybackCopies() throws Exception {
        BlockingLibrary library = new BlockingLibrary(dir.resolve("library"));
        library.failPublication = true;
        library.release.countDown();
        Path source = dir.resolve("publish.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.MUSIC, null, null);
            await(() -> item.stage() == AudioPreparationQueue.Stage.FAILED);
            AudioTrack failed = library.track(item.trackId()).orElseThrow();
            assertFalse(failed.isReady());
            assertNull(failed.getLoudnessLufs());
            assertNull(failed.getPlaybackFile());
            assertEquals(0, failed.getDurationMs());
            assertTrue(failed.getPreparationError().contains("Publication failed"));
            try (var files = Files.walk(library.filesFolder())) {
                assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".playback.wav")));
            }
        } finally {
            queue.close();
        }
    }

    @Test
    void immediateRetryOfCancelledCopyCannotDuplicateItsSafelyPublishedSource() throws Exception {
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch releaseCopy = new CountDownLatch(1);
        CountDownLatch copyExited = new CountDownLatch(1);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("library")) {
            @Override
            AudioTrack copyPending(Path source, AudioKind kind, String categoryId, DoubleConsumer progress)
                    throws IOException {
                AudioTrack track = super.copyPending(source, kind, categoryId, progress);
                copied.countDown();
                try {
                    // Model a copy that has published safely but has not returned when cancellation arrives.
                    while (releaseCopy.getCount() != 0) {
                        try {
                            releaseCopy.await();
                        } catch (InterruptedException ignored) {
                            // A completed source publication is not rolled back by this artificial boundary.
                        }
                    }
                    return track;
                } finally {
                    copyExited.countDown();
                }
            }
        };
        Path source = dir.resolve("copy-cancel.wav");
        TestAudioFiles.writeWav(source, 1000, 8000, 0.05);
        AudioPreparationQueue queue = library.preparationQueue();
        try {
            AudioPreparationQueue.Item item = queue.enqueue(source, AudioKind.EFFECT, null, null);
            assertTrue(copied.await(5, TimeUnit.SECONDS));
            queue.cancelRemaining();
            queue.retryFailed();
            assertEquals(1, queue.items().size(), "the old copy must finish publishing before a source retry");
            releaseCopy.countDown();
            assertTrue(copyExited.await(5, TimeUnit.SECONDS));
            await(() -> item.trackId() != null && library.track(item.trackId()).orElseThrow()
                    .getPreparationState() == AudioTrack.PreparationState.CANCELLED);
            Files.delete(source);
            queue.retryFailed();
            await(() -> library.tracks().getFirst().isReady());
            assertEquals(1, library.tracks().size());
            assertTrue(library.copyFailures().isEmpty());
        } finally {
            releaseCopy.countDown();
            queue.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Queue did not reach the expected state.");
    }

    private static final class BlockingLibrary extends AudioLibraryService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean fail;
        volatile boolean failPublication;
        final java.util.List<String> started = new java.util.concurrent.CopyOnWriteArrayList<>();

        BlockingLibrary(Path root) { super(root); }

        @Override
        public synchronized void save() throws IOException {
            if (failPublication) {
                for (AudioTrack track : tracks()) {
                    if (track.getPreparationState() == AudioTrack.PreparationState.READY) {
                        assertFalse(track.isReady(), "readiness must be published only after the index commit");
                        throw new IOException("Publication failed");
                    }
                }
            }
            super.save();
        }

        @Override
        void preparePending(String id, DoubleConsumer progress, Runnable playbackStage) throws IOException {
            started.add(id);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test preparation timed out.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Interrupted test preparation.");
            }
            if (fail) {
                throw new IOException("Deliberate failure");
            }
            super.preparePending(id, progress, playbackStage);
        }
    }
}
