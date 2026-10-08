package dmmt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentMapsTest {
    @TempDir
    Path dir;

    private AppSettings settings() {
        return new AppSettings(dir.resolve("settings.ini"));
    }

    private Path map(String name) throws IOException {
        Path file = dir.resolve(name + ".dmmap");
        Files.writeString(file, "{}");
        return file.toAbsolutePath().normalize();
    }

    @Test
    void mostRecentFirstWithoutDuplicates() throws IOException {
        RecentMaps recent = new RecentMaps(settings());
        Path a = map("a");
        Path b = map("b");
        recent.record(a);
        recent.record(b);
        recent.record(a);
        assertEquals(List.of(a, b), recent.existing());
    }

    @Test
    void persistsAcrossInstances() throws IOException {
        AppSettings settings = settings();
        Path a = map("a");
        Path b = map("b");
        RecentMaps first = new RecentMaps(settings);
        first.record(a);
        first.record(b);
        assertEquals(List.of(b, a), new RecentMaps(new AppSettings(settings.getFile())).existing());
    }

    @Test
    void limitsToConfiguredMax() throws IOException {
        AppSettings settings = settings();
        settings.putInt(RecentMaps.MAX_KEY, 2);
        RecentMaps recent = new RecentMaps(settings);
        Path a = map("a");
        Path b = map("b");
        Path c = map("c");
        recent.record(a);
        recent.record(b);
        recent.record(c);
        assertEquals(List.of(c, b), recent.existing());
    }

    @Test
    void dropsMapsThatNoLongerExist() throws IOException {
        RecentMaps recent = new RecentMaps(settings());
        Path a = map("a");
        Path b = map("b");
        recent.record(a);
        recent.record(b);
        Files.delete(a);
        assertEquals(List.of(b), recent.existing());
    }

    @Test
    void previousExcludesCurrentAndAlternatesAfterSuccessfulOpens() throws IOException {
        RecentMaps recent = new RecentMaps(settings());
        Path a = map("a");
        Path b = map("b");
        assertTrue(recent.previous(null).isEmpty());
        recent.record(a);
        assertTrue(recent.previous(a).isEmpty());
        recent.record(b);
        assertEquals(a, recent.previous(b).orElseThrow());
        recent.record(a);
        recent.record(a);
        assertEquals(b, recent.previous(a).orElseThrow());
        assertEquals(a, recent.previous(null).orElseThrow());
        assertEquals(b, recent.previous(dir.resolve("nested").resolve("..").resolve("a.dmmap")).orElseThrow());
    }

    @Test
    void previousUsesPersistedHistoryAndSkipsDeletedMaps() throws IOException {
        AppSettings settings = settings();
        RecentMaps recent = new RecentMaps(settings);
        Path a = map("a");
        Path b = map("b");
        Path c = map("c");
        recent.record(a);
        recent.record(b);
        recent.record(c);
        RecentMaps restored = new RecentMaps(new AppSettings(settings.getFile()));
        assertEquals(b, restored.previous(c).orElseThrow());
        Files.delete(b);
        assertEquals(a, restored.previous(c).orElseThrow());
        Files.delete(a);
        assertTrue(restored.previous(c).isEmpty());
    }

    @Test
    void oneEntryHistoryStillSupportsSwitchBackDuringTheSession() throws IOException {
        AppSettings settings = settings();
        settings.putInt(RecentMaps.MAX_KEY, 1);
        RecentMaps recent = new RecentMaps(settings);
        Path a = map("a");
        Path b = map("b");
        recent.record(a);
        recent.record(b);
        assertEquals(List.of(b), recent.existing());
        assertEquals(a, recent.previous(b).orElseThrow());
        recent.record(a);
        assertEquals(b, recent.previous(a).orElseThrow());
        Files.delete(b);
        assertTrue(recent.previous(a).isEmpty());
    }
}
