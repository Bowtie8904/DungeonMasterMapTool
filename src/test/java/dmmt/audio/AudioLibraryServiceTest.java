package dmmt.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The library index, the category rules and the import copy (3.35.1, 3.35.2). */
class AudioLibraryServiceTest {
    @TempDir
    Path dir;

    private AudioLibraryService library;

    @BeforeEach
    void setUp() {
        library = new AudioLibraryService(dir.resolve("audio"));
    }

    private Path sourceFile(String name) throws IOException {
        Path file = dir.resolve(name);
        TestAudioFiles.writeWav(file, 500, 8000);
        return file;
    }

    @Test
    void importsCopyIntoTheLibraryAndKeepTheOriginalUntouched() throws IOException {
        Path source = sourceFile("Tavern brawl.wav");
        AudioTrack track = library.importFile(source, AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);

        assertTrue(Files.exists(source), "the imported file must not be moved away");
        Path copy = library.fileOf(track);
        assertTrue(copy.startsWith(library.filesFolder()));
        assertEquals(Files.size(source), Files.size(copy));
        assertEquals("Tavern brawl", track.getName());
        assertTrue(track.getDurationMs() > 400 && track.getDurationMs() < 600, "duration " + track.getDurationMs());
    }

    @Test
    void importingTheSameNameTwiceKeepsBothFiles() throws IOException {
        library.importFile(sourceFile("ambience.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        AudioTrack second = library.importFile(sourceFile("ambience.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);

        assertEquals(2, library.tracks().size());
        assertEquals(2, Files.list(library.filesFolder()).count());
        assertTrue(Files.exists(library.fileOf(second)));
    }

    @Test
    void unsupportedFilesAreRejected() throws IOException {
        Path text = dir.resolve("notes.txt");
        Files.writeString(text, "not audio");
        assertThrows(IOException.class,
                () -> library.importFile(text, AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID));
    }

    @Test
    void filesThatOnlyLookLikeMp3sAreRejectedInsteadOfImportedAsAlmostEmptyTracks() throws IOException {
        Path fake = dir.resolve("Ambience_Place_Forest_Birds_Loop.mp3");
        TestAudioFiles.writeEncryptedLookingMp3(fake, 518124);

        IOException failure = assertThrows(IOException.class,
                () -> library.importFile(fake, AudioKind.EFFECT, null));

        assertTrue(failure.getMessage().contains("does not contain MP3 audio"), failure.getMessage());
        assertTrue(library.tracks().isEmpty(), "a rejected file must not end up in the library");
    }

    @Test
    void realMp3FilesPassTheFrameCoverageCheck() throws IOException {
        Path mp3 = dir.resolve("battle.mp3");
        TestAudioFiles.writeMp3(mp3, 200, true);

        AudioTrack track = library.importFile(mp3, AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);

        assertEquals("battle", track.getName());
        assertTrue(track.getDurationMs() > 5000, "duration " + track.getDurationMs());
    }

    @Test
    void folderScanWalksSubfoldersAndUsesTheDirectParentAsCategory() throws IOException {
        Path root = dir.resolve("Library");
        TestAudioFiles.writeWav(root.resolve("loose.wav"), 100, 8000);
        TestAudioFiles.writeWav(root.resolve("Combat").resolve("fight.wav"), 100, 8000);
        TestAudioFiles.writeWav(root.resolve("Fantasy").resolve("Boss").resolve("deep.wav"), 100, 8000);
        TestAudioFiles.writeWav(root.resolve(".hidden").resolve("skip.wav"), 100, 8000);
        Files.writeString(root.resolve("Combat").resolve("readme.txt"), "not audio");

        List<AudioLibraryService.FolderFile> found = AudioLibraryService.scanFolder(root);

        assertEquals(3, found.size(), found.toString());
        assertEquals(null, found.stream().filter(f -> f.file().getFileName().toString().equals("loose.wav"))
                .findFirst().orElseThrow().folderCategory());
        assertEquals("Combat", found.stream().filter(f -> f.file().getFileName().toString().equals("fight.wav"))
                .findFirst().orElseThrow().folderCategory());
        assertEquals("Boss", found.stream().filter(f -> f.file().getFileName().toString().equals("deep.wav"))
                .findFirst().orElseThrow().folderCategory(),
                "only the direct parent folder becomes the category");
    }

    @Test
    void foldersMatchCategoriesIgnoringCaseAndAreCreatedWithTheirExactName() throws IOException {
        AudioCategory existing = library.createCategory("Combat", null, null);

        assertEquals(existing.getId(), library.categoryForName("combat").getId());
        assertEquals(existing.getId(), library.categoryForName("COMBAT").getId());

        AudioCategory created = library.categoryForName("tAvErN");
        assertEquals("tAvErN", created.getName(), "a new category keeps the exact folder spelling");
        assertEquals(created.getId(), library.categoryForName("Tavern").getId());
        assertTrue(library.categoryByName("nothing here").isEmpty());
    }

    @Test
    void effectsIgnoreCategoriesAndMusicFallsBackToUncategorised() throws IOException {
        AudioTrack effect = library.importFile(sourceFile("rain.wav"), AudioKind.EFFECT, "does-not-exist");
        AudioTrack music = library.importFile(sourceFile("march.wav"), AudioKind.MUSIC, "does-not-exist");

        assertEquals(AudioCategory.UNCATEGORISED_ID, music.getCategoryId());
        assertEquals(List.of(effect.getId()), library.effects().stream().map(AudioTrack::getId).toList());
        assertEquals(List.of(music.getId()),
                library.musicOf(AudioCategory.UNCATEGORISED_ID).stream().map(AudioTrack::getId).toList());
    }

    @Test
    void deletingACategoryKeepsItsTracksInUncategorised() throws IOException {
        AudioCategory combat = library.createCategory("Combat", "#FF0000", "mdi2s-sword-cross");
        AudioTrack track = library.importFile(sourceFile("battle.wav"), AudioKind.MUSIC, combat.getId());

        library.deleteCategory(combat.getId());

        assertTrue(library.category(combat.getId()).isEmpty());
        assertTrue(Files.exists(library.fileOf(track)), "deleting a category must not delete audio files");
        assertEquals(1, library.musicOf(AudioCategory.UNCATEGORISED_ID).size());
    }

    @Test
    void theUncategorisedCategoryCannotBeDeletedOrRenamed() {
        assertThrows(IOException.class, () -> library.deleteCategory(AudioCategory.UNCATEGORISED_ID));
        assertThrows(IOException.class, () -> library.renameCategory(AudioCategory.UNCATEGORISED_ID, "Something"));
    }

    @Test
    void deletingATrackRemovesItsFile() throws IOException {
        AudioTrack track = library.importFile(sourceFile("wind.wav"), AudioKind.EFFECT, null);
        Path file = library.fileOf(track);

        library.deleteTrack(track.getId());

        assertFalse(Files.exists(file));
        assertTrue(library.tracks().isEmpty());
    }

    @Test
    void movingAndRenamingSurvivesAReload() throws IOException {
        AudioCategory tavern = library.createCategory("Tavern", "#00FF00", "mdi2g-glass-mug-variant");
        AudioTrack track = library.importFile(sourceFile("lute.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        library.renameTrack(track.getId(), "Lute solo");
        library.moveTrack(track.getId(), tavern.getId());

        AudioLibraryService reloaded = new AudioLibraryService(library.root());

        assertEquals("Lute solo", reloaded.track(track.getId()).orElseThrow().getName());
        assertEquals(List.of("Lute solo"), reloaded.musicOf(tavern.getId()).stream().map(AudioTrack::getName).toList());
        assertEquals("Tavern", reloaded.category(tavern.getId()).orElseThrow().getName());
    }

    @Test
    void changingTheKindMovesATrackBetweenMusicAndEffects() throws IOException {
        AudioTrack track = library.importFile(sourceFile("storm.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);

        library.changeKind(track.getId(), AudioKind.EFFECT, null);
        assertEquals(1, library.effects().size());
        assertTrue(library.musicOf(AudioCategory.UNCATEGORISED_ID).isEmpty());

        library.changeKind(track.getId(), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        assertTrue(library.effects().isEmpty());
        assertEquals(1, library.musicOf(AudioCategory.UNCATEGORISED_ID).size());
    }

    @Test
    void aBrokenIndexGivesAnEmptyLibraryInsteadOfFailing() throws IOException {
        library.importFile(sourceFile("x.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        Files.writeString(library.root().resolve(AudioLibraryService.INDEX_FILE), "{ this is not json");

        AudioLibraryService reloaded = new AudioLibraryService(library.root());

        assertTrue(reloaded.tracks().isEmpty());
        assertEquals(1, reloaded.categories().size(), "the uncategorised category is always there");
    }

    @Test
    void missingFilesAreReported() throws IOException {
        AudioTrack track = library.importFile(sourceFile("gone.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);
        Files.delete(library.fileOf(track));

        assertEquals(List.of(track.getId()), library.missingFiles().stream().map(AudioTrack::getId).toList());
    }

    @Test
    void clipsMustBeWrittenIntoTheLibraryFolder() throws IOException {
        Path outside = dir.resolve("outside.wav");
        TestAudioFiles.writeWav(outside, 100, 8000);
        assertThrows(IOException.class,
                () -> library.addClip(outside, "Clip", AudioKind.MUSIC, null, null, 0, 100));
    }

    @Test
    void hiddenCategoriesAndEffectsStayInTheLibraryButLeaveTheOverlay() throws IOException {
        AudioCategory combat = library.createCategory("Combat", AudioCategory.DEFAULT_COLOR, AudioCategory.DEFAULT_ICON);
        library.importFile(sourceFile("battle.wav"), AudioKind.MUSIC, combat.getId());
        AudioTrack rain = library.importFile(sourceFile("rain.wav"), AudioKind.EFFECT, null);

        assertTrue(library.visibleCategories().stream().anyMatch(c -> c.getId().equals(combat.getId())));
        assertEquals(1, library.visibleEffects().size());

        library.setCategoryHidden(combat.getId(), true);
        library.setTrackHidden(rain.getId(), true);

        assertTrue(library.visibleCategories().stream().noneMatch(c -> c.getId().equals(combat.getId())));
        assertTrue(library.visibleEffects().isEmpty());
        assertTrue(library.categories().stream().anyMatch(c -> c.getId().equals(combat.getId())),
                "hidden categories stay in the library window and in the API");
        assertEquals(1, library.effects().size());

        library.setCategoryHidden(combat.getId(), false);
        library.setTrackHidden(rain.getId(), false);
        assertEquals(1, library.visibleEffects().size());
    }

    @Test
    void emptyCategoriesAndUncategorisedAreLeftOutOfTheOverlay() throws IOException {
        AudioCategory empty = library.createCategory("Empty", null, null);
        library.importFile(sourceFile("lost.wav"), AudioKind.MUSIC, AudioCategory.UNCATEGORISED_ID);

        assertTrue(library.visibleCategories().stream().noneMatch(c -> c.getId().equals(empty.getId())),
                "a category without music has nothing to play");
        assertTrue(library.visibleCategories().stream().noneMatch(AudioCategory::isUncategorised),
                "Uncategorised is hidden by default even when it holds music");

        library.importFile(sourceFile("drums.wav"), AudioKind.MUSIC, empty.getId());
        assertTrue(library.visibleCategories().stream().anyMatch(c -> c.getId().equals(empty.getId())));

        library.setCategoryHidden(AudioCategory.UNCATEGORISED_ID, false);
        assertTrue(library.visibleCategories().stream().anyMatch(AudioCategory::isUncategorised),
                "it can still be shown on purpose");
    }

    @Test
    void olderLibrariesAreMigratedSoUncategorisedBecomesHidden() throws IOException {
        Path index = dir.resolve("audio").resolve(AudioLibraryService.INDEX_FILE);
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {
                  "schemaVersion" : 1,
                  "categories" : [ {
                    "id" : "uncategorised",
                    "name" : "Uncategorised",
                    "color" : "#9AA0A6",
                    "icon" : "mdi2m-music-box-multiple-outline",
                    "hidden" : false
                  } ],
                  "tracks" : [ ]
                }
                """);

        AudioLibraryService migrated = new AudioLibraryService(dir.resolve("audio"));
        assertTrue(migrated.category(AudioCategory.UNCATEGORISED_ID).orElseThrow().isHidden());
    }

    @Test
    void hidingAndStylingSurviveAReload() throws IOException {
        AudioCategory feast = library.createCategory("Feast", AudioCategory.DEFAULT_COLOR, AudioCategory.DEFAULT_ICON);
        AudioTrack wind = library.importFile(sourceFile("wind.wav"), AudioKind.EFFECT, null);
        library.setCategoryHidden(feast.getId(), true);
        library.styleTrack(wind.getId(), "#112233", "mdi2w-weather-windy");

        AudioLibraryService reloaded = new AudioLibraryService(library.root());
        assertTrue(reloaded.category(feast.getId()).orElseThrow().isHidden());
        AudioTrack stored = reloaded.track(wind.getId()).orElseThrow();
        assertEquals("#112233", stored.getColor());
        assertEquals("mdi2w-weather-windy", stored.getIcon());
    }

    @Test
    void stylingOnlyReplacesTheGivenValues() throws IOException {
        AudioTrack birds = library.importFile(sourceFile("birds.wav"), AudioKind.EFFECT, null);
        library.styleTrack(birds.getId(), "#445566", null);
        library.styleTrack(birds.getId(), null, "mdi2b-bird");

        AudioTrack stored = library.track(birds.getId()).orElseThrow();
        assertEquals("#445566", stored.getColor());
        assertEquals("mdi2b-bird", stored.getIcon());
    }

    @Test
    void newEntriesAreVisibleAndCarryTheDefaultEffectStyle() throws IOException {
        AudioTrack waves = library.importFile(sourceFile("waves.wav"), AudioKind.EFFECT, null);

        assertFalse(waves.isHidden());
        assertEquals(AudioTrack.DEFAULT_EFFECT_COLOR, waves.getColor());
        assertEquals(AudioTrack.DEFAULT_EFFECT_ICON, waves.getIcon());
    }
}
