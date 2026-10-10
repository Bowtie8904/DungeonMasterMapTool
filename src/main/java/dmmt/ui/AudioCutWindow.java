package dmmt.ui;

import dmmt.audio.AudioCategory;
import dmmt.audio.AudioClipCutter;
import dmmt.audio.AudioFormats;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioOutput;
import dmmt.audio.AudioTrack;
import dmmt.audio.JavaFxAudioOutput;
import dmmt.audio.LoopCrossfade;
import dmmt.audio.WaveformPeaks;
import dmmt.service.Tuning;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The waveform window (3.35.3): it shows a long recording, lets the DM listen to any point of it, select a range
 * and write that range into the library as a standalone clip. Peaks are analysed once in the background and cached.
 */
public final class AudioCutWindow {
    private static final Color WAVE = Color.web("#8AB4F8");
    private static final Color WAVE_SELECTED = Color.web("#FFD166");
    private static final Color BACKGROUND = Color.web("#1B1D21");
    private static final Color GRID = Color.web("#2E3238");
    private static final double MIN_SPAN_MS = 500;

    private static Stage open;

    private final Stage stage;
    private final AudioLibraryService library;
    private final AudioTrack track;
    private final Runnable onChanged;

    private final Canvas canvas = new Canvas(900, 220);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Reading the waveform...");
    private final TextField startField = new TextField("0:00.000");
    private final TextField endField = new TextField("0:00.000");
    private final TextField nameField = new TextField();
    private final ComboBox<AudioCategory> categoryBox = new ComboBox<>();
    private final CheckBox asEffect = new CheckBox("As sound effect");
    private final CheckBox loop = new CheckBox("Loop");
    private final ObservableList<WaveformPeaks.Range> detected = FXCollections.observableArrayList();
    private final ListView<WaveformPeaks.Range> detectedList = new ListView<>(detected);

    private WaveformPeaks peaks;
    private long totalMs;
    private long viewStartMs;
    private long viewSpanMs;
    private long selectionStartMs = -1;
    private long selectionEndMs = -1;
    private long playheadMs;
    private double dragAnchorX = -1;
    private long dragAnchorMs;
    private boolean dragging;
    /** Which border of the highlighted range the current drag moves, if any. */
    private Edge dragEdge = Edge.NONE;
    private boolean syncingSelection;
    /** Every detected track currently selected in the list; all of them are highlighted in the waveform. */
    private final List<WaveformPeaks.Range> highlighted = new ArrayList<>();

    private enum Edge {
        NONE, START, END
    }

    private final AudioOutput output;
    private AudioOutput.Voice voice;
    private AudioOutput.Voice preparedVoice;
    private AudioOutput.Voice outgoingVoice;
    private final Map<AudioOutput.Voice, AudioLibraryService.PlaybackSource> playbackSources =
            new IdentityHashMap<>();
    private final Map<AudioOutput.Voice, PreviewReplacement> replacements = new IdentityHashMap<>();
    private record PreviewReplacement(AudioOutput.Voice handle, AudioLibraryService.PlaybackSource source) {
    }
    private LoopCrossfade blend;
    private long preparedStartMs = -1;
    private long preparedEndMs = -1;
    private long lastPlaybackNanos;
    private final DoubleSupplier loopCrossfadeSeconds;
    private boolean playing;
    private AnimationTimer timer;
    private Task<WaveformPeaks> analysis;
    private Task<List<String>> cutting;
    private Button createButton;
    private Button playButton;
    private final BooleanProperty cuttingBusy = new SimpleBooleanProperty();
    private int clipCounter = 1;

    private AudioCutWindow(Window owner, AudioLibraryService library, AudioTrack track, Runnable onChanged) {
        this(owner, library, track, onChanged, new JavaFxAudioOutput());
    }

    AudioCutWindow(Window owner, AudioLibraryService library, AudioTrack track, Runnable onChanged,
                   AudioOutput output) {
        this(owner, library, track, onChanged, output, () -> Tuning.AUDIO_EFFECT_LOOP_CROSSFADE_SECONDS.get());
    }

