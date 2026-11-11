package dmmt.ui;

import javafx.application.HostServices;
import javafx.stage.Window;

import java.net.URI;
import java.util.Locale;

/**
 * Opens http(s) links from statblock text in the default browser. The embedded JavaFX WebView cannot render
 * the modern pages these links point to (they need CSS features its WebKit lacks and load endlessly).
 */
public final class LinkOpener {
    private static HostServices hostServices;

    private LinkOpener() {
    }

    public static void install(HostServices services) {
        hostServices = services;
    }

    /** Opens an http(s) link; returns false for any other kind of link or when no browser is available. */
    public static boolean open(Window owner, String url) {
        if (!isWebLink(url) || hostServices == null) return false;
        hostServices.showDocument(url.strip());
        return true;
    }

    static boolean isWebLink(String url) {
        if (url == null) return false;
        try {
            String scheme = URI.create(url.strip()).getScheme();
            return scheme != null && (scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
