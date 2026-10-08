package dmmt.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Shared loading and validation for bundled control text lookups. */
final class ControlText {
    private ControlText() {
    }

    static Properties load(String resource) {
        var stream = ControlText.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing control text: " + resource);
        }
        Properties entries = new Properties();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            entries.load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load control text: " + resource, ex);
        }
        for (String id : entries.stringPropertyNames()) {
            String text = entries.getProperty(id).strip();
            if (text.isEmpty() || !id.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
                throw new IllegalStateException("Invalid control text entry in " + resource + ": " + id);
            }
            entries.setProperty(id, text);
        }
        return entries;
    }
}
