package dmmt.ui;

import dmmt.FxTestSupport;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScrollingLabelTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void fixedWidthReadoutScrollsOnlyOverflowWithPausesAndRestarts() throws Exception {
        onFx(() -> {
            ScrollingLabel readout = new ScrollingLabel(180);
            StackPane root = new StackPane(readout);
            Scene scene = new Scene(root, 360, 40);
            scene.getStylesheets().add(Icons.STYLESHEET);
            Stage stage = new Stage();
            stage.setScene(scene);
            try {
                stage.show();
                String longName = "A very long music track name that cannot possibly fit in the status bar readout";
                readout.setText(longName);
                root.applyCss();
                root.layout();
                Label text = (Label) readout.getChildrenUnmodifiable().getFirst();
                Timeline animation = animation(readout);
                double overflow = text.getWidth() - readout.getWidth();
                assertTrue(overflow > 60);
                assertEquals(180, readout.getWidth(), 0.01);
                assertEquals(180, ((Rectangle) readout.getClip()).getWidth(), 0.01);
                assertEquals(longName, readout.getAccessibleText());
                assertEquals(Animation.Status.RUNNING, animation.getStatus());

                animation.jumpTo(Duration.seconds(0.5));
                assertEquals(0, text.getTranslateX(), 0.01, "pause at the beginning");
                animation.jumpTo(Duration.seconds(2));
                assertEquals(-30, text.getTranslateX(), 0.01, "30 pixels per second after the first-second pause");
                readout.setText(longName);
                root.layout();
                assertEquals(-30, text.getTranslateX(), 0.01, "state refresh must not restart the same name");
                animation.jumpTo(Duration.seconds(1.5 + overflow / 30));
                assertEquals(-overflow, text.getTranslateX(), 0.01, "pause with the end of the name visible");
                animation.jumpTo(Duration.ZERO);
                assertEquals(0, text.getTranslateX(), 0.01, "repeat starts at the beginning");

                root.resize(100, 40);
                root.layout();
                assertEquals(180, readout.getWidth(), 0.01, "a narrow parent must not shrink the display");
                readout.setText("Short song");
                root.layout();
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
                assertEquals(0, text.getTranslateX(), 0.01);
                assertEquals(180, readout.getWidth(), 0.01);
                readout.setText("");
                root.layout();
                assertEquals("", text.getText());
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
            } finally {
                readout.dispose();
                stage.close();
            }
        });
    }

    @Test
    void hiddenDetachedAndDisposedDisplaysDoNotAnimate() throws Exception {
        onFx(() -> {
            ScrollingLabel readout = new ScrollingLabel(180);
            readout.setText("Another long track name that needs to scroll across the fixed width display");
            StackPane root = new StackPane(readout);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 360, 40));
            try {
                stage.show();
                root.applyCss();
                root.layout();
                Timeline animation = animation(readout);
                assertEquals(Animation.Status.RUNNING, animation.getStatus());
                stage.hide();
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
                stage.show();
                assertEquals(Animation.Status.RUNNING, animation.getStatus());
                root.getChildren().clear();
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
                root.getChildren().add(readout);
                root.layout();
                assertEquals(Animation.Status.RUNNING, animation.getStatus());
                readout.dispose();
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
                stage.hide();
                stage.show();
                assertEquals(Animation.Status.STOPPED, animation.getStatus());
            } finally {
                readout.dispose();
                stage.close();
            }
        });
    }

    private static Timeline animation(ScrollingLabel readout) throws ReflectiveOperationException {
        var field = ScrollingLabel.class.getDeclaredField("scroll");
        field.setAccessible(true);
        return (Timeline) field.get(readout);
    }

    private static void onFx(CheckedRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
