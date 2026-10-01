package dmmt.ui;

import dmmt.model.DmProject;
import javafx.application.Platform;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place rich text editor for a text box. Every character carries its own font size and color; the editor
 * is laid over the canvas at the box position and scaled with the map zoom so it matches what is rendered.
 */
public final class TextBoxEditor {
    private static final Pattern STYLE = Pattern.compile("-fx-font-size:\\s*(\\d+)px;\\s*-fx-fill:\\s*(#[0-9A-Fa-f]{6})");
    private static final double PADDING = 14;
    private static final double BORDER_WIDTH = 8;
    private static final double CORNER_RADIUS = 12;
    private static final String CENTER = "-fx-text-alignment: center;";

    private final InlineCssTextArea area = new InlineCssTextArea();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private int typingSize = DmProject.DEFAULT_TEXT_SIZE;
    private String typingColor = DmProject.DEFAULT_TEXT_COLOR;
    private boolean loading;
    private boolean caretSyncScheduled;
    private Runnable onContentChanged = () -> { };
    private BiConsumer<Integer, String> onCaretStyleChanged = (size, color) -> { };
    private Runnable onFinish = () -> { };
    private String lastBoxStyle = "";
    private String background = DmProject.DEFAULT_TEXT_BACKGROUND;
    private String border = DmProject.TRANSPARENT;
    private double topExtra;

