package dmmt.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Popup;

import java.util.List;
import java.util.Comparator;
import java.util.Locale;

/** Non-focus-stealing suggestions: typing and keyboard navigation stay in the tag field. */
final class TagSuggestions extends Popup {
    private static final int ROW_HEIGHT = 32;
    private final TextField field;
    private final ListView<String> list = new ListView<>();
    private boolean accepting;

    TagSuggestions(TextField field) {
        this.field = field;
        setAutoHide(true);
        setHideOnEscape(false);
        list.setId("map-tag-suggestions");
        list.getStyleClass().addAll("root", "tag-suggestions");
        list.getStylesheets().add(Icons.STYLESHEET);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setFocusTraversable(false);
        getContent().add(list);
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && list.getSelectionModel().getSelectedItem() != null) {
                accept();
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!isShowing()) {
                return;
            }
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                int selected = list.getSelectionModel().getSelectedIndex();
                int next = event.getCode() == KeyCode.DOWN ? selected + 1
                        : selected < 0 ? list.getItems().size() - 1 : selected - 1;
                list.getSelectionModel().select(Math.clamp(next, 0, list.getItems().size() - 1));
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) {
                accept();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                event.consume();
            }
        });
        field.focusedProperty().addListener((obs, before, focused) -> {
            if (!focused) {
                hide();
            } else {
                showBelowField();
            }
        });
    }

    void refresh(List<String> matches) {
        String typed = field.getText().trim().toUpperCase(Locale.ROOT);
        Comparator<String> byFit = Comparator
                .comparingInt((String tag) -> {
                    String name = tag.toUpperCase(Locale.ROOT);
                    return name.equals(typed) ? 0 : name.startsWith(typed) ? 1 : 2;
                })
                .thenComparingInt(tag -> tag.toUpperCase(Locale.ROOT).indexOf(typed))
                .thenComparingInt(String::length)
                .thenComparing(String.CASE_INSENSITIVE_ORDER);
        list.getItems().setAll(matches.stream().sorted(byFit).limit(5).toList());
        list.getSelectionModel().clearSelection();
        if (accepting || list.getItems().isEmpty()) {
            hide();
        } else {
            showBelowField();
        }
    }

    private void showBelowField() {
        Bounds bounds = field.localToScreen(field.getBoundsInLocal());
        if (!field.isFocused() || bounds == null || list.getItems().isEmpty()) {
            return;
        }
        list.setPrefWidth(bounds.getWidth());
        list.setPrefHeight(list.getItems().size() * ROW_HEIGHT + 2);
        if (!isShowing()) {
            show(field, bounds.getMinX(), bounds.getMaxY() + 4);
        } else {
            setX(bounds.getMinX());
            setY(bounds.getMaxY() + 4);
        }
        list.applyCss();
        list.setPrefHeight(list.getItems().size() * ROW_HEIGHT
                + list.snappedTopInset() + list.snappedBottomInset());
        list.autosize();
    }

    private void accept() {
        String selected = list.getSelectionModel().getSelectedItem();
        accepting = true;
        try {
            field.setText(selected);
            field.positionCaret(selected.length());
            hide();
            field.requestFocus();
        } finally {
            accepting = false;
        }
    }
}
