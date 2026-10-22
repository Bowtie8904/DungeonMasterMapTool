package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.FxTestSupport;
import dmmt.audio.*;
import dmmt.remote.RemoteAssetStore;
import dmmt.remote.RemoteAudio;
import dmmt.remote.RemoteOperations;
import dmmt.service.AppSettings;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAudioControlsTest {
    private final Path directory = Path.of("target", "remote-audio-ui-tests", UUID.randomUUID().toString()).toAbsolutePath();

    @BeforeAll static void startFx() throws Exception { FxTestSupport.startJavaFx(); }

    @AfterEach void cleanup() throws Exception {
        dmmt.service.Tuning.reset();
        if (Files.exists(directory)) try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void sameTransportOverlayAndApiUseAcceptedHostLibraryWithoutPlayingOrPersistingOnClient() throws Exception {
        Files.createDirectories(directory);
        AudioLibraryService hostLibrary = new AudioLibraryService(directory.resolve("host"));
        AudioCategory hostCategory = hostLibrary.createCategory("Host forest", null, null);
        Path source = directory.resolve("music.wav");
        TestAudioFiles.writeWav(source, 500, 8000);
        AudioTrack hostTrack = hostLibrary.importFile(source, AudioKind.MUSIC, hostCategory.getId());
        AudioTrack hostEffect = hostLibrary.importFile(source, AudioKind.EFFECT, null);
        AudioLibraryService clientLibrary = new AudioLibraryService(directory.resolve("client"));
        AudioCategory retained = clientLibrary.createCategory("Retained local library", null, null);
        byte[] before = Files.readAllBytes(clientLibrary.root().resolve("library.json"));
        RemoteAssetStore assets = new RemoteAssetStore(directory.resolve("assets"), 10_000_000, 40_000_000, 10_000_000);
        onFx(() -> {
            FakeAudioOutput hostOutput = new FakeAudioOutput();
            AudioEngine hostEngine = new AudioEngine(hostLibrary, hostOutput);
            FakeAudioOutput clientOutput = new FakeAudioOutput();
            AudioControls controls = new AudioControls(new AppSettings(directory.resolve("client-settings.ini")),
                    () -> null, clientLibrary, clientOutput);
            RemoteAudio host = new RemoteAudio(() -> hostLibrary, () -> hostEngine, assets,
                    directory.resolve("cache"), Runnable::run, Runnable::run, ignored -> { });
            AppSettings hostSettings = new AppSettings(directory.resolve("host-settings.ini"));
            hostSettings.put("audio.folder", hostLibrary.root().toString());
            host.configureSettings(hostSettings);
            RemoteOperations.Connection connection = loopback(host, assets);
            try {
                byte[] clientSettings = Files.readAllBytes(directory.resolve("client-settings.ini"));
                controls.beginRemote(connection);
                assertTrue(controls.overlay().categoryButtons().containsKey(hostCategory.getId()));
                assertTrue(clientLibrary.category(retained.getId()).isEmpty());
                var api = controls.apiControls();
                ((Button) api.get("audio.track." + hostTrack.getId())).fire();
                assertEquals(hostTrack.getId(), hostEngine.currentTrack().orElseThrow().getId());
                assertEquals(hostTrack.getId(), controls.engine().currentTrack().orElseThrow().getId());
                ((ToggleButton) api.get("audio.effect." + hostEffect.getId())).fire();
                assertTrue(hostEngine.isEffectActive(hostEffect.getId()));
                ((ButtonBase) controls.apiControls().get("audio.play")).fire();
                assertTrue(hostEngine.isMusicPaused());
                ((Slider) controls.apiControls().get("audio.masterVolume")).setValue(0.35);
                assertEquals(0.35, hostEngine.masterVolume(), 1e-9);
                assertEquals("0.35", hostSettings.get("audio.masterVolume", null));
                assertArrayEquals(clientSettings, Files.readAllBytes(directory.resolve("client-settings.ini")),
                        "B's own INI keeps its values while it drives A's audio.");
                assertEquals(0, clientOutput.openedCount());
                assertTrue(hostOutput.openedCount() > 0);
                assertArrayEquals(before, Files.readAllBytes(clientLibrary.root().resolve("library.json")));
                controls.endRemote();
                assertTrue(clientLibrary.category(retained.getId()).isPresent());
                assertTrue(controls.engine().currentTrack().isEmpty());
                assertFalse(controls.engine().hasRunningEffects());
                assertArrayEquals(before, Files.readAllBytes(clientLibrary.root().resolve("library.json")));
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            } finally {
                controls.shutdown();
                host.close();
                hostEngine.shutdown();
            }
        });
        hostLibrary.stopPreparation();
        clientLibrary.stopPreparation();
    }

    private RemoteOperations.Connection loopback(RemoteAudio host, RemoteAssetStore assets) {
        return new RemoteOperations.Connection() {
            @Override public boolean connected() { return true; }
            @Override public CompletableFuture<JsonNode> query(String domain, String action, JsonNode payload) {
                return host.query(action, payload);
            }
            @Override public CompletableFuture<JsonNode> command(String domain, String action, JsonNode payload) {
                return host.execute(action, payload);
            }
            @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file) {
                try { return CompletableFuture.completedFuture(assets.publishRequiredAsset(file)); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
            @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset) {
                try { return CompletableFuture.completedFuture(assets.materialize(asset.sha256(), directory.resolve("downloads"), "peaks")); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
        };
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
