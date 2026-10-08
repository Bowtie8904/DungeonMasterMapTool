package dmmt.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Plays one music category and any number of looping sound effects on top of it (3.35.4).
 * <p>
 * Everything about playback lives here: the shuffled category playlist, the crossfade between two music tracks,
 * the separate music/effects/master volumes, the per-channel pause and the global panic fade. The engine never
 * touches JavaFX directly - it drives {@link AudioOutput} voices and is advanced by {@link #tick(double)}, which
 * the UI calls a few times per second. That makes the whole behaviour unit-testable without sound hardware.
 */
public class AudioEngine {
    /** Perceptual volume curve: slider value ^ this exponent, so the sliders feel linear to the ear. */
    private static final double VOLUME_EXPONENT = 2.2;
    private static final double MIN_FADE_SECONDS = 0.01;

    private final AudioLibraryService library;
    private final AudioOutput output;
    private final Random random;
    private final List<Runnable> listeners = new ArrayList<>();

    private double masterVolume = 0.8;
    private double musicVolume = 0.7;
    private double effectsVolume = 0.6;
    private double crossfadeSeconds = 4;
    private double effectFadeSeconds = 1.5;
    private double effectLoopCrossfadeSeconds = 0.5;
    private double panicFadeSeconds = 1;
    private boolean shuffle = true;
    private int maxEffects = 8;

    private String categoryId;
    private List<String> playlist = new ArrayList<>();
    private int playlistIndex = -1;
    private Voice music;
    private Voice fadingOut;
    private boolean musicPaused;
    private boolean effectsPaused;
    private boolean panic;
    /** Guards against starting the crossfade twice for the same track. */
    private boolean crossfadeStarted;

    private final Map<String, EffectLoop> effects = new LinkedHashMap<>();
    /** Voices that are fading out and get disposed once they are silent. */
    private final List<Voice> stopping = new ArrayList<>();
    /** Global fade factor of the panic mute, 1 = normal, 0 = silent. */
    private double panicGain = 1;

    public AudioEngine(AudioLibraryService library, AudioOutput output) {
        this(library, output, new Random());
    }

    AudioEngine(AudioLibraryService library, AudioOutput output, Random random) {
        this.library = library;
        this.output = output;
        this.random = random;
    }

    /** A playing sound with its own fade state; the gain is relative (0 to 1) inside its channel. */
    private static final class Voice {
        private final AudioOutput.Voice handle;
        private final String trackId;
        private final boolean musicChannel;
        private double gain;
        private double targetGain = 1;
        private double fadeSeconds = 1;
        private boolean disposeWhenSilent;
        private double loopGain = 1;

        Voice(AudioOutput.Voice handle, String trackId, boolean musicChannel, double gain) {
            this.handle = handle;
            this.trackId = trackId;
            this.musicChannel = musicChannel;
            this.gain = gain;
        }

        void fadeTo(double target, double seconds) {
            targetGain = target;
            fadeSeconds = Math.max(MIN_FADE_SECONDS, seconds);
        }
    }

    private static final class EffectLoop {
        private Voice current;
        private Voice prepared;
        private Voice outgoing;
        private LoopCrossfade blend;

        EffectLoop(Voice current) {
            this.current = current;
        }

        List<Voice> voices() {
            List<Voice> voices = new ArrayList<>();
            voices.add(current);
            if (prepared != null) {
                voices.add(prepared);
            }
            if (outgoing != null) {
                voices.add(outgoing);
            }
            return voices;
        }
    }

    // ---- Settings ----

    public void setVolumes(double master, double music, double effects) {
        this.masterVolume = clamp01(master);
        this.musicVolume = clamp01(music);
        this.effectsVolume = clamp01(effects);
        applyVolumes();
    }

    public void setMasterVolume(double value) {
        masterVolume = clamp01(value);
        applyVolumes();
    }

    public void setMusicVolume(double value) {
        musicVolume = clamp01(value);
        applyVolumes();
    }

    public void setEffectsVolume(double value) {
        effectsVolume = clamp01(value);
        applyVolumes();
    }

    public double masterVolume() {
        return masterVolume;
    }

    public double musicVolume() {
        return musicVolume;
    }

    public double effectsVolume() {
        return effectsVolume;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    public void setCrossfadeSeconds(double seconds) {
        this.crossfadeSeconds = Math.max(0, seconds);
    }

    public void setEffectFadeSeconds(double seconds) {
        this.effectFadeSeconds = Math.max(0, seconds);
    }

    public void setEffectLoopCrossfadeSeconds(double seconds) {
        this.effectLoopCrossfadeSeconds = Math.max(0, seconds);
    }

    public void setPanicFadeSeconds(double seconds) {
        this.panicFadeSeconds = Math.max(0, seconds);
    }

    public void setMaxEffects(int max) {
        this.maxEffects = Math.max(1, max);
    }

    /** Called whenever the UI should refresh (track change, play state, effects). */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    // ---- Music ----

    /** Id of the playing category, or {@code null}. */
    public String categoryId() {
        return categoryId;
    }

    /**
     * Whether this category is the one that is loaded and not stopped. The engine keeps {@link #categoryId()} after
     * {@link #stopMusic()} so play resumes the same category, so the overlay asks this instead (3.35.6).
     */
    public boolean isCategoryActive(String id) {
        return id != null && id.equals(categoryId) && music != null;
    }

    public Optional<AudioTrack> currentTrack() {
        return music == null ? Optional.empty() : library.track(music.trackId);
    }

    public boolean isMusicPlaying() {
        return music != null && !musicPaused;
    }

    public boolean isMusicPaused() {
        return musicPaused;
    }

    public double positionMs() {
        return music == null ? 0 : music.handle.positionMs();
    }

    public double durationMs() {
        if (music == null) {
            return 0;
        }
        double fromStream = music.handle.durationMs();
        if (fromStream > 0) {
            return fromStream;
        }
        return library.track(music.trackId).map(AudioTrack::getDurationMs).orElse(0L);
    }

    /** Starts (or restarts) a category; {@code null} stops the music. */
    public void playCategory(String categoryId) {
        if (categoryId == null) {
            stopMusic();
            return;
        }
        this.categoryId = categoryId;
        playlist = new ArrayList<>(library.musicOf(categoryId).stream().map(AudioTrack::getId).toList());
        shufflePlaylist(null);
        playlistIndex = -1;
        musicPaused = false;
        if (playlist.isEmpty()) {
            stopVoice(music, 0.2);
            music = null;
            fireChanged();
            return;
        }
        advance(1, crossfadeSeconds);
    }

    /** Play when stopped, pause when playing, resume when paused. */
    public void toggleMusic() {
        if (music == null) {
            if (categoryId != null) {
                playCategory(categoryId);
            }
            return;
        }
        musicPaused = !musicPaused;
        if (musicPaused) {
            music.handle.pause();
            if (fadingOut != null) {
                fadingOut.handle.pause();
            }
        } else {
            music.handle.play();
            if (fadingOut != null) {
                fadingOut.handle.play();
            }
        }
        fireChanged();
    }

    public void next() {
        if (!playlist.isEmpty()) {
            advance(1, Math.min(crossfadeSeconds, 1.5));
        }
    }

    public void previous() {
        if (playlist.isEmpty()) {
            return;
        }
        // Like every media player: restart the track when it is already running for a while.
        if (positionMs() > 3000) {
            advance(0, 0.3);
        } else {
            advance(-1, Math.min(crossfadeSeconds, 1.5));
        }
    }

    /** Fades the music out and forgets the playlist position. */
    public void stopMusic() {
        stopVoice(music, Math.min(crossfadeSeconds, 2));
        music = null;
        playlistIndex = -1;
        musicPaused = false;
        crossfadeStarted = false;
        fireChanged();
    }

    private void advance(int direction, double fadeSeconds) {
        if (playlist.isEmpty()) {
            return;
        }
        int next;
        if (direction == 0) {
            next = Math.max(0, playlistIndex);
        } else if (playlistIndex < 0) {
            next = direction > 0 ? 0 : playlist.size() - 1;
        } else {
            next = playlistIndex + direction;
            if (next >= playlist.size()) {
                shufflePlaylist(playlist.get(playlistIndex));
                next = 0;
            } else if (next < 0) {
                next = playlist.size() - 1;
            }
        }
        playlistIndex = next;
        startMusic(playlist.get(next), fadeSeconds);
    }

    private void startMusic(String trackId, double fadeSeconds) {
        Optional<AudioTrack> track = library.track(trackId);
        if (track.isEmpty()) {
            return;
        }
        stopVoice(fadingOut, 0);
        fadingOut = music;
        if (fadingOut != null) {
            fadingOut.fadeTo(0, fadeSeconds);
            fadingOut.disposeWhenSilent = true;
        }
        AudioOutput.Voice handle = output.open(library.fileOf(track.get()), false);
        if (handle == null) {
            music = null;
            fireChanged();
            return;
        }
        Voice voice = new Voice(handle, trackId, true, fadeSeconds > 0 ? 0 : 1);
        voice.fadeTo(1, fadeSeconds);
        music = voice;
        crossfadeStarted = false;
        handle.setVolume(voiceVolume(voice));
        handle.setOnEnd(() -> {
            if (music == voice) {
                advance(1, 0);
            }
        });
        if (!musicPaused) {
            handle.play();
        }
        fireChanged();
    }

    /** Reshuffles the playlist, avoiding {@code lastPlayed} as the new first track. */
    private void shufflePlaylist(String lastPlayed) {
        if (!shuffle || playlist.size() < 2) {
            return;
        }
        Collections.shuffle(playlist, random);
        if (lastPlayed != null && playlist.size() > 1 && playlist.get(0).equals(lastPlayed)) {
            Collections.swap(playlist, 0, 1);
        }
    }

    // ---- Sound effects ----

    /** Ids of the effects that are currently running, in the order they were started. */
    public List<String> activeEffects() {
        return List.copyOf(effects.keySet());
    }

    public boolean isEffectActive(String trackId) {
        return effects.containsKey(trackId);
    }

    public boolean areEffectsPaused() {
        return effectsPaused;
    }

    /** Starts or stops one looping sound effect with a fade. */
    public void setEffectActive(String trackId, boolean active) {
        if (trackId == null) {
            return;
        }
        if (!active) {
            stopEffect(effects.remove(trackId), effectFadeSeconds);
            fireChanged();
            return;
        }
        if (effects.containsKey(trackId)) {
            return;
        }
        if (effects.size() >= maxEffects) {
            // Oldest effect makes room, so the cap can never be exceeded on low-end hardware.
            String oldest = effects.keySet().iterator().next();
            stopEffect(effects.remove(oldest), effectFadeSeconds);
        }
        Optional<AudioTrack> track = library.track(trackId);
        if (track.isEmpty()) {
            return;
        }
        AudioOutput.Voice handle = output.open(library.fileOf(track.get()), false);
        if (handle == null) {
            return;
        }
        Voice voice = new Voice(handle, trackId, false, 0);
        voice.fadeTo(1, effectFadeSeconds);
        handle.setVolume(voiceVolume(voice));
        EffectLoop effect = new EffectLoop(voice);
        effects.put(trackId, effect);
        prepareEffect(effect);
        handle.setOnEnd(() -> effectEnded(effect, voice));
        if (!effectsPaused) {
            handle.play();
        }
        fireChanged();
    }

    private void prepareEffect(EffectLoop effect) {
        Optional<AudioTrack> track = library.track(effect.current.trackId);
        if (track.isEmpty()) {
            return;
        }
        AudioOutput.Voice handle = output.open(library.fileOf(track.get()), false);
        if (handle != null) {
            handle.setVolume(0);
            effect.prepared = new Voice(handle, effect.current.trackId, false, 1);
            effect.prepared.loopGain = 0;
        }
    }

    private void effectEnded(EffectLoop effect, Voice ended) {
        if (effects.get(ended.trackId) != effect || effect.current != ended || effectsPaused) {
            return;
        }
        // End events are a fallback for unknown durations or a delayed UI tick.
        if (effect.outgoing != null) {
            effect.outgoing.handle.dispose();
            effect.outgoing = null;
        }
        if (effect.prepared == null) {
            prepareEffect(effect);
        }
        if (effect.prepared == null) {
            System.err.println("Could not repeat sound effect " + ended.trackId);
            effects.remove(ended.trackId);
            stopEffect(effect, 0);
            fireChanged();
            return;
        }
        repeatEffect(effect, 0);
    }

    private void repeatEffect(EffectLoop effect, double overlapSeconds) {
        Voice previous = effect.current;
        effect.current = effect.prepared;
        effect.prepared = null;
        effect.blend = overlapSeconds > 0 ? new LoopCrossfade(0, overlapSeconds) : null;
        if (overlapSeconds > 0) {
            effect.outgoing = previous;
            effect.current.gain = previous.gain;
            effect.current.fadeTo(1, effectFadeSeconds);
        } else {
            previous.handle.dispose();
            effect.current.loopGain = 1;
        }
        Voice current = effect.current;
        current.handle.setOnEnd(() -> effectEnded(effect, current));
        current.handle.setVolume(voiceVolume(current));
        current.handle.play();
        if (overlapSeconds <= 0) {
            prepareEffect(effect);
        }
    }

    private void stepEffect(EffectLoop effect, double delta) {
        step(effect.current, delta);
        if (effect.outgoing != null) {
            effect.blend.advance(effect.current.handle.positionMs(), delta);
            effect.current.loopGain = effect.blend.incomingGain();
            effect.outgoing.loopGain = effect.blend.outgoingGain();
            effect.current.handle.setVolume(voiceVolume(effect.current));
            step(effect.outgoing, delta);
            if (effect.blend.finished()) {
                effect.outgoing.handle.dispose();
                effect.outgoing = null;
                prepareEffect(effect);
            }
            return;
        }
        double duration = effect.current.handle.durationMs();
        if (!Double.isFinite(duration) || duration <= 0) {
            duration = library.track(effect.current.trackId).map(AudioTrack::getDurationMs).orElse(0L);
        }
        double remaining = duration - effect.current.handle.positionMs();
        double overlap = Math.min(effectLoopCrossfadeSeconds, duration / 2000);
        if (overlap > 0 && remaining > 0 && remaining <= overlap * 1000
                && effect.prepared != null && effect.prepared.handle.isReady()) {
            repeatEffect(effect, remaining / 1000);
        }
    }

    private void stopEffect(EffectLoop effect, double fadeSeconds) {
        if (effect == null) {
            return;
        }
        if (effect.prepared != null) {
            effect.prepared.handle.dispose();
        }
        stopVoice(effect.current, effectsPaused ? 0 : fadeSeconds);
        stopVoice(effect.outgoing, effectsPaused ? 0 : fadeSeconds);
    }

    public void toggleEffect(String trackId) {
        setEffectActive(trackId, !isEffectActive(trackId));
    }

    /**
     * The status bar play/pause: pauses the music and every running sound effect together, or resumes both
     * (3.35.4). Channels that are not running at all are left alone.
     */
    public void toggleAll() {
        boolean musicRunning = isMusicPlaying();
        boolean effectsRunning = !effects.isEmpty() && !effectsPaused;
        if (musicRunning || effectsRunning) {
            if (musicRunning) {
                toggleMusic();
            }
            if (effectsRunning) {
                setEffectsPaused(true);
            }
            return;
        }
        if (music != null || categoryId != null) {
            toggleMusic();
        }
        if (!effects.isEmpty()) {
            setEffectsPaused(false);
        }
    }

    /** Pauses or resumes every running sound effect at once. */
    public void setEffectsPaused(boolean paused) {
        if (effectsPaused == paused) {
            return;
        }
        effectsPaused = paused;
        if (!paused) {
            effects.values().forEach(effect -> {
                if (effect.blend != null) {
                    effect.blend.resume();
                }
            });
        }
        effects.values().stream().flatMap(effect -> effect.outgoing == null
                        ? java.util.stream.Stream.of(effect.current)
                        : java.util.stream.Stream.of(effect.current, effect.outgoing))
                .forEach(voice -> {
            if (paused) {
                voice.handle.pause();
            } else {
                voice.handle.play();
            }
        });
        fireChanged();
    }

    public void stopAllEffects() {
        effects.values().forEach(effect -> stopEffect(effect, effectFadeSeconds));
        effects.clear();
        fireChanged();
    }

    // ---- Panic / mute ----

    public boolean isPanic() {
        return panic;
    }

    /** Fades everything down to silence (and back) without losing the playlist position. */
    public void setPanic(boolean panic) {
        if (this.panic == panic) {
            return;
        }
        this.panic = panic;
        applyVolumes();
        fireChanged();
    }

    public void togglePanic() {
        setPanic(!panic);
    }

    // ---- Ticking ----

    /**
     * Advances all fades and starts the crossfade into the next track. Call a few times per second;
     * {@code deltaSeconds} is the time since the previous call.
     */
    public void tick(double deltaSeconds) {
        double delta = Math.max(0, Math.min(1, deltaSeconds));
        stepPanic(delta);
        if (music != null) {
            step(music, delta);
            maybeCrossfade();
        }
        if (fadingOut != null) {
            step(fadingOut, delta);
            if (fadingOut.disposeWhenSilent && fadingOut.gain <= 0.001) {
                fadingOut.handle.dispose();
                fadingOut = null;
            }
        }
        if (!effectsPaused) {
            for (EffectLoop effect : List.copyOf(effects.values())) {
                stepEffect(effect, delta);
            }
        }
        for (Voice voice : List.copyOf(stopping)) {
            step(voice, delta);
            if (voice.gain <= 0.001) {
                voice.handle.dispose();
                stopping.remove(voice);
            }
        }
    }

    /** Moves the global panic factor towards its target so muting and unmuting are smooth fades, never a click. */
    private void stepPanic(double deltaSeconds) {
        double target = panic ? 0 : 1;
        if (panicGain == target) {
            return;
        }
        double stepSize = panicFadeSeconds <= 0 ? 1 : deltaSeconds / panicFadeSeconds;
        panicGain = panicGain < target
                ? Math.min(target, panicGain + stepSize)
                : Math.max(target, panicGain - stepSize);
        applyVolumes();
    }

    private void step(Voice voice, double deltaSeconds) {
        if (voice.gain != voice.targetGain) {
            double stepSize = deltaSeconds / voice.fadeSeconds;
            voice.gain = voice.gain < voice.targetGain
                    ? Math.min(voice.targetGain, voice.gain + stepSize)
                    : Math.max(voice.targetGain, voice.gain - stepSize);
        }
        voice.handle.setVolume(voiceVolume(voice));
    }

    /** Starts the next track early enough to cross-fade it with the one that is ending. */
    private void maybeCrossfade() {
        if (crossfadeStarted || crossfadeSeconds <= 0 || musicPaused || playlist.size() < 2) {
            return;
        }
        double duration = durationMs();
        double position = positionMs();
        if (duration <= 0 || position <= 0) {
            return;
        }
        if (duration - position <= crossfadeSeconds * 1000) {
            crossfadeStarted = true;
            advance(1, crossfadeSeconds);
        }
    }

    private void stopVoice(Voice voice, double fadeSeconds) {
        if (voice == null) {
            return;
        }
        if (fadeSeconds <= 0) {
            voice.handle.dispose();
            return;
        }
        voice.fadeTo(0, fadeSeconds);
        voice.disposeWhenSilent = true;
        stopping.add(voice);
    }

    private void applyVolumes() {
        if (music != null) {
            music.handle.setVolume(voiceVolume(music));
        }
        if (fadingOut != null) {
            fadingOut.handle.setVolume(voiceVolume(fadingOut));
        }
        effects.values().stream().flatMap(effect -> effect.voices().stream())
                .forEach(voice -> voice.handle.setVolume(voiceVolume(voice)));
        stopping.forEach(voice -> voice.handle.setVolume(voiceVolume(voice)));
    }

    /** Output volume of a voice: its fade gain times the channel, master and panic factors on a perceptual curve. */
    private double voiceVolume(Voice voice) {
        double channel = voice.musicChannel ? musicVolume : effectsVolume;
        return clamp01(voice.gain * voice.loopGain * curve(channel) * curve(masterVolume) * panicGain);
    }

    /** Volume of a channel as the output hears it, for tests and readouts. */
    public double effectiveVolume(double channelVolume) {
        return curve(channelVolume) * curve(masterVolume) * panicGain;
    }

    private static double curve(double value) {
        return Math.pow(clamp01(value), VOLUME_EXPONENT);
    }

    private static double clamp01(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    /** Stops everything and releases the output. */
    public void shutdown() {
        stopVoice(music, 0);
        stopVoice(fadingOut, 0);
        music = null;
        fadingOut = null;
        effects.values().forEach(effect -> stopEffect(effect, 0));
        effects.clear();
        stopping.forEach(voice -> voice.handle.dispose());
        stopping.clear();
        output.close();
    }
}
