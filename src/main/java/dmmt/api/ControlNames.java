package dmmt.api;

import java.util.Properties;

/** API display names, independent of control tooltips and sidebar settings labels. */
public final class ControlNames {
    private static final Properties NAMES = ControlText.load("/dmmt/api/control-names.properties");

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
}