    AudioCutWindow(Window owner, AudioLibraryService library, AudioTrack track, Runnable onChanged,
                   AudioOutput output, DoubleSupplier loopCrossfadeSeconds) {
        this.output = output;
        this.loopCrossfadeSeconds = loopCrossfadeSeconds;
        this.library = library;
        this.track = track;
        this.onChanged = onChanged == null ? () -> {
        } : onChanged;
        this.totalMs = Math.max(1, track.getDurationMs());
        this.viewSpanMs = totalMs;
        this.stage = new Stage();
        stage.setTitle("Cut clips - " + track.getName());
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        Dialogs.inheritIcons(stage, owner);
        Scene scene = new Scene(buildRoot(), 980, 620);
        scene.getStylesheets().add(Icons.STYLESHEET);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.SPACE) {
                togglePlay();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                clearSelection();
            }
        });
        stage.setScene(scene);
        stage.setOnHidden(event -> dispose());
    }

    /** Opens the waveform window for a track; a second call focuses the window that is already open. */
    public static void show(Window owner, AudioLibraryService library, AudioTrack track, Runnable onChanged) {
        if (open != null && open.isShowing()) {
            open.close();
        }
        AudioCutWindow window = new AudioCutWindow(owner, library, track, onChanged);
        open = window.stage;
        window.stage.show();
        window.startAnalysis();
        window.startTicker();
    }

    // ---- Layout ----

    private Region buildRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(12));
        root.setTop(buildHeader());
        root.setCenter(buildCanvas());
        root.setRight(buildDetected());
        root.setBottom(buildClipTools());
        return root;
    }

    private Region buildHeader() {
        Label title = new Label(track.getName());
        title.getStyleClass().add("panel-title");
        Label length = new Label(AudioTrack.formatDuration(totalMs));
        length.getStyleClass().add("muted");
        progress.setPrefWidth(160);
        status.getStyleClass().add("muted");
        HBox header = new HBox(10, title, length, Icons.separator(), progress, status);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        return header;
    }

    private Region buildCanvas() {
        Pane holder = new Pane(canvas);
        holder.setMinHeight(180);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener((observable, oldValue, newValue) -> draw());
        canvas.heightProperty().addListener((observable, oldValue, newValue) -> draw());
        canvas.setOnMousePressed(this::onPressed);
        canvas.setOnMouseDragged(this::onDragged);
        canvas.setOnMouseReleased(this::onReleased);
        canvas.setOnMouseMoved(event -> updateCursor(event.getX()));
        canvas.setOnScroll(event -> {
            if (event.isControlDown() || event.isShiftDown()) {
                zoomAt(timeAt(event.getX()), event.getDeltaY() > 0 ? 0.8 : 1.25);
            } else {
                pan((long) (-event.getDeltaY() / Math.max(1, canvas.getWidth()) * viewSpanMs * 3));
            }
            event.consume();
        });
        VBox.setVgrow(holder, Priority.ALWAYS);
        VBox box = new VBox(8, holder, buildTransport());
        box.setPadding(new Insets(0, 8, 8, 0));
        VBox.setVgrow(holder, Priority.ALWAYS);
        return box;
    }

    private Region buildTransport() {
        Button play = Icons.button(MaterialDesignP.PLAY, "Play or pause (Space)", this::togglePlay);
        play.setId("audioCutPlay");
        playButton = play;
        refreshPlayButton();
        Button toStart = Icons.button(MaterialDesignS.SKIP_PREVIOUS, "Jump to the start of the selection",
                () -> seek(hasSelection() ? selectionStartMs : 0));
        loop.setSelected(true);
        loop.selectedProperty().addListener((observable, oldValue, selected) -> cancelLoopBlend());
        Icons.tooltip(loop, "Repeat what is shown: the selection, or the visible part of the waveform when "
                + "nothing is selected.");
        Button zoomIn = Icons.button(MaterialDesignM.MAGNIFY_PLUS_OUTLINE, "Zoom in",
                () -> zoomAt(playheadMs, 0.6));
        Button zoomOut = Icons.button(MaterialDesignM.MAGNIFY_MINUS_OUTLINE, "Zoom out",
                () -> zoomAt(playheadMs, 1.6));
        Button fit = Icons.button(MaterialDesignM.MAGNIFY_SCAN, "Show the whole file", this::zoomToFit);
        Button zoomSelection = Icons.button(MaterialDesignS.SELECT_SEARCH, "Zoom to the selection", () -> {
            if (hasSelection()) {
                viewSpanMs = Math.max((long) MIN_SPAN_MS, (long) ((selectionEndMs - selectionStartMs) * 1.2));
                viewStartMs = clampStart(selectionStartMs - (viewSpanMs - (selectionEndMs - selectionStartMs)) / 2);
                cancelLoopBlend();
                draw();
            }
        });

        startField.setPrefColumnCount(9);
        endField.setPrefColumnCount(9);
        startField.setOnAction(event -> applyFields());
        endField.setOnAction(event -> applyFields());
        Icons.tooltip(startField, "Start of the clip as m:ss.mmm");
        Icons.tooltip(endField, "End of the clip as m:ss.mmm");
        Button setStart = Icons.button(MaterialDesignC.CONTENT_CUT, "Set the clip start to the playhead",
                () -> setSelection(playheadMs, hasSelection() && selectionEndMs > playheadMs ? selectionEndMs : totalMs));
        Button setEnd = Icons.button(MaterialDesignC.CONTENT_SAVE_MOVE_OUTLINE, "Set the clip end to the playhead",
                () -> setSelection(hasSelection() && selectionStartMs < playheadMs ? selectionStartMs : 0, playheadMs));

        HBox row = new HBox(6, play, toStart, loop, Icons.separator(), zoomOut, zoomIn, fit, zoomSelection,
                Icons.separator(), new Label("From"), startField, setStart, new Label("to"), endField, setEnd);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region buildDetected() {
        Label title = new Label("Detected tracks");
        title.getStyleClass().add("panel-title");
        detectedList.setId("audioCutDetected");
        detectedList.setPrefWidth(280);
        detectedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Icons.tooltip(detectedList, "Ctrl-click to select several tracks; Shift-click to select a range.");
        detectedList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(WaveformPeaks.Range range, boolean empty) {
                super.updateItem(range, empty);
                setText(empty || range == null ? null
                        : (getIndex() + 1) + ".  " + AudioTrack.formatDuration(range.startMs())
                        + " - " + AudioTrack.formatDuration(range.endMs())
                        + "  (" + AudioTrack.formatDuration(range.durationMs()) + ")");
            }
        });
        detectedList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<WaveformPeaks.Range>) change -> onDetectedSelectionChanged());
        detectedList.setPlaceholder(new Label("Use \"Detect\" to find\nthe songs in this file."));
        detectedList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelectedTracks();
                event.consume();
            }
        });
        VBox.setVgrow(detectedList, Priority.ALWAYS);

        Button detect = new Button("Detect");
        detect.setId("audioCutDetect");
        detect.disableProperty().bind(cuttingBusy);
        detect.setGraphic(Icons.icon(MaterialDesignS.SCISSORS_CUTTING));
        Icons.tooltip(detect, "Find songs by looking for the silent gaps between them.");
        detect.setOnAction(event -> detectTracks());
        Button update = new Button("Update bounds");
        update.setId("audioCutUpdateBounds");
        update.disableProperty().bind(cuttingBusy.or(
                Bindings.size(detectedList.getSelectionModel().getSelectedItems()).isNotEqualTo(1)));
        Icons.tooltip(update, "Select one track, adjust From/to or drag a waveform range, then apply its bounds. "
                + "The borders of the highlighted track can also be dragged directly in the waveform.");
        update.setOnAction(event -> updateSelectedTrackBounds());
        Button all = new Button("Create all");
        all.setId("audioCutCreateAll");
        all.disableProperty().bind(cuttingBusy.or(Bindings.isEmpty(detected)));
        Icons.tooltip(all, "Write every detected track into the library as a standalone clip.");
        all.setOnAction(event -> createAllClips());
        Button selected = new Button("Create selected");
        selected.setId("audioCutCreateSelected");
        selected.disableProperty().bind(cuttingBusy.or(
                Bindings.isEmpty(detectedList.getSelectionModel().getSelectedItems())));
        Icons.tooltip(selected, "Write only the selected detected tracks into the library as standalone clips.");
        selected.setOnAction(event -> createSelectedClips());
        Button merge = new Button("Merge selected");
        merge.setId("audioCutMerge");
        merge.disableProperty().bind(cuttingBusy.or(
                Bindings.size(detectedList.getSelectionModel().getSelectedItems()).lessThan(2)));
        Icons.tooltip(merge, "Replace the selected tracks with one range from the earliest start to the latest end.");
        merge.setOnAction(event -> mergeSelectedTracks());
        Button delete = new Button("Delete selected");
        delete.setId("audioCutDelete");
        delete.disableProperty().bind(selected.disableProperty());
        Icons.tooltip(delete, "Remove the selected proposals, not the source audio (Delete or Backspace).");
        delete.setOnAction(event -> deleteSelectedTracks());
        HBox editing = new HBox(6, merge, delete);
        HBox buttons = new HBox(6, all, selected);

        VBox box = new VBox(8, title, new HBox(6, detect, update), detectedList, editing, buttons);
        box.setPadding(new Insets(0, 0, 0, 12));
        return box;
    }

    private Region buildClipTools() {
        nameField.setPromptText("Name of the clip");
        nameField.setText(track.getName());
        Icons.tooltip(nameField, "Name for Create clip; base name plus track number for Create selected / Create all.");
        HBox.setHgrow(nameField, Priority.ALWAYS);

        categoryBox.setItems(FXCollections.observableArrayList(library.categories()));
        categoryBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AudioCategory category) {
                return category == null ? "" : category.getName();
            }

            @Override
            public AudioCategory fromString(String value) {
                return null;
            }
        });
        categoryBox.getSelectionModel().select(library.category(track.getCategoryId()).orElse(
                library.categories().isEmpty() ? null : library.categories().get(0)));
        asEffect.selectedProperty().addListener((observable, oldValue, newValue) -> categoryBox.setDisable(newValue));

        Button create = new Button("Create clip");
        create.setDefaultButton(true);
        create.setGraphic(Icons.icon(MaterialDesignC.CONTENT_CUT));
        create.setOnAction(event -> createClip());
        createButton = create;

        Label hint = new Label("Drag across the waveform to select, click to listen from there, Space plays. "
                + "Drag the borders of the highlighted range to move them.");
        hint.getStyleClass().add("muted");

        HBox row = new HBox(8, new Label("Clip name"), nameField, categoryBox, asEffect, create);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, row, hint);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    // ---- Waveform analysis ----

    private void startAnalysis() {
        Path file = library.fileOf(track);
        Path cache = library.peaksFileOf(track);
        int frameMs = Tuning.AUDIO_WAVEFORM_FRAME_MS.get();
        WaveformPeaks cached = WaveformPeaks.readCache(cache, frameMs);
        if (cached != null) {
            usePeaks(cached);
            return;
        }
        analysis = new Task<>() {
            @Override
            protected WaveformPeaks call() throws Exception {
                return WaveformPeaks.analyze(file, frameMs, this::updateProgress2);
            }

            private void updateProgress2(double value) {
                updateProgress(value, 1);
            }
        };
        progress.progressProperty().bind(analysis.progressProperty());
        analysis.setOnSucceeded(event -> {
            progress.progressProperty().unbind();
            WaveformPeaks result = analysis.getValue();
            try {
                Files.createDirectories(cache.getParent());
                result.writeCache(cache);
            } catch (IOException | RuntimeException e) {
                // the cache is only an optimisation
            }
            usePeaks(result);
        });
        analysis.setOnFailed(event -> {
            progress.progressProperty().unbind();
            progress.setProgress(0);
            status.setText("The waveform could not be read: " + analysis.getException().getMessage());
        });
        Thread thread = new Thread(analysis, "audio-waveform");
        thread.setDaemon(true);
        thread.start();
    }

    private void usePeaks(WaveformPeaks result) {
        peaks = result;
        if (result.durationMs() > 0) {
            totalMs = result.durationMs();
            viewSpanMs = Math.min(viewSpanMs <= 0 ? totalMs : viewSpanMs, totalMs);
        }
        progress.setProgress(1);
        status.setText("Ready");
        zoomToFit();
    }

    private void detectTracks() {
        if (peaks == null) {
            return;
        }
        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                Tuning.AUDIO_CUT_AUTO_THRESHOLD.get(), Tuning.AUDIO_SILENCE_DB.get(), Tuning.AUDIO_CUT_DROP_DB.get(),
                Tuning.AUDIO_MIN_SILENCE_SECONDS.get(), Tuning.AUDIO_MIN_TRACK_SECONDS.get(),
                Tuning.AUDIO_CUT_MIN_GAP_DROP_DB.get(), Tuning.AUDIO_CUT_DETECT_CHANGES.get(),
                Tuning.AUDIO_CUT_CHANGE_WINDOW_SECONDS.get(), Tuning.AUDIO_CUT_CHANGE_SENSITIVITY.get(),
                Tuning.AUDIO_CUT_DETECT_LOOPS.get()));
        List<WaveformPeaks.Range> ranges = detection.tracks();
        detected.setAll(ranges);
        status.setText(ranges.size() + (ranges.size() == 1 ? " track found" : " tracks found")
                + " (silence below " + Math.round(detection.thresholdDb()) + " dB"
                + (detection.changeBorders() > 0 ? ", " + detection.changeBorders() + " from changes in the music" : "")
                + (detection.loopRepetitions() > 0 ? ", looped " + detection.loopRepetitions() + " times" : "")
                + (detection.repeatsRemoved() > 0 ? ", " + detection.repeatsRemoved() + " repeats removed" : "")
                + ")");
        draw();
    }

    private void mergeSelectedTracks() {
        List<WaveformPeaks.Range> selected = List.copyOf(detectedList.getSelectionModel().getSelectedItems());
        if (cuttingBusy.get() || selected.size() < 2) {
            return;
        }
        long start = selected.stream().mapToLong(WaveformPeaks.Range::startMs).min().orElseThrow();
        long end = selected.stream().mapToLong(WaveformPeaks.Range::endMs).max().orElseThrow();
        WaveformPeaks.Range merged = new WaveformPeaks.Range(start, end);
        int index = detectedList.getSelectionModel().getSelectedIndices().getFirst();
        List<WaveformPeaks.Range> remaining = new ArrayList<>(detected);
        for (int selectedIndex : detectedList.getSelectionModel().getSelectedIndices().reversed()) {
            remaining.remove(selectedIndex);
        }
        remaining.add(index, merged);
        clearSelection();
        detected.setAll(remaining);
        detectedList.getSelectionModel().clearAndSelect(index);
        status.setText(selected.size() + " tracks merged");
        draw();
    }

    private void deleteSelectedTracks() {
        List<Integer> indices = List.copyOf(detectedList.getSelectionModel().getSelectedIndices());
        if (cuttingBusy.get() || indices.isEmpty()) {
            return;
        }
        for (int index : indices.reversed()) {
            detected.remove(index);
        }
        clearSelection();
        status.setText(indices.size() + (indices.size() == 1 ? " track removed" : " tracks removed"));
    }

    private void updateSelectedTrackBounds() {
        if (cuttingBusy.get() || detectedList.getSelectionModel().getSelectedIndices().size() != 1) {
            return;
        }
        long start = parseTime(startField.getText(), -1);
        long end = parseTime(endField.getText(), -1);
        if (start < 0 || end <= start || end > totalMs) {
            status.setText("Invalid bounds: enter times within the file, with the end after the start.");
            return;
        }
        int index = detectedList.getSelectionModel().getSelectedIndex();
        detected.set(index, new WaveformPeaks.Range(start, end));
        detectedList.getSelectionModel().clearAndSelect(index);
        setSelection(start, end);
        status.setText("Track " + (index + 1) + " bounds updated");
    }

    // ---- Drawing ----

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);
        if (width < 2 || height < 2) {
            return;
        }
        double rulerHeight = 18;
        double waveTop = rulerHeight;
        double waveHeight = height - rulerHeight;
        double middle = waveTop + waveHeight / 2;

        drawRuler(g, width, rulerHeight);

        List<WaveformPeaks.Range> highlights = highlightRanges();
        for (WaveformPeaks.Range range : highlights) {
            double from = xOf(range.startMs());
            double to = xOf(range.endMs());
            g.setFill(Color.web("#FFD166", 0.12));
            g.fillRect(Math.min(from, to), waveTop, Math.abs(to - from), waveHeight);
        }
        for (WaveformPeaks.Range range : detected) {
            double from = xOf(range.startMs());
            double to = xOf(range.endMs());
            g.setStroke(Color.web("#7CCB8A", 0.6));
            g.setLineWidth(1);
            g.strokeLine(from, waveTop, from, waveTop + waveHeight);
            g.strokeLine(to, waveTop, to, waveTop + waveHeight);
        }

        if (peaks == null) {
            g.setFill(Color.web("#9AA0A6"));
            g.fillText("Reading the waveform...", 12, middle);
            return;
        }
        g.setStroke(WAVE);
        g.setLineWidth(1);
        for (int x = 0; x < width; x++) {
            long from = timeAt(x);
            long to = timeAt(x + 1.0);
            int firstBucket = peaks.bucketAt(from);
            int lastBucket = Math.max(firstBucket, peaks.bucketAt(Math.max(from, to - 1)));
            float minimum = 0;
            float maximum = 0;
            for (int bucket = firstBucket; bucket <= lastBucket && bucket < peaks.buckets(); bucket++) {
                minimum = Math.min(minimum, peaks.minimum(bucket));
                maximum = Math.max(maximum, peaks.maximum(bucket));
            }
            boolean selected = isHighlighted(highlights, from);
            g.setStroke(selected ? WAVE_SELECTED : WAVE);
            double top = middle - maximum * (waveHeight / 2 - 2);
            double bottom = middle - minimum * (waveHeight / 2 - 2);
            g.strokeLine(x + 0.5, top, x + 0.5, Math.max(bottom, top + 0.6));
        }

        if (hasSelection()) {
            g.setStroke(WAVE_SELECTED);
            g.setLineWidth(2);
            g.strokeLine(xOf(selectionStartMs), waveTop, xOf(selectionStartMs), waveTop + waveHeight);
            g.strokeLine(xOf(selectionEndMs), waveTop, xOf(selectionEndMs), waveTop + waveHeight);
        }

        double playX = xOf(playheadMs);
        g.setStroke(Color.web("#FFFFFF", 0.9));
        g.setLineWidth(1);
        g.strokeLine(playX, waveTop, playX, waveTop + waveHeight);
    }

    private void drawRuler(GraphicsContext g, double width, double rulerHeight) {
        g.setFill(Color.web("#16181B"));
        g.fillRect(0, 0, width, rulerHeight);
        g.setFont(Font.font(10));
        g.setFill(Color.web("#9AA0A6"));
        long step = rulerStep();
        long first = (viewStartMs / step) * step;
        for (long time = first; time <= viewStartMs + viewSpanMs; time += step) {
            double x = xOf(time);
            g.setStroke(GRID);
            g.strokeLine(x, 0, x, rulerHeight);
            g.fillText(AudioTrack.formatDuration(time), x + 3, rulerHeight - 5);
        }
    }

    private long rulerStep() {
        long[] steps = {1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000,
                3_600_000};
        double target = viewSpanMs / Math.max(1, canvas.getWidth() / 90);
        for (long step : steps) {
            if (step >= target) {
                return step;
            }
        }
        return steps[steps.length - 1];
    }

    // ---- Interaction ----

    private void onPressed(MouseEvent event) {
        canvas.requestFocus();
        dragEdge = event.isShiftDown() ? Edge.NONE : edgeAt(event.getX());
        dragAnchorX = event.getX();
        if (dragEdge != Edge.NONE) {
            // The opposite border stays where it is while this one is dragged.
            dragAnchorMs = dragEdge == Edge.START ? selectionEndMs : selectionStartMs;
            dragging = true;
            canvas.setCursor(javafx.scene.Cursor.H_RESIZE);
            return;
        }
        dragAnchorMs = timeAt(event.getX());
        dragging = false;
    }

    private void onDragged(MouseEvent event) {
        if (dragAnchorX < 0) {
            return;
        }
        if (dragEdge != Edge.NONE) {
            dragSelectionEdge(timeAt(event.getX()));
            return;
        }
        if (Math.abs(event.getX() - dragAnchorX) > 3) {
            dragging = true;
            setSelection(Math.min(dragAnchorMs, timeAt(event.getX())), Math.max(dragAnchorMs, timeAt(event.getX())));
        }
    }

    private void onReleased(MouseEvent event) {
        if (dragEdge != Edge.NONE) {
            dragSelectionEdge(timeAt(event.getX()));
            int index = singleSelectedTrackIndex();
            status.setText(index < 0 ? "Selection border moved" : "Track " + (index + 1) + " bounds updated");
            dragEdge = Edge.NONE;
            dragAnchorX = -1;
            dragging = false;
            updateCursor(event.getX());
            return;
        }
        if (!dragging) {
            seek(timeAt(event.getX()));
        }
        dragAnchorX = -1;
        dragging = false;
    }

    /**
     * The border of the highlighted range under the mouse (3.35.3). Only the editable range has handles, so the
     * borders of the other detected tracks can never be moved by accident.
     */
    private Edge edgeAt(double x) {
        if (!hasSelection()) {
            return Edge.NONE;
        }
        double tolerance = Tuning.AUDIO_CUT_EDGE_GRAB_PIXELS.get();
        double toStart = Math.abs(x - xOf(selectionStartMs));
        double toEnd = Math.abs(x - xOf(selectionEndMs));
        if (toStart <= tolerance && toStart <= toEnd) {
            return Edge.START;
        }
        return toEnd <= tolerance ? Edge.END : Edge.NONE;
    }

    private void updateCursor(double x) {
        canvas.setCursor(edgeAt(x) == Edge.NONE ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.H_RESIZE);
    }

    /** Moves the dragged border to {@code millis}, keeping the other one and never letting the two cross. */
    private void dragSelectionEdge(long millis) {
        long other = dragAnchorMs;
        if (dragEdge == Edge.START) {
            setSelection(Math.min(millis, other - 1), other);
        } else {
            setSelection(other, Math.max(millis, other + 1));
        }
        applySelectionToSelectedTrack();
    }

    /** Index of the only selected detected track, or -1 when none or several are selected. */
    private int singleSelectedTrackIndex() {
        if (detectedList.getSelectionModel().getSelectedItems().size() != 1) {
            return -1;
        }
        int index = detectedList.getSelectionModel().getSelectedIndex();
        return index >= 0 && index < detected.size() ? index : -1;
    }

    /** Writes the dragged range back into the single selected proposal, so the list follows the drag live. */
    private void applySelectionToSelectedTrack() {
        int index = singleSelectedTrackIndex();
        if (cuttingBusy.get() || index < 0 || !hasSelection()) {
            return;
        }
        WaveformPeaks.Range range = new WaveformPeaks.Range(selectionStartMs, selectionEndMs);
        if (range.equals(detected.get(index))) {
            return;
        }
        syncingSelection = true;
        try {
            detected.set(index, range);
            detectedList.getSelectionModel().clearAndSelect(index);
        } finally {
            syncingSelection = false;
        }
        highlighted.clear();
        highlighted.add(range);
        draw();
    }

    /**
     * Keeps the waveform in step with the list: every selected proposal is highlighted, and a single selected
     * proposal also becomes the editable From/to range.
     */
    private void onDetectedSelectionChanged() {
        if (syncingSelection) {
            return;
        }
        List<WaveformPeaks.Range> selected = new ArrayList<>(detectedList.getSelectionModel().getSelectedItems());
        selected.removeIf(java.util.Objects::isNull);
        highlighted.clear();
        highlighted.addAll(selected);
        if (selected.size() == 1) {
            setSelection(selected.getFirst().startMs(), selected.getFirst().endMs());
        } else {
            if (!selected.isEmpty()) {
                cancelLoopBlend();
                selectionStartMs = -1;
                selectionEndMs = -1;
            }
            draw();
        }
    }

    /** The ranges painted as highlighted: every selected proposal plus the hand-drawn selection. */
    private List<WaveformPeaks.Range> highlightRanges() {
        List<WaveformPeaks.Range> ranges = new ArrayList<>(highlighted);
        if (hasSelection()) {
            WaveformPeaks.Range selection = new WaveformPeaks.Range(selectionStartMs, selectionEndMs);
            if (!ranges.contains(selection)) {
                ranges.add(selection);
            }
        }
        return ranges;
    }

    private static boolean isHighlighted(List<WaveformPeaks.Range> ranges, long millis) {
        for (WaveformPeaks.Range range : ranges) {
            if (millis >= range.startMs() && millis <= range.endMs()) {
                return true;
            }
        }
        return false;
    }

    private void zoomAt(long anchorMs, double factor) {
        long span = (long) Math.max(MIN_SPAN_MS, Math.min(totalMs, viewSpanMs * factor));
        double anchorFraction = viewSpanMs == 0 ? 0.5 : (anchorMs - viewStartMs) / (double) viewSpanMs;
        viewSpanMs = span;
        viewStartMs = clampStart(anchorMs - (long) (anchorFraction * span));
        cancelLoopBlend();
        draw();
    }

    private void zoomToFit() {
        viewStartMs = 0;
        viewSpanMs = totalMs;
        cancelLoopBlend();
        draw();
    }

    private void pan(long deltaMs) {
        viewStartMs = clampStart(viewStartMs + deltaMs);
        cancelLoopBlend();
        draw();
    }

    private long clampStart(long value) {
        return Math.max(0, Math.min(value, Math.max(0, totalMs - viewSpanMs)));
    }

    private long timeAt(double x) {
        double fraction = canvas.getWidth() <= 0 ? 0 : x / canvas.getWidth();
        return Math.max(0, Math.min(totalMs, viewStartMs + (long) (fraction * viewSpanMs)));
    }

    private double xOf(long millis) {
        if (viewSpanMs <= 0) {
            return 0;
        }
        return (millis - viewStartMs) / (double) viewSpanMs * canvas.getWidth();
    }

    private boolean hasSelection() {
        return selectionStartMs >= 0 && selectionEndMs > selectionStartMs;
    }

    private void setSelection(long startMs, long endMs) {
        cancelLoopBlend();
        selectionStartMs = Math.max(0, Math.min(startMs, totalMs));
        selectionEndMs = Math.max(selectionStartMs, Math.min(endMs, totalMs));
        startField.setText(formatSelectionTime(selectionStartMs));
        endField.setText(formatSelectionTime(selectionEndMs));
        draw();
    }

    private static String formatSelectionTime(long millis) {
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60_000, (millis / 1000) % 60, millis % 1000);
    }

    private void clearSelection() {
        cancelLoopBlend();
        selectionStartMs = -1;
        selectionEndMs = -1;
        highlighted.clear();
        detectedList.getSelectionModel().clearSelection();
        draw();
    }

    private void applyFields() {
        long start = parseTime(startField.getText(), selectionStartMs < 0 ? 0 : selectionStartMs);
        long end = parseTime(endField.getText(), selectionEndMs < 0 ? totalMs : selectionEndMs);
        setSelection(start, end);
    }

    /** Parses {@code m:ss.mmm}, {@code h:mm:ss} or a plain number of seconds. */
    static long parseTime(String text, long fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            String[] parts = text.trim().split(":");
            double seconds = Double.parseDouble(parts[parts.length - 1].replace(',', '.'));
            if (!Double.isFinite(seconds) || seconds < 0) {
                return fallback;
            }
            long millis = Math.round(seconds * 1000);
            if (parts.length > 1) {
                millis += Long.parseLong(parts[parts.length - 2].trim()) * 60_000L;
            }
            if (parts.length > 2) {
                millis += Long.parseLong(parts[parts.length - 3].trim()) * 3_600_000L;
            }
            return millis < 0 ? fallback : millis;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- Preview player ----

    private void togglePlay() {
        if (playing) {
            if (voice != null) {
                voice.pause();
            }
            if (outgoingVoice != null) {
                outgoingVoice.pause();
            }
            setPlaying(false);
            return;
        }
        if (voice == null) {
            AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track);
            voice = output.open(source.file(), false);
            if (voice == null) {
                status.setText("This file cannot be played here.");
                return;
            }
            playbackSources.put(voice, source);
            refreshPreviewVolumes();
            setPreviewEndHandler(voice);
            // Start where the playhead is; a seek before the media is ready is applied once it becomes ready.
            voice.seek(playheadMs);
        }
        if (loop.isSelected() && playheadMs >= loopEndMs()) {
            seek(loopStartMs());
        }
        voice.play();
        if (outgoingVoice != null) {
            if (outgoingVoice.positionMs() < loopEndMs()) {
                outgoingVoice.play();
            }
            blend.resume();
        }
        lastPlaybackNanos = 0;
        setPlaying(true);
        prepareLoopVoice();
    }

    private void setPlaying(boolean value) {
        playing = value;
        refreshPlayButton();
    }

    private void refreshPlayButton() {
        if (playButton != null) {
            playButton.setGraphic(Icons.icon(playing ? MaterialDesignP.PAUSE : MaterialDesignP.PLAY));
            Icons.tooltip(playButton, playing ? "Pause (Space)" : "Play (Space)");
        }
    }

    /**
     * Start of the looped range (3.35.3): the selection when there is one, otherwise what the waveform currently
     * shows, so zooming into a song is enough to hear it repeat.
     */
    private long loopStartMs() {
        return hasSelection() ? selectionStartMs : Math.max(0, viewStartMs);
    }

    private long loopEndMs() {
        return Math.min(totalMs, hasSelection() ? selectionEndMs : viewStartMs + viewSpanMs);
    }

    private void restartLoop() {
        seek(loopStartMs());
        voice.play();
    }

    private void setPreviewEndHandler(AudioOutput.Voice handle) {
        handle.setOnEnd(() -> {
            if (voice == handle) {
                onPreviewEnd();
            }
        });
    }

    private void cancelLoopBlend() {
        if (preparedVoice != null) {
            disposePreviewVoice(preparedVoice);
            preparedVoice = null;
        }
        if (outgoingVoice != null) {
            disposePreviewVoice(outgoingVoice);
            outgoingVoice = null;
        }
        blend = null;
        preparedStartMs = -1;
        preparedEndMs = -1;
        if (voice != null) {
            refreshPreviewVolumes();
        }
    }

    private void prepareLoopVoice() {
        if (!loop.isSelected() || loopCrossfadeSeconds.getAsDouble() <= 0 || outgoingVoice != null) {
            return;
        }
        if (preparedVoice == null) {
            AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track);
            preparedVoice = output.open(source.file(), false);
            if (preparedVoice == null) {
                status.setText("Could not preload the loop; preview will repeat without a crossfade.");
                return;
            }
            playbackSources.put(preparedVoice, source);
            preparedVoice.setVolume(0);
        }
        if (preparedVoice.isReady() && preparedStartMs < 0) {
            preparedStartMs = loopStartMs();
            preparedEndMs = loopEndMs();
            preparedVoice.seek(preparedStartMs);
        }
    }

    private void startLoopBlend(double remainingMs) {
        outgoingVoice = voice;
        voice = preparedVoice;
        preparedVoice = null;
        blend = new LoopCrossfade(preparedStartMs, remainingMs / 1000);
        setPreviewEndHandler(voice);
        voice.play();
        playheadMs = preparedStartMs;
    }

    private void onPreviewEnd() {
        if (!playing || voice == null) {
            return;
        }
        if (loop.isSelected()) {
            restartLoop();
        } else {
            setPlaying(false);
            seek(hasSelection() ? selectionStartMs : totalMs);
        }
    }

    private void updatePlayback() {
        refreshPreviewSources();
        refreshPreviewVolumes();
        if (playing && voice != null) {
            if (!voice.isReady()) {
                // A seek is still waiting for the media to initialise; its position would read as 0.
                lastPlaybackNanos = 0;
                draw();
                return;
            }
            long now = System.nanoTime();
            double delta = lastPlaybackNanos == 0 ? 0 : Math.min(1, (now - lastPlaybackNanos) / 1_000_000_000.0);
            lastPlaybackNanos = now;
            if (loopCrossfadeSeconds.getAsDouble() <= 0 && (preparedVoice != null || outgoingVoice != null)) {
                cancelLoopBlend();
            }
            if ((preparedVoice != null || outgoingVoice != null) && preparedStartMs >= 0
                    && (preparedStartMs != loopStartMs() || preparedEndMs != loopEndMs())) {
                cancelLoopBlend();
            }
            double positionMs = voice.positionMs();
            playheadMs = (long) positionMs;
            if (loop.isSelected()) {
                if (outgoingVoice != null) {
                    blend.advance(positionMs, delta);
                    refreshPreviewVolumes();
                    if (blend.finished()) {
                        disposePreviewVoice(outgoingVoice);
                        outgoingVoice = null;
                        blend = null;
                        preparedStartMs = -1;
                        preparedEndMs = -1;
                    }
                } else {
                    prepareLoopVoice();
                    double remainingMs = loopEndMs() - positionMs;
                    double overlapMs = Math.min(loopCrossfadeSeconds.getAsDouble() * 1000,
                            (loopEndMs() - loopStartMs()) / 2.0);
                    if (remainingMs > 0 && remainingMs <= overlapMs && preparedVoice != null
                            && preparedStartMs >= 0 && preparedVoice.isReady()
                            && Math.abs(preparedVoice.positionMs() - preparedStartMs)
                            <= Math.min(50, (loopEndMs() - loopStartMs()) / 4.0)) {
                        startLoopBlend(remainingMs);
                    }
                }
                if (outgoingVoice != null && outgoingVoice.positionMs() >= loopEndMs()) {
                    outgoingVoice.pause();
                }
                if (voice.positionMs() >= loopEndMs() || voice.positionMs() >= totalMs) {
                    restartLoop();
                }
            } else if (hasSelection() && positionMs >= selectionEndMs) {
                voice.pause();
                setPlaying(false);
                seek(selectionStartMs);
            }
            draw();
        }
    }

    private void refreshPreviewVolumes() {
        if (voice != null) {
            voice.setVolume(0.8 * track.playbackVolumeFactor(playbackSources.get(voice).bakedGainDb())
                    * (blend == null ? 1 : blend.incomingGain()));
        }
        if (outgoingVoice != null) {
            outgoingVoice.setVolume(0.8 * track.playbackVolumeFactor(playbackSources.get(outgoingVoice).bakedGainDb())
                    * blend.outgoingGain());
        }
    }

    private void refreshPreviewSources() {
        AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track);
        AudioOutput.Voice previous = voice;
        voice = replacePreviewVoice(voice, source, playing);
        preparedVoice = replacePreviewVoice(preparedVoice, source, false);
        outgoingVoice = replacePreviewVoice(outgoingVoice, source,
                playing && outgoingVoice != null && outgoingVoice.positionMs() < loopEndMs());
        if (voice != null && voice != previous) {
            setPreviewEndHandler(voice);
        }
    }

    private AudioOutput.Voice replacePreviewVoice(AudioOutput.Voice old,
                                                  AudioLibraryService.PlaybackSource source, boolean resume) {
        if (old == null) {
            return null;
        }
        PreviewReplacement pending = replacements.get(old);
        if (pending != null && !pending.source().equals(source)) {
            pending.handle().dispose();
            replacements.remove(old);
            pending = null;
        }
        if (playbackSources.get(old).equals(source)) {
            return old;
        }
        if (pending == null) {
            AudioOutput.Voice handle = output.open(source.file(), false);
            if (handle == null) {
                status.setText("Could not apply the new loudness playback copy.");
                return old;
            }
            handle.setVolume(0);
            pending = new PreviewReplacement(handle, source);
            replacements.put(old, pending);
        }
        if (!pending.handle().isReady()) {
            return old;
        }
        AudioOutput.Voice replacement = pending.handle();
        replacement.seek(old.positionMs());
        replacements.remove(old);
        playbackSources.put(replacement, source);
        disposePreviewVoice(old);
        if (resume) {
            replacement.play();
        }
        return replacement;
    }

    private void disposePreviewVoice(AudioOutput.Voice handle) {
        PreviewReplacement pending = replacements.remove(handle);
        if (pending != null) {
            pending.handle().dispose();
        }
        playbackSources.remove(handle);
        handle.dispose();
    }

    private void seek(long millis) {
        cancelLoopBlend();
        playheadMs = Math.max(0, Math.min(millis, totalMs));
        if (voice != null) {
            voice.seek(playheadMs);
        }
        draw();
    }

    private void startTicker() {
        timer = new AnimationTimer() {
            private long lastDraw;

            @Override
            public void handle(long now) {
                if (now - lastDraw < 40_000_000L) {
                    return;
                }
                lastDraw = now;
                updatePlayback();
            }
        };
        timer.start();
    }

    // ---- Clips ----

    private void createClip() {
        if (!hasSelection()) {
            Dialogs.error(stage, "Select a part of the file first",
                    "Drag across the waveform, or use \"Detect\" to find the songs automatically.");
            return;
        }
        String name = nameField.getText();
        runClips(List.of(new ClipRequest(name == null || name.isBlank() ? nextClipName() : name.trim(),
                selectionStartMs, selectionEndMs)));
    }

    private void createAllClips() {
        createDetectedClips(false);
    }

    private void createSelectedClips() {
        createDetectedClips(true);
    }

    private void createDetectedClips(boolean selectedOnly) {
        List<ClipRequest> requests = detectedClipRequests(selectedOnly);
        if (requests.isEmpty()) {
            return;
        }
        if (!Dialogs.confirm(stage, "Create clips", "Create " + requests.size() + " clips?",
                MaterialDesignC.CONTENT_CUT,
                (selectedOnly ? "Each selected track" : "Every remaining detected track")
                        + " is written into the library as its own audio file.", "Create")) {
            return;
        }
        runClips(requests);
    }

    private List<ClipRequest> detectedClipRequests(boolean selectedOnly) {
        List<ClipRequest> requests = new ArrayList<>();
        String baseName = clipBaseName();
        Pattern numberedName = Pattern.compile(Pattern.quote(baseName) + " ([0-9]+)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        BigInteger highestNumber = BigInteger.ZERO;
        for (AudioTrack existing : library.tracks()) {
            if (existing.getName() != null) {
                Matcher match = numberedName.matcher(existing.getName().trim());
                if (match.matches()) {
                    highestNumber = highestNumber.max(new BigInteger(match.group(1)));
                }
            }
        }
        List<Integer> indices = selectedOnly
                ? List.copyOf(detectedList.getSelectionModel().getSelectedIndices())
                : java.util.stream.IntStream.range(0, detected.size()).boxed().toList();
        for (int index : indices) {
            WaveformPeaks.Range range = detected.get(index);
            BigInteger number = highestNumber.add(BigInteger.valueOf(index + 1L));
            requests.add(new ClipRequest(baseName + " " + String.format(Locale.ROOT, "%02d", number),
                    range.startMs(), range.endMs()));
        }
        return requests;
    }

    /**
     * Writes the requested clips in the background (3.35.3) and keeps the header progress bar and status line
     * updated, so long cuts never freeze the window and never look like nothing happened.
     */
    private void runClips(List<ClipRequest> requests) {
        if (requests.isEmpty() || (cutting != null && cutting.isRunning())) {
            return;
        }
        AudioKind kind = asEffect.isSelected() ? AudioKind.EFFECT : AudioKind.MUSIC;
        AudioCategory category = categoryBox.getSelectionModel().getSelectedItem();
        String categoryId = category == null ? track.getCategoryId() : category.getId();
        int total = requests.size();
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                List<String> failures = new ArrayList<>();
                int done = 0;
                for (ClipRequest request : requests) {
                    if (isCancelled()) {
                        break;
                    }
                    updateMessage(total == 1
                            ? "Creating \"" + request.name() + "\"..."
                            : "Creating clip " + (done + 1) + " of " + total + " - " + request.name());
                    String failure = writeClip(request, kind, categoryId);
                    if (failure != null) {
                        failures.add(request.name() + ": " + failure);
                    }
                    done++;
                    updateProgress(done, total);
                }
                return failures;
            }
        };
        cutting = task;
        progress.progressProperty().unbind();
        progress.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((observable, oldValue, message) -> status.setText(message));
        status.setText("Creating clips...");
        setCuttingBusy(true);
        task.setOnSucceeded(event -> {
            List<String> failures = task.getValue();
            int created = total - failures.size();
            finishCutting(created == 0
                    ? "No clip was created"
                    : created + (created == 1 ? " clip added to the library" : " clips added to the library"));
            clipCounter += created;
            onChanged.run();
            if (!failures.isEmpty()) {
                Dialogs.error(stage, failures.size() + (failures.size() == 1
                        ? " clip could not be created" : " clips could not be created"), String.join("\n", failures));
            }
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            finishCutting("Clip failed: " + (error == null ? "unknown error" : error.getMessage()));
            onChanged.run();
        });
        task.setOnCancelled(event -> finishCutting("Cutting cancelled"));
        Thread thread = new Thread(task, "audio-cut");
        thread.setDaemon(true);
        thread.start();
    }

    private void setCuttingBusy(boolean busy) {
        cuttingBusy.set(busy);
        if (createButton != null) {
            createButton.setDisable(busy);
        }
        detectedList.setDisable(busy);
        nameField.setDisable(busy);
        categoryBox.setDisable(busy || asEffect.isSelected());
        asEffect.setDisable(busy);
    }

    private void finishCutting(String message) {
        progress.progressProperty().unbind();
        progress.setProgress(1);
        status.setText(message);
        setCuttingBusy(false);
        cutting = null;
    }

    private String nextClipName() {
        return clipBaseName() + " " + String.format(Locale.ROOT, "%02d", clipCounter);
    }

    private String clipBaseName() {
        String name = nameField.getText();
        return name == null || name.isBlank() ? track.getName() : name.trim();
    }

    /** One clip to write; resolved on the JavaFX thread before the background task starts. */
    private record ClipRequest(String name, long startMs, long endMs) {
    }

    /** @return {@code null} on success, otherwise the failure message. Runs on the cutting thread. */
    private String writeClip(ClipRequest request, AudioKind kind, String categoryId) {
        try {
            Path source = library.fileOf(track);
            Path target = library.reserveClipFile(request.name(), AudioFormats.extensionOf(source));
            AudioClipCutter.Cut cut = AudioClipCutter.cut(source, request.startMs(), request.endMs(), target);
            library.addClip(target, request.name(), kind, categoryId, track, cut.startMs(), cut.endMs());
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private void dispose() {
        playing = false;
        if (timer != null) {
            timer.stop();
        }
        if (analysis != null && analysis.isRunning()) {
            analysis.cancel();
        }
        if (cutting != null && cutting.isRunning()) {
            cutting.cancel();
        }
        if (voice != null) {
            disposePreviewVoice(voice);
            voice = null;
        }
        cancelLoopBlend();
        output.close();
        open = null;
    }
}
