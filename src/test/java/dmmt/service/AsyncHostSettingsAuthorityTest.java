package dmmt.service;

import dmmt.render.OverlayTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static dmmt.service.HostSettingsAuthority.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncHostSettingsAuthorityTest {
    @TempDir
    Path dir;
    private final Tasks owner = new Tasks();
    private final Tasks worker = new Tasks();

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void workersNeverAcquireTheSettingsMonitorWhileDoingFileIo() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
            synchronized (settings) {
                worker.runNextOnWorker();
            }
            owner.runNext();
            synchronized (settings) {
                worker.runNextOnWorker();
                assertFalse(future.isDone());
                assertEquals(1, authority.stateInMemory().nextSequence());
            }
            owner.runNext();
            assertEquals(ACCEPTED, future.join().status());
        }
    }

    @Test
    void diskCompletionPrecedesOwnerCommitAndAcknowledgement() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority, "ui.gridOpacity", "0.65");
            byte[] before = Files.readAllBytes(settings.getFile());
            var future = authority.executeAsync(request, owner, worker);
            assertFalse(future.isDone());
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
            assertEquals(request.edit().expectedValue(),
                    authority.stateInMemory().settings().values().get("ui.gridOpacity"));
            worker.runNextOnWorker();
            owner.runNext(); // External file check, then enqueue persistence.
            worker.runNextOnWorker();
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.65"));
            assertFalse(future.isDone(), "persisted does not mean runtime-applied yet");
            assertEquals(request.edit().expectedValue(),
                    authority.stateInMemory().settings().values().get("ui.gridOpacity"));
            owner.runNext();
            var result = future.join();
            assertEquals(ACCEPTED, result.status());
            assertEquals("0.65", result.state().settings().values().get("ui.gridOpacity"));
            assertEquals(0.65, Tuning.GRID_OPACITY.get());
            assertEquals(2, result.state().nextSequence());
            assertTrue(settings.pollExternalChange());
            assertFalse(settings.pollExternalChange());
        }
    }

    @Test
    void unrelatedLocalChangesDuringAndAfterIoAreSavedBeforeAcknowledgement() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
            worker.runNextOnWorker();
            owner.runNext();
            settings.putBoolean("player.showGrid", true);
            settings.put("import.lastDirectory", "A:\\new-maps");
            worker.runNextOnWorker(); // Writes the older, detached candidate.
            owner.runNext(); // Detects local updates and schedules a repair.
            assertFalse(future.isDone());
            settings.put("audio.lastDirectory", "A:\\new-audio");
            drain();
            assertEquals(ACCEPTED, future.join().status());
            assertEquals("true", future.join().state().settings().values().get("player.showGrid"));
            var persisted = new AppSettings(settings.getFile());
            assertEquals("0.6", persisted.get("ui.gridOpacity", null));
            assertEquals("true", persisted.get("player.showGrid", null));
            assertEquals("A:\\new-maps", persisted.get("import.lastDirectory", null));
            assertEquals("A:\\new-audio", persisted.get("audio.lastDirectory", null));
        }
    }

    @Test
    void sameKeyLocalEditAfterDiskWriteWinsAndIsNotResurrectedByPolling() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
            worker.runNextOnWorker();
            owner.runNext();
            worker.runNextOnWorker();
            settings.applyEdit("ui.gridOpacity", "0.8");
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
            assertEquals("0.8", authority.stateInMemory().settings().values().get("ui.gridOpacity"));
            settings.pollExternalChange(); // Must not reload the worker's uncommitted candidate.
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            drain();
            assertEquals(CONFLICT, future.join().status());
            assertEquals("0.8", future.join().state().settings().values().get("ui.gridOpacity"));
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.8"));
            settings.pollExternalChange();
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void changingAwayAndBackWhilePendingStillConflicts() throws Exception {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
            settings.applyEdit("ui.gridOpacity", "0.8");
            settings.applyEdit("ui.gridOpacity", "0.2");
            drain();
            assertEquals(CONFLICT, future.join().status());
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.2"));
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void localRemovalDuringIoIsNotResurrected() throws Exception {
        AppSettings settings = settings();
        settings.put("audio.lastCategory", "original");
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "audio.lastCategory", "remote"), owner, worker);
            worker.runNextOnWorker();
            owner.runNext();
            worker.runNextOnWorker();
            settings.put("audio.lastCategory", null);
            drain();
            assertEquals(CONFLICT, future.join().status());
            assertNull(settings.get("audio.lastCategory", null));
            assertNull(new AppSettings(settings.getFile()).get("audio.lastCategory", null));
        }
    }

    @Test
    void failedRepairDoesNotReloadAStaleSuccessfulWriteOverNewerLocalMemory() throws Exception {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        try (var authority = new HostSettingsAuthority(settings)) {
            var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
            worker.runNextOnWorker();
            owner.runNext();
            worker.runNextOnWorker();
            settings.applyEdit("ui.gridOpacity", "0.8");
            owner.runNext();
            Path blocked = settings.getFile().resolveSibling(settings.getFile().getFileName() + ".tmp");
            Files.createDirectory(blocked);
            drain();
            assertEquals(SAVE_FAILED, future.join().status());
            settings.pollExternalChange();
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
            assertEquals("0.8", authority.state().settings().values().get("ui.gridOpacity"));
            Files.delete(blocked);
            settings.putBoolean("player.showGrid", true);
            assertEquals("0.8", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
        }
    }

    @Test
    void failureKeepsCurrentLocalStateAndDeduplicatesTheFailedReceipt() throws Exception {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority, "ui.gridOpacity", "0.6");
            var future = authority.executeAsync(request, owner, worker);
            Files.createDirectory(settings.getFile().resolveSibling(settings.getFile().getFileName() + ".tmp"));
            settings.applyEdit("ui.gridOpacity", "0.8");
            drain();
            var result = future.join();
            assertEquals(SAVE_FAILED, result.status());
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
            assertSame(result, authority.executeAsync(request, owner, worker).join());
            assertTrue(worker.tasks.isEmpty());
            Files.delete(settings.getFile().resolveSibling(settings.getFile().getFileName() + ".tmp"));
            settings.putBoolean("player.showGrid", true);
            assertEquals("0.8", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
        }
    }

    @Test
    void retriesShareOneSaveAndCallerCancellationDoesNotCancelTheCommand() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority, "ui.gridOpacity", "0.6");
            var first = authority.executeAsync(request, owner, worker);
            var retry = authority.executeAsync(request, owner, worker);
            assertEquals(1, worker.tasks.size());
            first.cancel(false);
            assertEquals(RESYNC_REQUIRED, authority.executeAsync(new HostSettingsAuthority.Request(
                    request.sessionId(), request.sequence(), new AppSettings.RemoteEdit(UUID.randomUUID(),
                    request.edit().baseRevision(), "ui.gridOpacity", request.edit().expectedValue(), "0.9")),
                    owner, worker).join().status());
            assertEquals(RESYNC_REQUIRED, authority.executeAsync(new HostSettingsAuthority.Request(
                    request.sessionId(), 2, request.edit()), owner, worker).join().status());
            drain();
            assertEquals(ACCEPTED, retry.join().status());
            settings.applyEdit("ui.gridOpacity", "0.8");
            assertSame(retry.join(), authority.executeAsync(request, owner, worker).join());
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
        }
    }

    @Test
    void closingWhileWriteIsPendingRepairsDiskWithoutApplyingTheRemoteValue() throws Exception {
        AppSettings settings = settings();
        var authority = new HostSettingsAuthority(settings);
        var future = authority.executeAsync(request(authority, "ui.gridOpacity", "0.6"), owner, worker);
        worker.runNextOnWorker();
        owner.runNext();
        worker.runNextOnWorker();
        authority.close();
        settings.applyEdit("ui.gridOpacity", "0.9");
        drain();
        assertEquals(RESYNC_REQUIRED, future.join().status());
        assertEquals(0.9, Tuning.GRID_OPACITY.get());
        assertEquals("0.9", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
        settings.applyEdit("ui.gridOpacity", "0.7"); // Transaction has released standalone persistence.
        assertEquals("0.7", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
    }

    @Test
    void externalFileEditsAreReadOnWorkerAndParticipateInConflicts() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority, "ui.gridOpacity", "0.6");
            externalEdit(settings, "ui.gridOpacity", "0.8");
            assertEquals(request.edit().expectedValue(),
                    authority.stateInMemory().settings().values().get("ui.gridOpacity"));
            var future = authority.executeAsync(request, owner, worker);
            settings.putBoolean("player.showGrid", true); // Merge must retain these pending local edits.
            drain();
            assertEquals(CONFLICT, future.join().status());
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            assertEquals("true", settings.get("player.showGrid", null));
            assertEquals("true", new AppSettings(settings.getFile()).get("player.showGrid", null));
            externalEdit(settings, "ui.gridOpacity", "0.7");
            assertTrue(settings.pollExternalChange(), "ordinary external file polling is preserved");
            assertEquals("0.7", authority.stateInMemory().settings().values().get("ui.gridOpacity"));
        }
    }

    @Test
    void externalEditsCannotOverwriteLocalChangesMadeWhileTheirReadIsQueued() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority, "ui.gridOpacity", "0.6");
            externalEdit(settings, "ui.gridOpacity", "0.8");
            var future = authority.executeAsync(request, owner, worker);
            settings.applyEdit("ui.gridOpacity", "0.9");
            drain();
            assertEquals(CONFLICT, future.join().status());
            assertEquals("0.9", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
        }
    }

    @Test
    void rejectedWorkerAndInvalidCommandsHaveExplicitReceiptsAndReleaseTheTransaction() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var invalid = request(authority, "ui.gridOpacity", "NaN");
            assertEquals(INVALID, authority.executeAsync(invalid, owner, worker).join().status());
            assertTrue(worker.tasks.isEmpty());
            var request = request(authority, "ui.gridOpacity", "0.6");
            Executor rejected = task -> { throw new RejectedExecutionException("test"); };
            var result = authority.executeAsync(request, owner, rejected).join();
            assertEquals(SAVE_FAILED, result.status());
            assertSame(result, authority.executeAsync(request, owner, worker).join());
            settings.applyEdit("ui.gridOpacity", "0.8");
            assertEquals("0.8", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
            var next = authority.executeAsync(request(authority, "ui.gridOpacity", "0.7"), owner, worker);
            drain();
            assertEquals(ACCEPTED, next.join().status());
        }
    }

    @Test
    void expiredAndWrongSessionRequestsNeverScheduleIo() throws Exception {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings, 1)) {
            var first = request(authority, "ui.gridOpacity", "0.6");
            authority.executeAsync(first, owner, worker);
            drain();
            authority.executeAsync(request(authority, "ui.gridOpacity", "0.7"), owner, worker);
            drain();
            assertEquals(RESYNC_REQUIRED, authority.executeAsync(first, owner, worker).join().status());
            assertEquals(RESYNC_REQUIRED, authority.executeAsync(new HostSettingsAuthority.Request(
                    UUID.randomUUID(), 3, first.edit()), owner, worker).join().status());
            assertTrue(worker.tasks.isEmpty());
        }
    }

    private AppSettings settings() {
        return new AppSettings(dir.resolve("host.ini"));
    }

    private static HostSettingsAuthority.Request request(HostSettingsAuthority authority, String key, String value) {
        var state = authority.stateInMemory();
        return new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                        key, state.settings().values().get(key), value));
    }

    private static void externalEdit(AppSettings settings, String key, String value) throws IOException {
        String text = Files.readString(settings.getFile());
        String old = settings.sharedStateInMemory().snapshot().values().get(key);
        Files.writeString(settings.getFile(), text.replace(key + " = " + old, key + " = " + value));
        Files.setLastModifiedTime(settings.getFile(), FileTime.fromMillis(System.currentTimeMillis() + 10000));
    }

    private void drain() throws Exception {
        for (int round = 0; round < 30 && (!worker.tasks.isEmpty() || !owner.tasks.isEmpty()); round++) {
            if (!worker.tasks.isEmpty()) {
                worker.runNextOnWorker();
            }
            if (!owner.tasks.isEmpty()) {
                owner.runNext();
            }
        }
        assertTrue(worker.tasks.isEmpty());
        assertTrue(owner.tasks.isEmpty());
    }

    private static final class Tasks implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove();
            }
            task.run();
        }

        void runNextOnWorker() throws InterruptedException {
            var failure = new CompletableFuture<Throwable>();
            Thread thread = Thread.ofPlatform().daemon().start(() -> {
                try {
                    runNext();
                    failure.complete(null);
                } catch (Throwable thrown) {
                    failure.complete(thrown);
                }
            });
            thread.join(5000);
            assertFalse(thread.isAlive(), "worker must not wait for an owner-thread monitor");
            assertNull(failure.join(), "worker failed");
        }
    }
}