    public TextBoxEditor() {
        area.getStyleClass().add("text-box-editor");
        area.setWrapText(true);
        area.setManaged(false);
        area.getTransforms().add(scale);
        area.setVisible(false);

        area.plainTextChanges().subscribe(change -> {
            int inserted = change.getInserted().length();
            if (!loading && inserted > 0) {
                area.setStyle(change.getPosition(), change.getPosition() + inserted, style(typingSize, typingColor));
            }
        });
        area.richChanges().subscribe(change -> {
            Platform.runLater(this::centerParagraphs);
            if (!loading) {
                onContentChanged.run();
            }
        });
        area.caretPositionProperty().addListener((obs, was, now) -> scheduleCaretSync());
        area.selectionProperty().addListener((obs, was, now) -> scheduleCaretSync());
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                onFinish.run();
            }
        });
    }

    public InlineCssTextArea node() {
        return area;
    }

    public void setOnContentChanged(Runnable handler) {
        this.onContentChanged = handler;
    }

    /** Called with the size and color under the caret (or selection start) after the caret moved. */
    public void setOnCaretStyleChanged(BiConsumer<Integer, String> handler) {
        this.onCaretStyleChanged = handler;
    }

    public void setOnFinish(Runnable handler) {
        this.onFinish = handler;
    }

    public void show(List<DmProject.TextRun> runs, int fontSize, String color) {
        loading = true;
        try {
            area.clear();
            for (DmProject.TextRun run : runs) {
                if (run.getText() == null || run.getText().isEmpty()) {
                    continue;
                }
                int start = area.getLength();
                area.appendText(run.getText());
                area.setStyle(start, area.getLength(), style(run.getFontSize(), normalizeColor(run.getColor())));
            }
            typingSize = fontSize;
            typingColor = normalizeColor(color);
            if (area.getLength() > 0) {
                area.moveTo(area.getLength());
                Style caret = styleOf(area.getStyleOfChar(area.getLength() - 1));
                if (caret != null) {
                    typingSize = caret.size;
                    typingColor = caret.color;
                }
            }
        } finally {
            loading = false;
        }
        centerParagraphs();
        area.setVisible(true);
        area.requestFocus();
        Platform.runLater(area::requestFocus);
    }

    public void hide() {
        area.setVisible(false);
        area.clear();
    }

    public boolean isShowing() {
        return area.isVisible();
    }

    public boolean isFocused() {
        return area.isVisible() && area.isFocused();
    }

    public void focus() {
        area.requestFocus();
    }

    /** Merges the per-character styles back into runs of equal size and color. */
    public List<DmProject.TextRun> runs() {
        List<DmProject.TextRun> runs = new ArrayList<>();
        int length = area.getLength();
        if (length == 0) {
            return runs;
        }
        String text = area.getText();
        StyleSpans<String> spans = area.getStyleSpans(0, length);
        int position = 0;
        for (StyleSpan<String> span : spans) {
            Style style = styleOf(span.getStyle());
            int size = style == null ? typingSize : style.size;
            String color = style == null ? typingColor : style.color;
            String piece = text.substring(position, position + span.getLength());
            position += span.getLength();
            DmProject.TextRun last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last.getFontSize() == size && last.getColor().equalsIgnoreCase(color)) {
                last.setText(last.getText() + piece);
            } else {
                runs.add(DmProject.TextRun.builder().text(piece).fontSize(size).color(color).build());
            }
        }
        return runs;
    }

    public int typingSize() {
        return typingSize;
    }

    public String typingColor() {
        return typingColor;
    }

    public void applyFontSize(int size) {
        typingSize = size;
        restyleSelection(current -> style(size, current.color));
    }

    public void applyTextColor(String color) {
        typingColor = normalizeColor(color);
        String normalized = typingColor;
        restyleSelection(current -> style(current.size, normalized));
    }

    private void restyleSelection(java.util.function.Function<Style, String> mapper) {
        IndexRange selection = area.getSelection();
        if (selection.getLength() == 0) {
            return;
        }
        StyleSpans<String> spans = area.getStyleSpans(selection.getStart(), selection.getEnd());
        area.setStyleSpans(selection.getStart(), spans.mapStyles(s -> {
            Style current = styleOf(s);
            return mapper.apply(current == null ? new Style(typingSize, typingColor) : current);
        }));
    }

    public boolean hasSelection() {
        return area.getSelection().getLength() > 0;
    }

    /** Positions the editor over the box: (x, y) is the box's top-left in canvas pixels, w x h its unscaled size. */
    public void place(double x, double y, double width, double height, double zoom) {
        scale.setX(zoom);
        scale.setY(zoom);
        double textHeight = area.getTotalHeightEstimate();
        double extra = Math.max(0, (height - 2 * PADDING - textHeight) / 2);
        if (Math.abs(extra - topExtra) > 0.5) {
            topExtra = extra;
            applyBoxStyle();
        }
        area.resizeRelocate(x, y, width, height);
    }

    private void centerParagraphs() {
        for (int i = 0; i < area.getParagraphs().size(); i++) {
            if (!CENTER.equals(area.getParagraph(i).getParagraphStyle())) {
                area.setParagraphStyle(i, CENTER);
            }
        }
    }

    private double borderWidth() {
        return alpha(border) > 0 ? BORDER_WIDTH : 1.5;
    }

    public void setBoxColors(String background, String border) {
        this.background = background;
        this.border = border;
        applyBoxStyle();
    }

    private void applyBoxStyle() {
        boolean hasBorder = alpha(border) > 0;
        double borderWidth = borderWidth();
        String borderCss = hasBorder ? css(border) : "rgba(255,213,74,0.9)";
        String css = String.format(Locale.ROOT,
                "-fx-background-color: %s; -fx-background-insets: %.1f; -fx-background-radius: %.1f; -fx-border-color: %s; -fx-border-radius: %.1f;"
                        + " -fx-border-width: %.1f; -fx-border-style: %s; -fx-padding: %.1f %.1f %.1f %.1f;",
                css(background), hasBorder ? borderWidth / 2 : 0, Math.max(0, CORNER_RADIUS - (hasBorder ? borderWidth / 2 : 0)), borderCss, CORNER_RADIUS, borderWidth,
                hasBorder ? "solid" : "segments(6, 4)", PADDING - borderWidth + topExtra, PADDING - borderWidth,
                PADDING - borderWidth, PADDING - borderWidth);
        if (!css.equals(lastBoxStyle)) {
            lastBoxStyle = css;
            area.setStyle(css);
        }
    }

    private void scheduleCaretSync() {
        if (loading || caretSyncScheduled) {
            return;
        }
        caretSyncScheduled = true;
        // Deferred so typing has applied its style before the caret style is read back.
        Platform.runLater(() -> {
            caretSyncScheduled = false;
            if (!area.isVisible()) {
                return;
            }
            String raw = null;
            IndexRange selection = area.getSelection();
            if (selection.getLength() > 0) {
                raw = area.getStyleOfChar(selection.getStart());
            } else if (area.getCaretPosition() > 0) {
                raw = area.getStyleOfChar(area.getCaretPosition() - 1);
            } else if (area.getLength() > 0) {
                raw = area.getStyleOfChar(0);
            }
            Style style = styleOf(raw);
            if (style != null) {
                typingSize = style.size;
                typingColor = style.color;
                onCaretStyleChanged.accept(style.size, style.color);
            }
        });
    }

    private record Style(int size, String color) {
    }

    private static Style styleOf(String css) {
        if (css == null) {
            return null;
        }
        Matcher matcher = STYLE.matcher(css);
        if (!matcher.find()) {
            return null;
        }
        return new Style(Integer.parseInt(matcher.group(1)), matcher.group(2).toUpperCase(Locale.ROOT));
    }

    private static String style(int size, String color) {
        return "-fx-font-size: " + size + "px; -fx-fill: " + color + ";";
    }

    /** Text colors are plain "#RRGGBB"; any alpha component is dropped. */
    public static String normalizeColor(String color) {
        if (color == null || color.length() < 7 || color.charAt(0) != '#') {
            return DmProject.DEFAULT_TEXT_COLOR;
        }
        return color.substring(0, 7).toUpperCase(Locale.ROOT);
    }

    private static double alpha(String color) {
        try {
            return Color.web(color).getOpacity();
        } catch (IllegalArgumentException | NullPointerException ex) {
            return 0;
        }
    }

    private static String css(String color) {
        Color parsed;
        try {
            parsed = Color.web(color);
        } catch (IllegalArgumentException | NullPointerException ex) {
            parsed = Color.TRANSPARENT;
        }
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                Math.round(parsed.getRed() * 255), Math.round(parsed.getGreen() * 255),
                Math.round(parsed.getBlue() * 255), parsed.getOpacity());
    }
}
