package dmmt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
