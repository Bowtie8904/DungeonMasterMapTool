package dmmt.ui;

import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.io.IOException;
import java.util.Locale;

/** Saved, absolute per-file gain; automatic analysis never changes the original audio. */
final class AudioLoudnessDialog {
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final AudioLibraryService library;
    private final AudioTrack track;
    private final Runnable onChanged;
    private final Label recommendation = new Label();
    private final Label current = new Label();
    private final Label limits = new Label();
    private final Label error = new Label();
    private final Label operationStatus = new Label();
    private final Slider gain = new Slider(-60, AudioLibraryService.MANUAL_MAXIMUM_GAIN_DB, 0);
    private final TextField value = new TextField();
    private final Button apply = new Button("Apply override");
    private final Button automatic = new Button("Use automatic");
    private final Button analyze = new Button("Analyze file");
    private final ProgressIndicator progress = new ProgressIndicator();
    private Task<Void> analysis;

    AudioLoudnessDialog(Window owner, AudioLibraryService library, AudioTrack track, Runnable onChanged,
                        Runnable preview) {
        this.library = library;
        this.track = track;
        this.onChanged = onChanged;
        Dialogs.style(dialog, owner, "Loudness", track.getName(), MaterialDesignV.VOLUME_HIGH, false);
        dialog.initModality(Modality.NONE);

        Label explanation = new Label("Gain is relative to the original recording. Negative values make it "
                + "quieter; positive values make it louder. Apply prepares and saves the override. "
                + "Boosts beyond peak-safe headroom use a peak limiter. "
                + "Use automatic restores the uncompressed import-time recommendation.");
        explanation.setWrapText(true);
        limits.setWrapText(true);
        recommendation.setWrapText(true);
        error.setWrapText(true);
        error.getStyleClass().add("error-label");
        value.setPrefColumnCount(7);
        value.setId("audioLoudnessValue");
        gain.setId("audioLoudnessGain");
        gain.setBlockIncrement(0.5);
        gain.valueProperty().addListener((observable, oldValue, newValue) ->
                value.setText(String.format(Locale.ROOT, "%.1f", newValue.doubleValue())));
        HBox adjustment = new HBox(8, gain, value, new Label("dB"));
        adjustment.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(gain, javafx.scene.layout.Priority.ALWAYS);

        apply.setId("audioLoudnessApply");
        apply.setOnAction(event -> applyOverride());
        value.setOnAction(event -> applyOverride());
        automatic.setId("audioLoudnessAutomatic");
        automatic.setOnAction(event -> saveOverride(null));
        analyze.setId("audioLoudnessAnalyze");
        analyze.setOnAction(event -> analyze());
        progress.setPrefSize(20, 20);
        progress.setVisible(false);
        progress.setManaged(false);
        Button listen = new Button("Preview");
        listen.setOnAction(event -> preview.run());

        VBox content = new VBox(10, explanation, recommendation, current, adjustment, limits,
                new HBox(8, apply, automatic, listen), new HBox(8, analyze, progress, operationStatus), error);
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.setOnHidden(event -> {
            if (analysis != null) {
                analysis.cancel();
            }
        });
        refresh();
    }

    void show() {
        dialog.show();
    }

    Dialog<ButtonType> dialog() {
        return dialog;
    }

    private void refresh() {
        boolean analyzed = track.isLoudnessAnalyzed();
        recommendation.setText(!analyzed ? "Not analyzed: the original level is retained."
                : track.getLoudnessLufs() == null
                ? "No measurable loudness (silence or below the analysis gate). Automatic: "
                        + decibels(track.getAutoGainDb())
                : String.format(Locale.ROOT, "Measured: %.1f LUFS. Target: -23 LUFS. Automatic: %s",
                        track.getLoudnessLufs(), decibels(track.getAutoGainDb())));
        current.setText((track.getGainOverrideDb() == null ? "Automatic: " : "Saved override: ")
                + decibels(track.effectiveGainDb())
                + (track.getPlaybackFile() != null && track.getPlaybackFile().endsWith(".limited.wav")
                ? " (peak limiter active)" : ""));
        double editableGain = Math.min(track.manualMaximumGainDb(),
                Math.round(track.effectiveGainDb() * 10) / 10.0);
        gain.setValue(editableGain);
        value.setText(String.format(Locale.ROOT, "%.1f", editableGain));
        limits.setText("Manual range: -60 dB to +24 dB. "
                + (analyzed ? "Peak-safe headroom: " + decibels(track.maximumGainDb())
                + ". Higher gains limit peaks to -1 dBFS and may reduce dynamics."
                : "The file will be analyzed automatically when you apply a manual override.")
                + " Playback keeps its position when a new audio copy is ready.");
        automatic.setText(analyzed ? "Use automatic" : "Use original level");
        analyze.setVisible(!analyzed);
        analyze.setManaged(!analyzed);
    }

    private void applyOverride() {
        final double chosen;
        try {
            chosen = Double.parseDouble(value.getText().trim());
        } catch (NumberFormatException e) {
            error.setText("Enter a gain in decibels, for example -6 or 2.5.");
            return;
        }
        if (!Double.isFinite(chosen) || chosen < -60 || chosen > track.manualMaximumGainDb()) {
            error.setText("Gain must be between -60 dB and +24 dB.");
            return;
        }
        saveOverride(chosen);
    }

    private void saveOverride(Double chosen) {
        runOperation(() -> library.setGainOverride(track.getId(), chosen),
                "Preparing loudness adjustment...", "Could not save the loudness adjustment");
    }

    private void analyze() {
        runOperation(() -> library.analyzeLoudness(track.getId()), "Analyzing file...", "Could not analyze the file");
    }

    private void runOperation(LoudnessAction action, String message, String failure) {
        if (analysis != null && analysis.isRunning()) {
            return;
        }
        error.setText("");
        operationStatus.setText(message);
        setBusy(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                action.run();
                return null;
            }
        };
        analysis = task;
        task.setOnSucceeded(event -> {
            setBusy(false);
            refresh();
            onChanged.run();
        });
        task.setOnFailed(event -> {
            setBusy(false);
            error.setText(failure + ": " + task.getException().getMessage());
        });
        task.setOnCancelled(event -> {
            setBusy(false);
            refresh();
            onChanged.run();
        });
        dmmt.service.WorkScheduler.shared().executor(dmmt.service.WorkScheduler.Kind.AUDIO).execute(task);
    }

    private void setBusy(boolean busy) {
        analyze.setDisable(busy);
        apply.setDisable(busy);
        automatic.setDisable(busy);
        gain.setDisable(busy);
        value.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (!busy) {
            operationStatus.setText("");
        }
    }

    private interface LoudnessAction {
        void run() throws IOException;
    }

    static String decibels(double gainDb) {
        return String.format(Locale.ROOT, "%+.1f dB", gainDb);
    }
}
