package dmmt.api;

import dmmt.ui.Icons;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;

import java.util.Properties;

/** Descriptive control text shared by app hover help and API discovery. */
public final class ControlTooltips {
    private static final Properties TOOLTIPS = ControlText.load("/dmmt/api/control-tooltips.properties");
    private static final Object CONTROL_ID = new Object();

    private ControlTooltips() {
    }

    public static String tooltip(String id, String... uiTexts) {
        String mapped = TOOLTIPS.getProperty(id);
        if (mapped != null) {
            return mapped;
        }
        for (String text : uiTexts) {
            if (text != null && !text.isBlank()) {
                return text.strip();
            }
        }
        return ControlNames.name(id);
    }

    /** Registers labels and wrapped value controls as well as standalone buttons. */
    public static void apply(String id, Node node) {
        if (node instanceof Control control) {
            control.getProperties().put(CONTROL_ID, id);
            String mapped = TOOLTIPS.getProperty(id);
            if (mapped != null) {
                if (control.getTooltip() != null) {
                    control.getTooltip().setText(mapped);
                    control.setAccessibleText(mapped);
                } else {
                    Icons.tooltip(control, mapped);
                }
            }
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> apply(id, child));
        }
    }

    /** Keeps later UI tooltip refreshes consistent with the registered mapping. */
    public static String text(Control control, String fallback) {
        Object id = control.getProperties().get(CONTROL_ID);
        return id instanceof String key ? TOOLTIPS.getProperty(key, fallback) : fallback;
    }
}
