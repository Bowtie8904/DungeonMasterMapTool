package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.remote.RemoteFailure;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteErrorDialogsTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void stackTraceIsExpandableReadOnlySelectableAndCopyDoesNotCloseDialog() throws Exception {
        fx(() -> {
            var failure = RemoteFailure.capture("Host map artwork failed.", new IOException("Missing image.png"));
            var dialog = RemoteErrorDialogs.create(null, failure);
            try {
                dialog.show();
                var pane = dialog.getDialogPane();
                assertFalse(pane.isExpanded());
                pane.setExpanded(true);
                var trace = assertInstanceOf(TextArea.class, pane.getExpandableContent());
                assertFalse(trace.isEditable());
                assertEquals(failure.stackTrace(), trace.getText());
                trace.selectAll();
                assertEquals(failure.stackTrace(), trace.getSelectedText());
                ((Button) pane.lookup("#remote-error-copy")).fire();
                assertTrue(dialog.isShowing());
                assertTrue(Clipboard.getSystemClipboard().getString().contains(failure.message()));
                assertTrue(Clipboard.getSystemClipboard().getString().contains(failure.stackTrace()));
            } finally {
                dialog.close();
            }
            return null;
        });
    }

    @Test
    void overflowingFailuresKeepTheQueueBoundedAndNotifyTheUser() throws Exception {
        fx(() -> {
            var presenter = new RemoteErrorDialogs(null);
            for (int i = 0; i < 18; i++) {
                presenter.report(RemoteFailure.capture("Failure " + i, new IOException("Missing artwork")));
            }
            int dialogs = 0;
            while (true) {
                var shown = Window.getWindows().stream().filter(window ->
                        window.getScene() != null && window.getScene().lookup("#remote-error-copy") != null).toList();
                if (shown.isEmpty()) break;
                assertEquals(1, shown.size());
                var pane = (javafx.scene.control.DialogPane) shown.getFirst().getScene().getRoot();
                if (dialogs == 0) {
                    assertTrue(((javafx.scene.control.Label) pane.getContent()).getText()
                            .contains("application log"));
                }
                ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
                assertTrue(++dialogs <= 17);
            }
            assertEquals(17, dialogs);
            return null;
        });
    }

    @Test
    void repeatedPollingFailuresShowOnceAndDistinctFailuresQueueBehindOneDialog() throws Exception {
        fx(() -> {
            var presenter = new RemoteErrorDialogs(null);
            var first = RemoteFailure.capture("Host failed.", new IOException("Missing artwork"));
            long baseline = Window.getWindows().stream().filter(Window::isShowing).count();
            presenter.report(first);
            presenter.report(first);
            presenter.report(new RemoteFailure(java.util.UUID.randomUUID(), first.message(), first.stackTrace()));
            presenter.report(RemoteFailure.capture("Other map failed.", new IOException("Other image")));
            assertEquals(baseline + 1, Window.getWindows().stream().filter(Window::isShowing).count());
            var shown = Window.getWindows().stream().filter(window ->
                    window.getScene() != null && window.getScene().lookup("#remote-error-copy") != null).toList();
            assertEquals(1, shown.size());
            var firstPane = (javafx.scene.control.DialogPane) shown.getFirst().getScene().getRoot();
            ((Button) firstPane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            var next = Window.getWindows().stream().filter(window ->
                    window.getScene() != null && window.getScene().lookup("#remote-error-copy") != null).toList();
            assertEquals(1, next.size());
            var nextPane = (javafx.scene.control.DialogPane) next.getFirst().getScene().getRoot();
            ((Button) nextPane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            assertEquals(baseline, Window.getWindows().stream().filter(Window::isShowing).count());
            return null;
        });
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
