package dmmt.online;

import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Frozen format-1 snapshot of the catalogs as shipped before the format-2 cutover. Legacy-engine tests and the
 * converter parity baselines read it; the shipped {@code dmmap-daggerheart} directory is format 2.
 */
public final class LegacyCatalogs {
    private LegacyCatalogs() {
    }

    public static Path dir() {
        try {
            return Path.of(LegacyCatalogs.class.getResource("/dmmt/legacy-catalogs").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
