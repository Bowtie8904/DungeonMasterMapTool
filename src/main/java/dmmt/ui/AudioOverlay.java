package dmmt.ui;

import dmmt.audio.AudioCategory;
import dmmt.audio.AudioEngine;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.animation.FadeTransition;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audio overlay (3.35.6): a translucent panel drawn inside the main window with a ring of music categories on
 * the left and a ring of sound effects on the right, each button tinted in its own colour and lit up while it is
 * active. It is opened from the status bar group and closes with {@code Escape}, with a click on the dimmed
 * background or with the Audio button.
 *
 * <p>The overlay only refreshes while it is open, so a closed overlay costs nothing.</p>
 */
public final class AudioOverlay {
    private static final double ITEM_SIZE = 44;
    private static final double ITEM_GAP = 10;
    private static final double CENTRE_SIZE = 230;

    private final AudioLibraryService library;
    private final AudioEngine engine;
    private final AppSettings settings;

    private final StackPane layer = new StackPane();
    private final RingPane musicRing = new RingPane();
    private final RingPane effectRing = new RingPane();
    private final Label nowPlaying = new Label("Nothing playing");
    private final Label nowCategory = new Label("No category");
    private final Label nowPlayingTime = new Label();
    private final Label effectCount = new Label();
    private final Label emptyHint = new Label("The library is empty. Import music and sound effects first.");

    private final Button playButton = Icons.button(MaterialDesignP.PLAY, "Play or pause the music", null);
    private final Button previousButton = Icons.button(MaterialDesignS.SKIP_PREVIOUS, "Previous track", null);
    private final Button nextButton = Icons.button(MaterialDesignS.SKIP_NEXT, "Next track", null);
    private final Button stopButton = Icons.button(MaterialDesignS.STOP, "Stop the music (fades out)", null);
    private final ToggleButton effectsPause = Icons.toggle(MaterialDesignP.PAUSE,
            "Pause or resume all running sound effects");
    private final Button effectsStop = Icons.button(MaterialDesignS.STOP, "Stop all sound effects (fades out)", null);
    private final ToggleButton muteButton = Icons.toggle(MaterialDesignV.VOLUME_OFF,
            "Fade all audio to silence and back");
    private final Button libraryButton = Icons.button(MaterialDesignM.MUSIC_BOX_MULTIPLE_OUTLINE,
            "Open the audio library: import files, manage categories and cut clips", null);
    private final Button assignButton = Icons.button(MaterialDesignM.MAP_MARKER_STAR_OUTLINE,
            "Use the running music and sound effects as this map's ambience", null);
    private final Slider masterVolume = new Slider(0, 1, 0.8);
    private final Slider musicVolume = new Slider(0, 1, 0.7);
    private final Slider effectsVolume = new Slider(0, 1, 0.6);

    private final Map<String, ToggleButton> categoryButtons = new LinkedHashMap<>();
    private final Map<String, ToggleButton> effectButtons = new LinkedHashMap<>();

    private Runnable openLibrary = () -> {
    };
    private Runnable assignAmbience = () -> {
    };
    private Runnable closedHandler = () -> {
    };
    private Runnable buttonsRebuilt = () -> {
    };
    private boolean syncing;
    private boolean open;

    public AudioOverlay(AudioLibraryService library, AudioEngine engine, AppSettings settings) {
        this.library = library;
        this.engine = engine;
        this.settings = settings;
        build();
    }

    /** The layer to drop into the main window's root stack; invisible and non-blocking while closed. */
    public Region node() {
        return layer;
    }

    /** What the "Open audio library" button does. */
    public void setLibraryAction(Runnable action) {
        this.openLibrary = action == null ? () -> {
        } : action;
    }

    /** What the "Use current ambience for this map" button does. */
    public void setAssignAction(Runnable action) {
        this.assignAmbience = action == null ? () -> {
        } : action;
    }

    /** Called after the overlay closed itself, so the status bar toggle can follow. */
    public void setClosedHandler(Runnable handler) {
        this.closedHandler = handler == null ? () -> {
        } : handler;
    }

    /** Called after the ring buttons were rebuilt, so their API endpoints can be registered again (3.35.6). */
    public void setButtonsRebuiltHandler(Runnable handler) {
        this.buttonsRebuilt = handler == null ? () -> {
        } : handler;
    }

    public boolean isOpen() {
        return open;
    }

    // ---- Building ----

