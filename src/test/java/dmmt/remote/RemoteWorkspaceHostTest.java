package dmmt.remote;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceHostTest {
    @TempDir
    Path directory;

    @Test
    void publicationFailureRetainsDiagnosticsAndCorrectedArtworkRecoversWithFreshContext() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host()) {
            var original = host.state();
            fixture.project.get().getMap().setImagePath("missing.png");
            host.switchedContext();
            fixture.completeWork();
            assertFalse(host.ready());
            assertNotNull(host.failure());
            assertTrue(host.failure().stackTrace().contains("IOException"));
            assertEquals(original, host.state(), "Failed publication must not expose a partial map.");
            fixture.addArtwork();
            host.committed();
            fixture.completeWork();
            assertTrue(host.ready());
            assertNull(host.failure());
            assertNull(host.error());
            assertNotEquals(original.contextId(), host.state().contextId());
            assertEquals(1, host.state().assets().size());
        }
    }

    @Test
    void readinessRechecksPublicationStartedByTheApplicationCommitGate() throws Exception {
        var fixture = new Fixture(false);
        var reference = new AtomicReference<RemoteWorkspaceHost>();
        var flush = new java.util.concurrent.atomic.AtomicBoolean();
        try (var host = fixture.host(() -> {
            if (flush.getAndSet(false)) reference.get().committed();
            return true;
        })) {
            reference.set(host);
            assertTrue(host.ready());
            fixture.addArtwork();
            flush.set(true);
            assertFalse(host.ready(), "Flushing a completed edit may start asynchronous publication.");
            fixture.completeWork();
            assertTrue(host.ready());
            assertEquals(1, host.state().assets().size());
        }
    }

    @Test
    void stoppingBeforeQueuedPublicationStartsDoesNotHashOrPublishArtwork() throws Exception {
        var fixture = new Fixture(true);
        var host = fixture.host();
        assertFalse(fixture.worker.isEmpty());
        host.close();
        fixture.completeWork();
        try (var objects = Files.list(directory.resolve("store").resolve("objects"))) {
            assertEquals(0, objects.count(), "Stopping must prevent queued artwork from being ingested.");
        }
        assertTrue(fixture.errors.isEmpty(), "Cancelled queued work must not report a publication failure.");
        assertTrue(fixture.commits.isEmpty());
    }

    @Test
    void stoppingBeforeQueuedIncomingArtworkStartsDoesNotMaterializeClientAssets() throws Exception {
        var fixture = new Fixture(false);
        var host = fixture.host();
        var baseline = host.state();
        var descriptor = fixture.store.publishRequiredAsset(
                Files.write(directory.resolve("upload.png"), new byte[]{9, 8}));
        var client = baseline.document().materialize(camera());
        client.getMap().setImagePath(RemoteProjectAssets.referenceFor(descriptor));
        var pending = host.executeAsync(RemoteWorkspaceAuthority.diff(
                baseline, RemoteProjectDocument.capture(client), "Set image"));
        assertFalse(fixture.worker.isEmpty());
        host.close();
        assertTrue(pending.isCompletedExceptionally());
        fixture.completeWork();
        assertFalse(Files.exists(directory.resolve("cache")),
                "Queued remote work must not create cache files after stopping.");
        assertNull(fixture.project.get().getMap().getImagePath());
        assertTrue(fixture.commits.isEmpty());
        assertTrue(fixture.errors.isEmpty());
    }

    @Test
    void initialPublicationUsesCapturedCommitNotLaterUnfinishedGesture() throws Exception {
        var fixture = new Fixture(true);
        try (var host = fixture.host()) {
            assertFalse(host.ready());
            fixture.project.get().getTokens().getFirst().setX(999);
            fixture.completeWork();
            assertTrue(host.ready());
            assertEquals(0, host.state().document().materialize(camera()).getTokens().getFirst().getX());
            assertEquals(999, fixture.project.get().getTokens().getFirst().getX());
            assertEquals(1, host.state().assets().size());
        }
    }

    @Test
    void queuedAwayAndBackCommitsKeepTargetConflictsWhileArtworkIsPrepared() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host()) {
            var baseline = host.state();
            var client = baseline.document().materialize(camera());
            client.getTokens().getFirst().setX(17);
            var stale = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Move token");
            fixture.addArtwork();
            host.committed();
            fixture.project.get().getTokens().getFirst().setX(10);
            host.committed();
            fixture.project.get().getTokens().getFirst().setX(0);
            host.committed();
            assertFalse(host.ready());
            fixture.completeWork();
            assertTrue(host.ready());
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, host.execute(stale).status());
            assertEquals(0, fixture.project.get().getTokens().getFirst().getX());
            assertTrue(fixture.commits.isEmpty());
        }
    }

    @Test
    void busyReceiptIsExactAndRetryCannotAccidentallyApplyAfterPreparation() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host()) {
            var baseline = host.state();
            var client = baseline.document().materialize(camera());
            client.getWeather().setType("snow");
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Weather");
            fixture.addArtwork();
            host.committed();
            var busy = host.executeAsync(request).join();
            assertEquals(RemoteWorkspaceAuthority.Status.BUSY, busy.status());
            fixture.completeWork();
            assertSame(busy, host.executeAsync(request).join());
            assertNotEquals("snow", fixture.project.get().getWeather().getType());
        }
    }

    @Test
    void incomingArtworkIsPreparedOffThreadBeforeTargetedExistingHistoryApply() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host()) {
            var baseline = host.state();
            Path upload = Files.write(directory.resolve("upload.png"), new byte[]{9, 8});
            var descriptor = fixture.store.publishRequiredAsset(upload);
            var edited = fixture.project.get();
            var client = baseline.document().materialize(camera());
            client.getMap().setImagePath(RemoteProjectAssets.referenceFor(descriptor));
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Set image");
            CompletableFuture<RemoteWorkspaceAuthority.Result> pending = host.executeAsync(request);
            assertFalse(pending.isDone());
            assertNull(edited.getMap().getImagePath());
            fixture.completeWork();
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, pending.join().status());
            assertEquals(1, host.state().assets().size());
            assertSame(edited, fixture.project.get());
            assertSame(fixture.token, edited.getTokens().getFirst());
            assertEquals(1, fixture.commits.size());
            var commit = fixture.commits.getFirst();
            assertEquals(Set.of("map:imagePath"), commit.targets());
            assertTrue(Files.isRegularFile(Path.of(edited.getMap().getImagePath())));
            edited.getWeather().setType("rain");
            host.committed();
            RemoteProjectChanges.apply(edited, commit.before(), commit.targets());
            host.committed();
            assertNull(edited.getMap().getImagePath());
            assertEquals("rain", edited.getWeather().getType());
        }
    }

    @Test
    void changingMapDuringIncomingPreparationNeverAppliesToTheNewMap() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host()) {
            var baseline = host.state();
            var descriptor = fixture.store.publishRequiredAsset(
                    Files.write(directory.resolve("upload.png"), new byte[]{4}));
            var client = baseline.document().materialize(camera());
            client.getMap().setImagePath(RemoteProjectAssets.referenceFor(descriptor));
            var pending = host.executeAsync(RemoteWorkspaceAuthority.diff(
                    baseline, RemoteProjectDocument.capture(client), "Set image"));
            fixture.project.set(new DmProject());
            host.switchedContext();
            fixture.completeWork();
            assertEquals(RemoteWorkspaceAuthority.Status.BUSY, pending.join().status());
            assertNull(fixture.project.get().getMap().getImagePath());
            assertNotEquals(baseline.contextId(), host.state().contextId());
            assertTrue(fixture.commits.isEmpty());
        }
    }

    @Test
    void revocationDuringIncomingPreparationPreventsLateApplicationAndRetainsTheRejection() throws Exception {
        var fixture = new Fixture(false);
        try (var host = fixture.host(); var access = new RemoteSessionAccess()) {
            var approval = access.requestApproval(access.pairingCode().value(),
                    new RemoteSessionAccess.Client("Desktop", "127.0.0.1"));
            var grant = access.approve(approval.id());
            var lease = access.connect(grant.sessionId(), grant.credential());
            var baseline = host.state();
            var descriptor = fixture.store.publishRequiredAsset(
                    Files.write(directory.resolve("upload.png"), new byte[]{4}));
            var client = baseline.document().materialize(camera());
            client.getMap().setImagePath(RemoteProjectAssets.referenceFor(descriptor));
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Set image");
            var pending = host.executeAsync(request, () -> access.authorizeConnection(lease));
            access.revoke();
            fixture.completeWork();
            var result = pending.join();
            assertEquals(RemoteWorkspaceAuthority.Status.INVALID, result.status());
            assertSame(result, host.execute(request));
            assertNull(fixture.project.get().getMap().getImagePath());
            assertTrue(fixture.commits.isEmpty());
        }
    }

    @Test
    void missingUploadsRejectWithoutMutationAndCloseInvalidatesPendingCallbacks() throws Exception {
        var fixture = new Fixture(false);
        var host = fixture.host();
        var baseline = host.state();
        var client = baseline.document().materialize(camera());
        client.getMap().setImagePath("asset:" + "a".repeat(64));
        var pending = host.executeAsync(RemoteWorkspaceAuthority.diff(
                baseline, RemoteProjectDocument.capture(client), "Missing image"));
        fixture.completeWork();
        assertEquals(RemoteWorkspaceAuthority.Status.INVALID, pending.join().status());
        assertEquals(1, fixture.errors.size());
        assertTrue(fixture.commits.isEmpty());
        var second = host.executeAsync(RemoteWorkspaceAuthority.diff(
                host.state(), RemoteProjectDocument.capture(client), "Missing image again"));
        host.close();
        assertTrue(second.isCompletedExceptionally());
        fixture.completeWork();
        assertTrue(fixture.commits.isEmpty());
    }

    private static DmProject.CameraState camera() {
        return new DmProject.CameraState();
    }

    private final class Fixture {
        final AtomicReference<DmProject> project = new AtomicReference<>(new DmProject());
        final DmProject.TokenInstance token = new DmProject.TokenInstance();
        final RemoteAssetStore store;
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final ArrayDeque<Runnable> worker = new ArrayDeque<>();
        final List<RemoteWorkspaceAuthority.Commit> commits = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        Fixture(boolean image) throws IOException {
            token.setId("token");
            token.setDefinitionId("definition");
            project.get().getTokens().add(token);
            var definition = new DmProject.TokenDefinition();
            definition.setId("definition");
            project.get().getTokenDefinitions().put(definition.getId(), definition);
            store = new RemoteAssetStore(directory.resolve("store"), 1024, 8192, 4096);
            if (image) {
                addArtwork();
            }
        }

        void addArtwork() throws IOException {
            Files.write(directory.resolve("image.png"), new byte[]{1, 2});
            project.get().getMap().setImagePath("image.png");
        }

        RemoteWorkspaceHost host() {
            return host(() -> true);
        }

        RemoteWorkspaceHost host(java.util.function.BooleanSupplier gate) {
            Executor ownerExecutor = owner::addLast;
            Executor workerExecutor = worker::addLast;
            return new RemoteWorkspaceHost(project::get, () -> directory.resolve("level.dmmap"), Set::of, commit -> {
                RemoteProjectChanges.apply(project.get(), commit.after(), commit.targets());
                commits.add(commit);
            }, store, directory.resolve("cache"), ownerExecutor, workerExecutor, errors::add, gate);
        }

        void completeWork() throws InterruptedException {
            while (!worker.isEmpty() || !owner.isEmpty()) {
                if (!worker.isEmpty()) {
                    var thread = Thread.ofPlatform().start(worker.removeFirst());
                    thread.join();
                }
                while (!owner.isEmpty()) {
                    owner.removeFirst().run();
                }
            }
        }
    }
}
