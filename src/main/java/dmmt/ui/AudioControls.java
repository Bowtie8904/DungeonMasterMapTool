package dmmt.ui;

import dmmt.api.ControlTooltips;
import dmmt.audio.AudioCategory;
import dmmt.audio.AudioEngine;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import dmmt.audio.JavaFxAudioOutput;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The audio transport group in the status bar (3.35.6) and the owner of the audio library, the playback engine,
 * the {@link AudioOverlay} and their shared update loop. Everything audio-related hangs off this one class so the
 * feature can be left out entirely when {@code audio.enabled} is false.
 */
public final class AudioControls {
    private final AppSettings settings;
    private final Supplier<Window> owner;
    private final AudioLibraryService library;
    private final AudioEngine engine;
    private final AudioOverlay overlay;
    private final Timeline ticker;

    private final Button playButton = Icons.button(MaterialDesignP.PLAY, "Play or pause the music", null);
    private final Button previousButton = Icons.button(MaterialDesignS.SKIP_PREVIOUS, "Previous track", null);
    private final Button nextButton = Icons.button(MaterialDesignS.SKIP_NEXT, "Next track", null);
    private final ToggleButton overlayButton = Icons.toggle(MaterialDesignM.MUSIC_CIRCLE_OUTLINE,
            "Open the audio overlay: categories, sound effects and volumes");
    private final Button libraryButton = Icons.button(MaterialDesignM.MUSIC_BOX_MULTIPLE_OUTLINE,
            "Open the audio library: import files, manage categories and cut clips", null);
    private final ScrollingLabel trackName = new ScrollingLabel(180);
    private final HBox group = new HBox(2, trackName, previousButton, playButton, nextButton, overlayButton, libraryButton);
    /** Stand-in toggles for entries that are hidden from the overlay, kept so their endpoints stay stable. */
    private final Map<String, ToggleButton> hiddenToggles = new LinkedHashMap<>();
    private final Map<String, Button> trackButtons = new LinkedHashMap<>();

    private Runnable apiControlsChanged = () -> {
    };
    private boolean syncing;
    private long lastTickNanos;
    private long lastReadoutNanos;

    public AudioControls(AppSettings settings, Supplier<Window> owner) {
        this(settings, owner, new AudioLibraryService(resolveFolder()), new JavaFxAudioOutput());
    }

