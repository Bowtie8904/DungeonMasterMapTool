package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.audio.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.service.AppSettings;
import dmmt.service.JsonMappers;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAudioTest {
    private final ObjectMapper json = JsonMappers.create();
    private final Path directory = Path.of("target", "remote-audio-tests", UUID.randomUUID().toString()).toAbsolutePath();
    private AudioLibraryService library;
    private AudioEngine engine;
    private FakeAudioOutput output;
    private RemoteAssetStore assets;
    private RemoteAudio host;
    private final List<String> notices = new ArrayList<>();
    private Path hostSettingsFile;
    private AppSettings hostSettings;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(directory);
        library = new AudioLibraryService(directory.resolve("host"));
        output = new FakeAudioOutput();
        engine = new AudioEngine(library, output);
        assets = new RemoteAssetStore(directory.resolve("assets"), 20_000_000, 80_000_000, 20_000_000);
        // A deliberately non-default config path: remote writes must reach A's live settings, never AppSettings.load().
        hostSettingsFile = directory.resolve("host-config").resolve("custom-host.ini");
        Files.createDirectories(hostSettingsFile.getParent());
        hostSettings = new AppSettings(hostSettingsFile);
        hostSettings.put("audio.folder", library.root().toString());
        host = endpoint(Runnable::run);
        host.configureSettings(hostSettings);
    }

    private RemoteAudio endpoint(Executor worker) {
        return new RemoteAudio(() -> library, () -> engine, assets, directory.resolve("cache"),
                worker, Runnable::run, notices::add);
    }

    @AfterEach void cleanup() throws Exception {
        if (host != null) host.close();
        if (engine != null) engine.shutdown();
        if (library != null) library.stopPreparation();
        Tuning.reset();
        if (Files.exists(directory)) try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private JsonNode state() { return host.query("state", json.createObjectNode()).join(); }
    private JsonNode command(String action, Object... arguments) {
        return commandAt(state().path("revision").asLong(), action, arguments);
    }
    private JsonNode commandAt(long revision, String action, Object... arguments) {
        return host.execute(action, payload(revision, arguments)).join();
    }
    private ObjectNode payload(long revision, Object... arguments) {
        var payload = json.createObjectNode().put("revision", revision);
        payload.set("arguments", json.valueToTree(arguments));
        return payload;
    }
    private long revision() { return state().path("revision").asLong(); }
    private AudioTrack track(String name, AudioKind kind, String category) throws Exception {
        Path file = directory.resolve(name + ".wav");
        TestAudioFiles.writeWav(file, 1000, 8000);
        return library.importFile(file, kind, category);
    }

    @Test void transportEffectsVolumesAndLoopControlsExecuteOnlyOnHost() throws Exception {
        AudioCategory category = library.createCategory("Forest", "#112233", AudioCategory.DEFAULT_ICON);
        AudioTrack music = track("theme", AudioKind.MUSIC, category.getId());
        AudioTrack effect = track("rain", AudioKind.EFFECT, null);
        command("playTrack", music.getId());
        command("effectActive", effect.getId(), true);
        command("musicLoop", true);
        command("effectsPaused", true);
        command("volumes", 0.4, 0.5, 0.6);
        JsonNode playback = state().path("playback");
        assertEquals(music.getId(), playback.path("trackId").asText());
        assertTrue(playback.path("musicPlaying").asBoolean());
        assertTrue(playback.path("musicLoop").asBoolean());
        assertTrue(playback.path("effectsPaused").asBoolean());
        assertEquals(0.4, playback.path("masterVolume").asDouble());
        assertEquals(List.of(effect.getId()), engine.activeEffects());
        assertTrue(output.openedCount() > 0);
        command("toggleAll");
        assertTrue(engine.isMusicPaused());
        command("panic", true);
        assertTrue(engine.isPanic());
        command("stopMusic");
        command("stopAllEffects");
        assertTrue(engine.currentTrack().isEmpty());
        assertFalse(engine.hasRunningEffects());
        command("playTrack", music.getId());
        command("deleteTrack", music.getId());
        assertNull(engine.currentTrackId(), "Deleting a playing host track must release its transport.");
    }

    @Test void acceptedMetadataEditsPersistAndStaleHostLocalChangesWin() throws Exception {
        command("createCategory", "Caves", "#123456", AudioCategory.DEFAULT_ICON);
        AudioCategory category = library.categoryByName("Caves").orElseThrow();
        AudioTrack effect = track("wind", AudioKind.EFFECT, null);
        command("renameCategory", category.getId(), "Mountains");
        command("styleCategory", category.getId(), "#ABCDEF", null);
        command("categoryHidden", category.getId(), true);
        command("renameTrack", effect.getId(), "Mountain wind");
        command("effectLooping", effect.getId(), false);
        command("styleTrack", effect.getId(), "#AABBCC", "mdi2w-weather-windy");
        command("trackHidden", effect.getId(), true);
        command("changeKind", effect.getId(), AudioKind.MUSIC, category.getId());
        AudioLibraryService persisted = new AudioLibraryService(library.root());
        assertEquals("Mountains", persisted.category(category.getId()).orElseThrow().getName());
        assertTrue(persisted.category(category.getId()).orElseThrow().isHidden());
        assertEquals(AudioKind.MUSIC, persisted.track(effect.getId()).orElseThrow().getKind());
        assertEquals("#AABBCC", persisted.track(effect.getId()).orElseThrow().getColor());
        long staleLibraryRevision = state().path("revision").asLong();
        library.renameTrack(effect.getId(), "A's live edit");
        assertThrows(Exception.class, () -> commandAt(staleLibraryRevision, "renameTrack", effect.getId(), "B stale"));
        assertEquals("A's live edit", library.track(effect.getId()).orElseThrow().getName());
        long oldRevision = state().path("revision").asLong();
        engine.setPanic(true);
        assertThrows(Exception.class, () -> commandAt(oldRevision, "panic", false));
        assertTrue(engine.isPanic());
    }

    @Test void catalogIsPagedAndNeverContainsHostAudioPathsOrPreparedFileNames() throws Exception {
        AudioTrack track = track("secret-source", AudioKind.EFFECT, null);
        for (int i = 0; i < 110; i++) library.createCategory("Category " + i, null, null);
        JsonNode first = host.query("catalog", json.createObjectNode().put("offset", 0)).join();
        assertEquals(96, first.path("categories").size());
        assertEquals("", first.path("tracks").get(0).path("file").asText());
        assertTrue(first.path("tracks").get(0).path("playbackFile").isNull());
        assertFalse(first.toString().contains(library.root().toString()));
        assertFalse(first.toString().contains(track.getFile()));
        assertFalse(first.toString().contains(track.getId() + ".ogg"));
        if (track.getPlaybackFile() != null) assertFalse(first.toString().contains(track.getPlaybackFile()));
        JsonNode second = host.query("catalog", json.createObjectNode().put("offset", first.path("next").asInt())).join();
        assertEquals(-1, second.path("next").asInt());
        assertEquals(111, first.path("categories").size() + second.path("categories").size());
    }

    @Test void waveformAndCutsAreRealHostJobsAndLoudnessEditsRemainHostOwned() throws Exception {
        AudioTrack track = track("recording", AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        String waveformId = command("waveform", track.getId(), 40).path("result").path("jobId").asText();
        JsonNode waveform = findJob(waveformId);
        assertEquals("READY", waveform.path("state").asText());
        var descriptor = json.treeToValue(waveform.path("result"), RemoteAssetStore.AssetDescriptor.class);
        Path peaks = assets.materialize(descriptor.sha256(), directory.resolve("download"), "peaks");
        assertNotNull(WaveformPeaks.readCache(peaks, 40));
        String clipId = command("cut", track.getId(), AudioKind.EFFECT, null,
                List.of(new RemoteAudio.Clip("clip", 100, 500))).path("result").path("jobId").asText();
        assertEquals("READY", findJob(clipId).path("state").asText());
        AudioTrack clip = library.tracks().stream().filter(t -> "clip".equals(t.getName())).findFirst().orElseThrow();
        assertEquals(track.getId(), clip.getSourceTrackId());
        assertEquals(AudioKind.EFFECT, clip.getKind());
        assertTrue(Files.isRegularFile(library.fileOf(clip)));
        command("gain", clip.getId(), -6.0);
        assertEquals(-6, library.track(clip.getId()).orElseThrow().getGainOverrideDb());
        assertThrows(Exception.class, () -> command("cut", track.getId(), AudioKind.EFFECT, null,
                List.of(new RemoteAudio.Clip("outside", 0, track.getDurationMs() + 1))));
    }

    private JsonNode findJob(String id) {
        for (JsonNode job : state().path("jobs")) if (id.equals(job.path("id").asText())) return job;
        throw new AssertionError("Missing host job " + id);
    }

    @Test void deferredMetadataRejectsAnInterveningHostEditBeforeTouchingDisk() throws Exception {
        AudioTrack track = track("source", AudioKind.EFFECT, null);
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        host.close();
        host = endpoint(work::add);
        var payload = json.createObjectNode().put("revision", state().path("revision").asLong());
        payload.set("arguments", json.valueToTree(new Object[]{track.getId(), "B queued"}));
        var reply = host.execute("renameTrack", payload);
        assertFalse(reply.isDone());
        library.renameTrack(track.getId(), "A wins");
        work.remove().run();
        assertTrue(reply.isCompletedExceptionally());
        assertEquals("A wins", library.track(track.getId()).orElseThrow().getName());
    }

    @Test void cancellingAndRetryingJobsUsesKnownIdsAndNeverCallerPaths() throws Exception {
        AudioTrack track = track("queued", AudioKind.EFFECT, null);
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        host.close();
        host = endpoint(work::add);
        String job = command("waveform", track.getId(), 40).path("result").path("jobId").asText();
        command("cancelJob", job);
        work.remove().run();
        assertEquals("CANCELLED", findJob(job).path("state").asText());
        String retry = command("retryJob", job).path("result").path("jobId").asText();
        work.remove().run();
        assertEquals("READY", findJob(retry).path("state").asText());
        assertThrows(Exception.class, () -> command("retryJob", directory.toString()));
        assertThrows(Exception.class, () -> command("waveform", directory.toString(), 40));
    }

    @Test void retryingAnInterruptedBatchDoesNotDuplicateAlreadyCommittedClips() throws Exception {
        library = new AudioLibraryService(directory.resolve("interrupted-library")) {
            private boolean interruptOnce = true;
            @Override public AudioTrack addClip(Path file, String name, AudioKind kind, String category,
                                                 AudioTrack source, long start, long end) throws IOException {
                AudioTrack accepted = super.addClip(file, name, kind, category, source, start, end);
                if (interruptOnce) {
                    interruptOnce = false;
                    Thread.currentThread().interrupt();
                }
                return accepted;
            }
        };
        AudioTrack source = track("batch", AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        String failed;
        try {
            failed = command("cut", source.getId(), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID,
                    List.of(new RemoteAudio.Clip("first", 0, 300), new RemoteAudio.Clip("second", 400, 800)))
                    .path("result").path("jobId").asText();
        } finally {
            Thread.interrupted();
        }
        assertEquals("FAILED", findJob(failed).path("state").asText());
        String retry = command("retryJob", failed).path("result").path("jobId").asText();
        assertEquals("READY", findJob(retry).path("state").asText());
        assertEquals(1, library.tracks().stream().filter(track -> "first".equals(track.getName())).count());
        assertEquals(1, library.tracks().stream().filter(track -> "second".equals(track.getName())).count());
    }

    @Test void uploadsBecomeHostManagedAudioThroughPreparationQueue() throws Exception {
        Path source = directory.resolve("uploaded.wav");
        TestAudioFiles.writeWav(source, 200, 8000);
        var asset = assets.publishRequiredAsset(source);
        command("import", asset, "uploaded.wav", AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID, "Folder category");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (library.tracks().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, library.tracks().size());
        AudioTrack imported = library.tracks().getFirst();
        assertTrue(Files.isRegularFile(library.fileOf(imported)));
        assertEquals("Folder category", library.category(imported.getCategoryId()).orElseThrow().getName());
        while (!imported.isReady() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(imported.isReady(), "Uploaded audio must complete real host preparation.");
        assertThrows(Exception.class, () -> command("import", asset, "..\\outside.wav", AudioKind.MUSIC, null, null));
    }

    @Test void clientRetainsItsLocalLibraryNeverOpensVoicesAndRestoresStoppedOnDisconnect() throws Exception {
        AudioTrack remoteTrack = track("host-song", AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        command("playTrack", remoteTrack.getId());
        AudioLibraryService local = new AudioLibraryService(directory.resolve("client"));
        AudioCategory retained = local.createCategory("Local retained", null, null);
        byte[] localIndex = Files.readAllBytes(local.root().resolve("library.json"));
        FakeAudioOutput localOutput = new FakeAudioOutput();
        AudioEngine replica = new AudioEngine(local, localOutput);
        RemoteOperations.Connection connection = new RemoteOperations.Connection() {
            @Override public boolean connected() { return true; }
            @Override public CompletableFuture<JsonNode> query(String domain, String action, JsonNode body) {
                assertEquals("audio", domain);
                return host.query(action, body);
            }
            @Override public CompletableFuture<JsonNode> command(String domain, String action, JsonNode body) {
                assertEquals("audio", domain);
                return host.execute(action, body);
            }
            @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file) {
                try { return CompletableFuture.completedFuture(assets.publishRequiredAsset(file)); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
            @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset) {
                try { return CompletableFuture.completedFuture(assets.materialize(asset.sha256(), directory.resolve("client-cache"), "peaks")); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
        };
        RemoteAudio.Client client = new RemoteAudio.Client(connection, local, replica, Runnable::run, Runnable::run,
                () -> { }, notices::add);
        try {
            replica.beginRemote(client);
            local.beginRemote(client);
            client.tick();
            assertEquals(remoteTrack.getId(), replica.currentTrack().orElseThrow().getId());
            assertEquals("host-song", local.track(remoteTrack.getId()).orElseThrow().getName());
            assertTrue(local.category(retained.getId()).isEmpty());
            replica.toggleMusic();
            assertTrue(engine.isMusicPaused());
            assertTrue(replica.isMusicPaused());
            replica.tick(300);
            assertEquals(0, localOutput.openedCount());
            local.renameTrack(remoteTrack.getId(), "renamed on A");
            assertEquals("renamed on A", library.track(remoteTrack.getId()).orElseThrow().getName());
            assertArrayEquals(localIndex, Files.readAllBytes(local.root().resolve("library.json")));
            assertThrows(IllegalStateException.class, () -> local.fileOf(local.tracks().getFirst()));
        } finally {
            client.close();
            local.endRemote();
            replica.endRemote();
            local.stopPreparation();
            replica.shutdown();
        }
        assertTrue(local.category(retained.getId()).isPresent());
        assertTrue(replica.currentTrack().isEmpty());
        assertArrayEquals(localIndex, Files.readAllBytes(local.root().resolve("library.json")));
    }

    @Test void audioDisableEnableFollowsCurrentHostSupplier() {
        AtomicReference<AudioLibraryService> current = new AtomicReference<>(library);
        host.close();
        host = new RemoteAudio(current::get, () -> engine, assets, directory.resolve("cache"),
                Runnable::run, Runnable::run, notices::add);
        assertTrue(state().path("enabled").asBoolean());
        current.set(null);
        assertFalse(state().path("enabled").asBoolean());
        current.set(library);
        assertTrue(state().path("enabled").asBoolean());
        assertThrows(Exception.class, () -> host.query("arbitrary", json.createObjectNode()).join());
    }

    @Test void receiptReplayReturnsTheSameCutJobWithoutCreatingAnotherClip() throws Exception {
        AudioTrack source = track("receipt-source", AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        try (RemoteOperations operations = new RemoteOperations(Runnable::run)) {
            operations.register("audio", host);
            var handshake = operations.exchange(new RemoteOperations.Query(null, null, null, null), () -> { }).join();
            var payload = json.createObjectNode().put("revision", state().path("revision").asLong());
            payload.set("arguments", json.valueToTree(new Object[]{source.getId(), AudioKind.EFFECT, null,
                    List.of(new RemoteAudio.Clip("single accepted clip", 0, 300))}));
            var request = new RemoteOperations.Command(handshake.sessionId(), UUID.randomUUID(), 1,
                    "audio", "cut", payload);
            var query = new RemoteOperations.Query(request, null, null, null);
            var accepted = operations.exchange(query, () -> { }).join();
            var replay = operations.exchange(query, () -> { }).join();
            assertEquals(RemoteOperations.Status.ACCEPTED, accepted.status());
            assertEquals(accepted, replay);
            assertEquals(1, library.tracks().stream()
                    .filter(track -> "single accepted clip".equals(track.getName())).count());
        }
    }

    @Test void volumeAndShuffleCommandsPersistOnlyThroughTheConfiguredHostSettings() throws Exception {
        Path clientFile = directory.resolve("client-config").resolve("client.ini");
        Files.createDirectories(clientFile.getParent());
        new AppSettings(clientFile);
        byte[] clientBefore = Files.readAllBytes(clientFile);
        command("volumes", 0.4, 0.5, 0.6);
        command("shuffle", false);
        assertFalse(state().path("playback").path("shuffle").asBoolean());
        for (AppSettings view : List.of(hostSettings, new AppSettings(hostSettingsFile))) {
            assertEquals("0.4", view.get("audio.masterVolume", null));
            assertEquals("0.5", view.get("audio.musicVolume", null));
            assertEquals("0.6", view.get("audio.effectsVolume", null));
            assertEquals("false", view.get("audio.shuffle", null));
        }
        assertArrayEquals(clientBefore, Files.readAllBytes(clientFile), "A's remote save must not touch another INI.");

        RemoteAudio unconfigured = endpoint(Runnable::run);
        try {
            long revision = unconfigured.query("state", json.createObjectNode()).join().path("revision").asLong();
            assertThrows(CompletionException.class,
                    () -> unconfigured.execute("masterVolume", payload(revision, 0.1)).join());
            assertEquals(0.4, engine.masterVolume(), 1e-9, "Without A's settings nothing may change unpersisted.");
        } finally {
            unconfigured.close();
        }
    }

    @Test void hostSettingsEditedDuringRemoteSaveArePreservedAndReportedAsConflict() throws Exception {
        ArrayDeque<Runnable> io = new ArrayDeque<>();
        host.close();
        host = endpoint(io::add);
        host.configureSettings(hostSettings);
        CompletableFuture<JsonNode> pending = host.execute("masterVolume", payload(revision(), 0.3));
        assertFalse(pending.isDone(), "Settings IO must run on the worker, not the application thread.");
        assertEquals(0.3, engine.masterVolume(), 1e-9);
        hostSettings.put("audio.masterVolume", "0.9");
        while (!io.isEmpty()) io.poll().run();
        CompletionException failure = assertThrows(CompletionException.class, pending::join);
        assertTrue(failure.getCause().getMessage().contains("changed on A"), failure.getCause().getMessage());
        assertEquals("0.9", hostSettings.get("audio.masterVolume", null));
        assertEquals("0.9", new AppSettings(hostSettingsFile).get("audio.masterVolume", null));
        assertEquals(0.9, engine.masterVolume(), 1e-9, "A's engine must follow A's preserved value.");
    }

    @Test void rapidRemoteVolumeChangesSerializeAndPersistTheLatestValue() throws Exception {
        ArrayDeque<Runnable> io = new ArrayDeque<>();
        host.close();
        host = endpoint(io::add);
        host.configureSettings(hostSettings);
        CompletableFuture<JsonNode> first = host.execute("masterVolume", payload(revision(), 0.3));
        CompletableFuture<JsonNode> second = host.execute("masterVolume", payload(revision(), 0.2));
        while (!io.isEmpty()) io.poll().run();
        first.join();
        second.join();
        assertEquals("0.2", hostSettings.get("audio.masterVolume", null));
        assertEquals("0.2", new AppSettings(hostSettingsFile).get("audio.masterVolume", null));
        assertEquals(0.2, engine.masterVolume(), 1e-9);
    }

    @Test void audioFolderChangeIsReportedAsRestartPendingWithoutSwitchingTheLiveLibrary() {
        assertFalse(state().path("restartRequired").asBoolean());
        hostSettings.put("audio.folder", directory.resolve("another-audio").toString());
        JsonNode state = state();
        assertTrue(state.path("restartRequired").asBoolean());
        assertFalse(state.toString().contains("another-audio"), "Host folder paths are never replicated.");
        assertTrue(state.path("enabled").asBoolean());
    }
}
