package dmmt.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playlist, crossfade, sound effect layering, volumes and the panic mute (3.35.4). */
class AudioEngineTest {
    @TempDir
    Path dir;

    private AudioLibraryService library;
    private FakeAudioOutput output;
    private AudioEngine engine;
    private AudioCategory combat;

    @BeforeEach
    void setUp() throws IOException {
        library = new AudioLibraryService(dir.resolve("audio"));
        output = new FakeAudioOutput();
        // A fixed seed keeps the shuffle reproducible.
        engine = new AudioEngine(library, output, new Random(7));
        engine.setVolumes(1, 1, 1);
        combat = library.createCategory("Combat", "#FF0000", "mdi2s-sword-cross");
    }

    private AudioTrack music(String name) throws IOException {
        return importTrack(name, AudioKind.MUSIC, combat.getId());
    }

    private AudioTrack effect(String name) throws IOException {
        return importTrack(name, AudioKind.EFFECT, null);
    }

    private AudioTrack importTrack(String name, AudioKind kind, String categoryId) throws IOException {
        Path source = dir.resolve(name + ".wav");
        TestAudioFiles.writeWav(source, 200, 8000);
        return library.importFile(source, kind, categoryId);
    }

    /** Runs enough ticks for every fade to reach its target (one tick advances a fade by at most one second). */
    private void settle() {
        for (int i = 0; i < 20; i++) {
            engine.tick(0.5);
        }
    }

    @Test
    void playingACategoryStartsOneTrackAndFadesItIn() throws IOException {
        music("one");

        engine.playCategory(combat.getId());

        assertEquals(1, output.playing().size());
        assertTrue(engine.isMusicPlaying());
        assertEquals(0, output.last().volume, 0.0001, "the first tick has not run yet");
        settle();
        assertEquals(1, output.last().volume, 0.0001);
    }

    @Test
    void anEmptyCategoryPlaysNothing() {
        engine.playCategory(combat.getId());

        assertTrue(output.playing().isEmpty());
        assertFalse(engine.isMusicPlaying());
    }

    @Test
    void nextAndPreviousWalkThroughThePlaylist() throws IOException {
        engine.setShuffle(false);
        music("a");
        music("b");
        music("c");

        engine.playCategory(combat.getId());
        String first = engine.currentTrack().orElseThrow().getName();
        engine.next();
        String second = engine.currentTrack().orElseThrow().getName();
        assertNotEquals(first, second);

        engine.previous();
        assertEquals(first, engine.currentTrack().orElseThrow().getName(),
                "previous right after a switch goes back");
    }

    @Test
    void previousRestartsTheTrackWhenItHasBeenRunningForAWhile() throws IOException {
        engine.setShuffle(false);
        music("a");
        music("b");
        engine.playCategory(combat.getId());
        String playing = engine.currentTrack().orElseThrow().getName();
        output.last().advanceTo(20_000);

        engine.previous();

        assertEquals(playing, engine.currentTrack().orElseThrow().getName());
    }

    @Test
    void theEndOfATrackStartsTheNextOne() throws IOException {
        engine.setShuffle(false);
        music("a");
        music("b");
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice first = output.last();

        first.reachEnd();

        assertEquals(2, output.opened.size());
        assertTrue(output.last().playing);
    }

    @Test
    void theNextTrackIsStartedBeforeTheCurrentOneEnds() throws IOException {
        engine.setShuffle(false);
        engine.setCrossfadeSeconds(4);
        music("a");
        music("b");
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice first = output.last();
        engine.tick(0.25);
        assertEquals(1, output.opened.size(), "no crossfade while the track is still long");

        first.advanceTo(first.duration - 2_000);
        engine.tick(0.25);

        assertEquals(2, output.opened.size(), "the next track must already be running");
        assertTrue(output.last().playing);
        assertTrue(output.last().volume < first.volume, "the new track fades in while the old one fades out");

        engine.tick(0.25);
        assertEquals(2, output.opened.size(), "the crossfade must not be started twice");
    }

    @Test
    void aFinishedCrossfadeDisposesTheOldVoice() throws IOException {
        engine.setShuffle(false);
        engine.setCrossfadeSeconds(2);
        music("a");
        music("b");
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice first = output.last();
        first.advanceTo(first.duration - 1_000);
        engine.tick(0.25);

        for (int i = 0; i < 20; i++) {
            engine.tick(0.25);
        }

        assertTrue(first.disposed);
        assertEquals(1, output.live().size());
    }

    @Test
    void pauseAndResumeKeepTheTrack() throws IOException {
        music("a");
        engine.playCategory(combat.getId());

        engine.toggleMusic();
        assertTrue(engine.isMusicPaused());
        assertFalse(output.last().playing);

        engine.toggleMusic();
        assertFalse(engine.isMusicPaused());
        assertTrue(output.last().playing);
        assertEquals(1, output.opened.size(), "resuming must not reopen the file");
    }

    @Test
    void stoppingTheMusicLeavesTheSoundEffectsRunning() throws IOException {
        music("a");
        AudioTrack rain = effect("rain");
        engine.playCategory(combat.getId());
        engine.setEffectActive(rain.getId(), true);

        engine.stopMusic();
        for (int i = 0; i < 20; i++) {
            engine.tick(0.5);
        }

        assertFalse(engine.isMusicPlaying());
        assertEquals(List.of(rain.getId()), engine.activeEffects());
        assertEquals(1, output.playing().size());
    }

