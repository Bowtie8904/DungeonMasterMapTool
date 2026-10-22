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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceReplicaTest {
    @TempDir Path directory;

    @Test
    void baselineDownloadsOnlyActiveArtworkAndBecomesEditableAfterVerifiedMaterialization() throws Exception {
        var f = new Fixture();
        f.hostImage(new byte[]{1, 2, 3});
        try (var replica = f.replica()) {
            replica.tick();
            assertEquals(RemoteWorkspaceReplica.Phase.LOADING, replica.phase());
            assertFalse(replica.canEdit());
            assertTrue(f.displays.isEmpty());
            f.drain();
            assertTrue(replica.canEdit());
            assertEquals(1, f.downloads);
            assertEquals(1, f.displays.size());
            assertTrue(Files.isRegularFile(Path.of(f.project.get().getMap().getImagePath())));
            assertEquals(800, f.project.get().getViews().getDmCamera().getX());
            replica.tick();
            f.drain();
            assertEquals(1, f.downloads);
            assertEquals(1, f.displays.size());
        }
    }

    @Test
    void hostUpdatesWaitForGestureAndSubmittedEditKeepsItsOriginalExpectedTargets() throws Exception {
        var f = new Fixture();
        try (var replica = f.replica()) {
            replica.tick();
            assertTrue(replica.canEdit());
            f.gesture = true;
            f.project.get().getOverlays().add(DmProject.OverlayShape.builder().id("client").x(40).build());
            var host = f.hostState.document().materialize(new DmProject.CameraState());
            host.getWeather().setType("RAIN");
            f.state(host);
            replica.tick();
            assertNotEquals("RAIN", f.project.get().getWeather().getType());
            f.gesture = false;
            replica.committed("Draw circle");
            assertEquals(1, f.requests.size());
            assertEquals(1, f.requests.getFirst().changes().size());
            assertEquals("overlays:client", f.requests.getFirst().changes().getFirst().target());
            f.accept();
            f.drain();
            assertTrue(replica.canEdit());
            assertEquals("RAIN", f.project.get().getWeather().getType());
            assertEquals("client", f.project.get().getOverlays().getFirst().getId());
        }
    }

    @Test
    void hostMapSwitchCancelsTheUnfinishedGestureRatherThanWaitingOrSendingItToTheNewMap() throws Exception {
        var f = new Fixture();
        try (var replica = f.replica()) {
            replica.tick();
            f.gesture = true;
            f.project.get().getOverlays().add(DmProject.OverlayShape.builder().id("unfinished").build());
            var next = new DmProject();
            var old = f.hostState;
            f.hostState = new RemoteWorkspaceAuthority.State(old.sessionId(), java.util.UUID.randomUUID(),
                    old.revision() + 1, old.nextSequence(), RemoteProjectDocument.capture(next), java.util.Map.of());
            replica.tick();
            assertFalse(f.gesture);
            assertTrue(replica.canEdit());
            assertEquals(next.getId(), f.project.get().getId());
            assertTrue(f.project.get().getOverlays().isEmpty());
            assertTrue(f.requests.isEmpty());
            assertTrue(f.notices.stream().anyMatch(message -> message.contains("switched maps")));
        }
    }

    @Test
    void synchronousSendLossPausesAndRetriesTheSameImmutableCommandAfterReconnect() throws Exception {
        var f = new Fixture();
        try (var replica = f.replica()) {
            replica.tick();
            f.sendUnavailable = true;
            f.project.get().getWeather().setType("RAIN");
            replica.committed("Rain");
            assertEquals(RemoteWorkspaceReplica.Phase.PAUSED, replica.phase());
            var original = f.requests.getFirst();
            f.sendUnavailable = false;
            replica.tick();
            assertSame(original, f.requests.getLast());
            f.accept();
            f.drain();
            assertTrue(replica.canEdit());
            assertEquals("RAIN", f.project.get().getWeather().getType());
        }
    }

    @Test
    void supersededDownloadNeverBrieflyDisplaysThePreviousHostMap() throws Exception {
        var f = new Fixture();
        f.hostImage(new byte[]{1});
        try (var replica = f.replica()) {
            replica.tick();
            var previous = f.hostState;
            var next = new DmProject();
            f.hostImage(new byte[]{2}, next);
            f.drain();
            assertEquals(1, f.displays.size());
            assertEquals(next.getId(), f.displays.getFirst().project().getId());
            assertNotEquals(previous.contextId(), f.displays.getFirst().contextId());
            assertTrue(replica.canEdit());
        }
    }

    @Test
    void newLocalArtworkUploadsBeforeTheExactCompletedEditIsSent() throws Exception {
        var f = new Fixture();
        try (var replica = f.replica()) {
            replica.tick();
            Path source = Files.write(directory.resolve("new.png"), new byte[]{8, 9});
            f.project.get().getMap().setImagePath(source.toString());
            replica.committed("Import artwork");
            assertFalse(replica.canEdit());
            assertTrue(f.requests.isEmpty());
            f.drain();
            assertEquals(1, f.uploads);
            assertEquals(1, f.requests.size());
            assertTrue(f.requests.getFirst().changes().getFirst().replacement().textValue().startsWith("asset:"));
            Files.delete(source);
            f.accept();
            f.drain();
            assertTrue(replica.canEdit());
            assertTrue(Files.isRegularFile(Path.of(f.project.get().getMap().getImagePath())));
        }
    }

    @Test
    void disconnectDuringDownloadDiscardsLateCallbacksAndReconnectUsesVerifiedCache() throws Exception {
        var f = new Fixture();
        f.hostImage(new byte[]{1, 2});
        try (var replica = f.replica()) {
            replica.tick();
            f.connected = false;
            replica.tick();
            assertEquals(RemoteWorkspaceReplica.Phase.PAUSED, replica.phase());
            f.drain();
            assertTrue(f.displays.isEmpty());
            f.connected = true;
            replica.tick();
            f.drain();
            assertTrue(replica.canEdit());
            assertEquals(1, f.displays.size());
            assertEquals(1, f.downloads);
        }
    }

    @Test
    void lostAcknowledgementRecoversTheSameRequestInsteadOfDuplicatingTheEdit() throws Exception {
        var f = new Fixture();
        try (var replica = f.replica()) {
            replica.tick();
            f.project.get().getWeather().setType("SNOW");
            replica.committed("Snow");
            var exact = f.requests.getFirst();
            var applied = f.acceptedResult();
            f.connected = false;
            replica.tick();
            f.response.completeExceptionally(new IOException("lost"));
            f.drain();
            f.connected = true;
            replica.tick();
            f.drain();
            assertEquals(2, f.requests.size());
            assertSame(exact, f.requests.getLast());
            f.response.complete(applied);
            f.drain();
            assertTrue(replica.canEdit());
            assertEquals("SNOW", f.project.get().getWeather().getType());
        }
    }

    @Test
    void historyUsesOrderedWorkspaceStreamAndHostMetadataNotALocalUndoStack() throws Exception {
        var f = new Fixture();
        var state = f.hostState;
        f.hostState = new RemoteWorkspaceAuthority.State(state.sessionId(), state.contextId(), 1, 1,
                state.document(), state.changedAt(), state.assets(), new RemoteWorkspaceAuthority.History(7,
                new RemoteWorkspaceAuthority.HistoryEntry("Host drawing", RemoteWorkspaceAuthority.Origin.HOST), null));
        try (var replica = f.replica()) {
            replica.tick();
            replica.history(RemoteWorkspaceAuthority.Operation.UNDO);
            var request = f.requests.getFirst();
            assertEquals(RemoteWorkspaceAuthority.Operation.UNDO, request.operation());
            assertEquals(7, request.historyVersion());
            assertTrue(request.changes().isEmpty());
            assertFalse(replica.canEdit());
        }
    }

    @Test
    void failedUploadsRollBackUnsentPreviewAndCloseNeverDisplaysLatePreparedMaps() throws Exception {
        var f = new Fixture();
        var replica = f.replica();
        replica.tick();
        f.transferFailure = true;
        Path source = Files.write(directory.resolve("new.png"), new byte[]{8});
        f.project.get().getMap().setImagePath(source.toString());
        replica.committed("Image");
        f.drain();
        assertTrue(replica.canEdit());
        assertNull(f.project.get().getMap().getImagePath());
        assertTrue(f.requests.isEmpty());
        assertTrue(f.notices.getLast().contains("not sent"));
        f.transferFailure = false;
        f.hostImage(new byte[]{3});
        replica.tick();
        int displayed = f.displays.size();
        replica.close();
        f.drain();
        assertEquals(displayed, f.displays.size());
        assertEquals(RemoteWorkspaceReplica.Phase.CLOSED, replica.phase());
    }

    private final class Fixture implements RemoteWorkspaceReplica.Connection {
        final RemoteAssetStore local = new RemoteAssetStore(directory.resolve("local"), 1024, 8192, 4096);
        final RemoteAssetStore hostStore = new RemoteAssetStore(directory.resolve("host"), 1024, 8192, 4096);
        final AtomicReference<DmProject> project = new AtomicReference<>(new DmProject());
        final ArrayDeque<Runnable> worker = new ArrayDeque<>();
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final List<RemoteWorkspaceReplica.Display> displays = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final List<RemoteWorkspaceAuthority.Request> requests = new ArrayList<>();
        RemoteWorkspaceAuthority.State hostState;
        CompletableFuture<RemoteWorkspaceAuthority.Result> response;
        boolean connected = true;
        boolean gesture;
        boolean transferFailure;
        boolean sendUnavailable;
        int downloads;
        int uploads;

        Fixture() throws IOException {
            project.get().getViews().getDmCamera().setX(800);
            hostState = new RemoteWorkspaceAuthority.State(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    0, 1, RemoteProjectDocument.capture(new DmProject()), java.util.Map.of());
        }

        RemoteWorkspaceReplica replica() {
            return new RemoteWorkspaceReplica(this, local, directory.resolve("cache"), project::get, () -> gesture,
                    () -> gesture = false, shown -> {
                        displays.add(shown);
                        project.set(shown.project());
                    }, notices::add, owner::addLast, worker::addLast);
        }

        void state(DmProject next) {
            hostState = new RemoteWorkspaceAuthority.State(hostState.sessionId(), hostState.contextId(),
                    hostState.revision() + 1, hostState.nextSequence(), RemoteProjectDocument.capture(next),
                    java.util.Map.of(), hostState.assets());
        }

        void hostImage(byte[] bytes) throws IOException {
            hostImage(bytes, hostState.document().materialize(new DmProject.CameraState()));
        }

        void hostImage(byte[] bytes, DmProject next) throws IOException {
            var descriptor = hostStore.publishRequiredAsset(Files.write(directory.resolve("host.png"), bytes));
            String reference = RemoteProjectAssets.referenceFor(descriptor);
            next.getMap().setImagePath(reference);
            hostState = new RemoteWorkspaceAuthority.State(hostState.sessionId(),
                    next.getId().equals(hostState.document().projectId()) ? hostState.contextId() : java.util.UUID.randomUUID(),
                    hostState.revision() + 1, hostState.nextSequence(), RemoteProjectDocument.capture(next),
                    java.util.Map.of(), java.util.Map.of(reference, descriptor));
        }

        RemoteWorkspaceAuthority.Result acceptedResult() throws Exception {
            var request = requests.getLast();
            var parts = new java.util.LinkedHashMap<>(hostState.document().parts());
            var assets = new java.util.LinkedHashMap<>(hostState.assets());
            for (var change : request.changes()) {
                if (change.replacement() == null) {
                    parts.remove(change.target());
                } else {
                    parts.put(change.target(), change.replacement());
                }
            }
            var document = new RemoteProjectDocument(hostState.document().projectId(), parts);
            for (String ref : document.assetReferences()) {
                assets.put(ref, hostStore.descriptor(ref.substring(6)));
            }
            hostState = new RemoteWorkspaceAuthority.State(hostState.sessionId(), hostState.contextId(),
                    hostState.revision() + 1, request.sequence() + 1, document, java.util.Map.of(), assets);
            return new RemoteWorkspaceAuthority.Result(request.commandId(), request.sequence(),
                    RemoteWorkspaceAuthority.Status.ACCEPTED, "Applied", hostState);
        }

        void accept() throws Exception {
            response.complete(acceptedResult());
        }

        void drain() throws InterruptedException {
            while (!worker.isEmpty() || !owner.isEmpty()) {
                if (!worker.isEmpty()) {
                    Thread thread = Thread.ofPlatform().start(worker.removeFirst());
                    thread.join();
                }
                while (!owner.isEmpty()) {
                    owner.removeFirst().run();
                }
            }
        }

        @Override public boolean connected() { return connected; }
        @Override public boolean ready() { return connected; }
        @Override public RemoteWorkspaceAuthority.State latest() { return hostState; }
        @Override public void close() { connected = false; }

        @Override
        public CompletableFuture<RemoteWorkspaceAuthority.Result> send(RemoteWorkspaceAuthority.Request request) {
            requests.add(request);
            if (sendUnavailable) {
                throw new IllegalStateException("Transport disconnected during send");
            }
            response = new CompletableFuture<>();
            return response;
        }

        @Override
        public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(RemoteAssetTransfer.Direction direction,
                                                                            RemoteAssetStore.AssetDescriptor asset) {
            if (transferFailure) {
                return CompletableFuture.failedFuture(new IOException("transfer failed"));
            }
            try {
                var source = direction == RemoteAssetTransfer.Direction.DOWNLOAD ? hostStore : local;
                var target = direction == RemoteAssetTransfer.Direction.DOWNLOAD ? local : hostStore;
                var bytes = new java.io.ByteArrayOutputStream();
                source.transferTo(asset.sha256(), bytes);
                target.ingest(new java.io.ByteArrayInputStream(bytes.toByteArray()), asset.size(), asset.sha256());
                if (direction == RemoteAssetTransfer.Direction.DOWNLOAD) {
                    downloads++;
                } else {
                    uploads++;
                }
                return CompletableFuture.completedFuture(asset);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }
}
