package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.remote.RemoteDirectories;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDirectoryWindowTest {
    @BeforeAll static void startFx() throws Exception { FxTestSupport.startJavaFx(); }

    @Test
    void hostPathsAreNeverResolvedLocallyAndOnlyAcceptedDirectoryCanBeChosen() throws Exception {
        var queries = new ArrayList<RemoteDirectories.Query>();
        var replies = new ArrayList<CompletableFuture<RemoteDirectories.Listing>>();
        var chosen = new AtomicReference<String>();
        var stage = new AtomicReference<Stage>();
        try {
            onFx(() -> {
                RemoteDirectoryWindow.show(null, query -> {
                    queries.add(query);
                    var result = new CompletableFuture<RemoteDirectories.Listing>();
                    replies.add(result);
                    return result;
                }, chosen::set);
                stage.set(stage());
                assertEquals(new RemoteDirectories.Query(null, 0), queries.getFirst());
                assertTrue(button(stage.get(), "Use this host folder").isDisabled());
                replies.getFirst().complete(new RemoteDirectories.Listing(null, null,
                        List.of(new RemoteDirectories.Entry("Host root", "/host-only")), 0, false, null));
            });
            onFx(() -> {
                assertTrue(button(stage.get(), "Use this host folder").isDisabled());
                ((TextField) stage.get().getScene().lookup("#remote-directory-path")).setText("/host-only/maps");
                button(stage.get(), "Open host path").fire();
                assertEquals("/host-only/maps", queries.getLast().path());
                assertTrue(button(stage.get(), "Use this host folder").isDisabled());
                replies.getLast().complete(new RemoteDirectories.Listing("/host-only/maps", "/host-only",
                        List.of(new RemoteDirectories.Entry("Next", "/host-only/maps/next")), 0, true, null));
            });
            onFx(() -> {
                assertFalse(button(stage.get(), "Use this host folder").isDisabled());
                button(stage.get(), "Next").fire();
                assertEquals(128, queries.getLast().offset());
                assertEquals("/host-only/maps", queries.getLast().path());
                replies.getLast().complete(new RemoteDirectories.Listing("/host-only/maps", "/host-only",
                        List.of(), 128, false, null));
            });
            onFx(() -> {
                assertFalse(button(stage.get(), "Previous").isDisabled());
                button(stage.get(), "Use this host folder").fire();
                assertEquals("/host-only/maps", chosen.get());
                assertFalse(stage.get().isShowing());
            });
        } finally { onFx(() -> { if (stage.get() != null) stage.get().close(); }); }
    }

    @Test
    void failedAndCancelledListingsNeverSelectAPathOrApplyLateReplies() throws Exception {
        var requests = new ArrayList<CompletableFuture<RemoteDirectories.Listing>>();
        var chosen = new AtomicReference<String>();
        var stage = new AtomicReference<Stage>();
        try {
            onFx(() -> {
                RemoteDirectoryWindow.show(null, query -> {
                    var result = new CompletableFuture<RemoteDirectories.Listing>();
                    requests.add(result);
                    return result;
                }, chosen::set);
                stage.set(stage());
                requests.getFirst().completeExceptionally(new java.io.IOException("Disconnected"));
            });
            onFx(() -> {
                assertTrue(button(stage.get(), "Use this host folder").isDisabled());
                assertFalse(button(stage.get(), "Host roots").isDisabled());
                button(stage.get(), "Host roots").fire();
                assertEquals(2, requests.size());
                stage.get().close();
                assertTrue(requests.getLast().isCancelled());
                assertFalse(requests.getLast().complete(new RemoteDirectories.Listing("/late", null, List.of(), 0, false, null)));
                assertNull(chosen.get());
            });
        } finally { onFx(() -> { if (stage.get() != null) stage.get().close(); }); }
    }

    private static Stage stage() {
        return Window.getWindows().stream().filter(window -> window instanceof Stage stage
                && stage.getTitle().equals("Choose folder on host")).map(Stage.class::cast).findFirst().orElseThrow();
    }

    private static Button button(Stage stage, String text) {
        stage.getScene().getRoot().applyCss();
        return stage.getScene().getRoot().lookupAll(".button").stream().filter(node ->
                node instanceof Button button && button.getText().equals(text)).map(Button.class::cast).findFirst().orElseThrow();
    }

    private static void onFx(Action action) throws Exception {
        var task = new FutureTask<Void>(() -> {
            action.run();
            for (var window : Window.getWindows()) {
                if (window.getScene() != null) {
                    window.getScene().getRoot().applyCss();
                    window.getScene().getRoot().layout();
                }
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private interface Action { void run() throws Exception; }
}
