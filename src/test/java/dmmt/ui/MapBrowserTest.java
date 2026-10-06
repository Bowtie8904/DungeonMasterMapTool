package dmmt.ui;

import dmmt.service.AppSettings;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.ProjectService;
import dmmt.service.RecentMaps;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MapBrowserTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FutureTask<Void> started = new FutureTask<>(() -> {
            Platform.setImplicitExit(false);
            return null;
        });
        Platform.startup(started);
        started.get(10, TimeUnit.SECONDS);
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void multipleMapsEnableOnlyDuplicateAndDeleteForEitherMapKind() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            for (TreeItem<Entry> map : fixture.tree.getRoot().getChildren().subList(0, 2)) {
                ContextMenu menu = fixture.browser.buildMenu(map.getValue());
                for (MenuItem item : menu.getItems()) {
                    if (!(item instanceof SeparatorMenuItem)) {
                        assertEquals(!List.of("Duplicate", "Delete…").contains(item.getText()), item.isDisable(),
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
                    assertEquals(item.getText() != null && item.getText().equals("Make multilevel map…"),
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
                        assertEquals(!List.of("Duplicate", "Delete…", "Make multilevel map…")
                                .contains(item.getText()), item.isDisable(), item.getText());
                    }
                }
                menuItem(menu, "Make multilevel map…").fire();
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
