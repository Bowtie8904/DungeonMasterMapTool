package dmmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Makes sure docs/SETTINGS.md documents every entry written to the settings file. */
class SettingsDocumentationTest {
    private static final Path DOC = Path.of("docs", "SETTINGS.md");
    private static final Pattern ENTRY = Pattern.compile("^#?\\s*([A-Za-z][\\w]*(?:\\.[\\w]+)+)\\s*=.*$");

    @Test
    void everySettingIsDocumented() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Set<String> expected = new LinkedHashSet<>();
        for (String line : AppSettings.renderDefaults().split("\\R")) {
            Matcher m = ENTRY.matcher(line.trim());
            if (m.matches()) {
                expected.add(documentedName(m.group(1)));
            }
        }
        assertTrue(expected.size() > 100, "unexpectedly few settings found: " + expected.size());

        List<String> missing = new ArrayList<>();
        for (String key : expected) {
            if (!doc.contains("`" + key + "`")) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), DOC + " does not document: " + missing);
    }

    /** Maps concrete keys of repeated entries to the generic name used in the documentation. */
    private static String documentedName(String key) {
        if (key.startsWith("player.screenDiagonalInches.")) {
            return "player.screenDiagonalInches.<n>";
        }
        if (key.startsWith("texture.")) {
            String[] parts = key.split("\\.");
            if (parts.length == 4 && parts[2].startsWith("layer")) {
                return "texture.<kind>.layer<N>.<property>";
            }
            return parts.length == 3 ? "texture.<kind>." + parts[2] : key;
        }
        return key;
    }

    @Test
    void everyTextureKindAndLayerPropertyIsDocumented() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Set<String> kinds = new LinkedHashSet<>();
        Set<String> layerProperties = new LinkedHashSet<>();
        for (String line : AppSettings.renderDefaults().split("\\R")) {
            Matcher m = ENTRY.matcher(line.trim());
            if (m.matches() && m.group(1).startsWith("texture.")) {
                String[] parts = m.group(1).split("\\.");
                if (parts.length >= 3) {
                    kinds.add(parts[1]);
                }
                if (parts.length == 4) {
                    layerProperties.add(parts[3]);
                }
            }
        }
        assertTrue(kinds.size() > 10);
        for (String kind : kinds) {
            assertTrue(doc.contains("| `" + kind + "` |"), "texture defaults table misses " + kind);
        }
        for (String property : layerProperties) {
            assertTrue(doc.contains("**`" + property + "`**"), "layer property not documented: " + property);
        }
    }
}
