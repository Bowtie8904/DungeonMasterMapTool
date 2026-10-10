package dmmt.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import javafx.util.Duration;

/** A fixed-width readout that scrolls only when its full text does not fit. */
final class ScrollingLabel extends Region {
    private static final double SCROLL_PIXELS_PER_SECOND = 30;
    private final Label label = new Label();
    private final Tooltip tooltip = new Tooltip();
    private final Timeline scroll = new Timeline();
    private final ChangeListener<Boolean> showingListener = (observable, oldValue, newValue) -> updateAnimation();
    private final ChangeListener<Window> windowListener = (observable, oldValue, newValue) ->
            observeWindow(oldValue, newValue);
    private double overflow = -1;
    private boolean disposed;

    ScrollingLabel(double width) {
        setMinWidth(width);
        setPrefWidth(width);
        setMaxWidth(width);
        label.setManaged(false);
        getChildren().add(label);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        Tooltip.install(this, tooltip);
        scroll.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((observable, oldValue, newValue) -> observeScene(oldValue, newValue));
        visibleProperty().addListener(showingListener);
    }

    void setText(String text) {
        if (label.getText().equals(text)) {
            return;
        }
        scroll.stop();
        label.setTranslateX(0);
        label.setText(text);
        tooltip.setText(text);
        setAccessibleText(text);
        overflow = -1;
        requestLayout();
    }

    String getText() {
        return label.getText();
    }

    @Override
    protected double computePrefHeight(double width) {
        return label.prefHeight(-1);
    }

    @Override
    protected void layoutChildren() {
        double textWidth = label.prefWidth(-1);
        double textHeight = label.prefHeight(-1);
        label.resizeRelocate(0, (getHeight() - textHeight) / 2, textWidth, textHeight);
        double nextOverflow = Math.max(0, textWidth - getWidth());
        if (Double.compare(overflow, nextOverflow) != 0) {
            scroll.stop();
            label.setTranslateX(0);
            overflow = nextOverflow;
            scroll.getKeyFrames().clear();
            if (overflow > 0) {
                Duration end = Duration.seconds(1 + overflow / SCROLL_PIXELS_PER_SECOND);
                scroll.getKeyFrames().setAll(
                        new KeyFrame(Duration.ZERO, new KeyValue(label.translateXProperty(), 0)),
                        new KeyFrame(Duration.seconds(1), new KeyValue(label.translateXProperty(), 0)),
                        new KeyFrame(end, new KeyValue(label.translateXProperty(), -overflow, Interpolator.LINEAR)),
                        new KeyFrame(end.add(Duration.seconds(1)),
                                new KeyValue(label.translateXProperty(), -overflow)));
            }
            updateAnimation();
        }
    }

    private void observeScene(Scene oldScene, Scene newScene) {
        if (oldScene != null) {
            oldScene.windowProperty().removeListener(windowListener);
        }
        if (newScene != null) {
            newScene.windowProperty().addListener(windowListener);
        }
        observeWindow(oldScene == null ? null : oldScene.getWindow(),
                newScene == null ? null : newScene.getWindow());
    }

    private void observeWindow(Window oldWindow, Window newWindow) {
        if (oldWindow != null) {
            oldWindow.showingProperty().removeListener(showingListener);
        }
        if (newWindow != null) {
            newWindow.showingProperty().addListener(showingListener);
        }
        updateAnimation();
    }

    private void updateAnimation() {
        Scene scene = getScene();
        if (!disposed && overflow > 0 && isVisible() && scene != null
                && scene.getWindow() != null && scene.getWindow().isShowing()) {
            scroll.play();
        } else {
            scroll.stop();
            label.setTranslateX(0);
        }
    }

    void dispose() {
        disposed = true;
        updateAnimation();
    }
}
