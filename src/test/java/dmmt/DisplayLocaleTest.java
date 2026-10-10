package dmmt;

import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayLocaleTest {

    @Test
    void configuresEnglishDisplayLocaleBeforeJavaFxLaunch() {
        Locale original = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMANY);

            DungeonMasterMapToolLauncher.configureDisplayLocale();

            assertEquals(Locale.ENGLISH, Locale.getDefault(Locale.Category.DISPLAY));
            assertEquals("Cancel", ButtonType.CANCEL.getText());
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, original);
        }
    }
}
