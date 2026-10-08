package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.api.DmControlApi;
import dmmt.audio.AudioCategory;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import dmmt.audio.FakeAudioOutput;
import dmmt.audio.TestAudioFiles;
import dmmt.service.AppSettings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status bar transport group, the audio overlay and their local API endpoints (3.35.6). */
class AudioControlsTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    private AudioLibraryService library() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        AudioCategory combat = library.createCategory("Combat", AudioCategory.DEFAULT_COLOR, AudioCategory.DEFAULT_ICON);
        library.createCategory("Christmas Eve", AudioCategory.DEFAULT_COLOR, AudioCategory.DEFAULT_ICON);
        library.importFile(file("battle.wav"), AudioKind.MUSIC, combat.getId());
        library.importFile(file("rain.wav"), AudioKind.EFFECT, null);
        return library;
    }

    private Path file(String name) throws IOException {
        Path file = dir.resolve(name);
        TestAudioFiles.writeWav(file, 500, 8000);
        return file;
    }

    private static String categoryId(AudioLibraryService library, String name) {
        return library.categoryByName(name).orElseThrow().getId();
    }

    private AudioControls controls(AudioLibraryService library) {
        return new AudioControls(new AppSettings(dir.resolve("settings.ini")), () -> null, library,
                new FakeAudioOutput());
    }

    @Test
    void everyCategoryAndEffectGetsItsOwnEndpoint() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            Map<String, Node> api = audio.apiControls();

            assertTrue(api.containsKey("audio.previous"));
            assertTrue(api.containsKey("audio.play"));
            assertTrue(api.containsKey("audio.next"));
            assertTrue(api.containsKey("audio.overlay"));
            assertTrue(api.containsKey("audio.musicPlay"));
            assertTrue(api.containsKey("audio.masterVolume"));
            assertFalse(api.containsKey("audio.assign"));
            assertTrue(api.containsKey("audio.category." + categoryId(library, "Combat")), api.keySet().toString());
            assertTrue(api.containsKey("audio.category." + categoryId(library, "Christmas Eve")),
                    api.keySet().toString());
            assertTrue(api.containsKey("audio.effect." + library.effects().get(0).getId()),
                    api.keySet().toString());

            DmControlApi controlApi = new DmControlApi(new ControlVisibility());
            controlApi.replaceGroup("audio", api);
            assertTrue(controlApi.describe().stream()
                    .anyMatch(entry -> ("audio.category." + categoryId(library, "Combat")).equals(entry.get("id"))));
            audio.shutdown();
        });
    }

    @Test
    void theCategoryEndpointStartsAndStopsThatCategory() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            String combatId = categoryId(library, "Combat");
            ToggleButton combat = (ToggleButton) audio.apiControls().get("audio.category." + combatId);

            combat.fire();
            assertEquals(combatId, audio.engine().categoryId());
            assertTrue(audio.engine().isCategoryActive(combatId));

            ((ToggleButton) audio.apiControls().get("audio.category." + combatId)).fire();
            assertFalse(audio.engine().isCategoryActive(combatId), "firing it again stops the category");
            audio.shutdown();
        });
    }

    @Test
    void theEffectEndpointTogglesThatLoop() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            AudioTrack rain = library.effects().get(0);
            ToggleButton button = (ToggleButton) audio.apiControls().get("audio.effect." + rain.getId());

            button.fire();
            assertTrue(audio.engine().isEffectActive(rain.getId()));
            button.fire();
            assertFalse(audio.engine().isEffectActive(rain.getId()));
            audio.shutdown();
        });
    }

    @Test
    void hiddenEntriesLeaveTheOverlayButKeepTheirEndpoint() throws Exception {
        AudioLibraryService library = library();
        library.setCategoryHidden(library.categoryByName("Christmas Eve").orElseThrow().getId(), true);
        library.setTrackHidden(library.effects().get(0).getId(), true);
        onFx(() -> {
            AudioControls audio = controls(library);
            audio.refreshLibraryChoices();

            assertEquals(library.visibleCategories().size(), audio.overlay().categoryButtons().size(),
                    "the hidden category has no ring button");
            assertTrue(library.categories().size() > library.visibleCategories().size());
            assertTrue(audio.overlay().effectButtons().isEmpty());

            Map<String, Node> api = audio.apiControls();
            AudioTrack rain = library.effects().get(0);
            assertNotNull(api.get("audio.category." + categoryId(library, "Christmas Eve")));
            assertNotNull(api.get("audio.effect." + rain.getId()));

            ((ToggleButton) api.get("audio.effect." + rain.getId())).fire();
            assertTrue(audio.engine().isEffectActive(rain.getId()), "a hidden effect still plays over the API");
            audio.shutdown();
        });
    }

    @Test
    void theOverlayOpensAndClosesWithTheStatusBarButton() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            ToggleButton overlayButton = (ToggleButton) audio.apiControls().get("audio.overlay");

            assertFalse(audio.overlay().isOpen());
            assertFalse(audio.overlayLayer().isVisible());

            overlayButton.fire();
            assertTrue(audio.overlay().isOpen());
            assertTrue(audio.overlayLayer().isVisible());

            assertTrue(audio.closeOverlay());
            assertFalse(audio.overlay().isOpen());
            assertFalse(audio.closeOverlay(), "closing a closed overlay does nothing");
            audio.shutdown();
        });
    }

    @Test
    void theRingButtonsOnScreenAreTheRegisteredApiControlsWithTheirMenus() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            DmControlApi controlApi = new DmControlApi(new ControlVisibility());
            audio.setApiControlsChangedHandler(() -> controlApi.replaceGroup("audio", audio.apiControls()));
            controlApi.replaceGroup("audio", audio.apiControls());
            controlApi.setUrlOptionsVisible(true);
            controlApi.attachUrlMenus(() -> "http://127.0.0.1:8080", ignored -> {});

            // Opening the overlay must not swap the buttons out from under the API.
            audio.overlay().show();

            String combatId = categoryId(library, "Combat");
            ToggleButton onScreen = audio.overlay().categoryButtons().get(combatId);
            assertNotNull(onScreen);
            assertSame(onScreen, audio.apiControls().get("audio.category." + combatId),
                    "the endpoint must point at the button that is actually on screen");
            assertNotNull(onScreen.getContextMenu(), "ring buttons need the standard right-click menu");
            assertTrue(onScreen.getContextMenu().getItems().stream()
                    .anyMatch(item -> "Copy API URL".equals(item.getText())));
            assertTrue(onScreen.getContextMenu().getItems().stream()
                    .anyMatch(item -> "Copy key image URL".equals(item.getText())));

            // Discovery must name the category, not repeat its UUID, so a control device can offer a pick list.
            var described = controlApi.describe(List.of("audio.category." + combatId));
            assertEquals("Combat", described.getFirst().get("label"));
            ToggleButton effect = audio.overlay().effectButtons().get(library.effects().get(0).getId());
            assertNotNull(effect.getContextMenu());
            assertEquals(onScreen.getContextMenu().getItems().size(), effect.getContextMenu().getItems().size());

            // A library change rebuilds the rings, and the new buttons must be registered again.
            audio.refreshLibraryChoices();
            ToggleButton rebuilt = audio.overlay().categoryButtons().get(combatId);
            assertNotSame(onScreen, rebuilt);
            assertSame(rebuilt, audio.apiControls().get("audio.category." + combatId));
            assertNotNull(rebuilt.getContextMenu());
            assertTrue(rebuilt.getContextMenu().getItems().stream()
                    .anyMatch(item -> "Copy key image URL".equals(item.getText())));
            audio.closeOverlay();
            audio.shutdown();
        });
    }

    @Test
    void endpointsUseTheLibraryIdSoRenamingDoesNotBreakThem() throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        String rainId = library.effects().get(0).getId();
        onFx(() -> {
            AudioControls audio = controls(library);
            assertTrue(audio.apiControls().containsKey("audio.category." + combatId));

            library.renameCategory(combatId, "Boss fight");
            library.renameTrack(rainId, "Heavy rain");
            audio.refreshLibraryChoices();

            assertTrue(audio.apiControls().containsKey("audio.category." + combatId),
                    "the endpoint follows the id, not the name");
            assertTrue(audio.apiControls().containsKey("audio.effect." + rainId));
            assertFalse(audio.apiControls().containsKey("audio.category.combat"),
                    "names are not used as endpoint ids any more");
            audio.shutdown();
        });
    }

    private static void onFx(ThrowingRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
