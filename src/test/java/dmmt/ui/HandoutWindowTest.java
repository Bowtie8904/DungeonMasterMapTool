package dmmt.ui;

import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HandoutWindowTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void remoteRecentPaneUsesSuppliedHostThumbnailsAndCommandsWithoutOpeningLocalStorage() throws Exception {
        onFx(() -> {
            var adds = new java.util.ArrayList<String>();
            var removals = new java.util.ArrayList<String>();
            var window = new HandoutWindow(null, List.of(), () -> true, () -> {}, () -> {});
            try {
                window.remoteRecents(adds::add, removals::add);
                String id = "a".repeat(32);
                var thumbnails = new java.util.LinkedHashMap<String, Image>();
                thumbnails.put(id, new WritableImage(96, 80));
                window.applyRemoteRecents(thumbnails, 100);
                assertNull(get(window, "recents"));
                assertTrue(((javafx.scene.layout.VBox) get(window, "recentPane")).isVisible());
                assertEquals(List.of(id), ((javafx.scene.control.ListView<?>) get(window, "recentList")).getItems());
                invoke(window, "addFromRecent", new Class<?>[]{String.class}, id);
                invoke(window, "removeRecent", new Class<?>[]{String.class}, id);
                assertEquals(List.of(id), adds);
                assertEquals(List.of(id), removals);
                assertTrue(window.getImages().isEmpty(), "Only an accepted host snapshot may alter the board.");
                window.remoteStatus(true, "Paused");
                invoke(window, "addFromRecent", new Class<?>[]{String.class}, id);
                invoke(window, "removeRecent", new Class<?>[]{String.class}, id);
                assertEquals(1, adds.size());
                assertEquals(1, removals.size());
                window.applyRemoteRecents(java.util.Map.of(), 0);
                assertTrue(((javafx.scene.control.Label) get(window, "recentPlaceholder")).getText().contains("turned off"));
            } finally { window.close(); }
        });
    }

    @Test
    void remoteSnapshotsPreserveLocalSelectionWithoutEchoAndPausedKeyboardCannotMutateBoard() throws Exception {
        onFx(() -> {
            var changes = new java.util.concurrent.atomic.AtomicInteger();
            var closed = new java.util.concurrent.atomic.AtomicInteger();
            var window = new HandoutWindow(null, List.of(), () -> true, changes::incrementAndGet, closed::incrementAndGet);
            try {
                Image first = new WritableImage(20, 30), second = new WritableImage(10, 10);
                var snapshot = new HandoutWindow.Snapshot(List.of(first, second), 270, true, true, List.of(1));
                window.applySnapshot(snapshot);
                assertEquals(0, changes.get());
                invoke(window, "click", new Class<?>[]{int.class, boolean.class}, 0, false);
                window.applySnapshot(snapshot);
                assertFalse(((Button) get(window, "deleteButton")).isDisabled());
                assertEquals(List.of(second), window.getImages());
                assertTrue(((ToggleButton) get(window, "mirrorToggle")).isSelected());
                assertTrue(((ToggleButton) get(window, "showSelectedToggle")).isSelected());
                window.remoteStatus(true, "Paused");
                window.show();
                var stage = (Stage) get(window, "stage");
                javafx.event.Event.fireEvent(stage.getScene(), new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.DELETE,
                        false, false, false, false));
                assertEquals(snapshot, window.snapshot());
                window.close();
                assertTrue(window.isShownToPlayers());
                assertEquals(List.of(second), window.getImages());
                assertEquals(0, changes.get());
                assertEquals(1, closed.get());
            } finally {
                window.close();
            }
        });
    }

    @Test
    void applicationReusesImagesAfterClosingAndReopening() throws Exception {
        onFx(() -> {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            Stage owner = new Stage();
            HandoutWindow window = null;
            try {
                invoke(app, "openHandoutWindow", new Class<?>[]{Stage.class}, owner);
                window = (HandoutWindow) get(app, "handoutWindow");
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.close();

                assertFalse(window.isShowing());
                assertSame(window, get(app, "handoutWindow"));
                invoke(app, "openHandoutWindow", new Class<?>[]{Stage.class}, owner);
                assertSame(window, get(app, "handoutWindow"));
                assertTrue(window.isShowing());
                assertEquals(List.of(first, second), window.getImages());
                assertFalse(window.isShownToPlayers());
                assertFalse(((Button) get(window, "clearButton")).isDisabled());

                Image third = new WritableImage(10, 10);
                addImages(window, third);
                assertEquals(List.of(first, second, third), window.getImages());
            } finally {
                if (window != null) {
                    window.close();
                }
                owner.close();
            }
        });
    }

    @Test
    void closingStopsSelectedOnlyDisplayWithoutDiscardingImages() throws Exception {
        onFx(() -> {
            HandoutWindow window = new HandoutWindow(new Stage(), List.of(), () -> true, () -> {}, () -> {});
            try {
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.show();
                invoke(window, "click", new Class<?>[]{int.class, boolean.class}, 1, false);
                ((ToggleButton) get(window, "showSelectedToggle")).fire();
                assertTrue(window.isShownToPlayers());
                assertEquals(List.of(second), window.getImages());

                window.close();
                assertFalse(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
                window.show();
                assertFalse(((ToggleButton) get(window, "showToggle")).isSelected());
                assertFalse(((ToggleButton) get(window, "showSelectedToggle")).isSelected());
                assertTrue(((Button) get(window, "deleteButton")).isDisabled());

                ((ToggleButton) get(window, "showToggle")).fire();
                assertTrue(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
                window.close();
                window.show();
                assertFalse(window.isShownToPlayers());
                assertEquals(List.of(first, second), window.getImages());
            } finally {
                window.close();
            }
        });
    }

    @Test
    void deletedAndClearedImagesDoNotReturnOnReopen() throws Exception {
        onFx(() -> {
            HandoutWindow window = new HandoutWindow(new Stage(), List.of(), () -> true, () -> {}, () -> {});
            try {
                Image first = new WritableImage(20, 30);
                Image second = new WritableImage(40, 20);
                addImages(window, first, second);
                window.show();
                invoke(window, "click", new Class<?>[]{int.class, boolean.class}, 0, false);
                ((Button) get(window, "deleteButton")).fire();
                window.close();
                window.show();
                assertEquals(List.of(second), window.getImages());

                ((Button) get(window, "clearButton")).fire();
                window.close();
                window.show();
                assertTrue(window.getImages().isEmpty());
                assertTrue(((ToggleButton) get(window, "showToggle")).isDisabled());
                assertTrue(((Button) get(window, "clearButton")).isDisabled());
            } finally {
                window.close();
            }
        });
    }

    @Test
    void addingFromRecentsAddsImageAndMovesItToTheTop(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
            throws Exception {
        RecentHandouts recents = new RecentHandouts(folder);
        WritableImage red = new WritableImage(4, 4);
        red.getPixelWriter().setColor(0, 0, javafx.scene.paint.Color.RED);
        String redId = recents.add(red, 100);
        String blankId = recents.add(new WritableImage(4, 4), 100);
        onFx(() -> {
            HandoutWindow window = new HandoutWindow(new Stage(), List.of(), () -> true, () -> {}, () -> {}, recents);
            try {
                @SuppressWarnings("unchecked")
                javafx.scene.control.ListView<String> list =
                        (javafx.scene.control.ListView<String>) get(window, "recentList");
                assertEquals(List.of(blankId, redId), list.getItems());
                invoke(window, "addFromRecent", new Class<?>[]{String.class}, redId);
                assertEquals(1, window.getImages().size());
                assertEquals(List.of(redId, blankId), list.getItems());
            } finally {
                window.close();
            }
        });
        waitForRecentWorker();
        assertEquals(List.of(redId, blankId), new RecentHandouts(folder).ids());
    }

    private static void waitForRecentWorker() throws Exception {
        java.util.concurrent.ExecutorService worker = (java.util.concurrent.ExecutorService) staticField("RECENT_WORKER");
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static Object staticField(String name) throws Exception {
        Field field = HandoutWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static void addImages(HandoutWindow window, Image... images) throws Exception {
        ((List<Image>) get(window, "images")).addAll(List.of(images));
        invoke(window, "afterImagesChanged", new Class<?>[0]);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static void onFx(ThrowingRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
