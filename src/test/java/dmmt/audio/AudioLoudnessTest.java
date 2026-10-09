package dmmt.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioLoudnessTest {
    @TempDir
    Path dir;

    @Test
    void existingBareFileNamesResolveInSubfoldersWithoutRewritingTheLibrary() throws IOException {
        Path root = dir.resolve("audio");
        Path files = root.resolve(AudioLibraryService.FILES_FOLDER);
        Path playback = files.resolve(AudioLibraryService.PLAYBACK_FOLDER);
        Path limited = files.resolve(AudioLibraryService.LIMITED_FOLDER);
        TestAudioFiles.writeWav(files.resolve("original.wav"), 100, 8000);
        TestAudioFiles.writeWav(playback.resolve("safe.playback.wav"), 100, 8000);
        TestAudioFiles.writeWav(playback.resolve("boost.peak-safe.wav"), 100, 8000);
        TestAudioFiles.writeWav(limited.resolve("boost.limited.wav"), 100, 8000);
        Path index = root.resolve(AudioLibraryService.INDEX_FILE);
        String json = """
                {"schemaVersion": 4, "tracks": [
                  {"id": "safe", "file": "original.wav", "playbackFile": "safe.playback.wav",
                   "peakSafePlaybackFile": "safe.playback.wav"},
                  {"id": "boost", "file": "original.wav", "playbackFile": "boost.limited.wav",
                   "peakSafePlaybackFile": "boost.peak-safe.wav"}
                ]}
                """;
        Files.writeString(index, json);

        AudioLibraryService library = new AudioLibraryService(root);
        AudioTrack safe = library.track("safe").orElseThrow();
        AudioTrack boost = library.track("boost").orElseThrow();
        assertEquals(playback.resolve("safe.playback.wav"), library.playbackFileOf(safe));
        assertEquals(limited.resolve("boost.limited.wav"), library.playbackSourceOf(boost).file());
        assertTrue(Files.exists(library.playbackFileOf(safe)));
        assertTrue(Files.exists(library.playbackFileOf(boost)));
        assertEquals(files.resolve("original.wav"), library.fileOf(safe));
        assertEquals("safe.playback.wav", safe.getPlaybackFile());
        assertEquals("boost.limited.wav", boost.getPlaybackFile());
        assertEquals(json, Files.readString(index));

        library.setGainOverride("boost", null);
        assertEquals(playback.resolve("boost.peak-safe.wav"), library.playbackFileOf(boost));
        assertTrue(Files.exists(library.playbackFileOf(boost)));
        library.deleteTrack("boost");
        assertFalse(Files.exists(playback.resolve("boost.peak-safe.wav")));
        assertFalse(Files.exists(limited.resolve("boost.limited.wav")));
    }

    @Test
    void startupCleanupResolvesExistingStaleNamesInBothSubfolders() throws IOException {
        Path root = dir.resolve("audio");
        Path files = root.resolve(AudioLibraryService.FILES_FOLDER);
        Path playback = files.resolve(AudioLibraryService.PLAYBACK_FOLDER);
        Path limited = files.resolve(AudioLibraryService.LIMITED_FOLDER);
        TestAudioFiles.writeWav(playback.resolve("old.playback.wav"), 100, 8000);
        TestAudioFiles.writeWav(playback.resolve("old.peak-safe.wav"), 100, 8000);
        TestAudioFiles.writeWav(limited.resolve("old.limited.wav"), 100, 8000);
        TestAudioFiles.writeWav(files.resolve("original.wav"), 100, 8000);
        Files.writeString(root.resolve(AudioLibraryService.INDEX_FILE), """
                {"schemaVersion": 4,
                 "stalePlaybackFiles": ["old.playback.wav", "old.peak-safe.wav", "old.limited.wav"]}
                """);

        AudioLibraryService library = new AudioLibraryService(root);

        assertFalse(Files.exists(playback.resolve("old.playback.wav")));
        assertFalse(Files.exists(playback.resolve("old.peak-safe.wav")));
        assertFalse(Files.exists(limited.resolve("old.limited.wav")));
        assertTrue(Files.exists(files.resolve("original.wav")));
        assertEquals(0, library.deleteStalePlaybackFiles());
    }

    @Test
    void importsMeasureAndMatchQuietAndLoudFilesWithoutChangingOriginals() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path loudSource = dir.resolve("loud.wav");
        Path quietSource = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(loudSource, 1000, 44100, 0.5);
        TestAudioFiles.writeWav(quietSource, 1000, 44100, 0.05);
        byte[] quietOriginal = Files.readAllBytes(quietSource);

        AudioTrack loud = library.importFile(loudSource, AudioKind.MUSIC, null);
        AudioTrack quiet = library.importFile(quietSource, AudioKind.EFFECT, null);

        assertNotNull(loud.getLoudnessLufs());
        assertNotNull(quiet.getLoudnessLufs());
        assertEquals(-20, quiet.getLoudnessLufs() - loud.getLoudnessLufs(), 0.2);
        assertEquals(20, quiet.getAutoGainDb() - loud.getAutoGainDb(), 0.2);
        assertEquals(-23, quiet.getLoudnessLufs() + quiet.getAutoGainDb(), 0.05);
        assertEquals(-23, loud.getLoudnessLufs() + loud.getAutoGainDb(), 0.05);
        assertTrue(quiet.getAutoGainDb() > 0);
        assertTrue(Files.exists(library.playbackFileOf(quiet)));
        assertEquals(library.filesFolder().resolve(AudioLibraryService.PLAYBACK_FOLDER),
                library.playbackFileOf(quiet).getParent());
        assertEquals(quiet.getId() + ".playback.wav", quiet.getPlaybackFile());
        assertNotEquals(library.fileOf(quiet), library.playbackFileOf(quiet));
        assertTrue(LoudnessAnalyzer.analyze(library.playbackFileOf(quiet)).samplePeakDbfs() <= -1);
        assertTrue(java.util.Arrays.equals(quietOriginal, Files.readAllBytes(quietSource)));
        assertTrue(java.util.Arrays.equals(quietOriginal, Files.readAllBytes(library.fileOf(quiet))));

        AudioLibraryService reloaded = new AudioLibraryService(library.root());
        AudioTrack persisted = reloaded.track(quiet.getId()).orElseThrow();
        assertEquals(quiet.getLoudnessLufs(), persisted.getLoudnessLufs(), 0.001);
        assertEquals(quiet.getAutoGainDb(), persisted.getAutoGainDb(), 0.001);
        assertEquals(quiet.getPeakCeilingDbfs(), persisted.getPeakCeilingDbfs());
        assertEquals(quiet.getPeakHeadroomDb(), persisted.getPeakHeadroomDb(), 0.001);
        assertEquals(quiet.getPlaybackGainDb(), persisted.getPlaybackGainDb(), 0.001);
    }

    @Test
    void silenceIsNotAmplifiedAndShortEffectsUseTheirAvailableSamples() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path silence = dir.resolve("silence.wav");
        Path shortEffect = dir.resolve("short.wav");
        TestAudioFiles.writeWav(silence, 1000, 8000, 0);
        TestAudioFiles.writeWav(shortEffect, 40, 8000, 0.05);

        AudioTrack silent = library.importFile(silence, AudioKind.EFFECT, null);
        AudioTrack shortTrack = library.importFile(shortEffect, AudioKind.EFFECT, null);

        assertNull(silent.getLoudnessLufs());
        assertEquals(0, silent.getAutoGainDb());
        assertEquals(0, silent.maximumGainDb());
        assertNull(silent.getPlaybackFile());
        assertEquals(library.fileOf(silent), library.playbackFileOf(silent));
        assertEquals(40, shortTrack.getDurationMs());
        assertNotNull(shortTrack.getLoudnessLufs(), "short effects are gated over the samples they contain");
        assertTrue(shortTrack.getAutoGainDb() > 0);
    }

    @Test
    void stereoChannelsAreMeasuredIndependentlyAndPeakSafetyLimitsTheGain() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        int sampleRate = 8000;
        short[] left = new short[sampleRate];
        short[] right = new short[sampleRate];
        for (int i = 0; i < left.length; i++) {
            short sample = (short) Math.round(Math.sin(2 * Math.PI * 220 * i / sampleRate) * 1000);
            left[i] = sample;
            right[i] = (short) -sample;
        }
        Path stereo = dir.resolve("opposite-phase.wav");
        TestAudioFiles.writeStereoWav(stereo, left, right, sampleRate);
        AudioTrack track = library.importFile(stereo, AudioKind.EFFECT, null);

        assertNotNull(track.getLoudnessLufs());
        assertTrue(track.getLoudnessLufs() > -45,
                "opposite stereo channels must not cancel during loudness measurement");
        assertEquals(24, track.maximumGainDb(), 0.001, "the global boost ceiling is 24 dB");
        assertTrue(LoudnessAnalyzer.analyze(library.playbackFileOf(track)).samplePeakDbfs() <= -1);

        Path nearFullScale = dir.resolve("near-full-scale.wav");
        TestAudioFiles.writeWav(nearFullScale, 500, 8000, 0.99);
        AudioTrack loud = library.importFile(nearFullScale, AudioKind.EFFECT, null);
        assertTrue(loud.maximumGainDb() < 0, "the original peak already exceeds the -1 dBFS playback ceiling");
        assertNull(loud.getPlaybackFile(), "negative headroom needs attenuation, not a prepared boost copy");
        assertTrue(loud.effectiveGainDb() <= loud.maximumGainDb());
    }

    @Test
    void overridesPersistAndLimiterAssetsAreCleanedExplicitly() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path source = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(source, 300, 8000, 0.2);
        AudioTrack track = library.importFile(source, AudioKind.EFFECT, null);
        String preparedName = track.getPlaybackFile();
        assertNotNull(preparedName);
        Path safePrepared = library.playbackFileOf(track);

        library.setGainOverride(track.getId(), track.effectiveGainDb() - 6);
        assertEquals(track.getAutoGainDb() - 6, track.effectiveGainDb(), 0.001);
        AudioTrack persisted = new AudioLibraryService(library.root()).track(track.getId()).orElseThrow();
        assertEquals(track.getGainOverrideDb(), persisted.getGainOverrideDb());
        assertTrue(persisted.playbackVolumeFactor() <= 1);
        assertThrows(IOException.class, () -> library.setGainOverride(track.getId(), 24.1));
        assertThrows(IOException.class, () -> library.setGainOverride(track.getId(), Double.NaN));

        library.setGainOverride(track.getId(), 24.0);
        assertTrue(track.getPlaybackFile().endsWith(".limited.wav"));
        assertEquals(24, track.getPlaybackGainDb(), 0.001);
        assertEquals(1, track.playbackVolumeFactor(), 1e-9);
        Path limited = library.playbackFileOf(track);
        assertEquals(library.filesFolder().resolve(AudioLibraryService.LIMITED_FOLDER), limited.getParent());
        assertEquals(limited.getFileName().toString(), track.getPlaybackFile());
        assertTrue(LoudnessAnalyzer.analyze(limited).samplePeakDbfs() <= -1);
        AudioTrack restored = new AudioLibraryService(library.root()).track(track.getId()).orElseThrow();
        assertEquals(track.getPeakSafePlaybackFile(), restored.getPeakSafePlaybackFile());

        byte[] safeBytes = Files.readAllBytes(safePrepared);
        library.setGainOverride(track.getId(), null);
        assertNull(track.getGainOverrideDb());
        assertEquals(track.getAutoGainDb(), track.effectiveGainDb());
        Path prepared = library.playbackFileOf(track);
        assertEquals(safePrepared, prepared, "reset reuses the uncompressed peak-safe copy");
        assertTrue(java.util.Arrays.equals(safeBytes, Files.readAllBytes(prepared)));
        assertTrue(Files.exists(limited), "replaced copies remain until explicit cleanup/startup");
        assertEquals(0, library.deleteStalePlaybackFiles());
        assertFalse(Files.exists(limited));
        library.deleteTrack(track.getId());
        assertFalse(Files.exists(prepared));
        assertFalse(Files.exists(library.filesFolder().resolve(AudioLibraryService.PLAYBACK_FOLDER)
                .resolve(preparedName)));
    }

    @Test
    void manualGainOnLegacyTrackAnalyzesItAndPersistsLimiterSource() throws IOException {
        Path root = dir.resolve("legacy-audio");
        Path original = root.resolve(AudioLibraryService.FILES_FOLDER).resolve("legacy.wav");
        TestAudioFiles.writeWav(original, 500, 8000, 0.2);
        Files.createDirectories(root);
        Files.writeString(root.resolve(AudioLibraryService.INDEX_FILE), """
                {"schemaVersion": 2, "tracks": [{"id": "legacy", "name": "Legacy", "file": "legacy.wav",
                  "kind": "EFFECT"}]}
                """);
        AudioLibraryService library = new AudioLibraryService(root);
        AudioTrack track = library.track("legacy").orElseThrow();
        assertFalse(track.isLoudnessAnalyzed());
        assertEquals(24, track.manualMaximumGainDb(), 0.001);

        library.setGainOverride("legacy", 18.0);

        assertTrue(track.isLoudnessAnalyzed());
        assertEquals(18, track.effectiveGainDb(), 0.001);
        assertTrue(track.getPlaybackFile().endsWith(".limited.wav"));
        assertEquals(track.getPlaybackGainDb(), track.effectiveGainDb(), 0.001);
        AudioTrack reloaded = new AudioLibraryService(root).track("legacy").orElseThrow();
        assertEquals(track.getGainOverrideDb(), reloaded.getGainOverrideDb());
        assertEquals(track.getPeakSafePlaybackFile(), reloaded.getPeakSafePlaybackFile());
        assertEquals(track.getPlaybackFile(), reloaded.getPlaybackFile());
        assertTrue(Files.exists(root.resolve(AudioLibraryService.FILES_FOLDER)
                .resolve(AudioLibraryService.LIMITED_FOLDER)
                .resolve(reloaded.getPlaybackFile())));
    }

    @Test
    void lookaheadLimiterAmplifiesQuietStereoBedAndPreservesLinkedPeaksAndDuration() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        int sampleRate = 8000;
        int frames = sampleRate;
        short[] left = new short[frames];
        short[] right = new short[frames];
        for (int i = 0; i < frames; i++) {
            double tone = Math.sin(2 * Math.PI * 220 * i / sampleRate);
            left[i] = (short) Math.round(tone * 0.005 * Short.MAX_VALUE);
            right[i] = (short) Math.round(tone * 0.0025 * Short.MAX_VALUE);
        }
        left[2500] = (short) Math.round(0.95 * Short.MAX_VALUE);
        right[2500] = (short) Math.round(0.475 * Short.MAX_VALUE);
        Path source = dir.resolve("transient.wav");
        TestAudioFiles.writeStereoWav(source, left, right, sampleRate);
        AudioTrack track = library.importFile(source, AudioKind.EFFECT, null);
        assertTrue(track.maximumGainDb() < 0);

        library.setGainOverride(track.getId(), 24.0);

        Path limited = library.playbackFileOf(track);
        assertTrue(limited.getFileName().toString().endsWith(".limited.wav"));
        assertEquals(1.0, track.playbackVolumeFactor(), 1e-9,
                "the requested gain is baked into the limiter render");
        assertTrue(LoudnessAnalyzer.analyze(limited).samplePeakDbfs() <= -1);
        assertEquals(AudioFormats.durationMs(source), AudioFormats.durationMs(limited));

        ByteBuffer output = ByteBuffer.wrap(Files.readAllBytes(limited)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(frames, (output.capacity() - 44) / 4);
        int bedFrame = 7001;
        double bedAmplification = Math.abs(readStereoSample(output, bedFrame, 0) / (double) left[bedFrame]);
        assertTrue(bedAmplification > 10, "the bed should recover toward the full requested gain");
        assertEquals(2.0, readStereoSample(output, bedFrame, 0)
                / (double) readStereoSample(output, bedFrame, 1), 0.04);
        assertEquals(2.0, readStereoSample(output, 2500, 0)
                / (double) readStereoSample(output, 2500, 1), 0.04,
                "the limiter applies the same gain envelope to both channels");
    }

    @Test
    void interruptedManualPreparationLeavesGainAndPlaybackAssetsUnchanged() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path source = dir.resolve("interrupt.wav");
        TestAudioFiles.writeWav(source, 300, 8000, 0.2);
        AudioTrack track = library.importFile(source, AudioKind.EFFECT, null);
        Path originalPlayback = library.playbackFileOf(track);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> library.setGainOverride(track.getId(), 24.0));
        } finally {
            Thread.interrupted();
        }
        assertNull(track.getGainOverrideDb());
        assertEquals(originalPlayback, library.playbackFileOf(track));
        try (java.util.stream.Stream<Path> files = Files.walk(library.filesFolder())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".limited.wav")
                    || path.getFileName().toString().endsWith(".limited.wav.tmp")));
        }
    }

    @Test
    void legacyEntriesKeepOriginalGainUntilAnalyzedThenPersistPreparedHeadroom() throws IOException {
        Path root = dir.resolve("audio");
        Path files = root.resolve(AudioLibraryService.FILES_FOLDER);
        Path original = files.resolve("legacy.wav");
        TestAudioFiles.writeWav(original, 500, 8000, 0.01);
        Files.createDirectories(root);
        Files.writeString(root.resolve(AudioLibraryService.INDEX_FILE), """
                {
                  "schemaVersion": 2,
                  "tracks": [{
                    "id": "legacy",
                    "name": "Legacy",
                    "file": "legacy.wav",
                    "kind": "EFFECT"
                  }]
                }
                """);

        AudioLibraryService library = new AudioLibraryService(root);
        AudioTrack track = library.track("legacy").orElseThrow();
        assertFalse(track.isLoudnessAnalyzed());
        assertEquals(0, track.effectiveGainDb());
        assertEquals(original, library.playbackFileOf(track));

        library.analyzeLoudness(track.getId());

        assertTrue(track.isLoudnessAnalyzed());
        assertTrue(track.getAutoGainDb() > 0);
        assertNotEquals(original, library.playbackFileOf(track));
        assertTrue(Files.exists(library.playbackFileOf(track)));
        assertEquals(library.filesFolder().resolve(AudioLibraryService.PLAYBACK_FOLDER),
                library.playbackFileOf(track).getParent());
        assertTrue(track.getPlaybackFile().endsWith(".peak-safe.wav"));
        assertTrue(Files.exists(original));
        assertTrue(new AudioLibraryService(root).track(track.getId()).orElseThrow().isLoudnessAnalyzed());
    }

    @Test
    void cutClipsAreAnalyzedAndFailedClipAnalysisDoesNotLeaveFiles() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path source = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(source, 500, 8000, 0.01);
        Path clip = library.reserveClipFile("quiet clip", "wav");
        AudioClipCutter.cut(source, 50, 350, clip);
        AudioTrack track = library.addClip(clip, "Quiet clip", AudioKind.EFFECT, null, null, 50, 350);
        assertNotNull(track.getLoudnessLufs());
        assertTrue(track.getAutoGainDb() > 0);
        assertEquals(library.filesFolder().resolve(AudioLibraryService.PLAYBACK_FOLDER),
                library.playbackFileOf(track).getParent());

        Path badClip = library.reserveClipFile("broken", "wav");
        Files.writeString(badClip, "not a WAV");
        assertThrows(IOException.class,
                () -> library.addClip(badClip, "Broken", AudioKind.EFFECT, null, null, 0, 10));
        assertFalse(Files.exists(badClip));
        assertEquals(1, library.tracks().size());
    }

    @Test
    void preparedPlaybackUsesCurrentAbsoluteGainAcrossMusicCrossfadesAndEffectLoops() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        FakeAudioOutput output = new FakeAudioOutput();
        AudioEngine engine = new AudioEngine(library, output, new Random(1));
        engine.setVolumes(1, 1, 1);
        engine.setCrossfadeSeconds(1);
        engine.setEffectFadeSeconds(0);
        engine.setEffectLoopCrossfadeSeconds(0.5);
        AudioCategory category = library.createCategory("Test", null, null);
        AudioTrack first = importQuiet(library, "first", AudioKind.MUSIC, category.getId());
        AudioTrack second = importQuiet(library, "second", AudioKind.MUSIC, category.getId());
        AudioTrack effect = importQuiet(library, "effect", AudioKind.EFFECT, null);

        engine.playCategory(category.getId());
        engine.tick(1);
        FakeAudioOutput.FakeVoice firstVoice = output.last();
        double before = firstVoice.volume;
        library.setGainOverride(first.getId(), first.effectiveGainDb() - 6);
        engine.refreshTrackVolumes();
        assertEquals(before * Math.pow(10, -6.0 / 20), firstVoice.volume, 0.0001);

        engine.next();
        engine.tick(0.2);
        FakeAudioOutput.FakeVoice outgoing = output.opened.get(0);
        FakeAudioOutput.FakeVoice incoming = output.last();
        double outgoingBefore = outgoing.volume;
        double incomingBefore = incoming.volume;
        library.setGainOverride(second.getId(), second.effectiveGainDb() - 3);
        engine.refreshTrackVolumes();
        assertEquals(outgoingBefore, outgoing.volume, 0.0001);
        assertEquals(incomingBefore * Math.pow(10, -3.0 / 20), incoming.volume, 0.0001);

        engine.setEffectActive(effect.getId(), true);
        engine.tick(0.1);
        FakeAudioOutput.FakeVoice effectVoice = output.opened.get(output.opened.size() - 2);
        effectVoice.advanceTo(effectVoice.duration - 400);
        engine.tick(0.1);
        FakeAudioOutput.FakeVoice repeatedVoice = output.last();
        double effectBefore = effectVoice.volume;
        double repeatedBefore = repeatedVoice.volume;
        library.setGainOverride(effect.getId(), effect.effectiveGainDb() - 4);
        engine.refreshTrackVolumes();
        assertEquals(effectBefore * Math.pow(10, -4.0 / 20), effectVoice.volume, 0.0001);
        assertEquals(repeatedBefore * Math.pow(10, -4.0 / 20), repeatedVoice.volume, 0.0001);

        engine.shutdown();
    }

    @Test
    void playbackVolumeFactorIsRelativeToTheBakedGainAndClamped() {
        AudioTrack track = AudioTrack.builder().autoGainDb(6).maxGainDb(12).playbackGainDb(12).build();
        assertEquals(Math.pow(10, -6.0 / 20), track.playbackVolumeFactor(), 1e-9);
        track.setGainOverrideDb(12.0);
        assertEquals(1, track.playbackVolumeFactor(), 1e-9);
        assertEquals(1, track.playbackVolumeFactor(0), 1e-9, "an unboosted copy cannot exceed unity");
        track.setGainOverrideDb(-60.0);
        assertEquals(Math.pow(10, -72.0 / 20), track.playbackVolumeFactor(), 1e-12);
    }

    @Test
    void reanalyzingAPlayingLegacyTrackReopensItAndPreservesGainSemantics() throws IOException {
        Path root = dir.resolve("audio");
        Path original = root.resolve(AudioLibraryService.FILES_FOLDER).resolve("legacy.wav");
        TestAudioFiles.writeWav(original, 1500, 8000, 0.01);
        Files.writeString(root.resolve(AudioLibraryService.INDEX_FILE), """
                {"schemaVersion": 2, "tracks": [{"id": "legacy", "name": "Legacy", "file": "legacy.wav",
                  "kind": "EFFECT"}]}
                """);
        AudioLibraryService library = new AudioLibraryService(root);
        FakeAudioOutput output = new FakeAudioOutput();
        AudioEngine engine = new AudioEngine(library, output, new Random(1));
        engine.setVolumes(1, 1, 1);
        engine.setEffectFadeSeconds(0);
        engine.setEffectActive("legacy", true);
        engine.tick(0.1);
        FakeAudioOutput.FakeVoice playing = output.opened.get(0);
        assertEquals(original, playing.file);
        assertEquals(1, playing.volume, 1e-9);

        library.analyzeLoudness("legacy");
        AudioTrack track = library.track("legacy").orElseThrow();
        engine.refreshTrackVolumes();

        assertTrue(track.getAutoGainDb() > 0);
        assertTrue(playing.disposed, "the original voice is replaced when the prepared source becomes available");
        FakeAudioOutput.FakeVoice preparedVoice = output.opened.stream()
                .filter(voice -> !voice.disposed && voice.playing).findFirst().orElseThrow();
        assertEquals(library.playbackFileOf(track), preparedVoice.file);
        assertEquals(track.playbackVolumeFactor(), preparedVoice.volume, 1e-9);
        assertFalse(engine.needsReopen("legacy"));
        library.setGainOverride("legacy", -6.0);
        engine.refreshTrackVolumes();
        assertEquals(track.playbackVolumeFactor(), preparedVoice.volume, 1e-9,
                "ordinary gain changes update volume without replacing the source");

        library.setGainOverride("legacy", null);
        engine.refreshTrackVolumes();
        assertEquals(library.playbackFileOf(track), preparedVoice.file);
        assertFalse(engine.needsReopen("legacy"));
        assertEquals(track.playbackVolumeFactor(), preparedVoice.volume, 1e-9);
        engine.shutdown();
    }

    @Test
    void reanalysisRetainsOldPreparedCopiesUntilExplicitCleanup() throws IOException {
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        Path source = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(source, 300, 8000, 0.01);
        AudioTrack track = library.importFile(source, AudioKind.EFFECT, null);
        Path first = library.playbackFileOf(track);

        library.analyzeLoudness(track.getId());

        assertNotEquals(first, library.playbackFileOf(track));
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(library.playbackFileOf(track)));
        assertEquals(0, library.deleteStalePlaybackFiles());
        assertFalse(Files.exists(first));
    }

    private AudioTrack importQuiet(AudioLibraryService library, String name, AudioKind kind, String categoryId)
            throws IOException {
        Path source = dir.resolve(name + ".wav");
        TestAudioFiles.writeWav(source, 1500, 8000, 0.01);
        return library.importFile(source, kind, categoryId);
    }

    private static short readStereoSample(ByteBuffer wav, int frame, int channel) {
        return wav.getShort(44 + frame * 4 + channel * 2);
    }

    @Test
    void calibratedOneKilohertzToneMeasuresMinus23LufsAtCommonSampleRates() throws IOException {
        for (int rate : new int[]{44100, 48000}) {
            short[] samples = new short[rate * 2];
            java.nio.ByteBuffer pcm = java.nio.ByteBuffer.allocate(samples.length * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) Math.round(32767 * 0.1 * Math.sin(2 * Math.PI * 1000 * i / rate));
                pcm.putShort(samples[i]);
            }
            Path mono = dir.resolve("calibration-" + rate + ".wav");
            TestAudioFiles.writeWav(mono, pcm.array(), rate);
            double monoLufs = LoudnessAnalyzer.analyze(mono).loudnessLufs();
            assertEquals(-23.0, monoLufs, 0.15);
            Path stereo = dir.resolve("stereo-calibration-" + rate + ".wav");
            TestAudioFiles.writeStereoWav(stereo, samples, samples, rate);
            assertEquals(monoLufs + 10 * Math.log10(2),
                    LoudnessAnalyzer.analyze(stereo).loudnessLufs(), 0.01);
        }
    }

    @Test
    void longSilentGapsDoNotDiluteTheIntegratedLoudness() throws IOException {
        int rate = 44100;
        java.nio.ByteBuffer tone = java.nio.ByteBuffer.allocate(rate * 3 * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rate * 3; i++) {
            tone.putShort((short) Math.round(32767 * 0.1 * Math.sin(2 * Math.PI * 1000 * i / rate)));
        }
        Path continuous = dir.resolve("continuous.wav");
        TestAudioFiles.writeWav(continuous, tone.array(), rate);
        byte[] withGaps = new byte[rate * 15 * 2];
        System.arraycopy(tone.array(), 0, withGaps, rate * 6 * 2, tone.capacity());
        Path gaps = dir.resolve("gaps.wav");
        TestAudioFiles.writeWav(gaps, withGaps, rate);
        assertEquals(LoudnessAnalyzer.analyze(continuous).loudnessLufs(),
                LoudnessAnalyzer.analyze(gaps).loudnessLufs(), 0.5);
    }
}
