package dmmt.ui;

import dmmt.remote.RemoteFailure;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/** Serial, deduplicated diagnostics; a failed heartbeat must not open a dialog on every pulse. */
public final class RemoteErrorDialogs {
    private static final String OVERFLOW_NOTICE =
            "\nAdditional remote errors were recorded in this PC's application log.";
    private final Window owner;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final ArrayDeque<RemoteFailure> pending = new ArrayDeque<>();
    private Dialog<ButtonType> active;

    public RemoteErrorDialogs(Window owner) {
        this.owner = owner;
    }

    public void report(Throwable failure) {
        Throwable root = RemoteFailure.unwrap(failure);
        report(root instanceof RemoteFailure.HostException host ? host.details()
                : RemoteFailure.capture("Remote control failed.", root));
    }

    public void report(RemoteFailure failure) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> report(failure));
            return;
        }
        String key = failure.message() + "\n" + failure.stackTrace();
        if (seen.contains(key)) return;
        seen.add(key);
        if (seen.size() > 128) seen.remove(seen.iterator().next());
        if (pending.size() >= 16) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,
                    failure.message() + "\n" + failure.stackTrace());
            if (active != null && active.getDialogPane().getContent() instanceof Label summary
                    && !summary.getText().endsWith(OVERFLOW_NOTICE)) {
                summary.setText(summary.getText() + OVERFLOW_NOTICE);
            }
            return;
        }
        pending.addLast(failure);
        showNext();
    }

    private void showNext() {
        if (active != null || pending.isEmpty()) return;
        active = create(owner, pending.removeFirst());
        active.setOnHidden(event -> {
            active = null;
            showNext();
        });
        active.show();
    }

    static Dialog<ButtonType> create(Window owner, RemoteFailure failure) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initModality(Modality.NONE);
        Dialogs.style(dialog, owner, "Remote control error", "Remote control could not complete an operation",
                MaterialDesignA.ALERT_CIRCLE_OUTLINE, true);
        Label summary = new Label(failure.message());
        summary.setWrapText(true);
        summary.setMaxWidth(620);
        dialog.getDialogPane().setContent(summary);
        TextArea trace = new TextArea(failure.stackTrace());
        trace.setId("remote-error-stacktrace");
        trace.setEditable(false);
        trace.setWrapText(false);
        trace.setPrefColumnCount(90);
        trace.setPrefRowCount(18);
        dialog.getDialogPane().setExpandableContent(trace);
        dialog.getDialogPane().setExpanded(false);
        ButtonType copy = new ButtonType("Copy details", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(copy).setId("remote-error-copy");
        dialog.getDialogPane().lookupButton(copy).addEventFilter(ActionEvent.ACTION, event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("Remote control error\n" + failure.message() + "\n\n" + failure.stackTrace());
            if (!Clipboard.getSystemClipboard().setContent(content)) {
                summary.setText(failure.message() + "\nCould not copy details. Expand the stack trace and select/copy its text.");
            }
            event.consume();
        });
        dialog.setResizable(true);
        return dialog;
    }
}
