package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.remote.RemoteDirectories;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SettingsHostDirectoryTest {
    @TempDir Path directory;
    @BeforeAll static void startFx() throws Exception { FxTestSupport.startJavaFx(); }

    @Test
    void settingsBrowseTargetsHostAndRetainsDialogRevisionWithoutWritingClientIni() throws Exception {
        var settings = new AppSettings(directory.resolve("client.ini"));
        settings.applyEdit("library.folder", "local-library");
        byte[] before = Files.readAllBytes(settings.getFile());
        var edits = new ArrayList<AppSettings.RemoteEdit>();
        var replies = new ArrayList<CompletableFuture<RemoteDirectories.Listing>>();
        var context = new AtomicReference<AppSettings.HostContext>();
        var editor = new AtomicReference<TextField>();
        try {
            onFx(() -> {
                context.set(settings.useHostSettings(new AppSettings.HostSnapshot(4, Map.of("library.folder", "host-library")), edits::add));
                SettingsWindow.show(null, settings, () -> {}, query -> {
                    var result = new CompletableFuture<RemoteDirectories.Listing>();
                    replies.add(result);
                    return result;
                });
                var activeField = SettingsWindow.class.getDeclaredField("active");
                activeField.setAccessible(true);
                var active = activeField.get(null);
                var searchField = SettingsWindow.class.getDeclaredField("search");
                searchField.setAccessible(true);
                ((TextField) searchField.get(active)).setText("library.folder");
                var render = SettingsWindow.class.getDeclaredMethod("render");
                render.setAccessible(true);
                render.invoke(active);
                var settingsStage = stage("Settings");
                settingsStage.getScene().getRoot().applyCss();
                settingsStage.getScene().getRoot().layout();
                var browse = settingsStage.getScene().getRoot().lookupAll(".button").stream()
                        .filter(node -> "browse-library.folder".equals(node.getId())).map(Button.class::cast).findFirst().orElseThrow();
                assertEquals("Browse host...", browse.getText());
                editor.set(((javafx.scene.layout.HBox) browse.getParent()).getChildren().stream()
                        .filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow());
                browse.fire();
                assertEquals(1, replies.size());
                replies.getFirst().complete(new RemoteDirectories.Listing(null, null, List.of(), 0, false, null));
                context.get().accept(new AppSettings.HostSnapshot(5, Map.of("library.folder", "new-host-library")));
                SettingsWindow.refresh(settings);
                assertEquals("new-host-library", editor.get().getText());
            });
            onFx(() -> {
                var browser = stage("Choose folder on host");
                ((TextField) browser.getScene().lookup("#remote-directory-path")).setText("/host-only/maps");
                button(browser, "Open host path").fire();
                replies.getLast().complete(new RemoteDirectories.Listing("/host-only/maps", "/host-only", List.of(), 0, false, null));
            });
            onFx(() -> {
                button(stage("Choose folder on host"), "Use this host folder").fire();
                assertEquals(1, edits.size());
                assertEquals("library.folder", edits.getFirst().key());
                assertEquals("/host-only/maps", edits.getFirst().value());
                assertEquals(4, edits.getFirst().baseRevision());
                assertEquals("host-library", edits.getFirst().expectedValue());
                assertEquals("new-host-library", settings.get("library.folder", null));
                assertEquals("new-host-library", editor.get().getText(), "An unacknowledged path must not appear accepted.");
            });
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        } finally {
            onFx(() -> {
                for (var window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && (stage.getTitle().equals("Settings")
                            || stage.getTitle().equals("Choose folder on host"))) stage.close();
                }
                if (context.get() != null) context.get().close();
                Tuning.reset();
                dmmt.render.OverlayTextures.applySettings(key -> null);
            });
        }
    }

    private static Stage stage(String title) {
        return Window.getWindows().stream().filter(window -> window instanceof Stage stage && title.equals(stage.getTitle()))
                .map(Stage.class::cast).findFirst().orElseThrow();
    }

    private static Button button(Stage stage, String text) {
        stage.getScene().getRoot().applyCss();
        return stage.getScene().getRoot().lookupAll(".button").stream().filter(node -> node instanceof Button button
                && text.equals(button.getText())).map(Button.class::cast).findFirst().orElseThrow();
    }

    private static void onFx(Action action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private interface Action { void run() throws Exception; }
}
