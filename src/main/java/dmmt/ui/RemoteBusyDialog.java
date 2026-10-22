package dmmt.ui;

import dmmt.log.Log;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal progress dialog shown while the remote DM waits for the host (opening a map, importing assets, uploads).
 * Operations are reference counted; the dialog appears after a short delay so quick operations do not flicker.
 * Must be used on the JavaFX application thread.
 */
public final class RemoteBusyDialog {
    private static final Log LOG = Log.of(RemoteBusyDialog.class);
    private static final Duration SHOW_DELAY = Duration.millis(250);

    private final Window owner;
    private final Label title = new Label();
    private final Label message = new Label();
    private final Label detail = new Label();
    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final PauseTransition delay = new PauseTransition(SHOW_DELAY);
    private final List<Task> tasks = new ArrayList<>();
    private Stage stage;

    private static final class Task {
        final String title;
        final String message;

        Task(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    public RemoteBusyDialog(Window owner) {
        this.owner = owner;
        title.getStyleClass().add("dialog-title");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        message.setWrapText(true);
        message.setMaxWidth(360);
        detail.getStyleClass().add("muted");
        bar.setMaxWidth(Double.MAX_VALUE);
        delay.setOnFinished(event -> display());
    }

    /** Starts a tracked operation; run the returned handle when it completes (success or failure). */
    public Runnable begin(String taskTitle, String taskMessage) {
        var task = new Task(taskTitle, taskMessage);
        LOG.debug("Remote operation started: {}", taskTitle);
        tasks.add(task);
        if (stage == null || !stage.isShowing()) {
            delay.playFromStart();
        } else {
            update();
        }
        var finished = new boolean[1];
        return () -> {
            if (finished[0]) return;
            finished[0] = true;
            tasks.remove(task);
            if (tasks.isEmpty()) hide();
            else update();
        };
    }

    public boolean busy() {
        return !tasks.isEmpty();
    }

    /** Updates progress: a negative fraction means indeterminate. */
    public void progress(double fraction, String text) {
        if (tasks.isEmpty()) return;
        bar.setProgress(fraction >= 0 ? Math.min(1.0, fraction) : ProgressBar.INDETERMINATE_PROGRESS);
        detail.setText(text == null ? "" : text);
        detail.setVisible(!detail.getText().isEmpty());
        detail.setManaged(detail.isVisible());
    }

    public void close() {
        tasks.clear();
        hide();
    }

    private void update() {
        var current = tasks.getLast();
        title.setText(current.title);
        message.setText(tasks.size() > 1 ? current.message + " (+" + (tasks.size() - 1) + " more)" : current.message);
    }

    private void display() {
        if (tasks.isEmpty()) return;
        update();
        if (stage == null) {
            stage = new Stage(StageStyle.TRANSPARENT);
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setResizable(false);
            var card = new VBox(10, title, message, bar, detail);
            card.getStyleClass().add("remote-busy");
            card.setPadding(new Insets(22));
            var root = new VBox(card);
            root.setStyle("-fx-background-color: transparent; -fx-padding: 12;");
            var scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage.setScene(scene);
            stage.setOnCloseRequest(event -> event.consume());
            Dialogs.inheritIcons(stage, owner);
        }
        stage.show();
        if (owner != null) {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
        }
    }

    private void hide() {
        delay.stop();
        if (stage != null && stage.isShowing()) stage.hide();
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        detail.setText("");
        detail.setVisible(false);
        detail.setManaged(false);
    }
}