    private void build() {
        layer.getStyleClass().add("audio-overlay-layer");
        layer.setVisible(false);

        Region scrim = new Region();
        scrim.getStyleClass().add("audio-overlay-scrim");
        scrim.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            close();
            event.consume();
        });

        VBox panel = new VBox(14, header(), rings(), footer());
        panel.getStyleClass().add("audio-overlay-panel");
        panel.setPadding(new Insets(18));
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        panel.addEventHandler(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);

        layer.getChildren().setAll(scrim, panel);
        StackPane.setAlignment(panel, Pos.CENTER);
        layer.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                event.consume();
            }
        });
    }

    private Node header() {
        Label title = new Label("Audio");
        title.getStyleClass().add("audio-overlay-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeButton = Icons.button(MaterialDesignC.CLOSE, "Close the audio overlay (Escape)", this::close);
        HBox header = new HBox(8, title, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node rings() {
        playButton.setOnAction(event -> engine.toggleMusic());
        previousButton.setOnAction(event -> engine.previous());
        nextButton.setOnAction(event -> engine.next());
        stopButton.setOnAction(event -> engine.stopMusic());
        HBox transport = new HBox(6, previousButton, playButton, nextButton, stopButton);
        transport.setAlignment(Pos.CENTER);
        nowCategory.getStyleClass().add("audio-overlay-now");
        nowCategory.setWrapText(true);
        nowCategory.setMaxWidth(CENTRE_SIZE - 20);
        nowCategory.setAlignment(Pos.CENTER);
        nowPlaying.getStyleClass().add("muted");
        nowPlaying.setWrapText(true);
        nowPlaying.setMaxWidth(CENTRE_SIZE - 20);
        nowPlaying.setAlignment(Pos.CENTER);
        nowPlayingTime.getStyleClass().add("value-label");
        VBox musicCentre = new VBox(6, transport, nowCategory, nowPlaying, nowPlayingTime);
        musicCentre.setAlignment(Pos.CENTER);
        musicRing.setCentre(musicCentre);

        effectsPause.setOnAction(event -> {
            if (!syncing) {
                engine.setEffectsPaused(effectsPause.isSelected());
            }
        });
        effectsStop.setOnAction(event -> engine.stopAllEffects());
        HBox effectsTransport = new HBox(6, effectsPause, effectsStop);
        effectsTransport.setAlignment(Pos.CENTER);
        Label effectsTitle = new Label("Sound effects");
        effectsTitle.getStyleClass().add("audio-overlay-now");
        effectCount.getStyleClass().add("muted");
        effectCount.setWrapText(true);
        effectCount.setMaxWidth(CENTRE_SIZE - 20);
        effectCount.setAlignment(Pos.CENTER);
        VBox effectCentre = new VBox(6, effectsTransport, effectsTitle, effectCount);
        effectCentre.setAlignment(Pos.CENTER);
        effectRing.setCentre(effectCentre);

        emptyHint.getStyleClass().add("muted");
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);

        HBox rings = new HBox(24, musicRing, effectRing);
        rings.setAlignment(Pos.CENTER);
        VBox box = new VBox(10, rings, emptyHint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Node footer() {
        muteButton.setOnAction(event -> {
            if (!syncing) {
                engine.setPanic(muteButton.isSelected());
            }
        });
        libraryButton.setOnAction(event -> openLibrary.run());
        assignButton.setOnAction(event -> assignAmbience.run());

        HBox volumes = new HBox(16,
                volumeBox(MaterialDesignV.VOLUME_HIGH, "Master", masterVolume, "audio.masterVolume", 0.8,
                        engine::setMasterVolume),
                volumeBox(MaterialDesignM.MUSIC_NOTE, "Music", musicVolume, "audio.musicVolume", 0.7,
                        engine::setMusicVolume),
                volumeBox(MaterialDesignW.WAVES, "Effects", effectsVolume, "audio.effectsVolume", 0.6,
                        engine::setEffectsVolume));
        volumes.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(12, volumes, spacer, muteButton, assignButton, libraryButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("audio-overlay-footer");
        return footer;
    }

    private Node volumeBox(Ikon icon, String name, Slider slider, String key, double fallback,
                           java.util.function.DoubleConsumer apply) {
        slider.setValue(settings.getDouble(key, fallback));
        slider.setPrefWidth(120);
        Label value = new Label(percent(slider.getValue()));
        value.getStyleClass().add("value-label");
        Icons.tooltip(slider, name + " volume. Double-click to reset.");
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            value.setText(percent(newValue.doubleValue()));
            apply.accept(newValue.doubleValue());
            if (!syncing && !slider.isValueChanging()) {
                settings.putDouble(key, round(newValue.doubleValue()));
            }
        });
        slider.valueChangingProperty().addListener((observable, was, changing) -> {
            if (!changing && !syncing) {
                settings.putDouble(key, round(slider.getValue()));
            }
        });
        slider.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                slider.setValue(fallback);
            }
        });
        HBox box = new HBox(6, Icons.icon(icon), slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ---- Opening and closing ----

    public void toggle() {
        if (open) {
            close();
        } else {
            show();
        }
    }

    /**
     * Shows the overlay. The rings are <em>not</em> rebuilt here: they are rebuilt whenever the library changes,
     * so that the buttons registered with the control API stay the very buttons on screen (3.35.6).
     */
    public void show() {
        open = true;
        layer.setVisible(true);
        layer.toFront();
        double seconds = Tuning.AUDIO_OVERLAY_FADE_SECONDS.get();
        if (seconds > 0) {
            FadeTransition fade = new FadeTransition(Duration.seconds(seconds), layer);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();
        } else {
            layer.setOpacity(1);
        }
        layer.requestFocus();
        refreshState();
    }

    /** Hides the overlay; playback keeps running. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        double seconds = Tuning.AUDIO_OVERLAY_FADE_SECONDS.get();
        if (seconds > 0) {
            FadeTransition fade = new FadeTransition(Duration.seconds(seconds), layer);
            fade.setFromValue(layer.getOpacity());
            fade.setToValue(0);
            fade.setOnFinished(event -> hideLayer());
            fade.play();
        } else {
            hideLayer();
        }
        closedHandler.run();
    }

    private void hideLayer() {
        if (open) {
            return;
        }
        layer.setVisible(false);
        layer.setOpacity(1);
    }

    // ---- Content ----

    /** Rebuilds the category and effect buttons; called whenever the library changed. */
    public void refreshLibrary() {
        categoryButtons.clear();
        effectButtons.clear();
        List<Node> musicNodes = new ArrayList<>();
        for (AudioCategory category : library.visibleCategories()) {
            ToggleButton button = ringButton(AudioIcons.tinted(category.getIcon(), category.getColor(), 22), category.getName());
            button.setOnAction(event -> {
                if (syncing) {
                    return;
                }
                if (engine.isCategoryActive(category.getId())) {
                    engine.stopMusic();
                } else {
                    settings.put("audio.lastCategory", category.getId());
                    engine.playCategory(category.getId());
                }
                refreshState();
            });
            categoryButtons.put(category.getId(), button);
            musicNodes.add(button);
        }
        List<Node> effectNodes = new ArrayList<>();
        for (AudioTrack effect : library.visibleEffects()) {
            ToggleButton button = ringButton(AudioIcons.tintedEffect(effect.getIcon(), effect.getColor(), 22), effect.getName());
            button.setOnAction(event -> {
                if (syncing) {
                    return;
                }
                engine.setEffectActive(effect.getId(), button.isSelected());
                refreshState();
            });
            effectButtons.put(effect.getId(), button);
            effectNodes.add(button);
        }
        musicRing.setItems(musicNodes);
        effectRing.setItems(effectNodes);
        boolean empty = musicNodes.isEmpty() && effectNodes.isEmpty();
        emptyHint.setVisible(empty);
        emptyHint.setManaged(empty);
        refreshState();
        // New buttons need new API endpoints and new right-click menus.
        buttonsRebuilt.run();
    }

    private ToggleButton ringButton(Node icon, String tooltip) {
        ToggleButton button = new ToggleButton(null, icon);
        button.getStyleClass().add("audio-ring-button");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setPrefSize(ITEM_SIZE, ITEM_SIZE);
        button.setMinSize(ITEM_SIZE, ITEM_SIZE);
        button.setMaxSize(ITEM_SIZE, ITEM_SIZE);
        Icons.tooltip(button, tooltip);
        return button;
    }

    /** Pushes the engine state into the buttons, labels and sliders; only does work while the overlay is open. */
    public void refreshState() {
        if (!open) {
            return;
        }
        syncing = true;
        try {
            boolean playing = engine.isMusicPlaying();
            playButton.setGraphic(Icons.icon(playing ? MaterialDesignP.PAUSE : MaterialDesignP.PLAY));
            AudioTrack track = engine.currentTrack().orElse(null);
            AudioCategory running = engine.categoryId() == null ? null
                    : library.category(engine.categoryId()).filter(c -> engine.isCategoryActive(c.getId()))
                    .orElse(null);
            nowCategory.setText(running == null ? "No category" : running.getName());
            nowCategory.setGraphic(running == null ? null
                    : AudioIcons.tinted(running.getIcon(), running.getColor(), 16));
            nowPlaying.setText(track == null ? "Nothing playing" : track.getName());
            nowPlayingTime.setText(track == null ? "" : timeText());
            previousButton.setDisable(track == null);
            nextButton.setDisable(track == null);
            stopButton.setDisable(track == null);
            categoryButtons.forEach((id, button) -> button.setSelected(engine.isCategoryActive(id)));
            effectButtons.forEach((id, button) -> button.setSelected(engine.isEffectActive(id)));
            List<String> active = engine.activeEffects();
            effectCount.setText(active.isEmpty() ? "none running" : activeEffectNames(active));
            effectsPause.setSelected(engine.areEffectsPaused());
            effectsPause.setGraphic(Icons.icon(engine.areEffectsPaused()
                    ? MaterialDesignP.PLAY : MaterialDesignP.PAUSE));
            effectsPause.setDisable(active.isEmpty());
            effectsStop.setDisable(active.isEmpty());
            muteButton.setSelected(engine.isPanic());
            masterVolume.setValue(engine.masterVolume());
            musicVolume.setValue(engine.musicVolume());
            effectsVolume.setValue(engine.effectsVolume());
        } finally {
            syncing = false;
        }
    }

    /** Refreshes only the elapsed time, driven by the audio ticker. */
    public void refreshTime() {
        if (open && engine.currentTrack().isPresent()) {
            nowPlayingTime.setText(timeText());
        }
    }

    private String activeEffectNames(List<String> ids) {
        List<String> names = ids.stream()
                .map(id -> library.track(id).map(AudioTrack::getName).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (names.size() > 4) {
            return String.join(", ", names.subList(0, 4)) + " +" + (names.size() - 4);
        }
        return String.join(", ", names);
    }

    private String timeText() {
        return AudioTrack.formatDuration(Math.round(engine.positionMs()))
                + " / " + AudioTrack.formatDuration(Math.round(engine.durationMs()));
    }

    // ---- Control API ----

    /** The overlay controls that the local control API exposes, keyed by their api id (3.35.6). */
    public Map<String, Node> apiControls() {
        Map<String, Node> controls = new LinkedHashMap<>();
        controls.put("audio.musicPlay", playButton);
        controls.put("audio.stop", stopButton);
        controls.put("audio.mute", muteButton);
        controls.put("audio.library", libraryButton);
        controls.put("audio.assign", assignButton);
        controls.put("audio.effectsPause", effectsPause);
        controls.put("audio.effectsStop", effectsStop);
        controls.put("audio.masterVolume", masterVolume);
        controls.put("audio.musicVolume", musicVolume);
        controls.put("audio.effectsVolume", effectsVolume);
        return controls;
    }

    /** The per-category toggles, keyed by category id, in ring order. */
    public Map<String, ToggleButton> categoryButtons() {
        return Map.copyOf(categoryButtons);
    }

    /** The per-effect toggles, keyed by track id, in ring order. */
    public Map<String, ToggleButton> effectButtons() {
        return Map.copyOf(effectButtons);
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** Lays its children out on concentric circles around a centre node, see {@link RingLayout}. */
    private static final class RingPane extends Pane {
        private Node centre;
        private final List<Node> items = new ArrayList<>();

        void setCentre(Node node) {
            this.centre = node;
            rebuild();
        }

        void setItems(List<Node> newItems) {
            items.clear();
            items.addAll(newItems);
            rebuild();
        }

        private void rebuild() {
            List<Node> children = new ArrayList<>(items);
            if (centre != null) {
                children.add(centre);
            }
            getChildren().setAll(children);
            requestLayout();
        }

        private double span() {
            return Math.max(CENTRE_SIZE,
                    RingLayout.diameter(RingLayout.place(items.size(), ITEM_SIZE, ITEM_GAP, CENTRE_SIZE), ITEM_SIZE));
        }

        @Override
        protected double computePrefWidth(double height) {
            return span();
        }

        @Override
        protected double computePrefHeight(double width) {
            return span();
        }

        @Override
        protected void layoutChildren() {
            double cx = getWidth() / 2;
            double cy = getHeight() / 2;
            if (centre != null) {
                double w = Math.min(centre.prefWidth(-1), CENTRE_SIZE);
                double h = centre.prefHeight(w);
                layoutInArea(centre, cx - w / 2, cy - h / 2, w, h, 0, HPos.CENTER, VPos.CENTER);
            }
            List<RingLayout.Slot> slots = RingLayout.place(items.size(), ITEM_SIZE, ITEM_GAP, CENTRE_SIZE);
            for (int i = 0; i < items.size(); i++) {
                RingLayout.Slot slot = slots.get(i);
                layoutInArea(items.get(i), cx + slot.x() - ITEM_SIZE / 2, cy + slot.y() - ITEM_SIZE / 2,
                        ITEM_SIZE, ITEM_SIZE, 0, HPos.CENTER, VPos.CENTER);
            }
        }
    }
}
