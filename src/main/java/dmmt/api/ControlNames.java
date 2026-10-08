package dmmt.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** API display names, independent of control tooltips and sidebar settings labels. */
public final class ControlNames {
    private static final Properties NAMES = load();

    private ControlNames() {
    }

    public static String name(String id, String... uiNames) {
        String mapped = NAMES.getProperty(id);
        if (mapped != null) {
            return mapped;
        }
        for (String name : uiNames) {
            if (name != null && !name.isBlank()) {
                return name.strip();
            }
        }
        return id;
    }

    private static Properties load() {
        String resource = "/dmmt/api/control-names.properties";
        var stream = ControlNames.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing API control names: " + resource);
        }
        Properties names = new Properties();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            names.load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load API control names: " + resource, ex);
        }
        for (String id : names.stringPropertyNames()) {
            String name = names.getProperty(id).strip();
            if (name.isEmpty() || !id.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
                throw new IllegalStateException("Invalid API control name entry: " + id);
            }
            names.setProperty(id, name);
        }
        return names;
    }
}
