package dmmt.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Plays one music category and any number of looping sound effects on top of it (3.35.4).
 * <p>
 * Everything about playback lives here: the shuffled category playlist, the preloaded equal-power crossfades of
 * music transitions and effect repetitions (one shared {@link VoiceChain} mechanism),
 * the separate music/effects/master volumes, the per-channel pause and the global panic fade. The engine never
 * touches JavaFX directly - it drives {@link AudioOutput} voices and is advanced by {@link #tick(double)}, which
 * the UI calls a few times per second. That makes the whole behaviour unit-testable without sound hardware.
 */
public class AudioEngine {
    /** Perceptual volume curve: slider value ^ this exponent, so the sliders feel linear to the ear. */
    private static final double VOLUME_EXPONENT = 2.2;
    private static final double MIN_FADE_SECONDS = 0.01;
    /** Seconds before a music crossfade (or the end of a track) at which the upcoming song is preloaded. */
    static final double MUSIC_PRELOAD_LEAD_SECONDS = 10;

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
    private boolean musicLoop;
    private int maxEffects = 8;

    private String categoryId;
    private List<String> playlist = new ArrayList<>();
    private int playlistIndex = -1;
    /** The reshuffled order that follows the current cycle; peeked by preloading, committed on the transition. */
    private List<String> nextCycle;
    /** The music chain: playing song, preloaded upcoming song and the song blending out. {@code null} = stopped. */
    private VoiceChain music;
    /** The previous song during a manual (linear) transition. */
    private Voice fadingOut;
    /** Upcoming song whose preload failed; not retried every tick, only at the end-of-track fallback. */
    private String failedMusicPreload;
    private boolean musicPaused;
    private boolean effectsPaused;
    private boolean panic;

    private final Map<String, VoiceChain> effects = new LinkedHashMap<>();
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
        private AudioOutput.Voice handle;
        private final String trackId;
        private final boolean musicChannel;
        private java.nio.file.Path playbackFile;
        private double playbackGainDb;
        private Runnable onEnd;
        private AudioOutput.Voice replacement;
        private AudioLibraryService.PlaybackSource replacementSource;
        private double gain;
        private double targetGain = 1;
        private double fadeSeconds = 1;
        private boolean disposeWhenSilent;
        private double loopGain = 1;
        private boolean playing;

        Voice(AudioOutput.Voice handle, String trackId, AudioLibraryService.PlaybackSource source,
              boolean musicChannel, double gain) {
            this.handle = handle;
            this.trackId = trackId;
            this.musicChannel = musicChannel;
            this.playbackFile = source.file();
            this.playbackGainDb = source.bakedGainDb();
            this.gain = gain;
        }

        void fadeTo(double target, double seconds) {
            targetGain = target;
            fadeSeconds = Math.max(MIN_FADE_SECONDS, seconds);
        }

        void setOnEnd(Runnable action) {
            onEnd = action;
            AudioOutput.Voice opened = handle;
            opened.setOnEnd(() -> {
                if (handle == opened && onEnd != null) {
                    playing = false;
                    onEnd.run();
                }
            });
        }

        void dispose() {
            playing = false;
            onEnd = null;
            handle.dispose();
            if (replacement != null) {
                replacement.dispose();
                replacement = null;
            }
        }

        void play() {
            playing = true;
            handle.play();
        }

        void pause() {
            playing = false;
            handle.pause();
        }
    }

    /**
     * A playing voice that continues into a preloaded, silent second voice with an equal-power overlap. Sound effects
     * continue into another copy of themselves; music continues into the upcoming song (or itself when looping).
     */
    private static final class VoiceChain {
        private Voice current;
        private Voice prepared;
        private Voice outgoing;
        private LoopCrossfade blend;

        VoiceChain(Voice current) {
            this.current = current;
        }

        void resumeBlend() {
            if (blend != null) {
                blend.resume();
            }
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

    /**
     * Reapplies per-track gain after a library override or loudness analysis changes. Overrides take effect
     * immediately because each voice is scaled relative to the gain baked into the copy it opened.
     */
    public void refreshTrackVolumes() {
        for (Voice voice : allVoices()) {
            requestReplacement(voice);
        }
        applyVolumes();
    }

    /**
     * Whether a live voice of this track still plays an older playback copy (after {@code analyzeLoudness}).
     * Such voices stay correct but cannot exceed their old copy's baked gain until the track is reopened.
     */
    public boolean needsReopen(String trackId) {
        Optional<AudioTrack> track = library.track(trackId).filter(AudioTrack::isReady);
        if (track.isEmpty()) {
            return false;
        }
        java.nio.file.Path current = library.playbackSourceOf(track.get()).file();
        return allVoices().stream().anyMatch(voice -> voice.trackId.equals(trackId)
                && !voice.playbackFile.equals(current));
    }

    /**
     * Restarts the running music/effect voices of a track from the current playback copy at their positions,
     * so a fresh analysis can apply its newly prepared boost headroom. Other tracks are not touched.
     */
    public void reopenTrack(String trackId) {
        if (!needsReopen(trackId)) {
            return;
        }
        allVoices().stream().filter(voice -> voice.trackId.equals(trackId)).forEach(this::requestReplacement);
        fireChanged();
    }

    private void requestReplacement(Voice voice) {
        Optional<AudioTrack> track = library.track(voice.trackId);
        if (track.isEmpty()) {
            return;
        }
        AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track.get());
        if (voice.replacement != null && !source.equals(voice.replacementSource)) {
            voice.replacement.dispose();
            voice.replacement = null;
        }
        if (voice.playbackFile.equals(source.file())) {
            return;
        }
        if (voice.replacement == null) {
            voice.replacement = output.open(source.file(), false);
            voice.replacementSource = source;
            if (voice.replacement == null) {
                System.err.println("Could not apply the new playback source for " + voice.trackId);
                return;
            }
            voice.replacement.setVolume(0);
        }
        finishReplacement(voice);
    }

    private void finishReplacement(Voice voice) {
        if (voice.replacement == null || !voice.replacement.isReady()) {
            return;
        }
        AudioOutput.Voice old = voice.handle;
        double position = old.positionMs();
        voice.handle = voice.replacement;
        voice.playbackFile = voice.replacementSource.file();
        voice.playbackGainDb = voice.replacementSource.bakedGainDb();
        voice.replacement = null;
        voice.handle.seek(position);
        voice.handle.setVolume(voiceVolume(voice));
        voice.setOnEnd(voice.onEnd);
        old.dispose();
        if (voice.playing) {
            voice.handle.play();
        }
    }

    private List<Voice> allVoices() {
        List<Voice> voices = new ArrayList<>();
        if (music != null) {
            voices.addAll(music.voices());
        }
        if (fadingOut != null) {
            voices.add(fadingOut);
        }
        effects.values().forEach(effect -> voices.addAll(effect.voices()));
        voices.addAll(stopping);
        return voices;
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
        if (this.shuffle != shuffle) {
            nextCycle = null;
        }
        this.shuffle = shuffle;
    }

    public boolean isMusicLoop() {
        return musicLoop;
    }

    public void setMusicLoop(boolean loop) {
        musicLoop = loop;
        fireChanged();
    }

    /** Overlap of every automatic music transition: next song, single-song repeat and loop current song. */
    public void setMusicCrossfadeSeconds(double seconds) {
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
     * {@link #stopMusic()} as playlist history, so the overlay asks this instead (3.35.6).
     */
    public boolean isCategoryActive(String id) {
        return id != null && id.equals(categoryId) && music != null;
    }

    public Optional<AudioTrack> currentTrack() {
        return music == null ? Optional.empty() : library.track(music.current.trackId);
    }

    public boolean isMusicPlaying() {
        return music != null && !musicPaused;
    }

    public boolean isMusicPaused() {
        return musicPaused;
    }

    public double positionMs() {
        return music == null ? 0 : music.current.handle.positionMs();
    }

    public double durationMs() {
        return music == null ? 0 : durationOf(music.current);
    }

    /** Starts (or restarts) a category; {@code null} stops the music. */
    public void playCategory(String categoryId) {
        playCategory(categoryId, null);
    }

    /** Starts the track's category at this song, retaining the usual playlist order afterwards. */
    public void playTrack(String trackId) {
        AudioTrack track = library.track(trackId)
                .filter(AudioTrack::isReady)
                .filter(t -> t.getKind() == AudioKind.MUSIC)
                .orElseThrow(() -> new IllegalArgumentException("Unknown music track: " + trackId));
        playCategory(track.getCategoryId(), trackId);
    }

    private void playCategory(String categoryId, String firstTrackId) {
        if (categoryId == null) {
            stopMusic();
            return;
        }
        this.categoryId = categoryId;
        playlist = new ArrayList<>(library.musicOf(categoryId).stream().map(AudioTrack::getId).toList());
        nextCycle = null;
        shuffleOrder(playlist, null);
        playlistIndex = -1;
        if (firstTrackId != null) {
            if (shuffle) {
                playlist.remove(firstTrackId);
                playlist.addFirst(firstTrackId);
            }
            playlistIndex = playlist.indexOf(firstTrackId) - 1;
        }
        musicPaused = false;
        if (playlist.isEmpty()) {
            releaseMusic(0.2);
            fireChanged();
            return;
        }
        advance(1, crossfadeSeconds);
    }

    /** Pauses or resumes active music; stopped music stays stopped. */
    public void toggleMusic() {
        if (music == null) {
            return;
        }
        musicPaused = !musicPaused;
        List<Voice> audible = new ArrayList<>();
        audible.add(music.current);
        if (music.outgoing != null) {
            audible.add(music.outgoing);
        }
        if (fadingOut != null) {
            audible.add(fadingOut);
        }
        if (musicPaused) {
            audible.forEach(Voice::pause);
        } else {
            music.resumeBlend();
            audible.forEach(Voice::play);
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
        releaseMusic(Math.min(crossfadeSeconds, 2));
        playlistIndex = -1;
        musicPaused = false;
        fireChanged();
    }

    /** Fades out both sides of the music chain and drops its preloaded voice. */
    private void releaseMusic(double fadeSeconds) {
        if (music != null) {
            disposeVoice(music.prepared);
            stopVoice(music.current, fadeSeconds);
            stopVoice(music.outgoing, fadeSeconds);
        }
        music = null;
        failedMusicPreload = null;
    }

    /** A playlist position (and the order it belongs to) that has not necessarily been committed yet. */
    private record Upcoming(List<String> order, int index) {
        String trackId() {
            return order.get(index);
        }
    }

    /**
     * Where a step in {@code direction} (0 = restart, 1 = next, -1 = previous) would lead, without moving the
     * playlist: the reshuffle at the end of a cycle is prepared in {@link #nextCycle} and applied on commit.
     */
    private Upcoming peek(int direction) {
        dropDeletedTracks();
        if (playlist.isEmpty()) {
            return null;
        }
        if (direction == 0) {
            return new Upcoming(playlist, Math.max(0, playlistIndex));
        }
        if (playlistIndex < 0) {
            return new Upcoming(playlist, direction > 0 ? 0 : playlist.size() - 1);
        }
        int next = playlistIndex + direction;
        if (next >= playlist.size()) {
            if (nextCycle == null) {
                nextCycle = new ArrayList<>(playlist);
                shuffleOrder(nextCycle, playlist.get(playlistIndex));
            }
            return new Upcoming(nextCycle, 0);
        }
        return new Upcoming(playlist, next < 0 ? playlist.size() - 1 : next);
    }

    /** Forgets tracks that were deleted from the library since the category started. */
    private void dropDeletedTracks() {
        for (int i = playlist.size() - 1; i >= 0; i--) {
            if (library.track(playlist.get(i)).filter(AudioTrack::isReady).isEmpty()) {
                playlist.remove(i);
                nextCycle = null;
                if (i <= playlistIndex) {
                    playlistIndex--;
                }
            }
        }
    }

    private void commit(Upcoming upcoming) {
        if (upcoming.order() != playlist) {
            playlist = upcoming.order();
            nextCycle = null;
        }
        playlistIndex = upcoming.index();
    }

    /** Direction of the automatic transition at the end of a song. */
    private int automaticDirection() {
        return musicLoop ? 0 : 1;
    }

    private void advance(int direction, double fadeSeconds) {
        Upcoming upcoming = peek(direction);
        if (upcoming == null) {
            return;
        }
        commit(upcoming);
        startMusic(upcoming.trackId(), fadeSeconds);
    }

    /** Manual transition: the playing song fades out linearly while the new one fades in. */
    private void startMusic(String trackId, double fadeSeconds) {
        Optional<AudioTrack> track = library.track(trackId).filter(AudioTrack::isReady);
        if (track.isEmpty()) {
            return;
        }
        stopVoice(fadingOut, 0);
        fadingOut = null;
        if (music != null) {
            fadingOut = music.current;
            fadingOut.fadeTo(0, fadeSeconds);
            fadingOut.disposeWhenSilent = true;
            disposeVoice(music.prepared);
            stopVoice(music.outgoing, fadeSeconds);
        }
        failedMusicPreload = null;
        AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track.get());
        AudioOutput.Voice handle = output.open(source.file(), false);
        if (handle == null) {
            System.err.println("Could not play music track " + trackId);
            music = null;
            fireChanged();
            return;
        }
        Voice voice = new Voice(handle, trackId, source, true, fadeSeconds > 0 ? 0 : 1);
        voice.fadeTo(1, fadeSeconds);
        music = new VoiceChain(voice);
        handle.setVolume(voiceVolume(voice));
        voice.setOnEnd(() -> musicEnded(voice));
        if (!musicPaused) {
            voice.play();
        }
        fireChanged();
    }

    /**
     * Keeps the preloaded music voice in line with the song that would follow automatically, replacing it when the
     * loop toggle, shuffle or library changed that song. Preloads only within the lead time before the end unless
     * {@code now}.
     */
    private void syncPreparedMusic(boolean now) {
        Upcoming upcoming = peek(automaticDirection());
        String trackId = upcoming == null || library.track(upcoming.trackId()).isEmpty() ? null : upcoming.trackId();
        if (music.prepared != null && !music.prepared.trackId.equals(trackId)) {
            disposeVoice(music.prepared);
            music.prepared = null;
        }
        if (music.prepared != null || trackId == null) {
            return;
        }
        if (!now) {
            double duration = durationOf(music.current);
            double remaining = duration - music.current.handle.positionMs();
            if (duration <= 0 || remaining > (crossfadeSeconds + MUSIC_PRELOAD_LEAD_SECONDS) * 1000
                    || trackId.equals(failedMusicPreload)) {
                return;
            }
        }
        music.prepared = openPrepared(trackId, true);
        if (music.prepared == null) {
            if (!trackId.equals(failedMusicPreload)) {
                System.err.println("Could not preload music track " + trackId);
            }
            failedMusicPreload = trackId;
        } else {
            failedMusicPreload = null;
        }
    }

    private void stepMusic(double delta) {
        if (musicPaused) {
            step(music.current, delta);
            if (music.outgoing != null) {
                step(music.outgoing, delta);
            }
            return;
        }
        stepChain(music, delta);
        if (music.outgoing != null) {
            return;
        }
        syncPreparedMusic(false);
        double overlap = overlapDue(music, crossfadeSeconds);
        if (overlap > 0) {
            commit(peek(automaticDirection()));
            continueChain(music, overlap, crossfadeSeconds, this::musicEnded);
            fireChanged();
        }
    }

    /** End-of-track fallback for hard cuts, unknown durations, unready preloads or a delayed tick. */
    private void musicEnded(Voice ended) {
        if (music == null || music.current != ended || musicPaused) {
            return;
        }
        finishBlend(music);
        syncPreparedMusic(true);
        if (music.prepared == null) {
            System.err.println("Could not continue the music after " + ended.trackId);
            releaseMusic(0);
            fireChanged();
            return;
        }
        commit(peek(automaticDirection()));
        continueChain(music, 0, crossfadeSeconds, this::musicEnded);
        fireChanged();
    }

    /** Shuffles an order, avoiding {@code lastPlayed} as its first track so nothing repeats back to back. */
    private void shuffleOrder(List<String> order, String lastPlayed) {
        if (!shuffle || order.size() < 2) {
            return;
        }
        Collections.shuffle(order, random);
        if (lastPlayed != null && order.get(0).equals(lastPlayed)) {
            Collections.swap(order, 0, 1);
        }
    }

    // ---- Shared preloaded overlap ----

    /** Opens a silent, unstarted voice that a chain continues into. */
    private Voice openPrepared(String trackId, boolean musicChannel) {
        Optional<AudioTrack> track = library.track(trackId).filter(AudioTrack::isReady);
        if (track.isEmpty()) {
            return null;
        }
        AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track.get());
        AudioOutput.Voice handle = output.open(source.file(), false);
        if (handle == null) {
            return null;
        }
        handle.setVolume(0);
        Voice voice = new Voice(handle, trackId, source, musicChannel, 1);
        voice.loopGain = 0;
        return voice;
    }

    /** Length of a voice: the stream's own duration, else the analysed library duration, else 0. */
    private double durationOf(Voice voice) {
        double duration = voice.handle.durationMs();
        if (Double.isFinite(duration) && duration > 0) {
            return duration;
        }
        return library.track(voice.trackId).map(AudioTrack::getDurationMs).orElse(0L);
    }

    /**
     * Seconds of overlap to start now, or 0: the prepared voice must be ready and the current one within its last
     * {@code overlapSeconds} (capped at half its length).
     */
    private double overlapDue(VoiceChain chain, double overlapSeconds) {
        if (chain.outgoing != null || chain.prepared == null || !chain.prepared.handle.isReady()) {
            return 0;
        }
        double duration = durationOf(chain.current);
        double remaining = duration - chain.current.handle.positionMs();
        double overlap = Math.min(overlapSeconds, duration / 2000);
        return overlap > 0 && remaining > 0 && remaining <= overlap * 1000 ? remaining / 1000 : 0;
    }

    /** Starts the prepared voice, overlapping the current one for {@code overlapSeconds} (0 = hard cut). */
    private void continueChain(VoiceChain chain, double overlapSeconds, double fadeSeconds, Consumer<Voice> onEnd) {
        Voice previous = chain.current;
        Voice current = chain.prepared;
        chain.current = current;
        chain.prepared = null;
        current.gain = previous.gain;
        current.fadeTo(1, fadeSeconds);
        if (overlapSeconds > 0) {
            chain.outgoing = previous;
            chain.blend = new LoopCrossfade(0, overlapSeconds);
        } else {
            previous.dispose();
            chain.blend = null;
            current.loopGain = 1;
        }
        current.setOnEnd(() -> onEnd.accept(current));
        current.handle.setVolume(voiceVolume(current));
        current.play();
    }

    /** Advances fades and the equal-power blend; returns whether a blend finished during this step. */
    private boolean stepChain(VoiceChain chain, double delta) {
        if (chain.outgoing != null) {
            chain.blend.advance(chain.current.handle.positionMs(), delta);
            chain.current.loopGain = chain.blend.incomingGain();
            chain.outgoing.loopGain = chain.blend.outgoingGain();
        }
        step(chain.current, delta);
        if (chain.outgoing == null) {
            return false;
        }
        step(chain.outgoing, delta);
        if (!chain.blend.finished()) {
            return false;
        }
        finishBlend(chain);
        return true;
    }

    private void finishBlend(VoiceChain chain) {
        if (chain.outgoing != null) {
            chain.outgoing.dispose();
            chain.outgoing = null;
        }
        chain.blend = null;
        chain.current.loopGain = 1;
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
        Optional<AudioTrack> track = library.track(trackId).filter(AudioTrack::isReady);
        if (track.isEmpty()) {
            return;
        }
        if (effects.size() >= maxEffects) {
            // Oldest effect makes room, so the cap can never be exceeded on low-end hardware.
            String oldest = effects.keySet().iterator().next();
            stopEffect(effects.remove(oldest), effectFadeSeconds);
        }
        AudioLibraryService.PlaybackSource source = library.playbackSourceOf(track.get());
        AudioOutput.Voice handle = output.open(source.file(), false);
        if (handle == null) {
            return;
        }
        Voice voice = new Voice(handle, trackId, source, false, 0);
        voice.fadeTo(1, effectFadeSeconds);
        handle.setVolume(voiceVolume(voice));
        VoiceChain effect = new VoiceChain(voice);
        effects.put(trackId, effect);
        prepareEffect(effect);
        voice.setOnEnd(() -> effectEnded(effect, voice));
        if (!effectsPaused) {
            voice.play();
        }
        fireChanged();
    }

    private void prepareEffect(VoiceChain effect) {
        effect.prepared = openPrepared(effect.current.trackId, false);
    }

    private void effectEnded(VoiceChain effect, Voice ended) {
        if (effects.get(ended.trackId) != effect || effect.current != ended || effectsPaused) {
            return;
        }
        // End events are a fallback for unknown durations or a delayed UI tick.
        finishBlend(effect);
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
        continueChain(effect, 0, effectFadeSeconds, voice -> effectEnded(effect, voice));
        prepareEffect(effect);
    }

    private void stepEffect(VoiceChain effect, double delta) {
        if (stepChain(effect, delta)) {
            prepareEffect(effect);
            return;
        }
        double overlap = overlapDue(effect, effectLoopCrossfadeSeconds);
        if (overlap > 0) {
            continueChain(effect, overlap, effectFadeSeconds, voice -> effectEnded(effect, voice));
        }
    }

    private void stopEffect(VoiceChain effect, double fadeSeconds) {
        if (effect == null) {
            return;
        }
        disposeVoice(effect.prepared);
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
        if (music != null) {
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
            effects.values().forEach(VoiceChain::resumeBlend);
        }
        effects.values().stream().flatMap(effect -> effect.outgoing == null
                        ? java.util.stream.Stream.of(effect.current)
                        : java.util.stream.Stream.of(effect.current, effect.outgoing))
                .forEach(voice -> {
            if (paused) {
                voice.pause();
            } else {
                voice.play();
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
     * Whether playback needs the fast loop cadence: running effects, or music that is about to blend, blending or
     * fading out after a manual switch. Otherwise the slower readout cadence is enough.
     */
    public boolean needsSmoothUpdates() {
        boolean effectsRunning = !effects.isEmpty() && !effectsPaused;
        boolean musicBlending = music != null && !musicPaused
                && (music.prepared != null || music.outgoing != null || fadingOut != null);
        return effectsRunning || musicBlending;
    }

    /**
     * Advances all fades, preloads the upcoming song and starts the music and effect overlaps. Call a few times per
     * second; {@code deltaSeconds} is the time since the previous call.
     */
    public void tick(double deltaSeconds) {
        allVoices().forEach(this::requestReplacement);
        double delta = Math.max(0, Math.min(1, deltaSeconds));
        stepPanic(delta);
        if (music != null) {
            stepMusic(delta);
        }
        if (fadingOut != null) {
            step(fadingOut, delta);
            if (fadingOut.disposeWhenSilent && fadingOut.gain <= 0.001) {
                fadingOut.dispose();
                fadingOut = null;
            }
        }
        if (!effectsPaused) {
            for (VoiceChain effect : List.copyOf(effects.values())) {
                stepEffect(effect, delta);
            }
        }
        for (Voice voice : List.copyOf(stopping)) {
            step(voice, delta);
            if (voice.gain <= 0.001) {
                voice.dispose();
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

    private void stopVoice(Voice voice, double fadeSeconds) {
        if (voice == null) {
            return;
        }
        if (fadeSeconds <= 0) {
            voice.dispose();
            return;
        }
        voice.fadeTo(0, fadeSeconds);
        voice.disposeWhenSilent = true;
        stopping.add(voice);
    }

    private void disposeVoice(Voice voice) {
        if (voice != null) {
            voice.dispose();
        }
    }

    private void applyVolumes() {
        allVoices().forEach(voice -> voice.handle.setVolume(voiceVolume(voice)));
    }

    /** Output volume of a voice: its fade gain times the channel, master and panic factors on a perceptual curve. */
    private double voiceVolume(Voice voice) {
        double channel = voice.musicChannel ? musicVolume : effectsVolume;
        double trackGain = library.track(voice.trackId)
                .map(track -> track.playbackVolumeFactor(voice.playbackGainDb)).orElse(1.0);
        return clamp01(voice.gain * voice.loopGain * trackGain * curve(channel) * curve(masterVolume) * panicGain);
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
        releaseMusic(0);
        stopVoice(fadingOut, 0);
        fadingOut = null;
        effects.values().forEach(effect -> stopEffect(effect, 0));
        effects.clear();
        stopping.forEach(Voice::dispose);
        stopping.clear();
        output.close();
    }
}