    AudioControls(AppSettings settings, Supplier<Window> owner, AudioLibraryService library,
                  dmmt.audio.AudioOutput output) {
        this.settings = settings;
        this.owner = owner;
        this.library = library;
        this.engine = new AudioEngine(library, output);
        this.overlay = new AudioOverlay(library, engine, settings);
        overlay.setLibraryAction(this::openLibrary);
        overlay.setClosedHandler(this::refreshPlaybackState);
        overlay.setButtonsRebuiltHandler(() -> apiControlsChanged.run());
        applySettings();
        buildGroup();
        overlay.refreshLibrary();
        refreshPlaybackState();
        engine.addListener(this::refreshPlaybackState);
        ticker = new Timeline(new KeyFrame(Duration.seconds(1.0 / Math.max(Tuning.AUDIO_UPDATE_FPS.get(),
                Tuning.AUDIO_EFFECT_LOOP_UPDATE_FPS.get())),
                event -> tick()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    /** The audio library folder from the settings; relative paths start next to the settings file. */
    public static Path resolveFolder() {
        return AppSettings.resolveConfiguredFolder(AppSettings.resolveFile(), Tuning.AUDIO_FOLDER.get(), "dmmap-audio");
    }

    public AudioLibraryService library() {
        return library;
    }

    public AudioEngine engine() {
        return engine;
    }

    public AudioOverlay overlay() {
        return overlay;
    }

    /** The transport group for the right-hand side of the status bar. */
    public Region statusBarGroup() {
        return group;
    }

    /** The overlay layer to put on top of the main window's content. */
    public Region overlayLayer() {
        return overlay.node();
    }

    /** Called whenever the set of API controls changed, so the application can register them again. */
    public void setApiControlsChangedHandler(Runnable handler) {
        this.apiControlsChanged = handler == null ? () -> {
        } : handler;
    }

    // ---- Building the UI ----

    private void buildGroup() {
        playButton.setOnAction(event -> engine.toggleAll());
        Icons.tooltip(playButton, "Pause or resume the music and all running sound effects");
        ControlTooltips.apply("audio.play", playButton);
        ControlTooltips.apply("audio.previous", previousButton);
        ControlTooltips.apply("audio.next", nextButton);
        ControlTooltips.apply("audio.overlay", overlayButton);
        ControlTooltips.apply("audio.libraryWindow", libraryButton);
        libraryButton.setOnAction(event -> openLibrary());
        previousButton.setOnAction(event -> engine.previous());
        nextButton.setOnAction(event -> engine.next());
        overlayButton.setOnAction(event -> {
            if (!syncing) {
                setOverlayOpen(overlayButton.isSelected());
            }
        });
        group.getStyleClass().add("audio-status-group");
        group.setAlignment(Pos.CENTER_RIGHT);
        HBox.setMargin(trackName, new Insets(0, 6, 0, 0));
    }

    private void setOverlayOpen(boolean open) {
        if (open) {
            overlay.show();
        } else {
            overlay.close();
        }
        syncing = true;
        try {
            overlayButton.setSelected(open);
        } finally {
            syncing = false;
        }
    }

    /** Closes the overlay if it is open; used by the Escape handling of the main window. */
    public boolean closeOverlay() {
        if (!overlay.isOpen()) {
            return false;
        }
        setOverlayOpen(false);
        return true;
    }

    /** Opens or closes the overlay; bound to the {@code M} key of the DM window (3.35.6). */
    public void toggleOverlay() {
        setOverlayOpen(!overlay.isOpen());
    }

    // ---- Control API ----

    /**
     * Every audio control the local API exposes, keyed by its id (3.35.6): the fixed transport, overlay and volume
     * ids plus one play button per music track and one toggle per category and per sound effect, addressed by their library id so that renaming an
     * entry never changes its endpoint. Hidden entries keep their endpoint, so the map is rebuilt whenever the
     * library changes.
     */
    public Map<String, Node> apiControls() {
        Map<String, Node> controls = new LinkedHashMap<>();
        controls.put("audio.previous", previousButton);
        controls.put("audio.play", playButton);
        controls.put("audio.next", nextButton);
        controls.put("audio.overlay", overlayButton);
        controls.put("audio.libraryWindow", libraryButton);
        controls.putAll(overlay.apiControls());
        List<AudioCategory> categories = library.categories();
        for (AudioCategory category : categories) {
            controls.put("audio.category." + category.getId(), categoryToggle(category));
            for (AudioTrack track : library.musicOf(category.getId())) {
                Button button = trackButtons.computeIfAbsent(track.getId(), id -> {
                    Button play = new Button();
                    play.setOnAction(event -> {
                        AudioTrack current = library.track(id).orElseThrow();
                        settings.put("audio.lastCategory", current.getCategoryId());
                        engine.playTrack(id);
                    });
                    return play;
                });
                button.setText(track.getName());
                button.setGraphic(Icons.icon(MaterialDesignP.PLAY));
                Icons.tooltip(button, "Play " + track.getName() + " and start its music category");
                controls.put("audio.track." + track.getId(), button);
            }
        }
        trackButtons.keySet().removeIf(id -> !controls.containsKey("audio.track." + id));
        for (AudioTrack effect : library.effects()) {
            controls.put("audio.effect." + effect.getId(), effectToggle(effect));
        }
        return controls;
    }

    /**
     * The overlay button of this category, or a headless toggle with the same behaviour when the category is
     * hidden from the overlay (3.35.2). The headless toggles are cached, so an endpoint keeps pointing at the same
     * node across re-registrations.
     */
    private ToggleButton categoryToggle(AudioCategory category) {
        ToggleButton visible = overlay.categoryButtons().get(category.getId());
        if (visible != null) {
            return visible;
        }
        return hiddenToggles.computeIfAbsent(category.getId(), id -> {
            ToggleButton button = new ToggleButton(category.getName());
            button.setSelected(engine.isCategoryActive(id));
            button.setOnAction(event -> {
                if (engine.isCategoryActive(id)) {
                    engine.stopMusic();
                } else {
                    settings.put("audio.lastCategory", id);
                    engine.playCategory(id);
                }
            });
            return button;
        });
    }

    private ToggleButton effectToggle(AudioTrack effect) {
        ToggleButton visible = overlay.effectButtons().get(effect.getId());
        if (visible != null) {
            return visible;
        }
        return hiddenToggles.computeIfAbsent(effect.getId(), id -> {
            ToggleButton button = new ToggleButton(effect.getName());
            button.setSelected(engine.isEffectActive(id));
            button.setOnAction(event -> engine.setEffectActive(id, button.isSelected()));
            return button;
        });
    }

    // ---- Settings ----

    /** Pushes the current settings values into the engine; called at start-up and after settings changes. */
    public void applySettings() {
        engine.setVolumes(settings.getDouble("audio.masterVolume", 0.8),
                settings.getDouble("audio.musicVolume", 0.7),
                settings.getDouble("audio.effectsVolume", 0.6));
        engine.setShuffle(Tuning.AUDIO_SHUFFLE.get());
        engine.setMusicCrossfadeSeconds(Tuning.AUDIO_MUSIC_CROSSFADE_SECONDS.get());
        engine.setEffectFadeSeconds(Tuning.AUDIO_EFFECT_FADE_SECONDS.get());
        engine.setEffectLoopCrossfadeSeconds(Tuning.AUDIO_EFFECT_LOOP_CROSSFADE_SECONDS.get());
        engine.setPanicFadeSeconds(Tuning.AUDIO_PANIC_FADE_SECONDS.get());
        engine.setMaxEffects(Tuning.AUDIO_MAX_EFFECTS.get());
    }

    // ---- Library and playback state ----

    /** Rebuilds the overlay rings and the per-entry API endpoints after the library changed. */
    public void refreshLibraryChoices() {
        overlay.refreshLibrary();
        refreshPlaybackState();
    }

    /** Updates the status bar buttons and the overlay from the engine. */
    private void refreshPlaybackState() {
        syncing = true;
        try {
            boolean playing = engine.isMusicPlaying()
                    || (!engine.activeEffects().isEmpty() && !engine.areEffectsPaused());
            playButton.setGraphic(Icons.icon(playing ? MaterialDesignP.PAUSE : MaterialDesignP.PLAY));
            AudioTrack track = engine.currentTrack().orElse(null);
            trackName.setText(track == null ? "" : track.getName());
            previousButton.setDisable(track == null);
            nextButton.setDisable(track == null);
            AudioCategory category = engine.categoryId() == null || track == null ? null
                    : library.category(engine.categoryId()).orElse(null);
            if (category == null) {
                overlayButton.setGraphic(Icons.icon(MaterialDesignM.MUSIC_CIRCLE_OUTLINE));
                Icons.tooltip(overlayButton, "Open the audio overlay: categories, sound effects and volumes");
            } else {
                overlayButton.setGraphic(AudioIcons.tinted(category.getIcon(), category.getColor(), 16));
                Icons.tooltip(overlayButton, category.getName()
                        + (track == null ? "" : " - " + track.getName()) + "\nOpen the audio overlay");
            }
            overlayButton.setSelected(overlay.isOpen());
            hiddenToggles.forEach((id, button) ->
                    button.setSelected(engine.isCategoryActive(id) || engine.isEffectActive(id)));
        } finally {
            syncing = false;
        }
        overlay.refreshState();
    }

    /** Advances effect loops and music crossfades smoothly, keeping readouts and other playback on their slower cadence. */
    private void tick() {
        long now = System.nanoTime();
        double delta = lastTickNanos == 0 ? 0 : (now - lastTickNanos) / 1_000_000_000.0;
        double readoutInterval = 1.0 / Tuning.AUDIO_UPDATE_FPS.get();
        if (lastTickNanos == 0 || engine.needsSmoothUpdates() || delta >= readoutInterval) {
            lastTickNanos = now;
            engine.tick(delta);
        }
        if (lastReadoutNanos == 0 || (now - lastReadoutNanos) / 1_000_000_000.0 >= readoutInterval) {
            lastReadoutNanos = now;
            overlay.refreshTime();
        }
    }

    // ---- Actions ----

    private void openLibrary() {
        AudioLibraryWindow.show(owner.get(), library, engine, settings, this::refreshLibraryChoices);
    }

    /** Imports audio files directly, used by the library window and by drag & drop. */
    public AudioTrack importFile(Path file, AudioKind kind, String categoryId) throws java.io.IOException {
        AudioTrack track = library.importFile(file, kind, categoryId);
        refreshLibraryChoices();
        return track;
    }

    /** Stops playback and releases the media resources; called when the application closes. */
    public void shutdown() {
        ticker.stop();
        trackName.dispose();
        engine.shutdown();
    }
}
