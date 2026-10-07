package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.ProjectService;
import dmmt.service.RecentMaps;
import dmmt.service.AppSettings;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapBrowserApiTest {
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

    private MapBrowser browser() {
        MapBrowser.Host host = (MapBrowser.Host) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MapBrowser.Host.class}, (proxy, method, args) -> null);
        return new MapBrowser(new MapLibraryService(dir, new ProjectService()), host,
                new RecentMaps(new AppSettings(dir.resolve("settings.ini"))));
    }

    private Entry entry(String extension) {
        return new Entry(MapLibraryService.Kind.MAP, "Map", dir.resolve("Map"),
                dir.resolve("Map").resolve("Map" + extension), List.of());
    }

    private MenuItem copyItem(MapBrowser browser, Entry entry) {
        return browser.buildMenu(entry).getItems().stream()
                .filter(item -> "Copy API URL".equals(item.getText())).findFirst().orElseThrow();
    }

    @Test
    void copiesMapAndMultilevelUrlsOnlyWhenHookIsInstalled() throws Exception {
        onFx(() -> {
            MapBrowser browser = browser();
            assertTrue(browser.buildMenu(entry(".dmmap")).getItems().stream()
                    .noneMatch(item -> "Copy API URL".equals(item.getText())));
            browser.setApiUrlProvider(file -> {
                assertTrue(file.equals(entry(".dmmap").mapFile()) || file.equals(entry(".dmlevels").mapFile()));
                return "http://127.0.0.1:8123/api/maps/" + file.getFileName();
            });
            for (String extension : List.of(".dmmap", ".dmlevels")) {
                Entry map = entry(extension);
                MenuItem copy = copyItem(browser, map);
                assertFalse(copy.isDisable());
                copy.fire();
                assertEquals("http://127.0.0.1:8123/api/maps/" + map.mapFile().getFileName(),
                        Clipboard.getSystemClipboard().getString());
            }
        });
    }

    @Test
    void providerFailureShowsAnErrorAndDoesNotReplaceClipboard() throws Exception {
        onFx(() -> {
            MapBrowser browser = browser();
            ClipboardContent previous = new ClipboardContent();
            previous.putString("keep this");
            Clipboard.getSystemClipboard().setContent(previous);
            browser.setApiUrlProvider(file -> {
                throw new IllegalStateException("Local API is disabled.");
            });
            FutureTask<Void> answer = new FutureTask<>(() -> {
                Window window = Window.getWindows().stream().filter(Window::isShowing)
                        .filter(candidate -> candidate.getScene().getRoot() instanceof DialogPane)
                        .findFirst().orElseThrow();
                DialogPane pane = (DialogPane) window.getScene().getRoot();
                try {
                    assertEquals("Could not copy API URL", pane.getHeaderText());
                    assertEquals("Local API is disabled.", ((Label) pane.getContent()).getText());
                } finally {
                    ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                }
                return null;
            });
            Platform.runLater(answer);
            copyItem(browser, entry(".dmmap")).fire();
            assertTrue(answer.isDone());
            try {
                answer.get();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            assertEquals("keep this", Clipboard.getSystemClipboard().getString());
        });
    }
}
