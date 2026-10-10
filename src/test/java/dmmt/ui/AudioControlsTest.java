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
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void statusBarNamesTheCurrentTrackBeforeTheButtonsAndTracksPlaybackAndRenames() throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        AudioTrack first = library.musicOf(combatId).getFirst();
        AudioTrack second = library.importFile(file("second.wav"), AudioKind.MUSIC, combatId);
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                var children = audio.statusBarGroup().getChildrenUnmodifiable();
                ScrollingLabel readout = (ScrollingLabel) children.getFirst();
                assertSame(audio.apiControls().get("audio.previous"), children.get(1));
                assertEquals("", readout.getText());
                assertEquals(180, readout.getMinWidth());
                assertEquals(180, readout.getPrefWidth());
                assertEquals(180, readout.getMaxWidth());

                audio.engine().setShuffle(false);
                audio.engine().playTrack(first.getId());
                assertEquals(first.getName(), readout.getText());
                audio.engine().toggleAll();
                assertTrue(audio.engine().isMusicPaused());
                assertEquals(first.getName(), readout.getText());
                audio.engine().toggleAll();
                audio.engine().next();
                assertEquals(second.getName(), readout.getText());
                library.renameTrack(second.getId(), "Renamed current song");
                audio.refreshLibraryChoices();
                assertEquals("Renamed current song", readout.getText());
                audio.engine().stopMusic();
                assertEquals("", readout.getText());
                audio.engine().setEffectActive(library.effects().getFirst().getId(), true);
                assertEquals("", readout.getText(), "effects alone must not show a music name");
            } finally {
                audio.shutdown();
            }
        });
    }

    @Test
    void iconToggleSelectionUsesAccentHighlightExceptAudioTransport() throws Exception {
        onFx(() -> {
            ToggleButton regular = new ToggleButton();
            regular.getStyleClass().add("icon-toggle");
            ToggleButton transport = new ToggleButton();
            transport.getStyleClass().addAll("icon-toggle", "audio-transport-toggle");
            StackPane root = new StackPane(regular, transport);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Icons.STYLESHEET);
            root.applyCss();

            var regularBackground = regular.getBackground();
            regular.setSelected(true);
            root.applyCss();
            assertNotEquals(regularBackground, regular.getBackground());
            assertNotNull(regular.getEffect());

            var transportBackground = transport.getBackground();
            transport.setSelected(true);
            root.applyCss();
            assertEquals(transportBackground, transport.getBackground());
            assertNull(transport.getEffect());
        });
    }

    @Test
    void musicLoopHasAVisibleHighlightOnlyWhenEnabled() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                StackPane root = new StackPane(audio.overlayLayer());
                Scene scene = new Scene(root);
                scene.getStylesheets().add(Icons.STYLESHEET);
                audio.overlay().show();
                ToggleButton loop = (ToggleButton) audio.apiControls().get("audio.musicLoop");
                FontIcon icon = (FontIcon) loop.getGraphic();

                for (String state : List.of("normal", "hover", "pressed")) {
                    loop.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), state.equals("hover"));
                    loop.pseudoClassStateChanged(PseudoClass.getPseudoClass("pressed"), state.equals("pressed"));
                    audio.engine().setMusicLoop(false);
                    root.applyCss();
                    var inactiveBackground = loop.getBackground();
                    var inactiveIconColor = icon.getIconColor();
                    var inactiveEffect = loop.getEffect();
                    audio.engine().setMusicLoop(true);
                    root.applyCss();
                    assertNotEquals(inactiveBackground, loop.getBackground(), state);
                    assertNotEquals(inactiveIconColor, icon.getIconColor(), state);
                    assertNotEquals(inactiveEffect, loop.getEffect(), state);
                    assertNotNull(loop.getEffect(), state);
                    audio.engine().setMusicLoop(false);
                    root.applyCss();
                    assertEquals(inactiveBackground, loop.getBackground(), state);
                    assertEquals(inactiveIconColor, icon.getIconColor(), state);
                    assertEquals(inactiveEffect, loop.getEffect(), state);
                }
            } finally {
                audio.shutdown();
            }
        });
    }

    @Test
    void effectsPauseMatchesMusicButtonStylingWithoutChangingToggleBehavior() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                StackPane root = new StackPane(audio.overlayLayer());
                Scene scene = new Scene(root);
                scene.getStylesheets().add(Icons.STYLESHEET);
                audio.overlay().show();
                audio.engine().setEffectActive(library.effects().getFirst().getId(), true);
                Button music = (Button) audio.apiControls().get("audio.musicPlay");
                ToggleButton effects = (ToggleButton) audio.apiControls().get("audio.effectsPause");

                for (boolean paused : List.of(false, true)) {
                    if (effects.isSelected() != paused) {
                        effects.fire();
                    }
                    assertEquals(paused, audio.engine().areEffectsPaused());
                    assertEquals(paused, effects.isSelected());
                    for (String state : List.of("normal", "hover", "pressed")) {
                        for (Node button : List.of(music, effects)) {
                            button.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"),
                                    state.equals("hover"));
                            button.pseudoClassStateChanged(PseudoClass.getPseudoClass("pressed"),
                                    state.equals("pressed"));
                        }
                        root.applyCss();
                        assertEquals(music.getBackground(), effects.getBackground(), state);
                        assertEquals(music.getEffect(), effects.getEffect(), state);
                        assertEquals(((FontIcon) music.getGraphic()).getIconColor(),
                                ((FontIcon) effects.getGraphic()).getIconColor(), state);
                    }
                }
                effects.fire();
                assertFalse(audio.engine().areEffectsPaused());
            } finally {
                audio.shutdown();
            }
        });
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
    void playEndpointDoesNotReselectAStoppedCategory() throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        String rainId = library.effects().getFirst().getId();
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                DmControlApi api = new DmControlApi(new ControlVisibility());
                api.replaceGroup("audio", audio.apiControls());
                String categoryControl = "audio.category." + combatId;
                api.execute(categoryControl, Map.of());
                api.execute("audio.effect." + rainId, Map.of());
                api.execute(categoryControl, Map.of());
                api.execute("audio.play", Map.of());
                assertTrue(audio.engine().areEffectsPaused());
                api.execute("audio.play", Map.of());
                assertFalse(audio.engine().isCategoryActive(combatId));
                assertFalse(((ToggleButton) audio.apiControls().get(categoryControl)).isSelected());
                assertTrue(audio.engine().currentTrack().isEmpty());
                assertTrue(audio.engine().isEffectActive(rainId));
                assertFalse(audio.engine().areEffectsPaused());
            } finally {
                audio.shutdown();
            }
        });
    }

    @Test
    void musicLoopEndpointStaysSynchronizedWhileClosedAndIsNotSaved() throws Exception {
        AudioLibraryService library = library();
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                DmControlApi api = new DmControlApi(new ControlVisibility());
                api.replaceGroup("audio", audio.apiControls());
                List<String> ids = List.of("audio.musicLoop");
                assertEquals(false, api.describe(ids).getFirst().get("value"));
                api.execute("audio.musicLoop", Map.of());
                assertTrue(audio.engine().isMusicLoop());
                assertEquals(true, api.describe(ids).getFirst().get("value"));
                audio.applySettings();
                assertTrue(audio.engine().isMusicLoop(), "settings refresh must not reset session state");
                audio.engine().setMusicLoop(false);
                assertEquals(false, api.describe(ids).getFirst().get("value"));
                audio.engine().setMusicLoop(true);
                audio.overlay().show();
                assertTrue(((ToggleButton) audio.apiControls().get("audio.musicLoop")).isSelected());
                audio.closeOverlay();
                AudioControls restarted = controls(library);
                try {
                    assertFalse(restarted.engine().isMusicLoop());
                } finally {
                    restarted.shutdown();
                }
            } finally {
                audio.shutdown();
            }
        });
    }

    @Test
    void musicFileEndpointsStartHiddenCategoriesAndFollowRenameMoveAndKindChanges() throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        AudioTrack track = library.musicOf(combatId).getFirst();
        library.setCategoryHidden(combatId, true);
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                DmControlApi api = new DmControlApi(new ControlVisibility());
                audio.setApiControlsChangedHandler(() -> api.replaceGroup("audio", audio.apiControls()));
                api.replaceGroup("audio", audio.apiControls());
                String id = "audio.track." + track.getId();
                api.execute(id, Map.of());
                assertEquals(track.getId(), audio.engine().currentTrack().orElseThrow().getId());
                assertEquals(combatId, audio.engine().categoryId());
                library.renameTrack(track.getId(), "Boss fight");
                String other = categoryId(library, "Christmas Eve");
                library.moveTrack(track.getId(), other);
                audio.refreshLibraryChoices();
                assertEquals("Boss fight", api.describe(List.of(id)).getFirst().get("label"));
                api.execute(id, Map.of());
                assertEquals(other, audio.engine().categoryId());
                library.changeKind(track.getId(), AudioKind.EFFECT, null);
                audio.refreshLibraryChoices();
                assertTrue(api.describe(List.of(id)).isEmpty());
                assertTrue(audio.apiControls().containsKey("audio.effect." + track.getId()));
                library.changeKind(track.getId(), AudioKind.MUSIC, combatId);
                audio.refreshLibraryChoices();
                assertFalse(api.describe(List.of(id)).isEmpty());
                library.deleteTrack(track.getId());
                audio.refreshLibraryChoices();
                assertTrue(api.describe(List.of(id)).isEmpty());
            } finally {
                audio.shutdown();
            }
        });
    }

    @Test
    void musicFileContextMenuStartsItsCategoryAtTheSelectedSong() throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        AudioTrack second = library.importFile(file("second.wav"), AudioKind.MUSIC, combatId);
        onFx(() -> {
            AudioControls audio = controls(library);
            AudioLibraryWindow.show(null, library, audio.engine(), new AppSettings(dir.resolve("settings.ini")),
                    audio::refreshLibraryChoices);
            Stage stage = (Stage) Window.getWindows().stream()
                    .filter(window -> window instanceof Stage s && s.getTitle().equals("Audio library"))
                    .findFirst().orElseThrow();
            try {
                ListView<?> categories = stage.getScene().getRoot().lookupAll(".list-view").stream()
                        .filter(node -> node instanceof ListView<?> list
                                && list.getItems().size() == library.categories().size())
                        .map(node -> (ListView<?>) node).findFirst().orElseThrow();
                categories.getSelectionModel().select(library.categories().indexOf(
                        library.category(combatId).orElseThrow()));
                TableView<?> table = (TableView<?>) stage.getScene().lookup(".table-view");
                MenuItem play = table.getContextMenu().getItems().stream()
                        .filter(item -> "Play".equals(item.getText())).findFirst().orElseThrow();
                table.getSelectionModel().select(table.getItems().indexOf(second));
                table.getContextMenu().getOnShowing().handle(new javafx.stage.WindowEvent(
                        table.getContextMenu(), javafx.stage.WindowEvent.WINDOW_SHOWING));
                assertFalse(play.isDisable());
                play.fire();
                assertEquals(second.getId(), audio.engine().currentTrack().orElseThrow().getId());
                assertEquals(combatId, audio.engine().categoryId());
                table.getSelectionModel().select(0);
                table.getContextMenu().getOnShowing().handle(new javafx.stage.WindowEvent(
                        table.getContextMenu(), javafx.stage.WindowEvent.WINDOW_SHOWING));
                assertTrue(play.isDisable(), "playing requires one selected file");
            } finally {
                stage.close();
                audio.shutdown();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void categoryHighlightsFollowPlaybackWithoutOpeningTheOverlay(boolean hidden) throws Exception {
        AudioLibraryService library = library();
        String combatId = categoryId(library, "Combat");
        String christmasId = categoryId(library, "Christmas Eve");
        library.importFile(file("christmas.wav"), AudioKind.MUSIC, christmasId);
        library.setCategoryHidden(combatId, hidden);
        library.setCategoryHidden(christmasId, hidden);
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                DmControlApi api = new DmControlApi(new ControlVisibility());
                api.replaceGroup("audio", audio.apiControls());
                String combat = "audio.category." + combatId;
                String christmas = "audio.category." + christmasId;
                List<String> ids = List.of(combat, christmas);

                api.execute(combat, Map.of());
                assertEquals(List.of(true, false), api.describe(ids).stream().map(c -> c.get("value")).toList());
                api.execute(christmas, Map.of());
                assertEquals(christmasId, audio.engine().categoryId());
                assertEquals(List.of(false, true), api.describe(ids).stream().map(c -> c.get("value")).toList());

                audio.engine().toggleMusic();
                assertEquals(List.of(false, true), api.describe(ids).stream().map(c -> c.get("value")).toList(),
                        "pausing keeps the loaded category highlighted");
                api.execute(christmas, Map.of());
                assertEquals(List.of(false, false), api.describe(ids).stream().map(c -> c.get("value")).toList());

                audio.engine().playCategory(combatId);
                assertEquals(List.of(true, false), api.describe(ids).stream().map(c -> c.get("value")).toList());
                audio.engine().stopMusic();
                assertEquals(List.of(false, false), api.describe(ids).stream().map(c -> c.get("value")).toList());
                assertFalse(audio.overlay().isOpen());
                assertFalse(audio.overlayLayer().isVisible());
            } finally {
                audio.shutdown();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void effectHighlightsFollowPlaybackWithoutOpeningTheOverlay(boolean hidden) throws Exception {
        AudioLibraryService library = library();
        String rainId = library.effects().getFirst().getId();
        library.setTrackHidden(rainId, hidden);
        onFx(() -> {
            AudioControls audio = controls(library);
            try {
                DmControlApi api = new DmControlApi(new ControlVisibility());
                api.replaceGroup("audio", audio.apiControls());
                String rain = "audio.effect." + rainId;
                List<String> ids = List.of(rain);

                api.execute(rain, Map.of());
                assertEquals(true, api.describe(ids).getFirst().get("value"));
                audio.engine().stopAllEffects();
                assertEquals(false, api.describe(ids).getFirst().get("value"));
                audio.engine().setEffectActive(rainId, true);
                assertEquals(true, api.describe(ids).getFirst().get("value"));
                api.execute(rain, Map.of());
                assertEquals(false, api.describe(ids).getFirst().get("value"));
                assertFalse(audio.overlay().isOpen());
            } finally {
                audio.shutdown();
            }
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