    @Test
    void soundEffectsLoopAndMixOnTopOfTheMusic() throws IOException {
        music("a");
        AudioTrack rain = effect("rain");
        AudioTrack wind = effect("wind");

        engine.playCategory(combat.getId());
        engine.setEffectActive(rain.getId(), true);
        engine.setEffectActive(wind.getId(), true);
        settle();

        assertEquals(3, output.playing().size());
        assertEquals(List.of(rain.getId(), wind.getId()), engine.activeEffects());
        assertTrue(output.live().stream().filter(voice -> voice.loop).count() == 2, "effects must loop");
    }

    @Test
    void theEffectCapStopsTheOldestEffect() throws IOException {
        engine.setMaxEffects(2);
        AudioTrack a = effect("a");
        AudioTrack b = effect("b");
        AudioTrack c = effect("c");

        engine.setEffectActive(a.getId(), true);
        engine.setEffectActive(b.getId(), true);
        engine.setEffectActive(c.getId(), true);

        assertEquals(List.of(b.getId(), c.getId()), engine.activeEffects());
        assertFalse(engine.isEffectActive(a.getId()));
    }

    @Test
    void pausingTheEffectsPausesAllOfThemAtOnce() throws IOException {
        music("a");
        engine.setEffectActive(effect("rain").getId(), true);
        engine.setEffectActive(effect("wind").getId(), true);
        engine.playCategory(combat.getId());

        engine.setEffectsPaused(true);

        assertTrue(engine.areEffectsPaused());
        assertEquals(1, output.playing().size(), "only the music keeps playing");

        engine.setEffectsPaused(false);
        assertEquals(3, output.playing().size());
    }

    @Test
    void theStatusBarButtonPausesAndResumesMusicAndEffectsTogether() throws IOException {
        music("a");
        engine.setEffectActive(effect("rain").getId(), true);
        engine.playCategory(combat.getId());
        assertEquals(2, output.playing().size());

        engine.toggleAll();

        assertFalse(engine.isMusicPlaying());
        assertTrue(engine.areEffectsPaused());
        assertEquals(0, output.playing().size(), "nothing is left running");

        engine.toggleAll();

        assertTrue(engine.isMusicPlaying());
        assertFalse(engine.areEffectsPaused());
        assertEquals(2, output.playing().size());
    }

    @Test
    void theChannelVolumesScaleTheirOwnVoicesOnly() throws IOException {
        music("a");
        AudioTrack rain = effect("rain");
        engine.playCategory(combat.getId());
        engine.setEffectActive(rain.getId(), true);
        settle();

        engine.setMusicVolume(0.5);

        FakeAudioOutput.FakeVoice musicVoice = output.opened.get(0);
        FakeAudioOutput.FakeVoice effectVoice = output.opened.get(1);
        assertEquals(engine.effectiveVolume(0.5), musicVoice.volume, 0.0001);
        assertEquals(1, effectVoice.volume, 0.0001);

        engine.setMasterVolume(0.5);
        assertTrue(effectVoice.volume < 1, "the master volume scales both channels");
    }

    @Test
    void panicFadesEverythingOutAndBackIn() throws IOException {
        music("a");
        engine.setPanicFadeSeconds(1);
        engine.playCategory(combat.getId());
        settle();
        FakeAudioOutput.FakeVoice voice = output.last();

        engine.togglePanic();
        engine.tick(0.5);
        assertTrue(voice.volume > 0 && voice.volume < 1, "half way through the fade: " + voice.volume);
        engine.tick(1);
        assertEquals(0, voice.volume, 0.0001);
        assertTrue(voice.playing, "panic mutes, it does not stop playback");

        engine.togglePanic();
        engine.tick(2);
        assertEquals(1, voice.volume, 0.0001);
    }

    @Test
    void ambienceStartsTheStoredCategoryAndExactlyTheStoredEffects() throws IOException {
        music("a");
        AudioTrack rain = effect("rain");
        AudioTrack wind = effect("wind");
        engine.setEffectActive(wind.getId(), true);

        engine.applyAmbience(combat.getId(), List.of(rain.getId()));

        assertEquals(combat.getId(), engine.categoryId());
        assertTrue(engine.isMusicPlaying());
        assertEquals(List.of(rain.getId()), engine.activeEffects());
    }

    @Test
    void ambienceIgnoresUnknownIdsAndDoesNotRestartTheRunningCategory() throws IOException {
        music("a");
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice running = output.last();

        engine.applyAmbience(combat.getId(), List.of("no-such-effect"));

        assertEquals(running, output.last(), "the same category must not be restarted");
        assertTrue(engine.activeEffects().isEmpty());

        engine.applyAmbience("no-such-category", List.of());
        assertEquals(combat.getId(), engine.categoryId());
    }

    @Test
    void shuffleUsesADifferentOrderThanTheLibrary() throws IOException {
        for (int i = 0; i < 8; i++) {
            music("track" + i);
        }
        List<String> order = library.musicOf(combat.getId()).stream().map(AudioTrack::getId).toList();

        engine.setShuffle(true);
        engine.playCategory(combat.getId());
        List<String> played = new java.util.ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            played.add(engine.currentTrack().orElseThrow().getId());
            engine.next();
        }

        assertEquals(order.size(), played.size());
        assertEquals(java.util.Set.copyOf(order), java.util.Set.copyOf(played),
                "every track is played exactly once before the playlist repeats");
        assertNotEquals(order, played);
    }

    @Test
    void shutdownReleasesEveryVoice() throws IOException {
        music("a");
        engine.setEffectActive(effect("rain").getId(), true);
        engine.playCategory(combat.getId());

        engine.shutdown();

        assertTrue(output.live().isEmpty());
    }
}
