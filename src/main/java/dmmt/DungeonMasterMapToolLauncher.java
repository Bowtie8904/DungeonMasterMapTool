package dmmt;

import javafx.application.Application;

import java.util.Locale;

public final class DungeonMasterMapToolLauncher {
    private DungeonMasterMapToolLauncher() {
    }

    public static void main(String[] args) {
        configureDisplayLocale();
        Application.launch(DungeonMasterMapToolApplication.class, args);
    }

    static void configureDisplayLocale() {
        // JavaFX localizes built-in controls from DISPLAY; the authored application UI is English.
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
    }
}
