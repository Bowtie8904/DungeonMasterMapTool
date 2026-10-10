package dmmt.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        TestAudioFiles.writeWav(source, 200, 8000, 0);
        return library.importFile(source, kind, categoryId);
    }

    private AudioTrack transientTrack(String name, AudioKind kind, String categoryId) throws IOException {
        int rate = 8000;
        java.nio.ByteBuffer pcm = java.nio.ByteBuffer.allocate(rate * 2 * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rate * 2; i++) {
            pcm.putShort((short) (i == rate ? 32000 : Math.sin(2 * Math.PI * 220 * i / rate) * 1000));
        }
        Path file = dir.resolve(name + ".wav");
        TestAudioFiles.writeWav(file, pcm.array(), rate);
        return library.importFile(file, kind, categoryId);
    }

    @Test
    void limiterSourceSwitchPreservesPausedMusicPositionAndPreparedEffectState() throws IOException {
        AudioTrack track = transientTrack("transient", AudioKind.MUSIC, combat.getId());
        engine.setMusicCrossfadeSeconds(0);
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice old = output.last();
        old.advanceTo(720);
        engine.toggleMusic();
        library.setGainOverride(track.getId(), 12.0);
        engine.refreshTrackVolumes();
        FakeAudioOutput.FakeVoice replaced = output.last();
        assertTrue(old.disposed);
        assertEquals(720, replaced.position);
        assertFalse(replaced.playing);
        assertEquals(library.playbackFileOf(track), replaced.file);
        engine.toggleMusic();
        assertTrue(replaced.playing);

        AudioTrack effect = transientTrack("effect", AudioKind.EFFECT, null);
        engine.setEffectFadeSeconds(0);
        engine.setEffectActive(effect.getId(), true);
        engine.tick(0.1);
        FakeAudioOutput.FakeVoice current = output.opened.get(output.opened.size() - 2);
        current.advanceTo(400);
        engine.setEffectsPaused(true);
        library.setGainOverride(effect.getId(), 12.0);
        engine.refreshTrackVolumes();
        List<FakeAudioOutput.FakeVoice> effectCopies = output.live().stream()
                .filter(voice -> voice.file.equals(library.playbackFileOf(effect))).toList();
        assertEquals(2, effectCopies.size());
        assertTrue(effectCopies.stream().noneMatch(voice -> voice.playing));
        assertEquals(400, effectCopies.get(0).position);
        engine.setEffectsPaused(false);
        assertTrue(effectCopies.get(0).playing);
        assertFalse(effectCopies.get(1).playing, "preloaded repetition must remain silent and unstarted");
        engine.shutdown();
    }

    @Test
    void changingLimiterDuringMusicCrossfadePreservesOutgoingFadeAndOtherTrack() throws IOException {
        engine.setShuffle(false);
        engine.setMusicCrossfadeSeconds(1);
        AudioTrack first = transientTrack("a-transient", AudioKind.MUSIC, combat.getId());
        transientTrack("b-transient", AudioKind.MUSIC, combat.getId());
        engine.playCategory(combat.getId());
        engine.tick(1);
        FakeAudioOutput.FakeVoice outgoing = output.last();
        outgoing.advanceTo(500);
        engine.next();
        engine.tick(0.25);
        FakeAudioOutput.FakeVoice incoming = output.last();
        double incomingVolume = incoming.volume;
        library.setGainOverride(first.getId(), 12.0);
        engine.refreshTrackVolumes();
        FakeAudioOutput.FakeVoice replacement = output.last();
        assertTrue(outgoing.disposed);
        assertEquals(500, replacement.position);
        assertEquals(0.75, replacement.volume, 0.0001);
        assertEquals(incomingVolume, incoming.volume);
        outgoing.reachEnd();
        assertEquals("b-transient", engine.currentTrack().orElseThrow().getName());
        engine.tick(0.25);
        assertEquals(0.5, replacement.volume, 0.0001);
        engine.shutdown();
    }

    @Test
    void sourceSwitchDuringEffectOverlapKeepsBothSidesAndLoopProgress() throws IOException {
        AudioTrack track = transientTrack("effect", AudioKind.EFFECT, null);
        engine.setEffectFadeSeconds(0);
        engine.setEffectLoopCrossfadeSeconds(0.5);
        engine.setEffectActive(track.getId(), true);
        engine.tick(0.1);
        FakeAudioOutput.FakeVoice old = output.opened.get(0);
        old.advanceTo(old.duration - 400);
        engine.tick(0);
        FakeAudioOutput.FakeVoice incoming = output.opened.get(1);
        incoming.advanceTo(200);
        engine.tick(0);
        library.setGainOverride(track.getId(), 12.0);
        engine.refreshTrackVolumes();
        List<FakeAudioOutput.FakeVoice> live = output.playing();
        assertEquals(2, live.size());
        assertTrue(old.disposed);
        assertTrue(incoming.disposed);
        FakeAudioOutput.FakeVoice replacedIncoming = live.stream()
                .filter(voice -> voice.position == 200).findFirst().orElseThrow();
        assertEquals(Math.sqrt(0.5), replacedIncoming.volume, 0.0001);
        assertEquals(Math.sqrt(0.5), live.stream().filter(voice -> voice != replacedIncoming)
                .findFirst().orElseThrow().volume, 0.0001);
        incoming.reachEnd();
        assertEquals(2, output.playing().size(), "late events from old handles must not restart loops");
        replacedIncoming.advanceTo(400);
        engine.tick(0);
        assertEquals(1, output.playing().size());
        engine.shutdown();
    }

    @Test
    void sourceSwitchWaitsUntilReadyAndSeeksToTheThenCurrentPosition() throws IOException {
        AudioTrack track = transientTrack("transient", AudioKind.MUSIC, combat.getId());
        engine.setMusicCrossfadeSeconds(0);
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice old = output.last();
        old.advanceTo(300);
        library.setGainOverride(track.getId(), 12.0);
        output.readyOnOpen = false;
        engine.refreshTrackVolumes();
        FakeAudioOutput.FakeVoice pending = output.last();
        assertFalse(old.disposed);
        assertFalse(pending.playing);
        old.advanceTo(550);
        pending.ready = true;
        engine.tick(0);
        assertTrue(old.disposed);
        assertTrue(pending.playing);
        assertEquals(550, pending.position);
        engine.shutdown();
        assertTrue(output.opened.stream().allMatch(voice -> voice.disposed));
    }

    @Test
    void aLimiterReplacementMustNotResumePausedMusicThatWasStopped() throws IOException {
        AudioTrack track = transientTrack("transient", AudioKind.MUSIC, combat.getId());
        engine.setMusicCrossfadeSeconds(1);
        engine.playCategory(combat.getId());
        engine.tick(1);
        engine.toggleMusic();
        engine.stopMusic();
        library.setGainOverride(track.getId(), 12.0);
        engine.refreshTrackVolumes();
        assertFalse(output.last().playing);
        assertFalse(engine.isMusicPlaying());
        engine.tick(1);
        assertTrue(output.live().isEmpty());
        engine.shutdown();
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void playingATrackStartsItsCategoryAtThatSong(boolean shuffle) throws IOException {
        music("a");
        AudioTrack selected = music("b");
        music("c");
        engine.setShuffle(shuffle);
        engine.setMusicCrossfadeSeconds(0);
        engine.playCategory(combat.getId());
        engine.toggleMusic();

        engine.playTrack(selected.getId());
        assertEquals(combat.getId(), engine.categoryId());
        assertEquals(selected.getId(), engine.currentTrack().orElseThrow().getId());
        assertTrue(engine.isMusicPlaying(), "direct playback resumes paused music");
        FakeAudioOutput.FakeVoice first = output.last();
        first.advanceTo(5000);
        engine.playTrack(selected.getId());
        assertEquals(0, output.last().position, "playing the file again restarts it");
        output.last().reachEnd();
        assertNotEquals(selected.getId(), engine.currentTrack().orElseThrow().getId());
        if (!shuffle) {
            assertEquals("c", engine.currentTrack().orElseThrow().getName());
        }
        engine.shutdown();
    }

    @Test
    void directPlaybackRejectsMissingTracksAndSoundEffects() throws IOException {
        AudioTrack effect = effect("rain");
        assertThrows(IllegalArgumentException.class, () -> engine.playTrack("missing"));
        assertThrows(IllegalArgumentException.class, () -> engine.playTrack(effect.getId()));
        assertFalse(engine.isMusicPlaying());
    }

    @Test
    void loopCurrentSongCrossfadesIntoItself() throws IOException {
        AudioTrack first = music("a");
        engine.setShuffle(false);
        engine.setMusicCrossfadeSeconds(4);
        assertFalse(engine.isMusicLoop());
        engine.setMusicLoop(true);
        engine.playTrack(first.getId());
        FakeAudioOutput.FakeVoice voice = output.last();
        for (int i = 0; i < 4; i++) {
            engine.tick(1);
        }
        voice.advanceTo(voice.duration - 1000);
        engine.tick(1);
        assertEquals(2, output.opened.size(), "a repeat voice is preloaded before the current song ends");
        FakeAudioOutput.FakeVoice incoming = output.last();
        assertEquals(voice.file, incoming.file);
        incoming.advanceTo(500);
        engine.tick(0);
        assertEquals(Math.sqrt(0.5), voice.volume, 0.0001);
        assertEquals(Math.sqrt(0.5), incoming.volume, 0.0001,
                "the equal-power overlap reaches unity combined power at its midpoint");
        incoming.reachEnd();
        assertEquals(first.getId(), engine.currentTrack().orElseThrow().getId());
        assertEquals(3, output.opened.size());
        assertEquals(first.getId(), engine.currentTrack().orElseThrow().getId());
        engine.shutdown();
        assertTrue(output.live().isEmpty());
    }

    @Test
    void aSingleSongCategoryCrossfadesEvenWhenLoopCurrentSongIsOff() throws IOException {
        music("only");
        engine.setMusicCrossfadeSeconds(2);
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice outgoing = output.last();
        outgoing.advanceTo(outgoing.duration - 1000);

        engine.tick(0.25);

        assertFalse(engine.isMusicLoop());
        assertEquals(2, output.opened.size());
        assertEquals(outgoing.file, output.last().file);
        assertEquals(2, output.playing().size(),
                "the single song repeats with both voices playing during the overlap");
        engine.shutdown();
    }

    @Test
    void loopModeAllowsManualNavigationAndSurvivesPauseStopAndCategoryChanges() throws IOException {
        music("a");
        music("b");
        engine.setShuffle(false);
        engine.setMusicCrossfadeSeconds(0);
        engine.playCategory(combat.getId());
        engine.setMusicLoop(true);
        engine.next();
        assertEquals("b", engine.currentTrack().orElseThrow().getName());
        output.last().reachEnd();
        assertEquals("b", engine.currentTrack().orElseThrow().getName());
        engine.previous();
        assertEquals("a", engine.currentTrack().orElseThrow().getName());
        engine.toggleMusic();
        engine.tick(1);
        assertTrue(engine.isMusicPaused());
        engine.toggleMusic();
        assertTrue(output.last().playing);
        engine.stopMusic();
        assertTrue(engine.isMusicLoop());
        engine.playCategory(combat.getId());
        output.last().reachEnd();
        assertEquals("a", engine.currentTrack().orElseThrow().getName());
        AudioCategory other = library.createCategory("Other", AudioCategory.DEFAULT_COLOR, AudioCategory.DEFAULT_ICON);
        AudioTrack selected = importTrack("other", AudioKind.MUSIC, other.getId());
        engine.playTrack(selected.getId());
        output.last().reachEnd();
        assertEquals(selected.getId(), engine.currentTrack().orElseThrow().getId());
        assertFalse(new AudioEngine(library, new FakeAudioOutput()).isMusicLoop());
        engine.shutdown();
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
        engine.setMusicCrossfadeSeconds(4);
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
        engine.setMusicCrossfadeSeconds(2);
        music("a");
        music("b");
        engine.playCategory(combat.getId());
        FakeAudioOutput.FakeVoice first = output.last();
        first.advanceTo(first.duration - 1_000);
        engine.tick(0.25);

        for (int i = 0; i < 20; i++) {
            output.last().advanceTo((i + 1) * 250);
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
        assertTrue(output.live().stream().noneMatch(voice -> voice.loop), "effects overlap instead of native repeat");
        assertEquals(5, output.live().size(), "each effect preloads its next repetition");
    }

    private FakeAudioOutput.FakeVoice startRain() throws IOException {
        engine.setEffectFadeSeconds(0);
        engine.setEffectActive(effect("rain").getId(), true);
        engine.tick(0.25);
        return output.opened.get(0);
    }

    @Test
    void effectRepetitionsOverlapWithEqualPowerAndDisposeTheOldVoice() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.opened.get(1);
        assertFalse(next.playing);
        assertEquals(0, next.volume);

        first.advanceTo(first.duration - 500);
        engine.tick(0.05);
        assertTrue(next.playing);
        assertFalse(first.disposed);
        assertEquals(1, first.volume, 0.0001);
        assertEquals(0, next.volume);

        next.advanceTo(250);
        engine.tick(0.05);
        assertEquals(Math.sqrt(0.5), first.volume, 0.0001);
        assertEquals(Math.sqrt(0.5), next.volume, 0.0001);
        assertEquals(1, first.volume * first.volume + next.volume * next.volume, 0.0001);
        assertEquals(2, output.opened.size(), "no second transition during an overlap");

        next.advanceTo(500);
        engine.tick(0.05);
        assertTrue(first.disposed);
        assertEquals(1, next.volume, 0.0001);
        assertEquals(2, output.live().size(), "one playing voice and one preloaded voice");
        assertFalse(output.last().playing);

        next.advanceTo(next.duration - 500);
        engine.tick(0.05);
        assertTrue(output.last().playing, "later repetitions overlap too");
    }

    @Test
    void overlapWaitsForTheNextVoiceToAdvanceAndForItToBeReady() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        next.ready = false;
        first.advanceTo(first.duration - 500);
        engine.tick(0.25);
        assertFalse(next.playing);
        next.ready = true;
        engine.tick(0.25);
        assertEquals(1, first.volume, 0.0001, "startup delay must not fade the outgoing voice");
        assertEquals(0, next.volume);
        engine.tick(0.25);
        assertEquals(Math.sqrt(0.5), first.volume, 0.0001,
                "the equal-power fade begins once the prepared voice is ready");
    }

    @Test
    void overlapInterpolatesBetweenCoarseNativePositionUpdates() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 500);
        engine.tick(0.01);
        next.advanceTo(100);
        engine.tick(0.01);
        double before = next.volume;
        engine.tick(0.02);
        assertTrue(next.volume > before, "a blend advances even between native position notifications");
        assertEquals(Math.sin(0.12 / 0.5 * Math.PI / 2), next.volume, 0.0001);
    }

    @Test
    void effectStartedWhilePausedKeepsItsPreparedVoiceSilentOnResume() throws IOException {
        engine.setEffectsPaused(true);
        engine.setEffectActive(effect("rain").getId(), true);
        engine.tick(1);
        assertTrue(output.playing().isEmpty());
        engine.setEffectsPaused(false);
        assertEquals(1, output.playing().size());
        assertFalse(output.last().playing);
    }

    @Test
    void pausingAnOverlapFreezesBothVoicesAndDoesNotPlayThePreloadedVoice() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 500);
        engine.tick(0.05);
        next.advanceTo(250);
        engine.tick(0.05);
        double before = first.volume;

        engine.setEffectsPaused(true);
        engine.tick(1);
        assertTrue(output.playing().isEmpty());
        assertEquals(before, first.volume, 0.0001);
        assertEquals(before, next.volume, 0.0001);

        engine.setEffectsPaused(false);
        assertEquals(2, output.playing().size());
        engine.tick(0.2);
        assertEquals(before, first.volume, 0.0001, "the first resumed tick must not count paused time");
        assertEquals(before, next.volume, 0.0001);
        next.advanceTo(500);
        engine.tick(0.05);
        engine.setEffectsPaused(true);
        engine.setEffectsPaused(false);
        assertEquals(1, output.playing().size(), "the prepared repetition must stay silent and paused");
    }

    @Test
    void stoppingAndMutingAnOverlapAffectBothSides() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 500);
        engine.tick(0.05);
        next.advanceTo(250);
        engine.tick(0.05);
        engine.setPanicFadeSeconds(0);
        engine.setPanic(true);
        engine.tick(0.05);
        assertEquals(0, first.volume);
        assertEquals(0, next.volume);
        engine.setPanic(false);
        engine.tick(0.05);
        assertTrue(first.volume > 0);
        assertTrue(next.volume > 0);

        engine.stopAllEffects();
        assertTrue(output.live().isEmpty());
        first.reachEnd();
        next.reachEnd();
        assertTrue(engine.activeEffects().isEmpty());
        assertEquals(2, output.opened.size(), "stale end callbacks must not restart a stopped effect");
    }

    @Test
    void stoppingAnOverlapFadesBothSidesOut() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 500);
        engine.tick(0.05);
        next.advanceTo(250);
        engine.tick(0.05);
        engine.setEffectFadeSeconds(1);
        engine.stopAllEffects();
        engine.tick(0.25);
        assertTrue(first.volume > 0 && first.volume < Math.sqrt(0.5));
        assertTrue(next.volume > 0 && next.volume < Math.sqrt(0.5));
        settle();
        assertTrue(output.live().isEmpty());
    }

    @Test
    void effectCapAndShutdownReleaseOverlappingAndPreparedVoices() throws IOException {
        engine.setMaxEffects(1);
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 500);
        engine.tick(0.05);
        engine.setEffectActive(effect("wind").getId(), true);
        assertTrue(first.disposed);
        assertTrue(next.disposed);
        assertEquals(2, output.live().size());
        engine.shutdown();
        assertTrue(output.live().isEmpty());
    }

    @Test
    void shortEffectsLimitTheOverlapToHalfTheirLength() throws IOException {
        FakeAudioOutput.FakeVoice first = startRain();
        first.duration = 200;
        first.advanceTo(50);
        engine.tick(0.05);
        assertFalse(output.last().playing);
        first.advanceTo(100);
        engine.tick(0.05);
        assertTrue(output.last().playing);
        output.last().advanceTo(100);
        engine.tick(0.05);
        assertTrue(first.disposed);
    }

    @Test
    void endEventsRepeatEffectsWhenOverlapIsDisabledOrTimingIsUnknown() throws IOException {
        engine.setEffectLoopCrossfadeSeconds(0);
        FakeAudioOutput.FakeVoice first = startRain();
        FakeAudioOutput.FakeVoice next = output.last();
        first.advanceTo(first.duration - 250);
        engine.tick(0.05);
        assertFalse(next.playing);
        first.reachEnd();
        assertTrue(first.disposed);
        assertTrue(next.playing);
        assertEquals(1, next.volume);
        assertFalse(output.last().playing);
        next.duration = 0;
        next.reachEnd();
        assertTrue(next.disposed);
        assertEquals(1, output.playing().size());
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
