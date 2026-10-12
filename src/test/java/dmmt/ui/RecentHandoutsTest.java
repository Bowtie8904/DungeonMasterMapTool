package dmmt.ui;

import dmmt.FxTestSupport;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentHandoutsTest {
    @TempDir
    Path folder;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void storesNewestFirstAndPersistsAcrossReload() throws Exception {
        RecentHandouts store = new RecentHandouts(folder);
        String red = store.add(image(Color.RED, 20, 10), 100);
        String blue = store.add(image(Color.BLUE, 10, 20), 100);

        assertEquals(List.of(blue, red), store.ids());
        assertTrue(Files.isRegularFile(store.file(red)));
        assertEquals(List.of(blue, red), new RecentHandouts(folder).ids());
    }

    @Test
    void identicalImageMovesToTopInsteadOfDuplicating() throws Exception {
        RecentHandouts store = new RecentHandouts(folder);
        String red = store.add(image(Color.RED, 20, 10), 100);
        String blue = store.add(image(Color.BLUE, 20, 10), 100);

        assertEquals(red, store.add(image(Color.RED, 20, 10), 100));
        assertEquals(List.of(red, blue), store.ids());

        store.moveToTop(blue);
        assertEquals(List.of(blue, red), new RecentHandouts(folder).ids());
    }

    @Test
    void limitDropsOldestImagesAndTheirFiles() throws Exception {
        RecentHandouts store = new RecentHandouts(folder);
        String red = store.add(image(Color.RED, 5, 5), 2);
        String green = store.add(image(Color.GREEN, 5, 5), 2);
        String blue = store.add(image(Color.BLUE, 5, 5), 2);

        assertEquals(List.of(blue, green), store.ids());
        assertFalse(Files.exists(store.file(red)));

        store.trim(1);
        assertEquals(List.of(blue), new RecentHandouts(folder).ids());
        assertFalse(Files.exists(store.file(green)));
    }

    @Test
    void removeDeletesTheFileAndZeroLimitStoresNothing() throws Exception {
        RecentHandouts store = new RecentHandouts(folder);
        String red = store.add(image(Color.RED, 5, 5), 10);
        store.remove(red);
        assertTrue(store.ids().isEmpty());
        assertFalse(Files.exists(store.file(red)));
        assertTrue(new RecentHandouts(folder).ids().isEmpty());

        assertNull(store.add(image(Color.RED, 5, 5), 0));
        assertTrue(store.ids().isEmpty());
    }

    @Test
    void loadingDoesNotCreateTheFolder() throws Exception {
        Path missing = folder.resolve("missing");
        RecentHandouts store = new RecentHandouts(missing);
        store.trim(10);
        assertTrue(store.ids().isEmpty());
        assertFalse(Files.exists(missing));
    }

    private static WritableImage image(Color color, int width, int height) {
        WritableImage image = new WritableImage(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getPixelWriter().setColor(x, y, color);
            }
        }
        return image;
    }
}
