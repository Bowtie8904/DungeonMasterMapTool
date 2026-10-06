package dmmt.ui;

import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;
import dmmt.service.AppSettings;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MultiLevelService;
import dmmt.service.ProjectService;
import dmmt.service.RecentMaps;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.control.TextField;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MapBrowserTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void multipleMapsEnableOnlyTagAddDuplicateAndDeleteForEitherMapKind() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            for (TreeItem<Entry> map : fixture.tree.getRoot().getChildren().subList(0, 2)) {
                ContextMenu menu = fixture.browser.buildMenu(map.getValue());
                for (MenuItem item : menu.getItems()) {
                    if (!(item instanceof SeparatorMenuItem)) {
                        assertEquals(!List.of("Add tags…", "Duplicate", "Delete…").contains(item.getText()), item.isDisable(),
                                item.getText());
                    }
                }
            }
        });
    }

    @Test
    void singleMapDisablesMultilevelCreationOnly() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            for (int index = 0; index < 2; index++) {
                fixture.select(index);
                ContextMenu menu = fixture.browser.buildMenu(fixture.entry(index));
                for (MenuItem item : menu.getItems()) {
                    assertEquals(item.getText() != null && item.getText().equals("Combine into multilevel map"),
                            item.isDisable());
                }
            }
        });
    }

    @Test
    void multipleOrdinaryMapsEnableMultilevelCreationAndPassTheEntireSelection() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            Path path = dir.resolve("Second");
            Entry second = new Entry(MapLibraryService.Kind.MAP, "Second", path,
                    path.resolve("Second.dmmap"), List.of());
            fixture.tree.getRoot().getChildren().add(new TreeItem<>(second));
            fixture.select(0, 3);
            for (Entry target : List.of(fixture.entry(0), second)) {
                ContextMenu menu = fixture.browser.buildMenu(target);
                for (MenuItem item : menu.getItems()) {
                    if (!(item instanceof SeparatorMenuItem)) {
                        assertEquals(!List.of("Add tags…", "Duplicate", "Delete…", "Combine into multilevel map")
                                .contains(item.getText()), item.isDisable(), item.getText());
                    }
                }
                menuItem(menu, "Combine into multilevel map").fire();
                assertEquals(List.of(fixture.entry(0), second), fixture.host.merged);
            }
        });
    }

    @Test
    void mixedSelectionDisablesAllActionsIncludingFolderDelete() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 2);
            for (int index : new int[]{0, 2}) {
                assertTrue(fixture.browser.buildMenu(fixture.entry(index)).getItems().stream()
                        .allMatch(MenuItem::isDisable));
            }
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.DELETE));
            assertTrue(fixture.library.deleted.isEmpty());
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void tagMenuPassesSingleAndMultipleSelectionsToHost() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0);
            menuItem(fixture.browser.buildMenu(fixture.entry(0)), "Manage tags…").fire();
            assertEquals(List.of(fixture.entry(0)), fixture.host.tagged);
            fixture.select(0, 1);
            menuItem(fixture.browser.buildMenu(fixture.entry(1)), "Add tags…").fire();
            assertEquals(List.of(fixture.entry(0), fixture.entry(1)), fixture.host.tagged);
        });
    }

    @Test
    void tagDialogSuggestsWithoutAutocompletingAndPreventsCaseInsensitiveDuplicates() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Haven", 1, false,
                    List.of("Forest"), List.of("Tavern", "TAVERN", "Castle"));
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            TextField input = (TextField) pane.lookup("#map-tag-input");
            Button add = (Button) pane.lookup("#map-tag-add");
            dialog.show();
            try {
                input.requestFocus();
                input.setText("tav");
                assertEquals("TAV", input.getText());
                ListView<?> suggested = (ListView<?>) Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .map(window -> window.getScene().lookup("#map-tag-suggestions"))
                        .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertEquals(List.of("TAVERN"), suggested.getItems());
                suggested.getSelectionModel().select(0);
                suggested.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10,
                        MouseButton.PRIMARY, 1, false, false, false, false,
                        false, false, false, false, false, true, null));
                assertEquals("TAVERN", input.getText());
                add.fire();
                input.setText(" TAVERN ");
                add.fire();
                assertEquals(List.of("TAVERN"), tagResult(dialog).additions());
                assertEquals(List.of(), tagResult(dialog).removals());
                assertTrue(((Label) pane.lookup(".error-label")).getText().contains("already"));
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    void overlaySuggestionsLimitToFiveNavigateWithoutCompletingAndDismissCleanly() throws Exception {
        onFx(() -> {
            TextField input = new TextField("T");
            Stage stage = new Stage();
            stage.setScene(new Scene(new javafx.scene.layout.VBox(input), 400, 300));
            TagSuggestions popup = new TagSuggestions(input);
            List<String> matches = List.of("TOWN", "TREE", "TOWER", "TRAIL", "TAVERN", "TEMPLE");
            try {
                stage.show();
                input.requestFocus();
                popup.refresh(matches);
                assertTrue(popup.isShowing());
                ListView<?> list = (ListView<?>) popup.getContent().getFirst();
                for (int count = 1; count <= 5; count++) {
                    popup.refresh(matches.subList(0, count));
                    list.layout();
                    assertEquals(count * list.getFixedCellSize(),
                            list.getHeight() - list.snappedTopInset() - list.snappedBottomInset(), 0.01);
                    assertTrue(list.lookupAll(".scroll-bar").stream()
                            .filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
                            .filter(bar -> bar.getOrientation() == Orientation.VERTICAL)
                            .noneMatch(ScrollBar::isVisible), "Unexpected scrollbar for " + count + " suggestions");
                }
                popup.refresh(matches);
                assertEquals(matches.subList(0, 5), list.getItems());
                assertEquals(input.localToScreen(input.getBoundsInLocal()).getMaxY() + 4, popup.getY(), 1);
                assertTrue(list.getSelectionModel().isEmpty());
                input.fireEvent(key(KeyCode.DOWN));
                assertEquals(0, list.getSelectionModel().getSelectedIndex());
                input.fireEvent(key(KeyCode.DOWN));
                assertEquals(1, list.getSelectionModel().getSelectedIndex());
                input.fireEvent(key(KeyCode.UP));
                assertEquals(0, list.getSelectionModel().getSelectedIndex());
                assertEquals("T", input.getText());
                input.fireEvent(key(KeyCode.ENTER));
                assertEquals("TOWN", input.getText());
                assertFalse(popup.isShowing());
                popup.refresh(matches);
                input.fireEvent(key(KeyCode.ESCAPE));
                assertFalse(popup.isShowing());
                popup.refresh(matches);
                popup.refresh(List.of());
                assertFalse(popup.isShowing());
                assertEquals("TOWN", input.getText());
            } finally {
                popup.hide();
                stage.close();
            }
        });
    }

    @Test
    void suggestionsRankByFitBeforeLimitingToFiveAndRerankAsInputChanges() throws Exception {
        onFx(() -> {
            TextField input = new TextField("tav");
            TagSuggestions popup = new TagSuggestions(input);
            List<String> matches = List.of("OLD TAVERN", "A TAVERN", "TAVERN DISTRICT", "TAVERNS",
                    "TAVERN", "TAV", "TAVA", "TAVB");
            popup.refresh(matches);
            ListView<?> list = (ListView<?>) popup.getContent().getFirst();
            assertEquals(List.of("TAV", "TAVA", "TAVB", "TAVERN", "TAVERNS"), list.getItems());
            input.setText("tavern");
            popup.refresh(matches.stream().filter(tag -> tag.contains("TAVERN")).toList());
            assertEquals(List.of("TAVERN", "TAVERNS", "TAVERN DISTRICT", "A TAVERN", "OLD TAVERN"),
                    list.getItems());
            assertEquals("tavern", input.getText());
        });
    }

    @Test
    void tagRemovalDoesNotAddPendingInputAndCancelDiscardsEdits() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Tower", 1, true,
                    List.of("Tavern", "Forest"), List.of());
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            TextField input = (TextField) pane.lookup("#map-tag-input");
            input.setText("Castle");
            Button remove = (Button) pane.lookupAll(".map-tag-chip .button").stream()
                    .filter(node -> "Remove TAVERN".equals(node.getAccessibleText())).findFirst().orElseThrow();
            remove.fire();
            assertEquals("CASTLE", input.getText());
            assertEquals(List.of("TAVERN"), tagResult(dialog).removals());
            assertEquals(List.of(), tagResult(dialog).additions());
            assertNull(dialog.getResultConverter().call(ButtonType.CANCEL));
        });
    }

    @Test
    void bulkTagDialogCannotRemoveExistingTagsAndApplyAddsPendingInput() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Haven", 2, false,
                    List.of("Forest"), List.of("Tavern"));
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            assertTrue(pane.lookupAll(".map-tag-chip").isEmpty());
            TextField input = (TextField) pane.lookup("#map-tag-input");
            input.setText(" Tavern ");
            ButtonType apply = pane.getButtonTypes().stream()
                    .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow();
            ((Button) pane.lookupButton(apply)).fire();
            assertEquals("", input.getText());
            assertEquals(List.of("TAVERN"), tagResult(dialog).additions());
            assertEquals(List.of(), tagResult(dialog).removals());
        });
    }

    private static MapTagsDialog.Result tagResult(Dialog<MapTagsDialog.Result> dialog) {
        ButtonType apply = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow();
        return dialog.getResultConverter().call(apply);
    }

    @Test
    void applicationTagEditsPreserveUnsavedContentAndSynchronizeOpenMapAndLevel() throws Exception {
        for (boolean multilevel : List.of(false, true)) {
            CompletableFuture<Void> completed = new CompletableFuture<>();
            FutureTask<ApplicationTagsFixture> setup = new FutureTask<>(() -> {
                ApplicationTagsFixture fixture = new ApplicationTagsFixture(multilevel);
                Timeline responder = new Timeline();
                responder.getKeyFrames().add(new KeyFrame(Duration.millis(20), event -> {
                    Window dialogWindow = Window.getWindows().stream().filter(Window::isShowing)
                            .filter(window -> window.getScene().getRoot() instanceof DialogPane)
                            .findFirst().orElse(null);
                    if (dialogWindow == null) {
                        return;
                    }
                    responder.stop();
                    DialogPane pane = (DialogPane) dialogWindow.getScene().getRoot();
                    try {
                        Button remove = (Button) pane.lookupAll(".map-tag-chip .button").stream()
                                .filter(node -> "Remove TAVERN".equals(node.getAccessibleText()))
                                .findFirst().orElseThrow();
                        remove.fire();
                        ((TextField) pane.lookup("#map-tag-input")).setText("Town");
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow())).fire();
                    } catch (Throwable failure) {
                        completed.completeExceptionally(failure);
                        ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                    }
                }));
                responder.setCycleCount(Timeline.INDEFINITE);
                fixture.status.textProperty().addListener((obs, before, after) -> {
                    if (after.startsWith("Updated tags for ")) {
                        completed.complete(null);
                    } else if (after.startsWith("Library operation failed") || after.startsWith("Could not read map tags")) {
                        responder.stop();
                        completed.completeExceptionally(new AssertionError(after));
                    }
                });
                responder.play();
                Method manage = DungeonMasterMapToolApplication.class.getDeclaredMethod("handleManageTags", List.class);
                manage.setAccessible(true);
                manage.invoke(fixture.app, List.of(fixture.entry));
                return fixture;
            });
            Platform.runLater(setup);
            ApplicationTagsFixture fixture = setup.get(10, TimeUnit.SECONDS);
            completed.get(15, TimeUnit.SECONDS);
            onFx(() -> {
                assertEquals(List.of("ROOF", "TOWN"), fixture.live.getMap().getTags());
                assertDoesNotThrow(() -> {
                    DmProject persisted = fixture.projects.load(fixture.openFile);
                    assertEquals(fixture.live.getMap().getTags(), persisted.getMap().getTags());
                    assertEquals(1, persisted.getWalls().size());
                    assertEquals(42, persisted.getWalls().getFirst().getX1());
                    if (fixture.manifest != null) {
                        List<String> union = fixture.library.tags().readTags(fixture.manifest);
                        assertEquals(List.of("CELLAR", "TOWN", "ROOF"), union);
                    }
                    fixture.projects.save(fixture.openFile, fixture.live);
                    assertEquals(List.of("ROOF", "TOWN"), fixture.library.tags().readTags(fixture.openFile));
                });
            });
        }
    }

    private final class ApplicationTagsFixture {
        final ProjectService projects = new ProjectService();
        final MapLibraryService library;
        final DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
        final Label status = new Label();
        final Entry entry;
        final Path manifest;
        final Path openFile;
        final DmProject live;

        ApplicationTagsFixture(boolean multilevel) throws Exception {
            library = new MapLibraryService(dir.resolve(multilevel ? "multi" : "single"), projects);
            Path roof = library.getRoot().resolve("Roof").resolve("Roof.dmmap");
            DmProject project = DmProject.builder().build();
            project.getMap().setTags(List.of("Tavern", "Roof"));
            projects.save(roof, project);
            if (multilevel) {
                Path cellar = library.getRoot().resolve("Cellar").resolve("Cellar.dmmap");
                project.getMap().setTags(List.of("Cellar"));
                projects.save(cellar, project);
                manifest = library.multiLevels().create(library.getRoot(), "Tower", List.of(
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(cellar.getParent(), cellar), "Cellar"),
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(roof.getParent(), roof), "Roof")),
                        null).manifestFile();
                MultiLevelManifest levels = library.multiLevels().loadManifest(manifest);
                MultiLevelManifest.Level level = levels.getLevels().get(1);
                openFile = MultiLevelService.levelFile(manifest, level);
                field(app, "multiLevelFile", manifest);
                field(app, "multiLevelManifest", levels);
                field(app, "currentLevelId", level.getId());
            } else {
                manifest = null;
                openFile = roof;
            }
            live = projects.load(openFile);
            live.getWalls().add(DmProject.WallSegment.builder().x1(42).build());
            field(app, "project", live);
            field(app, "projectFile", openFile);
            field(app, "mapLibrary", library);
            field(app, "statusLabel", status);
            Method createHost = DungeonMasterMapToolApplication.class.getDeclaredMethod("createBrowserHost");
            createHost.setAccessible(true);
            MapBrowser browser = new MapBrowser(library, (MapBrowser.Host) createHost.invoke(app),
                    new RecentMaps(new AppSettings(dir.resolve("app-tags.ini"))));
            field(app, "mapBrowser", browser);
            entry = library.scan().children().getFirst();
        }
    }

    private static void field(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    @Test
    void duplicateCopiesEverySelectedMapAndSavesTheSelectedOpenMapFirst() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            Entry ordinary = fixture.entry(0);
            Entry multilevel = fixture.entry(1);
            fixture.host.current = multilevel.mapFile();
            menuItem(fixture.browser.buildMenu(ordinary), "Duplicate").fire();
            assertEquals(List.of(ordinary, multilevel), fixture.library.copied);
            assertEquals(multilevel.path(), fixture.host.affected);
            assertEquals(1, fixture.host.operations);
        });
    }

    @Test
    void deleteMenuAndKeyDeleteAllSelectedMapsWithOneConfirmation() throws Exception {
        for (boolean keyboard : new boolean[]{false, true}) {
            onFx(() -> {
                Fixture fixture = new Fixture();
                fixture.select(0, 1);
                List<Entry> maps = List.of(fixture.entry(0), fixture.entry(1));
                fixture.host.current = maps.get(1).mapFile();
                FutureTask<Void> confirmation = answerDeleteDialog(true);
                if (keyboard) {
                    KeyEvent event = key(KeyCode.DELETE);
                    fixture.tree.getOnKeyPressed().handle(event);
                    assertTrue(event.isConsumed());
                } else {
                    menuItem(fixture.browser.buildMenu(maps.get(0)), "Delete…").fire();
                }
                assertTrue(confirmation.isDone());
                assertDoesNotThrow(() -> confirmation.get());
                assertEquals(maps, fixture.library.deleted);
                assertEquals(maps.get(1).path(), fixture.host.affected);
                assertEquals(1, fixture.host.operations);
            });
        }
    }

    @Test
    void cancellingBatchDeleteLeavesEveryMapUntouched() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            FutureTask<Void> confirmation = answerDeleteDialog(false);
            menuItem(fixture.browser.buildMenu(fixture.entry(0)), "Delete…").fire();
            assertDoesNotThrow(() -> confirmation.get());
            assertTrue(fixture.library.deleted.isEmpty());
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void enterAndRenameDoNotActOnMultipleMaps() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.ENTER));
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.F2));
            assertNull(fixture.host.opened);
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void rightClickPreservesSelectedMapsAndReplacesSelectionForAnUnselectedMap() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            Stage stage = new Stage();
            stage.setScene(new Scene(fixture.browser, 400, 600));
            try {
                stage.show();
                fixture.browser.applyCss();
                fixture.browser.layout();
                fixture.select(0, 1);
                TreeCell<?> cell = fixture.tree.lookupAll(".tree-cell").stream()
                        .filter(TreeCell.class::isInstance).map(node -> (TreeCell<?>) node)
                        .filter(candidate -> fixture.entry(1).equals(candidate.getItem()))
                        .findFirst().orElseThrow();
                cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 10, 10, 10, 10,
                        MouseButton.SECONDARY, 1, false, false, false, false,
                        false, false, true, false, true, true, null));
                cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        10, 10, 10, 10, false, null));
                assertEquals(2, fixture.tree.getSelectionModel().getSelectedItems().size());
                fixture.select(0);
                cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        10, 10, 10, 10, false, null));
                assertEquals(1, fixture.tree.getSelectionModel().getSelectedItems().size());
                assertEquals(fixture.entry(1), fixture.tree.getSelectionModel().getSelectedItem().getValue());
            } finally {
                stage.close();
            }
        });
    }

    private static FutureTask<Void> answerDeleteDialog(boolean confirm) {
        FutureTask<Void> answer = new FutureTask<>(() -> {
            Window window = Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
            DialogPane pane = (DialogPane) window.getScene().getRoot();
            try {
                assertEquals("Delete 2 maps?", pane.getHeaderText());
                String message = ((Label) pane.getContent()).getText();
                assertTrue(message.contains("Ordinary"));
                assertTrue(message.contains("Multilevel"));
            } finally {
                var type = pane.getButtonTypes().stream()
                        .filter(button -> button.getText().equals(confirm ? "Delete" : "Cancel"))
                        .findFirst().orElseThrow();
                ((Button) pane.lookupButton(type)).fire();
            }
            return null;
        });
        Platform.runLater(answer);
        return answer;
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private final class Fixture {
        final RecordingLibrary library = new RecordingLibrary(dir);
        final RecordingHost host = new RecordingHost();
        final MapBrowser browser = new MapBrowser(library, host, new RecentMaps(new AppSettings(dir.resolve("settings.ini"))));
        final TreeView<Entry> tree;

        @SuppressWarnings("unchecked")
        Fixture() {
            tree = (TreeView<Entry>) browser.getChildren().stream()
                    .filter(TreeView.class::isInstance).findFirst().orElseThrow();
        }

        Entry entry(int index) {
            return tree.getRoot().getChildren().get(index).getValue();
        }

        void select(int... indices) {
            tree.getSelectionModel().clearSelection();
            for (int index : indices) {
                tree.getSelectionModel().select(tree.getRoot().getChildren().get(index));
            }
        }
    }

    private static final class RecordingLibrary extends MapLibraryService {
        final List<Entry> copied = new ArrayList<>();
        final List<Entry> deleted = new ArrayList<>();

        RecordingLibrary(Path root) {
            super(root, new ProjectService());
        }

        @Override
        public Entry scan() {
            Path ordinary = getRoot().resolve("Ordinary");
            Path multilevel = getRoot().resolve("Multilevel");
            return new Entry(Kind.FOLDER, "Library", getRoot(), null, List.of(
                    new Entry(Kind.MAP, "Ordinary", ordinary, ordinary.resolve("Ordinary.dmmap"), List.of()),
                    new Entry(Kind.MAP, "Multilevel", multilevel, multilevel.resolve("Multilevel.dmlevels"), List.of()),
                    new Entry(Kind.FOLDER, "Folder", getRoot().resolve("Folder"), null, List.of())));
        }

        @Override
        public Result copy(Entry entry) {
            copied.add(entry);
            return new Result(Map.of(), entry.mapFile());
        }

        @Override
        public void delete(Entry entry) {
            deleted.add(entry);
        }
    }

    private static final class RecordingHost implements MapBrowser.Host {
        Path current;
        Path affected;
        Path opened;
        List<Entry> merged;
        List<Entry> tagged;
        int operations;

        @Override public void newMap() {}
        @Override public void newMapIn(Path folder) {}
        @Override public void importMap(Path folder) {}
        @Override public void importMapFolder(Path folder) {}
        @Override public void saveMap() {}
        @Override public void rotateMap(boolean clockwise) {}
        @Override public void openMap(Path file) { opened = file; }
        @Override public Path currentMapFile() { return current; }
        @Override public void importMultiLevelMap(Path folder) {}
        @Override public void mergeIntoMultiLevelMap(List<Entry> maps) { merged = maps; }
        @Override public void manageLevels(Path file) {}
        @Override public void manageTags(List<Entry> maps) { tagged = maps; }
        @Override public void dropMapOnMap(List<Entry> dragged, Entry target) {}
        @Override public void dissolveMultiLevel(Path file) {}

        @Override
        public void runLibraryOperation(String message, Path affectedPath, MapBrowser.LibraryOperation operation,
                                        Consumer<MapLibraryService.Result> onDone) {
            affected = affectedPath;
            operations++;
            try {
                onDone.accept(operation.run());
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
