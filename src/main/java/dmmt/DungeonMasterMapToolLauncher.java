package dmmt;

import dmmt.log.Log;
import dmmt.log.LogSetup;
import dmmt.service.AppSettings;
import javafx.application.Application;

import java.nio.file.Path;
import java.util.Locale;

public final class DungeonMasterMapToolLauncher {
    private DungeonMasterMapToolLauncher() {
    }

    public static void main(String[] args) {
        configureLogging();
        configureTextureMemory();
        configureDisplayLocale();
        Log log = Log.of(DungeonMasterMapToolLauncher.class);
        log.info("Starting Dungeon Master Map Tool (Java {}, {} {}, log folder {})", System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"), LogSetup.logFolder());
        log.info("Settings file: {}; prism.maxvram={}", AppSettings.resolveFile(), System.getProperty("prism.maxvram"));
        Application.launch(DungeonMasterMapToolApplication.class, args);
    }

    static void configureLogging() {
        Path settings = AppSettings.resolveFile().getParent();
        LogSetup.configure(settings == null ? null : settings.resolve("logs"));
    }

    static void configureTextureMemory() {
        if (System.getProperty("prism.maxvram") == null) {
            System.setProperty("prism.maxvram", "1g");
        }
    }

    static void configureDisplayLocale() {
        // JavaFX localizes built-in controls from DISPLAY; the authored application UI is English.
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
    }
}
